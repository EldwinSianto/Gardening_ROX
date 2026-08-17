package com.roxgarden

import android.graphics.Bitmap
import android.util.Log
import com.roxgarden.ocr.OcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * FITUR TERPISAH — navigasi ke kota dengan weather boost lewat menu Life > Gardening.
 *
 * Cara membuang:
 *   1. Hapus file ini (CityNavigator.kt)
 *   2. Hapus field cityNavigator + blok navigasi di GardenService (bertanda "CITY NAV")
 *
 * Template yang dibutuhkan di assets/map_icons/:
 *   life_btn.png       — tombol Life di bottom bar
 *   gardening_btn.png  — tile Gardening di menu Ro Life
 *   [plant].png        — ikon tanaman target (set lewat targetPlantAsset)
 */
class CityNavigator(
    private val capture: () -> Bitmap?,
    private val tap: (Float, Float) -> Unit,
    private val ocrProvider: () -> OcrProcessor?,
    private val loadAsset: (String) -> Bitmap?,
    private val tapFrac: (Float, Float) -> Unit = { _, _ -> },  // tap pakai fraksi layar (tombol posisi tetap)
    private val swipe: (Float, Float, Float, Float, Long) -> Unit = { _, _, _, _, _ -> },
    private val regionPx: (String, Bitmap) -> IntArray? = { _, _ -> null },  // ambil bounds region kalibrasi
    private val back: () -> Unit = {}   // tombol Back — tutup panel yang terlanjur terbuka saat gagal
) {
    companion object {
        private const val TAG = "CityNavigator"
        // Match benar ~1800 (Gardening 1841), match salah ~10000+. 6000 = tolak match lemah,
        // paksa scroll-search lanjut sampai ikon tanaman yg benar muncul (Life pakai tapFrac, bukan template).
        private const val TEMPLATE_THRESHOLD = 6000.0
        private const val D_NAV = 2000L   // delay antar langkah navigasi

        // Tombol UI posisi tetap (fraksi layar) — Life di panel kanan
        private const val LIFE_FX = 0.72f
        private const val LIFE_FY = 0.66f

        // Panel kanan punya DUA mode: menu (Guild/Life/Pet/...) dan roda skill. Game sering balik
        // sendiri ke mode skill setelah AFK → tombol Life HILANG total, tap fixed jadi nyasar ke skill.
        // Panah bulat di samping Backpack menukar kedua mode itu.
        private const val PANEL_TOGGLE_FX = 0.963f
        private const val PANEL_TOGGLE_FY = 0.367f
        private const val PANEL_TOGGLE_TRIES = 3
        private const val D_PANEL = 1500L
        // Teks yang HANYA ada saat panel menu terbuka (mode skill cuma nampilkan "Lv5"/"Lv10"/"auto").
        // "life" sendiri sering tak terbaca OCR (ikonnya menimpa label) → sengaja pakai banyak penanda.
        private val MENU_MARKERS = listOf(
            "life", "wardrobe", "companion", "awakening", "divinity", "guild", "mount"
        )

        // Bukti POSITIF ada panel menutupi layar → Back aman ditekan.
        // Termasuk sidebar world map ("Monsters on Current Map", "Lifeskill Area", ...) — panel itu
        // pernah nyangkut terbuka dan bikin garden loop diam berjam-jam.
        private val PANEL_MARKERS = listOf(
            "encyclopedia", "common area", "gardening exp", "growth time",
            // region view (tap minimap) DAN world map (grid kota) — teksnya beda, daftarkan keduanya
            "map info", "monsters on current map", "quests on current map", "lifeskill area",
            "affiliated areas", "defending guild"
        )
        // Bukti POSITIF layar game normal (HUD terlihat) → tidak perlu Back.
        private val CLEAR_MARKERS = listOf("gardening", "backpack", "quest", "world")
        // Dialog keluar game — MUNCUL kalau Back ditekan di layar polos. Wajib di-Cancel.
        private val LEAVE_MARKERS = listOf("leave the game", "leave game")

        // Scroll-search encyclopedia. Swipe PELAN (durasi besar) supaya tidak fling/inersia —
        // fling melompati baris target di antara frame stabil → tanaman terlewat.
        private const val MAX_SCROLL = 14     // langkah kecil & terkontrol → butuh lebih banyak
        private const val SCROLL_TO_TOP = 12  // swipe ke atas dulu (reset posisi) — encyclopedia ingat scroll terakhir
        private const val D_SCROLL = 600L     // delay setelah tiap swipe
        private const val SWIPE_MS = 750L     // durasi swipe (pelan = drag, bukan fling)

        // Verifikasi kedatangan. Diukur live: dari tap tombol lokasi sampai nama kota baru muncul
        // butuh ~60 DETIK (loading map + pathfinding karakter ke kebun). Toleransi 25s dulu bikin
        // navigasi yang SUKSES dilaporkan gagal → planner reset & mengulang percuma.
        private const val ARRIVE_POLLS = 40   // 40 × 2.5s ≈ 100 detik; keluar lebih awal kalau sudah tiba
        private const val D_ARRIVE = 2500L
    }

    // Ganti ini sesuai tanaman yang sedang digarap
    var targetPlantAsset: String = "plants/cactus.png"

    private val templateCache = mutableMapOf<String, Bitmap?>()

    private fun getTemplate(path: String): Bitmap? = templateCache.getOrPut(path) {
        loadAsset(path).also {
            if (it != null) Log.d(TAG, "template '$path' loaded: ${it.width}×${it.height}")
            else Log.w(TAG, "template '$path' tidak ditemukan di assets/")
        }
    }

    private suspend fun captureRetry(): Bitmap? {
        repeat(5) {
            val b = capture()
            if (b != null) return b
            delay(250)
        }
        Log.w(TAG, "captureRetry gagal")
        return null
    }

    // Tunggu layar BERHENTI bergerak (grid selesai scroll) sebelum capture final →
    // hindari template match di frame yang masih meluncur (tap/skip jadi salah).
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
        Log.d(TAG, "captureStable: timeout ${maxWaitMs}ms, pakai frame terakhir")
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
                sum += kotlin.math.abs(la - lb); n++
                x += step
            }
            y += step
        }
        if (n == 0) return true
        return (sum.toDouble() / n / 3.0) <= threshold
    }

    // SSD matching. searchRect (left,top,right,bottom px) membatasi area cari; null = seluruh layar.
    // Return center match + avg SSD (utk caller yang mau cek kualitas), atau null kalau > threshold.
    private fun findTemplate(screen: Bitmap, tmpl: Bitmap, searchRect: IntArray? = null): Pair<Float, Float>? {
        val sw = screen.width; val sh = screen.height
        val tw = tmpl.width;  val th = tmpl.height
        if (sw - tw < 0 || sh - th < 0) return null

        val rl = searchRect?.get(0)?.coerceIn(0, sw - tw) ?: 0
        val rt = searchRect?.get(1)?.coerceIn(0, sh - th) ?: 0
        val rr = (searchRect?.get(2) ?: sw).coerceIn(rl + 1, sw)
        val rb = (searchRect?.get(3) ?: sh).coerceIn(rt + 1, sh)
        val maxDx = (rr - tw).coerceAtLeast(rl)
        val maxDy = (rb - th).coerceAtLeast(rt)

        val screenPx = IntArray(sw * sh)
        screen.getPixels(screenPx, 0, sw, 0, 0, sw, sh)
        val tmplPx = IntArray(tw * th)
        tmpl.getPixels(tmplPx, 0, tw, 0, 0, tw, th)

        var bestSsd = Long.MAX_VALUE
        var bestDx = -1; var bestDy = -1
        val step = 2

        for (dy in rt..maxDy step step) {
            for (dx in rl..maxDx step step) {
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
        return if (avg <= TEMPLATE_THRESHOLD) {
            Log.d(TAG, "match OK avg=${"%.0f".format(avg)} → center=(${bestDx + tw/2},${bestDy + th/2})")
            (bestDx + tw / 2f) to (bestDy + th / 2f)
        } else {
            Log.w(TAG, "match GAGAL avg=${"%.0f".format(avg)} best pos=($bestDx,$bestDy) threshold=$TEMPLATE_THRESHOLD")
            null
        }
    }

    private suspend fun tapTemplate(assetPath: String, label: String): Boolean {
        val tmpl = getTemplate(assetPath) ?: run {
            Log.w(TAG, "tapTemplate '$label': template tidak ada"); return false
        }
        val screen = captureRetry() ?: return false
        val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl) }
        screen.recycle()
        if (pos == null) { Log.w(TAG, "tapTemplate '$label': tidak ketemu di layar"); return false }
        Log.d(TAG, "tap '$label' → (${pos.first.toInt()},${pos.second.toInt()})")
        tap(pos.first, pos.second)
        return true
    }

    // Hitung titik swipe vertikal di dalam grid. Return (gx, yTop, yBot).
    private suspend fun gridSwipeBounds(): Triple<Float, Float, Float>? {
        val screen = captureRetry() ?: return null
        val grid = regionPx("garden_grid", screen)
        val sw = screen.width; val sh = screen.height
        screen.recycle()
        // Jarak swipe KECIL (~26% tinggi grid) → scroll terkontrol ~2 baris, tidak melompat banyak.
        return if (grid != null) {
            val gx = (grid[0] + grid[2]) / 2f
            val yTop = grid[1] + (grid[3] - grid[1]) * 0.40f
            val yBot = grid[1] + (grid[3] - grid[1]) * 0.66f
            Triple(gx, yTop, yBot)
        } else {
            Triple(sw * 0.27f, sh * 0.46f, sh * 0.72f)
        }
    }

    // Reset grid ke paling atas — encyclopedia ingat posisi scroll terakhir, jadi tanaman di atas
    // posisi terakhir tak akan ketemu kalau cuma scroll ke bawah.
    private suspend fun scrollEncyclopediaToTop() {
        val b = gridSwipeBounds() ?: return
        val (gx, yTop, yBot) = b
        Log.d(TAG, "reset encyclopedia ke atas ($SCROLL_TO_TOP×)")
        repeat(SCROLL_TO_TOP) {
            swipe(gx, yTop, gx, yBot, SWIPE_MS)   // konten turun → ke arah awal (atas), pelan biar tak fling
            delay(400L)
        }
    }

    // Cari ikon tanaman di grid encyclopedia (region "garden_grid"). Reset ke atas dulu, lalu scroll ke bawah.
    private suspend fun scrollFindPlant(assetPath: String, label: String): Boolean {
        val tmpl = getTemplate(assetPath) ?: run {
            Log.w(TAG, "scrollFindPlant '$label': template tidak ada"); return false
        }
        scrollEncyclopediaToTop()   // mulai dari posisi yang pasti (atas)
        for (attempt in 0..MAX_SCROLL) {
            val screen = captureStable() ?: return false   // tunggu grid berhenti scroll dulu
            val grid = regionPx("garden_grid", screen)   // null kalau region belum dikalibrasi → cari full screen
            val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl, grid) }
            if (pos != null) {
                screen.recycle()
                Log.d(TAG, "tap '$label' (scroll $attempt) → (${pos.first.toInt()},${pos.second.toInt()})")
                tap(pos.first, pos.second)
                return true
            }
            screen.recycle()
            if (attempt >= MAX_SCROLL) break
            // belum ketemu → swipe ke atas di dalam grid (scroll daftar ke bawah)
            val b = gridSwipeBounds() ?: break
            val (gx, yTop, yBot) = b
            Log.d(TAG, "'$label' belum ketemu, scroll $attempt")
            swipe(gx, yBot, gx, yTop, SWIPE_MS)   // konten naik → item berikutnya, pelan biar tak fling
            delay(D_SCROLL)
        }
        Log.w(TAG, "scrollFindPlant '$label': tidak ketemu setelah $MAX_SCROLL scroll")
        return false
    }

    // OCR layar, cari tombol lokasi di bawah header "Common Area", tap.
    private suspend fun tapLocationButton(targetCity: String): Boolean {
        val ocr = ocrProvider() ?: run { Log.w(TAG, "OCR tidak ready"); return false }
        val screen = captureRetry() ?: return false
        val screenH = screen.height

        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR error: ${t.message}"); screen.recycle(); return false
        }
        screen.recycle()

        Log.d(TAG, "location OCR: ${res.texts.joinToString(" | ")}")

        // Tentukan batas atas: bawah teks "Common Area"
        var minY = screenH * 0.25f   // fallback: abaikan 25% atas
        for (i in res.texts.indices) {
            val t = res.texts[i].lowercase().filter { it.isLetterOrDigit() }
            if (t.contains("commonarea") || t == "common" || t == "commonarea") {
                val r = res.boxes[i].boundingRect()
                minY = r.bottom.toFloat()
                Log.d(TAG, "Common Area ditemukan y=${r.top}..${r.bottom}, minY=${minY.toInt()}")
                break
            }
        }

        val target = targetCity.lowercase().filter { it.isLetterOrDigit() }
        var bestScore = 0.0
        var bestPos: Pair<Float, Float>? = null

        for (i in res.texts.indices) {
            val r = res.boxes[i].boundingRect()
            val cy = (r.top + r.bottom) / 2f
            if (cy < minY) continue   // skip di atas Common Area

            val cand = res.texts[i].lowercase().filter { it.isLetterOrDigit() }
            if (cand.length < 3) continue
            // buang suffix "[Channel N]" → "sogratdesertchannel1" jadi "sogratdesert"
            val core = cand.substringBefore("channel")
            val contains = core.contains(target) || target.contains(core) || cand.contains(target)
            // similarity dihitung pada core (tanpa suffix channel) agar tidak terdilusi
            val sim = maxOf(similarity(core, target), similarity(cand, target))
            val score = if (contains) maxOf(sim, 0.85) else sim
            if (score < 0.6) continue
            if (score > bestScore) {
                bestScore = score
                bestPos = (r.left + r.right) / 2f to cy
                Log.d(TAG, "location match '${res.texts[i]}' core='$core' y=${r.top} sim=${"%.2f".format(score)}")
            }
        }

        if (bestPos == null) {
            Log.w(TAG, "Tombol lokasi '$targetCity' tidak ditemukan (minY=${minY.toInt()})")
            return false
        }
        Log.d(TAG, "tap lokasi '$targetCity' → (${bestPos.first.toInt()},${bestPos.second.toInt()})")
        tap(bestPos.first, bestPos.second)
        return true
    }

    /**
     * Nama kota sekarang ditampilkan di strip kanan-atas (mis. "Sograt Desert  CH 1").
     * Dipakai untuk memverifikasi teleport benar-benar terjadi.
     */
    private suspend fun readCityStrip(): List<String> {
        val ocr = ocrProvider() ?: return emptyList()
        val shot = captureRetry() ?: return emptyList()
        val w = shot.width; val h = shot.height
        val x0 = (w * 0.60f).toInt()
        val cw = (w * 0.40f).toInt(); val ch = (h * 0.09f).toInt()
        val crop = try { Bitmap.createBitmap(shot, x0, 0, cw, ch) } catch (_: Throwable) { null }
        shot.recycle()
        if (crop == null) return emptyList()
        val texts = try {
            ocr.processImage(crop, includeAllConfidenceScores = true).texts
        } catch (_: Throwable) { emptyList() }
        crop.recycle()
        return texts
    }

    private fun cityTextMatches(texts: List<String>, city: String): Boolean {
        val target = city.lowercase().filter { it.isLetterOrDigit() }
        for (t in texts) {
            val cand = t.lowercase().filter { it.isLetterOrDigit() }
            if (cand.length < 3) continue
            val core = cand.substringBefore("ch")   // "sogratdesertch1" → "sogratdesert"
            if (core.contains(target) || cand.contains(target) ||
                similarity(core, target) >= 0.75 || similarity(cand, target) >= 0.75) return true
        }
        return false
    }

    private suspend fun currentCityMatches(city: String): Boolean {
        val texts = readCityStrip()
        val ok = cityTextMatches(texts, city)
        Log.d(TAG, "verifikasi: ${if (ok) "sudah" else "belum"} di '$city' (baca: ${texts.joinToString(" | ")})")
        return ok
    }

    /** Kota mana (dari kandidat) yang sedang ditempati? null kalau tak terbaca. Satu OCR untuk semua. */
    suspend fun detectCurrentCity(candidates: List<String>): String? {
        val texts = readCityStrip()
        val hit = candidates.firstOrNull { cityTextMatches(texts, it) }
        Log.d(TAG, "kota sekarang: ${hit ?: "TIDAK DIKENALI"} (baca: ${texts.joinToString(" | ")})")
        return hit
    }

    /** Tombol Gardening (atau status kebun) terlihat = karakter benar-benar SAMPAI di kebun. */
    private suspend fun gardenReachable(): Boolean {
        val ocr = ocrProvider() ?: return false
        val shot = captureRetry() ?: return false
        val texts = try {
            ocr.processImage(shot, includeAllConfidenceScores = true).texts
        } catch (_: Throwable) { emptyList() }
        shot.recycle()
        val joined = texts.joinToString(" ").lowercase()
        return "gardening" in joined || "until maturity" in joined || "gathering" in joined
    }

    /**
     * Mode force: kota sudah benar, yang salah posisi karakter (nyangkut di tengah map karena
     * disconnect saat pathfinding). Verifikasi kedatangan tak bisa pakai nama kota — nama kotanya
     * memang sudah cocok sejak awal. Jadi tunggu sampai tombol Gardening kelihatan.
     */
    private suspend fun waitUntilGardenReachable(): Boolean {
        repeat(ARRIVE_POLLS) { i ->
            delay(D_ARRIVE)
            if (gardenReachable()) {
                Log.d(TAG, "SAMPAI di kebun (poll ${i + 1})")
                return true
            }
        }
        Log.w(TAG, "GAGAL sampai di kebun setelah $ARRIVE_POLLS poll")
        return false
    }

    /** Tunggu sampai benar-benar sampai di kota target (loading map bisa 5-20 detik). */
    private suspend fun waitUntilArrived(city: String): Boolean {
        repeat(ARRIVE_POLLS) { i ->
            delay(D_ARRIVE)
            if (currentCityMatches(city)) {
                Log.d(TAG, "TIBA di '$city' (poll ${i + 1})")
                return true
            }
        }
        Log.w(TAG, "GAGAL tiba di '$city' setelah ${ARRIVE_POLLS} poll — masih di map lama")
        return false
    }

    /**
     * Status layar. UNKNOWN itu penting: layar loading / kota tanpa tombol Gardening bukan berarti
     * ada panel terbuka. Menyamakan "tidak terbaca" dengan "panel terbuka" bikin Back ditekan di
     * layar polos → game memunculkan dialog "Leave game?".
     */
    private enum class ScreenState { CLEAR, PANEL_OPEN, LEAVE_DIALOG, UNKNOWN }

    private suspend fun readScreenState(): ScreenState {
        val ocr = ocrProvider() ?: return ScreenState.UNKNOWN
        val shot = captureRetry() ?: return ScreenState.UNKNOWN
        val texts = try {
            ocr.processImage(shot, includeAllConfidenceScores = true).texts
        } catch (_: Throwable) { emptyList() }
        shot.recycle()
        val joined = texts.joinToString(" ").lowercase()

        if (LEAVE_MARKERS.any { it in joined }) return ScreenState.LEAVE_DIALOG
        if (PANEL_MARKERS.any { it in joined }) return ScreenState.PANEL_OPEN
        if (CLEAR_MARKERS.any { it in joined }) return ScreenState.CLEAR
        Log.d(TAG, "status layar TIDAK JELAS (loading?) — baca: ${texts.joinToString(" | ")}")
        return ScreenState.UNKNOWN
    }

    /** Dialog "Are you sure you want to leave the game?" → tap Cancel. JANGAN PERNAH Confirm. */
    private suspend fun dismissLeaveDialog(): Boolean {
        val ocr = ocrProvider() ?: return false
        val shot = captureRetry() ?: return false
        val res = try {
            ocr.processImage(shot, includeAllConfidenceScores = true)
        } catch (_: Throwable) { shot.recycle(); return false }
        shot.recycle()
        for (i in res.texts.indices) {
            if (res.texts[i].lowercase().filter { it.isLetter() } != "cancel") continue
            val r = res.boxes[i].boundingRect()
            Log.w(TAG, "dialog LEAVE GAME muncul → tap Cancel (${(r.left + r.right) / 2},${(r.top + r.bottom) / 2})")
            tap((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
            delay(1200)
            return true
        }
        Log.e(TAG, "dialog leave game terdeteksi tapi tombol Cancel tidak ketemu — biarkan, jangan tap acak")
        return false
    }

    /**
     * Tutup panel yang terlanjur terbuka (Life/encyclopedia/map) supaya garden loop tidak buta.
     *
     * ATURAN KERAS: Back HANYA ditekan kalau panel benar-benar TERBUKTI terbuka. Layar polos atau
     * tak terbaca → diam saja; Back di situ memunculkan dialog keluar game.
     */
    suspend fun closePanels() {
        repeat(3) { attempt ->
            when (readScreenState()) {
                ScreenState.CLEAR -> {
                    Log.d(TAG, "layar sudah bersih, tidak perlu Back")
                    return
                }
                ScreenState.LEAVE_DIALOG -> {
                    dismissLeaveDialog()
                    return
                }
                ScreenState.UNKNOWN -> {
                    Log.w(TAG, "status layar tidak jelas → TIDAK menekan Back (hindari dialog keluar game)")
                    return
                }
                ScreenState.PANEL_OPEN -> {
                    Log.d(TAG, "panel terbukti terbuka → Back (percobaan ${attempt + 1})")
                    back()
                    delay(1200)
                }
            }
        }
        Log.w(TAG, "panel mungkin masih terbuka setelah 3× Back — garden loop akan retry sendiri")
    }

    /** Panel kanan sedang mode menu (ada Life) atau mode roda skill? */
    private suspend fun isLifeMenuVisible(): Boolean {
        val ocr = ocrProvider() ?: return false
        val shot = captureRetry() ?: return false
        val w = shot.width; val h = shot.height
        val x0 = (w * 0.66f).toInt(); val y0 = (h * 0.44f).toInt()
        val cw = w - x0; val chh = (h * 0.88f).toInt() - y0
        val crop = try { Bitmap.createBitmap(shot, x0, y0, cw, chh) } catch (_: Throwable) { null }
        shot.recycle()
        if (crop == null) return false
        val texts = try {
            ocr.processImage(crop, includeAllConfidenceScores = true).texts
        } catch (_: Throwable) { emptyList() }
        crop.recycle()

        val joined = texts.joinToString(" ").lowercase()
        val visible = MENU_MARKERS.any { it in joined }
        Log.d(TAG, "panel kanan: ${if (visible) "MENU (Life ada)" else "SKILL (Life tidak ada)"} — baca: ${texts.joinToString(" | ")}")
        return visible
    }

    /**
     * Pastikan panel kanan menampilkan menu, bukan roda skill — kalau masih skill, tap panah tukar.
     * Tanpa ini, tap Life di koordinat tetap akan mendarat di skill (navigasi gagal diam-diam).
     */
    suspend fun ensureLifeMenuOpen(): Boolean {
        repeat(PANEL_TOGGLE_TRIES) { attempt ->
            if (isLifeMenuVisible()) return true
            // Panah tukar cuma boleh ditekan di layar bersih. Kalau ada panel (mis. world map)
            // menutupi, koordinat panah itu jatuh DI ATAS isi panel — bisa ketap tujuan teleport.
            val st = readScreenState()
            if (st != ScreenState.CLEAR) {
                Log.w(TAG, "layar tidak bersih ($st) → panah tukar TIDAK ditekan")
                return false
            }
            Log.d(TAG, "panel kanan mode skill → tap panah tukar (percobaan ${attempt + 1})")
            tapFrac(PANEL_TOGGLE_FX, PANEL_TOGGLE_FY)
            delay(D_PANEL)
        }
        val ok = isLifeMenuVisible()
        if (!ok) Log.w(TAG, "gagal menukar panel kanan ke mode menu — Life tetap tidak terlihat")
        return ok
    }

    /**
     * Navigasi ke kota target lewat Life → Gardening → Plant (scroll-search) → Location button,
     * lalu VERIFIKASI benar-benar tiba. Return true HANYA kalau sudah tiba di kota target.
     * Gagal di langkah mana pun → tutup panel dulu (jangan tinggalkan menu terbuka).
     */
    suspend fun navigateTo(plant: PlantDef, city: String, force: Boolean = false): Boolean {
        targetPlantAsset = plant.iconAsset
        Log.d(TAG, "navigateTo '${plant.displayName}' @ '$city'${if (force) " [FORCE]" else ""} (icon=${plant.iconAsset})")

        // Sudah di kota target? (mis. state 'current' hilang setelah restart) → tak perlu pindah.
        // KECUALI force: kotanya memang sudah benar, tapi karakternya nyangkut di tengah map —
        // justru harus tetap jalan supaya tombol lokasi menuntunnya kembali ke kebun.
        if (!force && currentCityMatches(city)) {
            Log.d(TAG, "sudah berada di '$city', navigasi dilewati")
            return true
        }

        // Panel kanan bisa lagi mode roda skill (sering terjadi setelah AFK) → Life tidak ada.
        if (!ensureLifeMenuOpen()) {
            Log.w(TAG, "navigasi dibatalkan: panel Life tidak bisa dibuka")
            return false
        }

        // Life button: posisi tetap di panel kanan (template terlalu kecil → false positive)
        Log.d(TAG, "tap Life (fixed) → frac($LIFE_FX,$LIFE_FY)")
        tapFrac(LIFE_FX, LIFE_FY)
        delay(D_NAV)

        if (!tapTemplate("map_icons/gardening_btn.png", "Gardening")) { closePanels(); return false }
        delay(D_NAV)

        if (!scrollFindPlant(targetPlantAsset, plant.displayName)) { closePanels(); return false }
        delay(D_NAV)

        if (!tapLocationButton(city)) { closePanels(); return false }

        // Tap saja tidak cukup — pastikan benar-benar sampai.
        val arrived = if (force) waitUntilGardenReachable() else waitUntilArrived(city)
        if (!arrived) { closePanels(); return false }
        return true
    }

    // ── util ──
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i-1] == b[j-1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
