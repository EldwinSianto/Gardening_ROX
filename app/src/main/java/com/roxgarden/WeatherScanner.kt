package com.roxgarden

import android.graphics.Bitmap
import android.util.Log
import com.roxgarden.ocr.OcrProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FITUR TERPISAH — deteksi weather boost gardening.
 *
 * Terdekopel penuh dari GardenService lewat lambda (tidak ada referensi balik).
 * Cara membuang fitur ini kalau gagal/mengganggu:
 *   1. Hapus file ini (WeatherScanner.kt)
 *   2. Hapus blok bertanda "WEATHER SCAN" di GardenService (field + hook UNTIL_MATURITY)
 * Tidak ada bagian lain yang bergantung pada kelas ini.
 *
 * Region debugOverlay yang harus dikalibrasi:
 *   - "weather_row"  : strip forecast 4-slot (di view region/Map Info)
 *   - "minimap_btn"  : minimap kanan-atas (untuk buka map)
 *   - "worldmap_btn" : tombol "World Map" kanan-bawah
 *   - "map_close_x"  : tombol X putih kanan-atas
 */

data class WeatherSlot(
    val time: String,    // "5:40"
    val phase: String,   // "Day" / "Evening" / "Night"
    val type: String,    // "hot" / "sunny" / "drizzle" / ...
    val isBoost: Boolean
)

data class CityForecast(
    val city: String,
    val slots: List<WeatherSlot>,
    val readAt: Long
)

