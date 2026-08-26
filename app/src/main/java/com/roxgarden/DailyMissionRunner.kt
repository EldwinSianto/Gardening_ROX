package com.roxgarden

import android.graphics.Bitmap
import android.util.Log
import com.roxgarden.ocr.OcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Otomasi misi harian Mission Board (Carnival) — subsistem KEDUA di samping gardening.
 *
 * Dikonstruksi murni dari lambda dan TIDAK PERNAH mereferensikan GardenService balik
 * (pola WeatherScanner/CityNavigator). Hapus file ini + blok pemanggilnya di GardenService
 * = fitur hilang bersih.
 *
 * Bot TIDAK punya kemampuan combat. Fitur ini tetap bisa jalan karena game menyediakan
 * auto-travel + auto-battle lewat item "wing": bot cukup men-tap Use, menunggu dialog
 * Submit muncul sendiri, lalu men-tap Submit. Konsekuensinya bot TIDAK PERLU tahu jenis
 * misi (kill / gather / talk) sama sekali — loopnya identik untuk ketiganya.
 */
class DailyMissionRunner(
    private val capture: () -> Bitmap?,
    private val tap: (Float, Float) -> Unit,
    private val swipe: (Float, Float, Float, Float, Long) -> Unit,
    private val ocrProvider: () -> OcrProcessor?,
    private val loadAsset: (String) -> Bitmap?,
    private val hud: (String) -> Unit,
    // Mengembalikan true kalau captcha ditangani — DAN MENGAMBIL ALIH bitmap.
    // Pemanggil TIDAK BOLEH recycle bitmap itu lagi.
    private val checkCaptcha: (Bitmap) -> Boolean,
    private val isCaptchaActive: () -> Boolean,
    private val closePanels: suspend () -> Unit,
    private val markActivity: () -> Unit
) {
    companion object {
        private const val TAG = "DailyMission"

        // Ambang SSD LONGGAR — sengaja, dan hanya aman karena tiap pencarian DIBATASI ke
        // pita kecil di sekitar posisi ikon yang sudah diketahui (lihat *_RECT di bawah).
        // Alasan: ikon toolbar semi-transparan di atas adegan 3D yang HIDUP, jadi latar
        // yang berubah ikut terhitung. Terukur di device: match benar yang sama menghasilkan
        // SSD 5561 lalu 6301 pada dua run berbeda — ambang 6000 memotongnya di tengah.
        private const val TEMPLATE_THRESHOLD = 14000.0

        // Pita pencarian (fraksi layar) — membuat ambang longgar di atas tetap aman karena
        // tidak ada kandidat lain di dalam kotak sekecil ini.
        // Carnival: toolbar kanan-atas, terukur di (858,18)..(932,68).
        private val CARNIVAL_RECT = floatArrayOf(0.60f, 0.0f, 0.80f, 0.16f)
        // Mission Board: di dalam panel Carnival, terukur di (610,412)..(684,484).
        private val MISSION_BOARD_RECT = floatArrayOf(0.30f, 0.42f, 0.75f, 0.85f)

        private const val D_NAV = 2000L      // jeda antar langkah navigasi panel
        private const val D_SETTLE = 1200L   // jeda setelah tap tombol teks

        // Chat ada di dasar layar dan bisa memuat kata apa saja — SEMUA pencarian teks
        // wajib mengecualikan pita ini, kalau tidak "use" di chat bisa memicu tap nyasar.
        private const val CHAT_TOP_FRAC = 0.78f
    }

    private val templateCache = mutableMapOf<String, Bitmap?>()

    private fun getTemplate(path: String): Bitmap? = templateCache.getOrPut(path) {
        loadAsset(path).also {
            if (it != null) Log.d(TAG, "template '$path' loaded: ${it.width}×${it.height}")
            else Log.w(TAG, "template '$path' TIDAK ADA di assets/")
        }
    }

    private suspend fun captureRetry(): Bitmap? {
        repeat(5) {
            val b = capture()
            if (b != null) return b
            delay(250)
        }
        Log.w(TAG, "captureRetry gagal 5×")
        return null
    }

    // Tunggu layar berhenti bergerak sebelum capture final → hindari match di frame
    // yang masih beranimasi (panel Carnival punya animasi buka/tutup).
    private suspend fun captureStable(maxWaitMs: Long = 1800L): Bitmap? {
        var prev: Bitmap? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val cur = captureRetry() ?: return prev
            if (prev != null) {
                val stable = framesSimilar(prev, cur)
                prev.recycle()
                if (stable) return cur
            }
            prev = cur
            delay(130)
        }
        Log.d(TAG, "captureStable timeout ${maxWaitMs}ms → pakai frame terakhir")
        return prev
    }

    private fun framesSimilar(a: Bitmap, b: Bitmap, threshold: Double = 6.0): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val w = a.width; val h = a.height
        val step = 24
        var sum = 0L; var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
                val la = (pa shr 16 and 0xff) + (pa shr 8 and 0xff) + (pa and 0xff)
                val lb = (pb shr 16 and 0xff) + (pb shr 8 and 0xff) + (pb and 0xff)
                sum += Math.abs(la - lb).toLong(); n++
                x += step
            }
            y += step
        }
        if (n == 0) return false
        return sum.toDouble() / n / 3.0 <= threshold
    }

    // SSD template matching. Salinan sendiri — SENGAJA: CityNavigator.findTemplate dan
    // WeatherScanner.findTemplateOnScreen juga masing-masing punya salinannya. Itu
    // konsekuensi aturan "fitur terpisah = hapus file + blok pemanggil = hilang bersih".
    // JANGAN diekstrak jadi util bersama.
    private fun findTemplate(
        screen: Bitmap, tmpl: Bitmap, rectFrac: FloatArray? = null
    ): Pair<Float, Float>? {
        val sw = screen.width; val sh = screen.height
        val tw = tmpl.width;  val th = tmpl.height
        if (sw - tw < 0 || sh - th < 0) return null

        // Batas pencarian dalam piksel kanonik. Tanpa rectFrac = seluruh layar.
        val rl = ((rectFrac?.get(0) ?: 0f) * sw).toInt().coerceIn(0, sw - tw)
        val rt = ((rectFrac?.get(1) ?: 0f) * sh).toInt().coerceIn(0, sh - th)
        val rr = ((rectFrac?.get(2) ?: 1f) * sw).toInt().coerceIn(rl + tw, sw)
        val rb = ((rectFrac?.get(3) ?: 1f) * sh).toInt().coerceIn(rt + th, sh)

        val screenPx = IntArray(sw * sh)
        screen.getPixels(screenPx, 0, sw, 0, 0, sw, sh)
        val tmplPx = IntArray(tw * th)
        tmpl.getPixels(tmplPx, 0, tw, 0, 0, tw, th)

        var bestSsd = Long.MAX_VALUE
        var bestDx = -1; var bestDy = -1
        val step = 2
        for (dy in rt..(rb - th) step step) {
            for (dx in rl..(rr - tw) step step) {
                var ssd = 0L
                outer@ for (ty in 0 until th step step) {
                    for (tx in 0 until tw step step) {
                        val sp = screenPx[(dy + ty) * sw + (dx + tx)]
                        val tp = tmplPx[ty * tw + tx]
                        val dr = (sp shr 16 and 0xff) - (tp shr 16 and 0xff)
                        val dg = (sp shr 8  and 0xff) - (tp shr 8  and 0xff)
                        val db = (sp        and 0xff) - (tp        and 0xff)
                        ssd += (dr * dr + dg * dg + db * db).toLong()
                        if (ssd >= bestSsd) break@outer
                    }
                }
                if (ssd < bestSsd) { bestSsd = ssd; bestDx = dx; bestDy = dy }
            }
        }
        if (bestDx < 0) return null
        val sampled = ((tw + step - 1) / step) * ((th + step - 1) / step)
        val avg = bestSsd.toDouble() / sampled
        Log.d(TAG, "SSD=${"%.0f".format(avg)} pos=($bestDx,$bestDy)")
        return if (avg <= TEMPLATE_THRESHOLD) (bestDx + tw / 2f) to (bestDy + th / 2f) else null
    }

    private suspend fun tapTemplate(
        assetPath: String, label: String, rectFrac: FloatArray? = null
    ): Boolean {
        val tmpl = getTemplate(assetPath) ?: return false
        val screen = captureStable() ?: return false
        val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl, rectFrac) }
        screen.recycle()
        if (pos == null) { Log.w(TAG, "tapTemplate '$label': tidak ketemu"); return false }
        Log.d(TAG, "tap '$label' → (${pos.first.toInt()},${pos.second.toInt()})")
        tap(pos.first, pos.second)
        return true
    }

    // Normalisasi teks OCR: huruf/angka saja, lowercase. "Go Now" → "gonow".
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Cari tombol berupa TEKS lalu tap tengah kotaknya. Dipakai untuk Go Now / Accept All /
     * Use / Submit — semuanya teks, jadi tidak perlu aset template dan tahan terhadap
     * pergeseran posisi tombol.
     *
     * Cocok PERSIS (bukan substring): "use" harus berdiri sendiri sebagai satu kotak teks.
     * Substring akan kena "House"/"Reuse" dan sejenisnya.
     *
     * minYFrac/maxYFrac membatasi pita pencarian. Pita default mengecualikan chat
     * (CHAT_TOP_FRAC) yang isinya tidak terkendali.
     */
    private suspend fun tapTextButton(
        needle: String,
        minYFrac: Float = 0f,
        maxYFrac: Float = CHAT_TOP_FRAC
    ): Boolean {
        val ocr = ocrProvider() ?: run { Log.w(TAG, "OCR belum ready"); return false }
        val screen = captureStable() ?: return false
        val h = screen.height.toFloat()
        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR error: ${t.message}"); screen.recycle(); return false
        }
        screen.recycle()

        val target = norm(needle)
        for (i in res.texts.indices) {
            if (norm(res.texts[i]) != target) continue
            val r = res.boxes[i].boundingRect()
            val cy = (r.top + r.bottom) / 2f
            if (cy < h * minYFrac || cy > h * maxYFrac) continue
            val cx = (r.left + r.right) / 2f
            Log.d(TAG, "tap teks '$needle' → (${cx.toInt()},${cy.toInt()})")
            tap(cx, cy)
            return true
        }
        Log.w(TAG, "teks '$needle' tidak ketemu di pita ${minYFrac}..${maxYFrac}")
        return false
    }

    /** Batal bersih: tutup panel yang terlanjur terbuka supaya tidak memicu PANEL_STUCK nanti. */
    private suspend fun abort(reason: String): Boolean {
        Log.w(TAG, "BATAL: $reason")
        hud("⚠ daily mission batal: $reason")
        closePanels()
        return false
    }

    /**
     * Navigasi Carnival → Mission Board → Go Now → Accept All, lalu tutup panel.
     * Dipisah dari run() supaya bisa diuji sendiri dengan maxMissions = 0.
     */
    private suspend fun openAndAcceptMissions(): Boolean {
        hud("🎪 buka Carnival")
        if (!tapTemplate("map_icons/carnival.png", "Carnival", CARNIVAL_RECT)) return abort("ikon Carnival tak ketemu")
        delay(D_NAV)

        hud("📋 buka Mission Board")
        if (!tapTemplate("map_icons/mission_board.png", "Mission Board", MISSION_BOARD_RECT)) return abort("ikon Mission Board tak ketemu")
        delay(D_NAV)

        // Pita sengaja turun sampai 0.95, MELEWATI CHAT_TOP_FRAC: tombol Go Now ada di
        // y=0.839 (terukur di device), dan saat panel modal terbuka chat justru tertutup
        // panel — jadi pengecualian chat tidak berlaku di konteks ini. Uji pertama gagal
        // persis karena pita 0.45..0.78 memotong tombolnya.
        hud("▶ Go Now")
        if (!tapTextButton("go now", 0.45f, 0.95f)) return abort("tombol Go Now tak ketemu")
        delay(D_NAV)

        // "Accept All" hanya ADA kalau misi hari ini belum di-accept. Kalau sudah (mis. user
        // meng-accept manual, atau run sebelumnya sudah lewat sini), tombolnya berganti jadi
        // "Complete Immediately" — yang tampaknya MENGHABISKAN ITEM. Karena itu:
        //   1. tidak ketemu = BUKAN error, lanjut saja ke tracker;
        //   2. pencocokan teks WAJIB persis "acceptall". Substring atau tap koordinat tetap
        //      bisa menekan "Complete Immediately" dan membakar item user.
        hud("✅ Accept All")
        if (tapTextButton("accept all", 0.45f, 0.98f)) {
            Log.d(TAG, "Accept All ditekan — misi hari ini baru di-accept")
            delay(D_NAV)
        } else {
            Log.d(TAG, "Accept All tidak ada → misi kemungkinan sudah ter-accept, lanjut")
        }

        hud("🚪 tutup panel")
        closePanels()
        delay(D_NAV)
        return true
    }

    /**
     * @param maxMissions batas ATAS pengaman; 0 = hanya navigasi + Accept All (untuk pengetesan).
     *   Terminasi sebenarnya BERBASIS PERILAKU, bukan hitungan — lihat runMissionLoop.
     * @return true kalau selesai tanpa error.
     */
    suspend fun run(maxMissions: Int = 10): Boolean {
        Log.d(TAG, "=== DAILY MISSION mulai (maxMissions=$maxMissions) ===")
        hud("🎯 daily mission mulai")
        if (!openAndAcceptMissions()) return false
        if (maxMissions <= 0) {
            Log.d(TAG, "maxMissions=0 → berhenti setelah Accept All (mode uji navigasi)")
            hud("✅ navigasi OK (mode uji)")
            return true
        }
        // Loop misi diisi di Task 2.
        Log.d(TAG, "loop misi belum diimplementasikan (Task 2)")
        return true
    }
}
