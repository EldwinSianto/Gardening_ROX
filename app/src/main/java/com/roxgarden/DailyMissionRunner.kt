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

        // Quest tracker di kiri layar. Terukur di frame kanonik 1280×720: entry quest
        // terbaca di x≈60..275, y≈205..430.
        private const val TRACKER_X_MIN = 0.03f
        private const val TRACKER_X_MAX = 0.26f
        private const val TRACKER_Y_MIN = 0.20f
        // 0.62 (=446px) bukan lebih rendah: area entry tracker berakhir ~y=440, dan di
        // bawahnya ada JOYSTICK ANALOG (lingkaran x 120..300, y 480..660 kanonik).
        private const val TRACKER_Y_MAX = 0.62f
        // Scroll BERHENTI karena daftar terbukti mentok (teks tracker tidak berubah lagi),
        // bukan karena hitungan. Batas tetap sudah dua kali salah tebak: 4 scroll baru
        // sampai kategori [Favor], dan panjang daftar berbeda-beda tiap akun/hari.
        // Angka ini hanya pengaman terakhir supaya tidak pernah jadi loop tak berujung.
        private const val TRACKER_SCROLL_HARD_CAP = 40
        private const val D_SCROLL = 900L

        // Titik swipe untuk menggulung tracker. WAJIB di luar joystick analog pada KEDUA
        // sumbu — versi pertama memakai x=0.145 (186px) dan mulai dari y=0.68 (490px),
        // yang jatuh tepat di dalam lingkaran analog: alih-alih menggulung daftar, swipe
        // itu menyeret joystick dan membuat karakter berjalan (terlihat langsung oleh user).
        // x=0.05 (64px) ada di kolom kiri kotak quest, kiri dari tepi analog (120px);
        // y 0.56..0.30 (403..216px) seluruhnya di atas tepi atas analog (480px).
        private const val TRACKER_SWIPE_X = 0.05f
        private const val TRACKER_SWIPE_Y_BOT = 0.56f
        private const val TRACKER_SWIPE_Y_TOP = 0.30f

        // Popup item wing muncul di tengah-kanan layar. Dipakai DUA kali: sebagai pembeda
        // (misi Mission Board vs daily lain) dan sebagai tombol yang ditekan.
        private const val WING_Y_MIN = 0.30f
        private const val WING_Y_MAX = 0.75f
        private const val WING_X_MIN = 0.40f

        // Tombol Skip cutscene, pojok kanan-atas. Terukur dari screenshot device: ~(0.91, 0.05).
        // Misi tipe "bicara ke NPC" memicu cutscene dialog; tanpa Skip bot hanya menunggu
        // sampai timeout. User yang menemukan ini.
        private const val SKIP_Y_MAX = 0.20f
        private const val SKIP_X_MIN = 0.75f

        // Berapa kali mencoba menemukan wing sebelum menyimpulkan "tidak ada misi tersisa".
        // Bukan 1: wingVisible() dan tapTextButton("use") memotret layar MASING-MASING, jadi
        // ada jeda ~1 detik di antaranya dan popup bisa berubah persis di celah itu — terlihat
        // di log sebagai "wing muncul" lalu "use tidak ketemu" satu detik berikutnya.
        private const val WING_FIND_TRIES = 3

        // Jeda setelah Confirm wing: animasi terbang + loading map.
        private const val D_TRANSPORT = 4000L
        private const val TEMPLATE_RETRY_MS = 1500L
        // Sesudah wing, UI butuh waktu tak tentu untuk kembali; 8x1.5s = sampai ~12 detik.
        private const val NAV_TRIES_AFTER_TRANSPORT = 8

        // Menunggu dialog Submit. Misi TALK butuh MANUSIA melanjutkan obrolan NPC, dan itu
        // tidak bisa dibedakan secara teknis dari "bot nyangkut" — jadi timeoutnya longgar.
        // Timeout yang sama juga menyelamatkan kasus karakter MATI saat auto-battle:
        // respawn di kota, quest tak selesai, Submit tak akan pernah muncul.
        private const val SUBMIT_TIMEOUT_MS = 5 * 60_000L
        private const val SUBMIT_POLL_MS = 2000L

        private const val D_NAV = 2000L      // jeda antar langkah navigasi panel
        private const val D_SETTLE = 1200L   // jeda setelah tap tombol teks

        // Chat ada di dasar layar dan bisa memuat kata apa saja — SEMUA pencarian teks
        // wajib mengecualikan pita ini, kalau tidak "use" di chat bisa memicu tap nyasar.
        private const val CHAT_TOP_FRAC = 0.78f

        // Tombol interaksi misi (lingkaran emas + ikon tangan) dicari lewat POSISI, bukan
        // labelnya. Labelnya berganti tiap misi — terukur di device: "Check" untuk Morning
        // Glory, "Search" untuk sarang Crow, dan dugaan awal "Take" tidak pernah muncul sama
        // sekali. Mengejar daftar kata kerja berarti bot diam tiap kali ketemu label baru.
        //
        // Yang KONSISTEN adalah tempatnya: pada kedua misi tombol itu ada di titik yang sama
        // persis, (866,275) kanonik, dengan labelnya sedikit di bawah. Kotak sempit ini juga
        // aman: di area itu tidak ada elemen UI lain — menu kanan mulai dari x>0.70 dan
        // berada lebih ke bawah.
        // Kotak dipersempit dari 0.62 ke 0.65: pada 0.62 (=794px) kotak masih menangkap
        // potongan teks menu kanan — terukur di device sebagai 'GV' (dari "GVG League") di
        // x=810, dan bot menekannya sehingga popup lain terbuka.
        private val INTERACT_RECT = floatArrayOf(0.65f, 0.36f, 0.73f, 0.46f)

        // Titik tap tombol interaksi, BUKAN titik teksnya. Teks yang ditemukan hanyalah LABEL
        // di bawah tombol (terukur: label "Search" di y=304, lingkaran emasnya di y=275) —
        // menekan label tidak memicu apa pun, terlihat di log sebagai 'Search' ditekan 4×
        // berturut-turut tanpa reaksi. Posisi tombolnya sendiri konsisten di kedua misi.
        private const val INTERACT_TAP_X = 0.677f
        private const val INTERACT_TAP_Y = 0.382f

        // Ikon Fly Wing di popup, terukur di device pada (828,267)..(902,341) kanonik.
        // Pita dilebarkan sedikit dari itu untuk menoleransi pergeseran popup.
        private val FLY_WING_RECT = floatArrayOf(0.58f, 0.30f, 0.78f, 0.55f)
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

    /**
     * @param tries berapa kali mencoba sebelum menyerah. >1 dipakai saat UI game mungkin
     *   belum siap — mis. tepat setelah Butterfly Wing: layar masih animasi terbang/loading
     *   dan toolbar belum terpasang, sehingga ikon Carnival belum ada (terlihat di log
     *   device sebagai SSD=20704, jauh dari match). Menambah jeda tetap hanya tebakan lain;
     *   percobaan berulang menyesuaikan diri dengan lama loading yang berubah-ubah.
     */
    private suspend fun tapTemplate(
        assetPath: String, label: String, rectFrac: FloatArray? = null, tries: Int = 1
    ): Boolean {
        val tmpl = getTemplate(assetPath) ?: return false
        repeat(tries) { attempt ->
            val screen = captureStable()
            if (screen != null) {
                val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl, rectFrac) }
                screen.recycle()
                if (pos != null) {
                    Log.d(TAG, "tap '$label' → (${pos.first.toInt()},${pos.second.toInt()})")
                    tap(pos.first, pos.second)
                    return true
                }
            }
            if (attempt < tries - 1) {
                Log.d(TAG, "tapTemplate '$label': belum ketemu, coba lagi (${attempt + 1}/$tries)")
                delay(TEMPLATE_RETRY_MS)
            }
        }
        Log.w(TAG, "tapTemplate '$label': tidak ketemu setelah $tries percobaan")
        return false
    }

    /**
     * Cari template TANPA men-tap — dipakai sebagai pengecekan "ada/tidak" yang murah
     * (~30ms) menggantikan OCR (~1-2 detik) di jalur yang sering dilewati.
     */
    private suspend fun locateTemplate(
        assetPath: String, label: String, rectFrac: FloatArray? = null
    ): Pair<Float, Float>? {
        val tmpl = getTemplate(assetPath) ?: return null
        val screen = captureStable() ?: return null
        val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl, rectFrac) }
        screen.recycle()
        if (pos != null) Log.d(TAG, "template '$label' ada di (${pos.first.toInt()},${pos.second.toInt()})")
        return pos
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
    /** Cari kotak teks, kembalikan titik tengahnya TANPA men-tap. null kalau tak ada. */
    private suspend fun findTextBox(
        needle: String,
        minYFrac: Float = 0f,
        maxYFrac: Float = CHAT_TOP_FRAC,
        minXFrac: Float = 0f
    ): Pair<Float, Float>? {
        val ocr = ocrProvider() ?: run { Log.w(TAG, "OCR belum ready"); return null }
        val screen = captureStable() ?: return null
        val w = screen.width.toFloat(); val h = screen.height.toFloat()
        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR error: ${t.message}"); screen.recycle(); return null
        }
        screen.recycle()

        val target = norm(needle)
        for (i in res.texts.indices) {
            if (norm(res.texts[i]) != target) continue
            val r = res.boxes[i].boundingRect()
            val cy = (r.top + r.bottom) / 2f
            val cx = (r.left + r.right) / 2f
            if (cy < h * minYFrac || cy > h * maxYFrac) continue
            if (cx < w * minXFrac) continue
            return cx to cy
        }
        return null
    }

    private suspend fun tapTextButton(
        needle: String,
        minYFrac: Float = 0f,
        maxYFrac: Float = CHAT_TOP_FRAC,
        minXFrac: Float = 0f
    ): Boolean {
        val pos = findTextBox(needle, minYFrac, maxYFrac, minXFrac)
        if (pos == null) {
            Log.w(TAG, "teks '$needle' tidak ketemu di pita ${minYFrac}..${maxYFrac}")
            return false
        }
        Log.d(TAG, "tap teks '$needle' → (${pos.first.toInt()},${pos.second.toInt()})")
        tap(pos.first, pos.second)
        return true
    }

    /** Swipe memakai fraksi layar; lambda swipe sendiri menerima piksel kanonik. */
    private suspend fun swipeFrac(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val s = captureRetry() ?: return
        val w = s.width.toFloat(); val h = s.height.toFloat()
        s.recycle()
        swipe(x1 * w, y1 * h, x2 * w, y2 * h, dur)
    }

    /**
     * BUTTERFLY WING dan FLY WING adalah DUA BENDA BERBEDA (koreksi user), dan membedakannya
     * butuh kehati-hatian karena string "butterfly wing" MENGANDUNG "fly wing" sebagai
     * substring. Versi sebelumnya juga menerima kata "transport" sebagai penanda Butterfly,
     * padahal KEDUA dialog memakainya — akibatnya Fly Wing salah dilabeli Butterfly di log
     * dan alur berikutnya ikut salah.
     *
     *   BUTTERFLY WING : muncul sesudah Go Now saat karakter di LUAR kota.
     *                    Mengantar ke PAPAN misi. Tombolnya "Confirm".
     *   FLY WING       : muncul sesudah men-tap misi di quest tracker.
     *                    Mengantar ke LOKASI misi. Tombolnya "Use".
     *
     * Fly Wing sekaligus PEMBEDA misi papan: menurut user, misi Mission Board pasti
     * memunculkannya sedangkan quest harian lain (mis. Xavier) tidak.
     *
     * PENJAGA: "Confirm" juga nama tombol di dialog CAPTCHA, jadi Confirm hanya diterima
     * kalau di frame yang SAMA benar-benar ada kata "butterfly".
     */
    private enum class WingKind { FLY, BUTTERFLY }

    private data class WingHit(val kind: WingKind, val x: Float, val y: Float)

    private suspend fun findWingDialog(): WingHit? {
        val ocr = ocrProvider() ?: return null
        val screen = captureStable() ?: return null
        val w = screen.width.toFloat(); val h = screen.height.toFloat()
        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR wing error: ${t.message}"); screen.recycle(); return null
        }
        screen.recycle()

        val joined = norm(res.texts.joinToString(" "))
        // URUTAN WAJIB butterfly dulu: "butterflywing" mengandung "flywing".
        val kind = when {
            joined.contains("butterfly") -> WingKind.BUTTERFLY
            joined.contains("flywing") -> WingKind.FLY
            else -> return null
        }

        val wantBtn = if (kind == WingKind.BUTTERFLY) "confirm" else "use"
        for (i in res.texts.indices) {
            if (norm(res.texts[i]) != wantBtn) continue
            val r = res.boxes[i].boundingRect()
            val cy = (r.top + r.bottom) / 2f
            val cx = (r.left + r.right) / 2f
            if (cy < h * WING_Y_MIN || cy > h * WING_Y_MAX) continue
            if (kind == WingKind.FLY && cx < w * WING_X_MIN) continue
            Log.d(TAG, "dialog ${kind.name} WING, tombol '$wantBtn' di (${cx.toInt()},${cy.toInt()})")
            return WingHit(kind, cx, cy)
        }
        Log.d(TAG, "teks ${kind.name} WING terbaca tapi tombol '$wantBtn' tidak ketemu")
        return null
    }

    // Dimensi kanonik terakhir yang terlihat — dipakai mengubah fraksi jadi piksel tanpa
    // memotret ulang. Diisi tiap findAnyTextIn/findTextBox berjalan.
    @Volatile private var lastW = 1280f
    @Volatile private var lastH = 720f
    private fun canonW() = lastW
    private fun canonH() = lastH

    /**
     * Teks APA PUN di dalam kotak (fraksi layar), beserta isinya. Dipakai untuk tombol yang
     * posisinya tetap tapi labelnya berganti-ganti — teksnya ikut dikembalikan supaya label
     * baru terlihat di log, bukan menghilang diam-diam.
     */
    private suspend fun findAnyTextIn(rect: FloatArray): Triple<Float, Float, String>? {
        val ocr = ocrProvider() ?: return null
        val screen = captureStable() ?: return null
        val w = screen.width.toFloat(); val h = screen.height.toFloat()
        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR rect error: ${t.message}"); screen.recycle(); return null
        }
        screen.recycle()
        lastW = w; lastH = h

        for (i in res.texts.indices) {
            val r = res.boxes[i].boundingRect()
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            if (cx < w * rect[0] || cx > w * rect[2]) continue
            if (cy < h * rect[1] || cy > h * rect[3]) continue
            val txt = res.texts[i].trim()
            if (txt.isEmpty()) continue
            return Triple(cx, cy, txt)
        }
        return null
    }

    /** Fly Wing terlihat? Inilah pembeda misi papan (koreksi user). */
    private suspend fun flyWingVisible(): Boolean = findWingDialog()?.kind == WingKind.FLY

    /**
     * Tekan tombol popup wing JENIS TERTENTU, beberapa kali percobaan.
     * Percobaan berulang PENTING: pencarian dan penekanan memakai potret layar yang berbeda,
     * dan popup sempat hilang di celah itu (terlihat di log device).
     */
    private suspend fun tapWing(want: WingKind): Boolean {
        repeat(WING_FIND_TRIES) { attempt ->
            val hit = findWingDialog()
            if (hit != null && hit.kind == want) {
                Log.d(TAG, "tap ${want.name} WING -> (${hit.x.toInt()},${hit.y.toInt()})")
                tap(hit.x, hit.y)
                return true
            }
            if (attempt < WING_FIND_TRIES - 1) delay(800)
        }
        return false
    }

    /**
     * Lewati cutscene dialog kalau tombol Skip terlihat (temuan user).
     * Misi tipe "bicara ke NPC" memicu cutscene yang tidak selesai sendiri; tanpa ini bot
     * hanya menunggu sampai timeout 5 menit.
     */
    private suspend fun skipCutsceneIfAny(): Boolean {
        val pos = findTextBox("skip", 0f, SKIP_Y_MAX, SKIP_X_MIN) ?: return false
        Log.d(TAG, "cutscene: tap Skip -> (${pos.first.toInt()},${pos.second.toInt()})")
        hud("lewati cutscene")
        tap(pos.first, pos.second)
        return true
    }

    /** Isi tracker sekali baca: entry [Daily] yang terlihat + sidik teks seluruh pita. */
    private data class TrackerRead(
        val dailyEntries: List<Pair<Float, Float>>,
        val fingerprint: String
    )

    /**
     * Baca pita quest tracker SEKALI. Selain titik entry [Daily], kembalikan juga sidik teks
     * seluruh pita — dipakai untuk mendeteksi daftar sudah mentok tanpa capture tambahan.
     */
    private suspend fun readTracker(): TrackerRead {
        val ocr = ocrProvider() ?: return TrackerRead(emptyList(), "")
        val screen = captureStable() ?: return TrackerRead(emptyList(), "")
        val w = screen.width.toFloat(); val h = screen.height.toFloat()
        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR tracker error: ${t.message}"); screen.recycle()
            return TrackerRead(emptyList(), "")
        }
        screen.recycle()

        val out = mutableListOf<Pair<Float, Float>>()
        val fp = StringBuilder()
        for (i in res.texts.indices) {
            val r = res.boxes[i].boundingRect()
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            if (cx < w * TRACKER_X_MIN || cx > w * TRACKER_X_MAX) continue
            if (cy < h * TRACKER_Y_MIN || cy > h * TRACKER_Y_MAX) continue
            fp.append(norm(res.texts[i]))
            if (norm(res.texts[i]).contains("daily")) out.add(cx to cy)
        }
        return TrackerRead(out.sortedBy { it.second }, fp.toString())
    }

    /**
     * Pilih quest Mission Board di tracker.
     *
     * MASALAHNYA (temuan user): tidak semua "[Daily]" berasal dari Mission Board — quest harian
     * lain (mis. "Xavier Mysterious Item") juga berlabel [Daily] tapi bukan bagian dari 10 misi
     * papan. Strukturnya identik di tracker, jadi tidak ada pembeda visual maupun tekstual, dan
     * nama misinya berganti tiap hari sehingga daftar nama pun tidak bisa di-hardcode.
     *
     * PEMBEDANYA PERILAKU (dikonfirmasi user): item wing HANYA muncul untuk misi Mission Board.
     * Jadi: tap satu entry, lihat wing muncul atau tidak, kalau tidak lanjut ke entry berikutnya.
     * Ini kebal terhadap urutan entry maupun nama misi yang berubah tiap hari.
     *
     * Sengaja TIDAK mengandalkan "misi Mission Board selalu berkelompok": di layar user, quest
     * harian lain justru muncul DI ATAS misi papan, jadi asumsi urutan berisiko. Pengelompokan
     * hanya membuat pencarian ini cepat dalam praktik, bukan syarat kebenarannya.
     */
    /**
     * Gulung tracker ke ATAS sampai mentok. Posisi scroll tracker BERTAHAN dari interaksi
     * sebelumnya (user maupun run sebelumnya), jadi tanpa ini pencarian mulai dari titik
     * yang tidak diketahui dan bisa melewatkan [Daily] yang ada di atas posisi awal.
     * Pola yang sama dipakai scrollEncyclopediaToTop() di CityNavigator.
     */
    private suspend fun scrollTrackerToTop() {
        repeat(6) {
            swipeFrac(TRACKER_SWIPE_X, TRACKER_SWIPE_Y_TOP, TRACKER_SWIPE_X, TRACKER_SWIPE_Y_BOT, 600L)
            delay(350)
        }
        delay(D_SCROLL)
    }

    private suspend fun selectMissionBoardQuest(): Boolean {
        scrollTrackerToTop()
        val tried = mutableSetOf<Int>()   // entry yang sudah dicoba, dikunci per posisi y (10px)
        var prevFp = ""
        var scroll = 0
        while (scroll < TRACKER_SCROLL_HARD_CAP) {
            val t = readTracker()
            Log.d(TAG, "tracker: ${t.dailyEntries.size} entry [Daily] terlihat (scroll $scroll)")
            for ((idx, e) in t.dailyEntries.withIndex()) {
                // Setelah scroll, entry yang sama bisa muncul lagi di posisi berbeda; kunci
                // kasar per-10px sudah cukup untuk tidak mencoba entry yang persis sama dua kali
                // berturut-turut tanpa scroll di antaranya.
                val key = (e.second / 10f).toInt()
                if (!tried.add(key)) continue
                Log.d(TAG, "coba entry [Daily] #${idx + 1} di (${e.first.toInt()},${e.second.toInt()})")
                tap(e.first, e.second)
                delay(D_SETTLE)
                if (flyWingVisible()) {
                    Log.d(TAG, "Fly Wing muncul -> entry ini misi Mission Board")
                    return true
                }
                Log.d(TAG, "Fly Wing tidak muncul -> bukan misi papan, lanjut entry berikutnya")
            }

            // Daftar sudah mentok? Sidik teks pita tracker TIDAK BERUBAH setelah scroll = tidak
            // ada lagi yang bisa digulung. Ini yang menghentikan loop, bukan hitungan.
            if (t.fingerprint.isNotEmpty() && t.fingerprint == prevFp) {
                Log.d(TAG, "isi tracker tidak berubah setelah scroll -> sudah mentok di bawah")
                break
            }
            prevFp = t.fingerprint
            tried.clear()   // posisi bergeser setelah scroll, kunci lama tidak berlaku lagi

            scroll++
            Log.d(TAG, "belum ketemu misi Mission Board, scroll tracker $scroll")
            swipeFrac(TRACKER_SWIPE_X, TRACKER_SWIPE_Y_BOT, TRACKER_SWIPE_X, TRACKER_SWIPE_Y_TOP, 700L)
            delay(D_SCROLL)
        }
        if (scroll >= TRACKER_SCROLL_HARD_CAP)
            Log.w(TAG, "berhenti di pengaman $TRACKER_SCROLL_HARD_CAP scroll")
        Log.w(TAG, "tidak ada entry [Daily] yang memunculkan wing")
        return false
    }

    /**
     * Tunggu dialog Submit muncul SENDIRI (auto-battle / auto-interact selesai).
     * Captcha tetap dicek tiap poll — ongkosnya murah lewat template yang sudah ada, jadi
     * tidak diasumsikan captcha mustahil muncul di luar konteks gardening.
     */
    private suspend fun waitForSubmit(missionNo: Int): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < SUBMIT_TIMEOUT_MS) {
            delay(SUBMIT_POLL_MS)

            // Captcha sedang ditangani GardenService -> tunggu bersih, jangan menyela.
            if (isCaptchaActive()) {
                Log.d(TAG, "captcha aktif, daily mission menunggu")
                hud("captcha, misi menunggu")
                continue
            }
            val shot = captureRetry()
            if (shot != null) {
                // checkCaptcha MENGAMBIL ALIH bitmap kalau true — jangan recycle lagi.
                if (checkCaptcha(shot)) {
                    Log.d(TAG, "captcha terdeteksi saat menunggu Submit")
                    continue
                }
                shot.recycle()
            }

            // Cutscene dialog NPC menghalangi Submit dan tidak selesai sendiri.
            if (skipCutsceneIfAny()) continue

            // Misi tipe ambil/interaksi memunculkan tombol bulat di kanan-tengah saat karakter
            // sampai ("Distance to target: 0 m"). LABELNYA BERBEDA-BEDA per misi — terukur di
            // device: "Check" untuk Morning Glory, bukan "Take" seperti dugaan awal. Karena
            // labelnya tidak bisa ditebak, semua kata kerja interaksi yang diketahui didaftar
            // di sini; menambah satu label baru cukup menambah satu entri.
            //
            // minXFrac 0.45 penting: teks quest di tracker kiri bisa memuat kata yang sama
            // ("Check the ...") dan tap ke situ hanya akan memilih ulang quest, bukan interaksi.
            val btn = findAnyTextIn(INTERACT_RECT)
            if (btn != null) {
                // Tap LINGKARAN tombolnya, bukan koordinat teks yang ditemukan — teks itu
                // cuma label di bawahnya dan menekannya tidak memicu apa pun.
                val tx = INTERACT_TAP_X * canonW()
                val ty = INTERACT_TAP_Y * canonH()
                Log.d(TAG, "tombol interaksi '${btn.third}' (label di y=${btn.second.toInt()}) -> tap (${tx.toInt()},${ty.toInt()})")
                hud("interaksi: ${btn.third}")
                tap(tx, ty)
                delay(D_SETTLE)
                continue
            }

            if (findTextBox("submit", 0.30f, 0.95f) != null) {
                Log.d(TAG, "dialog Submit muncul (misi $missionNo, ${System.currentTimeMillis() - start}ms)")
                return true
            }
            val waited = (System.currentTimeMillis() - start) / 1000
            hud("misi $missionNo - menunggu ${waited}s")
        }
        Log.w(TAG, "TIMEOUT menunggu Submit misi $missionNo")
        return false
    }

    /**
     * Loop misi. TERMINASI BERBASIS PERILAKU, bukan hitungan 10: kalau wing tidak muncul lagi,
     * berarti tidak ada misi tersisa -> SELESAI, bukan error. Benar baik untuk 10 misi penuh
     * maupun sisa sebagian (mis. sebagian sudah dikerjakan manual).
     */
    private suspend fun runMissionLoop(maxMissions: Int): Int {
        var done = 0
        while (done < maxMissions) {
            val no = done + 1

            // JALUR CEPAT (permintaan user): sesudah Submit, popup Fly Wing untuk misi
            // berikutnya MUNCUL SENDIRI — terlihat langsung di device pada akhir misi 1.
            // Kalau popupnya sudah ada, seluruh pencarian tracker tidak perlu dijalankan.
            //
            // Cek awalnya TEMPLATE (~30ms), bukan OCR (~1-2 detik): satu-satunya pertanyaan
            // di sini adalah "popupnya ada atau tidak", dan template menjawabnya jauh lebih
            // murah. Latarnya kotak putih solid, jadi SSD-nya stabil — beda dari ikon
            // Carnival yang semi-transparan di atas adegan 3D dan nilainya bergoyang.
            hud("misi $no: cek Fly Wing")
            var flying = false
            if (locateTemplate("map_icons/fly_wing.png", "Fly Wing", FLY_WING_RECT) != null) {
                Log.d(TAG, "template Fly Wing ada -> langsung terbang, lewati pencarian tracker")
                if (tapWing(WingKind.FLY)) flying = true
                else Log.w(TAG, "ikon Fly Wing terlihat tapi tombol Use tak bisa ditekan")
            }

            if (!flying) {
                // TERMINASI BERBASIS PERILAKU: tidak ada entry [Daily] yang memunculkan wing =
                // tidak ada misi papan tersisa = SELESAI, bukan error. Benar untuk 10 misi penuh
                // maupun sisa sebagian (mis. sebagian sudah dikerjakan manual).
                hud("misi $no: pilih quest")
                if (!selectMissionBoardQuest()) {
                    Log.d(TAG, "tidak ada misi Mission Board tersisa -> SELESAI ($done misi)")
                    return done
                }
                hud("misi $no: Fly Wing")
                if (!tapWing(WingKind.FLY)) { abort("Fly Wing tak bisa ditekan, misi $no"); return done }
            }
            delay(D_TRANSPORT)

            if (!waitForSubmit(no)) { abort("timeout menunggu Submit misi $no"); return done }

            hud("misi $no: Submit")
            if (!tapTextButton("submit", 0.30f, 0.95f)) { abort("Submit tak bisa di-tap misi $no"); return done }

            done++
            markActivity()
            Log.d(TAG, "misi $no SELESAI (total $done)")
            delay(D_NAV)
        }
        Log.d(TAG, "batas maxMissions=$maxMissions tercapai")
        return done
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
    /**
     * Carnival -> Mission Board -> Go Now.
     *
     * Dipakai DUA KALI dalam satu siklus misi, dan itu bukan pemborosan: langkah yang sama
     * yang di awal membuka daftar misi, juga yang MEMULAI misi setelah Butterfly Wing
     * mendaratkan karakter (temuan user). Wing mengantar ke PAPANNYA, bukan ke sasaran misi.
     *
     * Pita Go Now sengaja turun sampai 0.95, MELEWATI CHAT_TOP_FRAC: tombolnya ada di
     * y=0.839 (terukur di device), dan saat panel modal terbuka chat justru tertutup panel —
     * jadi pengecualian chat tidak berlaku di konteks ini. Uji pertama gagal persis karena
     * pita 0.45..0.78 memotong tombolnya.
     */
    private suspend fun openMissionBoardGoNow(tries: Int = 1): Boolean {
        hud("🎪 buka Carnival")
        if (!tapTemplate("map_icons/carnival.png", "Carnival", CARNIVAL_RECT, tries)) {
            Log.w(TAG, "ikon Carnival tak ketemu"); return false
        }
        delay(D_NAV)

        hud("📋 buka Mission Board")
        if (!tapTemplate("map_icons/mission_board.png", "Mission Board", MISSION_BOARD_RECT, tries)) {
            Log.w(TAG, "ikon Mission Board tak ketemu"); return false
        }
        delay(D_NAV)

        hud("▶ Go Now")
        if (!tapTextButton("go now", 0.45f, 0.95f)) {
            Log.w(TAG, "tombol Go Now tak ketemu"); return false
        }
        delay(D_NAV)

        // Di LUAR kota, Go Now memunculkan dialog Butterfly Wing untuk mengantar ke papan
        // (koreksi user). Di dalam kota dialog ini tidak muncul, jadi tidak ketemu BUKAN
        // kegagalan.
        if (tapWing(WingKind.BUTTERFLY)) {
            hud("terbang ke papan misi")
            delay(D_TRANSPORT)
        }
        return true
    }

    private suspend fun openAndAcceptMissions(): Boolean {
        if (!openMissionBoardGoNow()) return abort("navigasi ke Mission Board gagal")

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
    /**
     * @param skipNav lewati navigasi Carnival -> Mission Board -> Go Now -> Accept All dan
     *   langsung ke loop misi. ALAT UJI: misi hari ini biasanya sudah ter-accept, dan bagian
     *   yang masih disetel ada di tracker ke bawah — melewati navigasi memangkas ~60 detik
     *   per percobaan. Jangan dipakai di jalur normal: kalau misi belum di-accept, tracker
     *   tidak akan berisi apa pun.
     */
    suspend fun run(maxMissions: Int = 10, skipNav: Boolean = false): Boolean {
        Log.d(TAG, "=== DAILY MISSION mulai (maxMissions=$maxMissions skipNav=$skipNav) ===")
        hud("🎯 daily mission mulai")
        if (!skipNav) {
            if (!openAndAcceptMissions()) return false
        } else {
            Log.d(TAG, "skipNav: langsung ke loop misi, navigasi papan dilewati")
        }
        if (maxMissions <= 0) {
            Log.d(TAG, "maxMissions=0 → berhenti setelah Accept All (mode uji navigasi)")
            hud("✅ navigasi OK (mode uji)")
            return true
        }
        val done = runMissionLoop(maxMissions)
        Log.d(TAG, "=== DAILY MISSION selesai: $done misi ===")
        hud("daily mission selesai: $done misi")
        return done > 0
    }
}