class WeatherScanner(
    private val ocrProvider: () -> OcrProcessor?,
    private val capture: () -> Bitmap?,
    private val tap: (Float, Float) -> Unit,
    private val swipe: (Float, Float, Float, Float, Long) -> Unit,
    private val regionPx: (String, Bitmap) -> IntArray?,
    private val loadAsset: (String) -> Bitmap?,
    private val savePrefs: (String) -> Unit = {},
    private val loadPrefs: () -> String? = { null }
) {
    companion object {
        private const val TAG = "WeatherScanner"

        // Cuaca yang memberi boost gardening (panen terus-menerus)
        private val BOOST_TYPES = setOf("hot", "drizzle", "meteor", "sandstorm")
        // Semua keyword yang dikenali (boost + non-boost) untuk pencocokan OCR
        private val ALL_TYPES = BOOST_TYPES + setOf("sunny", "sunset", "cloudy", "rainy", "snowy")

        private const val SLOT_MS = 20 * 60 * 1000L           // 1 slot forecast = 20 menit
        private const val MIN_RESCAN_MS = 5 * 60 * 1000L      // guard: scan gagal jangan diulang rapat
        private const val ICON_ABOVE_PX = 30f                 // piksel di atas TOP label = pusat ikon kota (skala peta tetap)
        // Zona vertikal aman untuk tap (hindari tepi atas & tepi bawah dekat joystick/dpad)
        private const val SAFE_TOP = 0.14f
        private const val SAFE_BOTTOM = 0.82f

        // Delay antar langkah navigasi (ms) — beri waktu UI animasi
        private const val D_OPEN = 1800L
        private const val D_CITY = 2200L
        private const val D_BACK = 1500L
        private const val D_PAN = 650L
        // Sweep dari posisi sekarang: turun dulu (ke arah kota berikutnya), baru naik kalau gagal.
        private const val SWEEP_DOWN = 9     // langkah ke bawah (selatan)
        private const val SWEEP_UP = 13      // langkah ke atas (utara) sbg fallback
        // Jarak tiap pan sebagai fraksi tinggi layar — KECIL biar kota tidak loncati zona tengah.
        private const val PAN_NEAR = 0.46f
        private const val PAN_FAR = 0.56f

        // Template matching — threshold avgSSD per sampled pixel.
        // 3000: tolak match lemah (mis. emblem guild WoE dekat Morroc yg pernah false-positive SSD=3777).
        // Match asli biasanya jauh lebih rendah (Sagrat ~1728). Kalau Morroc asli > 3000, crop ulang ikonnya.
        private const val TEMPLATE_THRESHOLD = 3000.0
        // Asset path → template bitmap (lazy load, cached)
        private val CITY_ASSETS = mapOf(
            "Glast Heim"    to "map_icons/glast_heim.png",
            "Sograt Desert" to "map_icons/sograt_desert.png",
            "Morroc"        to "map_icons/morroc.png"
        )

        // Alur: game → [minimap_btn] → REGION VIEW → [worldmap_btn] → WORLD MAP.
        // Dua view ini punya teks yang BEDA — jangan tertukar (pernah salah: dipakai penanda region
        // view untuk memverifikasi world map → verifikasi selalu gagal, scan batal, map tertinggal
        // terbuka, garden loop macet).
        // WORLD MAP = grid kota + tombol guild-overlay.
        private val WORLDMAP_MARKERS = listOf(
            "show affiliated areas", "hide defending guild", "affiliated areas", "defending guild"
        )
        // REGION VIEW = panel info peta kota yang sedang ditempati.
        private val REGIONVIEW_MARKERS = listOf(
            "monsters on current map", "quests on current map",
            "other nearby players", "lifeskill area", "map info"
        )

        // Area strip forecast cuaca — posisi selalu sama di panel Map Info, hardcoded.
        // Sesuaikan kalau resolusi MuMu berbeda.
        private const val WEATHER_X1 = 0.600f
        private const val WEATHER_Y1 = 0.100f
        private const val WEATHER_X2 = 0.970f
        private const val WEATHER_Y2 = 0.210f
    }

    // Configurable — nanti bisa di-map per tanaman
    // Urut utara→selatan biar tiap kota berikutnya cuma sedikit di bawah (sweep pendek)
    @Volatile var targetCities: List<String> = listOf("Glast Heim", "Sograt Desert", "Morroc")

    private val forecasts = mutableMapOf<String, CityForecast>()
    @Volatile private var lastScan = 0L
    @Volatile private var scanning = false

    init { loadSavedForecasts() }

    // Template cache — load sekali dari assets, reuse selama service hidup
    private val templateCache = mutableMapOf<String, Bitmap?>()
    private fun getTemplate(city: String): Bitmap? {
        return templateCache.getOrPut(city) {
            val path = CITY_ASSETS[city] ?: return@getOrPut null
            loadAsset(path).also {
                if (it != null) Log.d(TAG, "template '$city' loaded: ${it.width}×${it.height}")
                else Log.w(TAG, "template '$city' tidak ditemukan di assets/$path")
            }
        }
    }

    // SSD template matching di seluruh layar — return center ikon kalau match.
    private fun findTemplateOnScreen(screen: Bitmap, tmpl: Bitmap): Pair<Float, Float>? {
        val sw = screen.width; val sh = screen.height
        val tw = tmpl.width;  val th = tmpl.height
        val maxDx = sw - tw;  val maxDy = sh - th
        if (maxDx < 0 || maxDy < 0) return null

        val screenPx = IntArray(sw * sh)
        screen.getPixels(screenPx, 0, sw, 0, 0, sw, sh)
        val tmplPx = IntArray(tw * th)
        tmpl.getPixels(tmplPx, 0, tw, 0, 0, tw, th)

        var bestSsd = Long.MAX_VALUE
        var bestDx = -1; var bestDy = -1
        val step = 2

        for (dy in 0..maxDy step step) {
            for (dx in 0..maxDx step step) {
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
        val sampledPx = ((tw + step - 1) / step) * ((th + step - 1) / step)
        val avgSsd = bestSsd.toDouble() / sampledPx
        Log.d(TAG, "template SSD=${"%.0f".format(avgSsd)} pos=($bestDx,$bestDy)")
        return if (avgSsd <= TEMPLATE_THRESHOLD)
            Pair((bestDx + tw / 2).toFloat(), (bestDy + th / 2).toFloat())
        else null
    }

    // ── SLOT BERBASIS WAKTU ─────────────────────────────────────────────────
    // Satu scan membawa 4 slot × 20 menit (~80 menit ramalan). Dulu planner selalu membaca
    // slot PERTAMA hasil scan — basi begitu 20 menit lewat, sehingga scan harus diulang tiap
    // 15 menit (60-90 dtk terbuang per scan). Sekarang: slot dipilih menurut JAM SEKARANG,
    // dan scan ulang hanya saat ramalan mau habis.

    // "21:40" → menit-sejak-tengah-malam; null kalau tak terbaca (OCR rusak)
    private fun slotMinutes(t: String): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(t) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        return h * 60 + mi
    }

    /** Timestamp mulai slot — okurensi jam slot yang TERDEKAT dengan readAt (atasi lewat tengah malam). */
    private fun slotStartMs(slot: WeatherSlot, readAt: Long): Long? {
        val mins = slotMinutes(slot.time) ?: return null
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = readAt
            set(java.util.Calendar.HOUR_OF_DAY, mins / 60)
            set(java.util.Calendar.MINUTE, mins % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        var t = cal.timeInMillis
        if (t - readAt > 12 * 3600_000L) t -= 24 * 3600_000L
        if (readAt - t > 12 * 3600_000L) t += 24 * 3600_000L
        return t
    }

    /** Slot yang BERLAKU SEKARANG menurut jam — bukan slot pertama hasil scan. */
    fun currentSlot(fc: CityForecast, nowMs: Long = System.currentTimeMillis()): WeatherSlot? {
        for (s in fc.slots) {
            val start = slotStartMs(s, fc.readAt) ?: continue
            if (nowMs >= start && nowMs < start + SLOT_MS) return s
        }
        return null
    }

    private fun hasNextSlot(fc: CityForecast, nowMs: Long): Boolean =
        fc.slots.any { s -> slotStartMs(s, fc.readAt)?.let { it > nowMs } == true }

    /** Map kota → slot yang berlaku sekarang (null = data habis/absen → dianggap tanpa boost). */
    fun currentSlots(): Map<String, WeatherSlot?> = synchronized(forecasts) {
        targetCities.associateWith { c -> forecasts[c]?.let { currentSlot(it) } }
    }

    /**
     * Perlu scan HANYA kalau ada kota target yang: belum punya data, slot sekarang tak
     * diketahui, atau sedang di slot TERAKHIR (slot berikutnya belum diketahui).
     * Dengan 4 slot per scan, kondisi ini tercapai ~tiap 60 menit — bukan tiap 15 menit.
     */
    fun shouldScan(): Boolean {
        if (scanning) return false
        val now = System.currentTimeMillis()
        if (now - lastScan < MIN_RESCAN_MS) return false
        synchronized(forecasts) {
            return targetCities.any { city ->
                val fc = forecasts[city] ?: return@any true
                currentSlot(fc, now) == null || !hasNextSlot(fc, now)
            }
        }
    }

    fun getForecasts(): Map<String, CityForecast> = synchronized(forecasts) { forecasts.toMap() }

    /** Kota pertama (urut targetCities) yang slot BERLAKU-nya boost. Null kalau tidak ada. */
    fun getBoostCity(): String? = synchronized(forecasts) {
        targetCities.firstOrNull { city -> forecasts[city]?.let { currentSlot(it)?.isBoost } == true }
    }

    // ── Persistence ─────────────────────────────────────────────────────────
    private fun saveForecasts() {
        val arr = org.json.JSONArray()
        synchronized(forecasts) {
            for ((_, fc) in forecasts) {
                val slotsArr = org.json.JSONArray()
                for (s in fc.slots) {
                    slotsArr.put(org.json.JSONObject()
                        .put("time", s.time).put("phase", s.phase)
                        .put("type", s.type).put("isBoost", s.isBoost))
                }
                arr.put(org.json.JSONObject()
                    .put("city", fc.city).put("slots", slotsArr).put("readAt", fc.readAt))
            }
        }
        savePrefs(arr.toString())
        Log.d(TAG, "Forecast saved (${forecasts.size} kota)")
    }

    private fun loadSavedForecasts() {
        val json = loadPrefs() ?: return
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val city = obj.getString("city")
                // Buang kota yang sudah tak dikenal (mis. "Sagrat Desert" — ejaan lama sebelum
                // diperbaiki jadi "Sograt Desert"). Kalau tidak, sisa data lama ikut terus.
                if (city !in CITY_ASSETS) {
                    Log.d(TAG, "Forecast lama '$city' dibuang (tidak ada di katalog kota)")
                    continue
                }
                val readAt = obj.getLong("readAt")
                val slotsArr = obj.getJSONArray("slots")
                val slots = (0 until slotsArr.length()).map { j ->
                    val s = slotsArr.getJSONObject(j)
                    WeatherSlot(s.getString("time"), s.getString("phase"),
                        s.getString("type"), s.getBoolean("isBoost"))
                }
                synchronized(forecasts) { forecasts[city] = CityForecast(city, slots, readAt) }
            }
            Log.d(TAG, "Forecast loaded: ${forecasts.size} kota — boost sekarang: ${getBoostCity() ?: "(tidak ada)"}")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal load saved forecasts: ${e.message}")
        }
    }

    /**
     * World map benar-benar terbuka? Tap tombolnya bisa GAGAL diam-diam (mis. game baru restart /
     * masih loading). Tanpa cek ini, scan menyapu layar game yang polos selama 3 menit, tidak
     * menemukan kota mana pun, lalu tetap "menutup map" — tap penutupnya jatuh liar ke UI game.
     */
    private suspend fun readMapText(ocr: OcrProcessor): String {
        val shot = captureRetry() ?: return ""
        val texts = try {
            ocr.processImage(shot, includeAllConfidenceScores = true).texts
        } catch (_: Throwable) { emptyList() }
        shot.recycle()
        return texts.joinToString(" ").lowercase()
    }

    private suspend fun isWorldMapOpen(ocr: OcrProcessor): Boolean {
        val t = readMapText(ocr)
        return WORLDMAP_MARKERS.any { it in t }
    }

    /** Ada view map apa pun terbuka (region view ATAU world map)? Dipakai untuk menutup saat batal. */
    private suspend fun isAnyMapOpen(ocr: OcrProcessor): Boolean {
        val t = readMapText(ocr)
        return WORLDMAP_MARKERS.any { it in t } || REGIONVIEW_MARKERS.any { it in t }
    }

    /** Buka map (dengan verifikasi + 1× retry). Return false kalau map tetap tidak terbuka. */
    private suspend fun openWorldMap(ocr: OcrProcessor): Boolean {
        repeat(2) { attempt ->
            if (!tapRegion("minimap_btn")) {
                Log.w(TAG, "gagal tap minimap_btn — capture null atau region belum dikalibrasi")
                return false
            }
            delay(D_OPEN)
            if (!tapRegion("worldmap_btn")) {
                Log.w(TAG, "gagal tap worldmap_btn — capture null atau region belum dikalibrasi")
                return false
            }
            delay(D_OPEN)
            if (isWorldMapOpen(ocr)) return true
            Log.w(TAG, "world map belum terbuka setelah tap (percobaan ${attempt + 1})")
            delay(D_OPEN)
        }
        return false
    }

    /** Tutup view map apa pun yang terlanjur terbuka — dipanggil saat scan batal. */
    private suspend fun closeMapIfOpen(ocr: OcrProcessor) {
        repeat(2) {
            if (!isAnyMapOpen(ocr)) return
            Log.d(TAG, "map masih terbuka → tap map_close_x")
            tapRegion("map_close_x")
            delay(900)
        }
        if (isAnyMapOpen(ocr)) Log.w(TAG, "map MASIH terbuka setelah 2× close — garden loop akan pulih sendiri")
    }

    /** Buka map, scan tiap kota target, simpan forecast, tutup map. */
    suspend fun scan() {
        if (scanning) return
        val ocr = ocrProvider() ?: return
        scanning = true
        var mapOpened = false
        try {
            if (!openWorldMap(ocr)) {
                // Batalkan: jangan menyapu layar yang bukan map. TAPI tap tadi mungkin sudah membuka
                // SEBAGIAN (region view) — wajib ditutup, kalau tidak garden loop buta selamanya.
                Log.w(TAG, "world map TIDAK terbuka — scan dibatalkan (forecast lama dipertahankan)")
                closeMapIfOpen(ocr)
                return
            }
            mapOpened = true
            var readOk = 0

            for (city in targetCities) {
                val tapPoint = sweepFindCity(ocr, city)
                if (tapPoint == null) {
                    Log.w(TAG, "Kota '$city' tidak ketemu setelah sweep")
                    continue
                }
                Log.d(TAG, "tap '$city' di (${tapPoint.first}, ${tapPoint.second})")
                tap(tapPoint.first, tapPoint.second)
                delay(D_CITY)

                var opened = false
                val fshot = captureRetry()
                if (fshot != null) {
                    val slots = readForecast(ocr, fshot)
                    fshot.recycle()
                    if (slots.isNotEmpty()) {
                        opened = true
                        readOk++
                        synchronized(forecasts) { forecasts[city] = CityForecast(city, slots, System.currentTimeMillis()) }
                        val summary = slots.joinToString(" | ") {
                            "${it.time} ${it.type}${if (it.isBoost) "*BOOST" else ""}"
                        }
                        Log.d(TAG, "WEATHER $city: $summary")
                    } else {
                        Log.w(TAG, "$city: tap tidak membuka forecast (masih di world map?)")
                    }
                }
                // Balik ke world map untuk kota berikutnya:
                // - kalau forecast kebuka (di region view kota) → tombol World Map
                // - kalau tidak (masih di world map) → tidak perlu apa-apa
                if (opened) { tapRegion("worldmap_btn"); delay(D_BACK) }
            }

            // tutup: world map → region view → game
            tapRegion("map_close_x"); delay(600)
            tapRegion("map_close_x"); delay(600)
            mapOpened = false
            if (readOk == 0) {
                // Semua kota gagal dibaca padahal map terbuka → forecast di memori adalah SISA scan
                // lama. Planner masih memakainya, jadi ini harus kelihatan di log, bukan senyap.
                Log.w(TAG, "TIDAK ADA kota yang berhasil dibaca — forecast di bawah ini data LAMA")
            }
            saveForecasts()
            logBoostSummary()
        } catch (t: Throwable) {
            Log.e(TAG, "scan error: ${t.javaClass.simpleName}: ${t.message}", t)
            // best-effort: pastikan map tertutup agar tidak mengganggu loop gardening
            if (mapOpened) {
                repeat(2) { tapRegion("map_close_x"); }
            }
        } finally {
            lastScan = System.currentTimeMillis()
            scanning = false
        }
    }

    private fun logBoostSummary() {
        val snap = getForecasts()
        val boostCities = snap.values.filter { f -> f.slots.any { it.isBoost } }
            .map { it.city }
        Log.d(TAG, "WEATHER scan selesai. Kota dengan boost: ${if (boostCities.isEmpty()) "(tidak ada)" else boostCities.joinToString(", ")}")
    }

    // capture() bisa null kalau dipanggil tepat setelah swipe (frame belum siap) → retry.
    private suspend fun captureRetry(): Bitmap? {
        repeat(5) {
            val b = capture()
            if (b != null) return b
            delay(250)
        }
        Log.w(TAG, "captureRetry: gagal dapat frame setelah 5x")
        return null
    }

    // Tunggu peta BERHENTI bergerak sebelum capture final — hindari template match di frame
    // yang masih meluncur (tap jadi ke posisi lama). Ambil 2 frame berturut, lanjut kalau mirip.
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

    // Beda dua frame via sampling kasar (luminance). avg kecil = layar diam (stabil).
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
        val avg = sum.toDouble() / n / 3.0   // /3 → per channel
        return avg <= threshold
    }

    // Tap pusat region terkalibrasi (tombol UI tetap). Return false kalau gagal.
    private suspend fun tapRegion(id: String): Boolean {
        val shot = captureRetry()
        if (shot == null) { Log.w(TAG, "tapRegion($id): capture null — MediaProjection mati? restart bot dulu"); return false }
        val px = regionPx(id, shot)
        shot.recycle()
        if (px == null) { Log.w(TAG, "tapRegion($id): region null — belum terdaftar di overlay"); return false }
        val cx = (px[0] + px[2]) / 2f
        val cy = (px[1] + px[3]) / 2f
        Log.d(TAG, "tapRegion($id): tap ($cx, $cy)")
        tap(cx, cy)
        return true
    }

    // Geser peta. revealNorth=true → drag konten ke bawah (buka area utara).
    //               revealNorth=false → drag konten ke atas (buka area selatan).
    private suspend fun pan(revealNorth: Boolean) {
        val shot = captureRetry() ?: return
        val w = shot.width; val h = shot.height
        shot.recycle()
        val cx = w * 0.55f   // sisi kanan area peta (kiri ada panel Map Info)
        val yNear = h * PAN_NEAR
        val yFar = h * PAN_FAR
        if (revealNorth) swipe(cx, yNear, cx, yFar, 320L)   // turun → utara
        else swipe(cx, yFar, cx, yNear, 320L)               // naik → selatan
    }

    // Cari di view sekarang — template match (lebih cepat, lebih akurat posisi).
    // Fallback ke OCR kalau template belum ada di assets.
    private suspend fun findInView(ocr: OcrProcessor, city: String): Pair<Float, Float>? {
        val shot = captureStable() ?: return null   // tunggu peta berhenti dulu → koordinat match valid
        val tmpl = getTemplate(city)
        val result = if (tmpl != null) {
            val pos = withContext(Dispatchers.Default) { findTemplateOnScreen(shot, tmpl) }
            if (pos != null) Log.d(TAG, "'$city' template match di (${pos.first.toInt()},${pos.second.toInt()})")
            pos
        } else {
            findCityTap(ocr, shot, city)   // fallback OCR
        }
        shot.recycle()
        return result
    }

    // Sweep dari posisi sekarang: cek view → turun dulu → naik (fallback). Tanpa reset blind.
    private suspend fun sweepFindCity(ocr: OcrProcessor, city: String): Pair<Float, Float>? {
        findInView(ocr, city)?.let { Log.d(TAG, "'$city' langsung ketemu (tanpa sweep)"); return it }
        for (s in 0 until SWEEP_DOWN) {
            pan(revealNorth = false); delay(D_PAN)   // turun (selatan)
            findInView(ocr, city)?.let { Log.d(TAG, "'$city' ketemu sweep-turun $s"); return it }
        }
        for (s in 0 until SWEEP_UP) {
            pan(revealNorth = true); delay(D_PAN)    // naik (utara) melewati titik awal
            findInView(ocr, city)?.let { Log.d(TAG, "'$city' ketemu sweep-naik $s"); return it }
        }
        return null
    }

    // Cari kota di world map via OCR nama label; return titik tap (ikon di atas label).
    private fun findCityTap(ocr: OcrProcessor, shot: Bitmap, city: String): Pair<Float, Float>? {
        val res = try {
            ocr.processImage(shot, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR world map gagal: ${t.message}"); return null
        }
        Log.d(TAG, "worldmap OCR (${res.texts.size} teks): ${res.texts.joinToString(" | ")}")
        val safeTop = shot.height * SAFE_TOP
        val safeBottom = shot.height * SAFE_BOTTOM
        val target = normalize(city)
        var best: Pair<Float, Float>? = null
        var bestSim = 0.0
        var bestInfo = ""
        for (i in res.texts.indices) {
            val cand = normalize(res.texts[i])
            if (cand.length < 3) continue
            val sim = similarity(cand, target)
            val contains = cand.contains(target) || (target.length >= 4 && target.contains(cand))
            val score = if (contains) maxOf(sim, 0.8) else sim
            if (score < 0.7) continue
            val r = res.boxes[i].boundingRect()
            val labelCy = (r.top + r.bottom) / 2f
            // zona-aman dicek pada LABEL; tapi tap pada IKON di atas label
            if (labelCy < safeTop || labelCy > safeBottom) {
                Log.d(TAG, "'$city'~'${res.texts[i]}' (sim=${"%.2f".format(score)}) di tepi y=${labelCy.toInt()}, lewati")
                continue
            }
            if (score > bestSim) {
                bestSim = score
                val cx = (r.left + r.right) / 2f
                val tapCy = r.top - ICON_ABOVE_PX   // pusat ikon di atas TOP label
                best = cx to tapCy
                bestInfo = "'${res.texts[i]}' sim=${"%.2f".format(score)} label=(${cx.toInt()},${labelCy.toInt()}) top=${r.top.toInt()} tap=(${cx.toInt()},${tapCy.toInt()})"
            }
        }
        if (best != null) Log.d(TAG, "match $city → $bestInfo")
        return best
    }

    // ── forecast strip: crop area hardcoded (posisi selalu sama di panel Map Info) ──
    private fun readForecast(ocr: OcrProcessor, shot: Bitmap): List<WeatherSlot> {
        val x0 = (shot.width  * WEATHER_X1).toInt()
        val y0 = (shot.height * WEATHER_Y1).toInt()
        val x1 = (shot.width  * WEATHER_X2).toInt()
        val y1 = (shot.height * WEATHER_Y2).toInt()
        val w = x1 - x0; val h = y1 - y0
        if (w <= 4 || h <= 2) return emptyList()
        val crop = try { Bitmap.createBitmap(shot, x0, y0, w, h) } catch (_: Throwable) { null } ?: return emptyList()
        val up = Bitmap.createScaledBitmap(crop, w * 2, h * 2, true)
        val text = try {
            ocr.processImage(up, includeAllConfidenceScores = true).texts.joinToString(" ")
        } catch (_: Throwable) { "" }
        if (!up.isRecycled) up.recycle()
        if (!crop.isRecycled) crop.recycle()
        Log.d(TAG, "weather_row raw: '$text'")
        return parseForecastLine(text)
    }

    // Per slot: segmen dari satu waktu ke waktu berikutnya berisi phase + type slot itu.
    private fun parseForecastLine(t: String): List<WeatherSlot> {
        val low = t.lowercase()
        val times = Regex("""\d{1,2}:\d{2}""").findAll(t).toList()
        if (times.isEmpty()) return emptyList()
        val slots = mutableListOf<WeatherSlot>()
        for (i in times.indices) {
            val segStart = times[i].range.first
            val segEnd = if (i + 1 < times.size) times[i + 1].range.first else low.length
            val seg = low.substring(segStart.coerceIn(0, low.length), segEnd.coerceIn(0, low.length))
            val phase = when {
                "night" in seg -> "Night"
                "evening" in seg -> "Evening"
                "morning" in seg -> "Morning"
                else -> "Day"
            }
            val type = ALL_TYPES.firstOrNull { it in seg } ?: "?"
            slots.add(WeatherSlot(times[i].value, phase, type, type in BOOST_TYPES))
        }
        return slots
    }

    // ── util ──
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        return 1.0 - dist.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }
}