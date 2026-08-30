package com.roxgarden

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.roxgarden.ocr.ModelManager
import com.roxgarden.ocr.OcrProcessor
import com.roxgarden.ocr.TextBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

class GardenService : AccessibilityService() {

    companion object {
        // Untuk tombol DIAGNOSA di MainActivity — di device tanpa adb (cloud phone) ini
        // satu-satunya jendela ke dalam service. null = accessibility belum aktif.
        @Volatile var instance: GardenService? = null
        // Persist niat user: true selama user menghendaki bot jalan. Dipakai untuk memutuskan
        // auto-resume saat sistem membunuh proses lalu me-restart service (START_STICKY).
        const val KEY_WAS_RUNNING = "bot_was_running"
    }

    private val TAG = "GardenService"
    private var isRunning = false
    @Volatile private var isHandlingCaptcha = false
    @Volatile private var ocrInProgress = false
    // ── WEATHER SCAN flag: set true saat scan aktif, gardenLoop berhenti re-post ──
    @Volatile private var gardenPaused = false

    // Deteksi screen capture mati (MediaProjection putus) → notifikasi user untuk START ulang
    @Volatile private var captureFailStreak = 0
    @Volatile private var captureDeadNotified = false
    private val CAPTURE_DEAD_THRESHOLD = 8   // ~8× gagal beruntun (≈24s) baru anggap projection mati

    // ── WATCHDOG NAVIGASI ──────────────────────────────────────────────────
    // Karakter bisa berhenti di TENGAH map (disconnect saat pathfinding setelah pindah lokasi).
    // Kotanya benar, jadi navigasi biasa menganggap "sudah sampai" dan tidak berbuat apa-apa —
    // padahal tombol Gardening tak terlihat dan bot mengulang "tidak ada teks dikenali" selamanya.
    // Obatnya: kalau sekian lama TIDAK ADA aktivitas kebun padahal masih di dalam window gardening,
    // paksa navigasi ulang (force) supaya tombol lokasi menuntun karakter kembali ke kebun.
    private val GARDEN_STUCK_MS = 5 * 60_000L
    @Volatile private var lastGardenActivityMs = 0L

    // ── MULTI-DISPLAY (tab MuMu = display terpisah) ────────────────────────
    // Capture & gesture diarahkan ke display tempat game berada — urutan buka tab jadi tak penting.
    private val GAME_PACKAGE = "com.play.rosea"
    @Volatile private var gameDisplayId = 0

    /**
     * Context yang terikat ke DISPLAY GAME, untuk menaruh overlay (HUD, kotak region).
     *
     * Tanpa ini overlay mendarat di display 0 sementara game jalan di display lain (terukur:
     * 14 di MuMu multi-tab). Tidak ada error, tidak ada log gagal - window-nya benar-benar
     * ada, hanya di layar yang tidak dilihat siapa pun. Persis alasan refreshGameDisplay()
     * ada di sisi capture: id display game TIDAK BOLEH diasumsikan 0.
     *
     * Kalau display game belum diketahui atau tidak bisa diambil, jatuh balik ke context
     * service - overlay tetap muncul di suatu tempat, tidak hilang diam-diam.
     */
    private fun overlayContextForGameDisplay(): android.content.Context {
        refreshGameDisplay()
        if (gameDisplayId == 0) return this
        return try {
            val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val d = dm.getDisplay(gameDisplayId)
            if (d == null) this else createDisplayContext(d).also {
                Log.d(TAG, "overlay dipasang di display game $gameDisplayId")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createDisplayContext($gameDisplayId) gagal: ${t.message}")
            this
        }
    }
    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Cari display yang menampilkan game. Perlu flagRetrieveInteractiveWindows. */
    private fun refreshGameDisplay() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val all = windowsOnAllDisplays
            for (i in 0 until all.size()) {
                val id = all.keyAt(i)
                val hasGame = all.valueAt(i).any { w ->
                    try { w.root?.packageName?.toString() == GAME_PACKAGE } catch (_: Throwable) { false }
                }
                if (hasGame) {
                    if (gameDisplayId != id) Log.d(TAG, "Game ditemukan di display $id (sebelumnya $gameDisplayId)")
                    gameDisplayId = id
                    return
                }
            }
            Log.w(TAG, "Game '$GAME_PACKAGE' tidak ditemukan di display mana pun (game belum jalan?)")
        } catch (t: Throwable) {
            Log.w(TAG, "refreshGameDisplay error: ${t.message}")
        }
    }

    // ── ROOT CAPTURE (MuMu multi-tab) ──────────────────────────────────────
    // takeScreenshot()/MediaProjection sering blank di display tab MuMu (display privat, churn id).
    // Root `screencap -d <physicalId>` TERBUKTI bisa memotret display game. Probe deterministik:
    // dumpsys activity → logical display yang punya task game; dumpsys display → map ke physical id.
    private data class GameDisplay(val physId: Long, val logicalId: Int)
    @Volatile private var rootDisplay: GameDisplay? = null

    // Cek root SEKALI. Tanpa guard ini, device non-root (cloud phone) memanggil `su` yang gagal
    // di TIAP iterasi loop — buang ~detik per capture cuma untuk exception.
    @Volatile private var rootAvailable: Boolean? = null
    private fun hasRoot(): Boolean {
        rootAvailable?.let { return it }
        val ok = try {
            rootExec("id")?.toString(Charsets.UTF_8)?.contains("uid=0") == true
        } catch (_: Throwable) { false }
        rootAvailable = ok
        Log.d(TAG, if (ok) "root tersedia — pakai screencap" else "root TIDAK tersedia — pakai screenshot API")
        return ok
    }

    private fun rootExec(cmd: String): ByteArray? {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.readBytes()
            p.waitFor()
            if (out.isNotEmpty()) out else null
        } catch (t: Throwable) {
            Log.w(TAG, "rootExec gagal: ${t.message}"); null
        } finally {
            // WAJIB tutup pipe + destroy — kalau tidak, tiap capture bocor 3 fd + resource native
            p?.let {
                try { it.inputStream.close() } catch (_: Throwable) {}
                try { it.outputStream.close() } catch (_: Throwable) {}
                try { it.errorStream.close() } catch (_: Throwable) {}
                try { it.destroy() } catch (_: Throwable) {}
            }
        }
    }

    // ── SHELL ROOT PERSISTEN ───────────────────────────────────────────────
    // rootExec() mem-fork proses `su` BARU tiap capture (fork + exec + transisi SELinux). Di loop
    // panen capture terjadi 5-6× per siklus, jadi ongkos itu dibayar terus-menerus. Shell ini
    // dibuka sekali, perintah dikirim lewat stdin.
    //
    // Output screencap sengaja TIDAK dialirkan lewat stdout shell: satu byte meleset akan men-
    // desync stream SELAMANYA, dan device target tanpa adb mustahil didiagnosa kalau itu terjadi.
    // Gantinya screencap menulis ke /dev (tmpfs = RAM, bukan disk) dan shell hanya membalas satu
    // baris "OK" — tiap capture berdiri sendiri, desync mustahil.
    private val CAP_TMP = "/dev/roxgarden_cap.raw"
    private var rootShell: Process? = null
    private var rootShellOut: java.io.Writer? = null
    private var rootShellIn: java.io.BufferedReader? = null

    private fun closeRootShell() {
        try { rootShellOut?.close() } catch (_: Throwable) {}
        try { rootShellIn?.close() } catch (_: Throwable) {}
        try { rootShell?.destroy() } catch (_: Throwable) {}
        rootShellOut = null; rootShellIn = null; rootShell = null
    }

    private fun rootShellReady(): Boolean {
        rootShell?.let { p ->
            // exitValue() melempar selama proses masih hidup — itulah cara cek "masih jalan".
            val alive = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
            if (alive) return true
            Log.w(TAG, "shell root mati → buka ulang")
            closeRootShell()
        }
        return try {
            val np = Runtime.getRuntime().exec(arrayOf("su"))
            rootShell = np
            rootShellOut = java.io.OutputStreamWriter(np.outputStream)
            rootShellIn = java.io.BufferedReader(java.io.InputStreamReader(np.inputStream))
            Log.d(TAG, "shell root persisten dibuka")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "shell root gagal dibuka: ${t.message}")
            closeRootShell(); false
        }
    }

    // Buffer raw dipakai ulang: framebuffer 1080p = 8.3MB, dan readBytes() lama menumbuhkan
    // buffer dengan doubling (~4 realokasi + copy 8MB tiap frame). Panjang efektif dikembalikan
    // terpisah karena buffer boleh lebih besar dari isi file.
    private var rawBuf: ByteArray? = null

    private fun rootShellCapture(physId: Long): Pair<ByteArray, Int>? {
        if (!rootShellReady()) return null
        val out = rootShellOut ?: return null
        val inp = rootShellIn ?: return null
        return try {
            // chmod tiap kali: `>` mempertahankan mode file yang sudah ada, tapi file bisa saja
            // baru dibuat (reboot / /dev bersih) — sekali lewat, app selamanya tak bisa membacanya.
            out.write("screencap -d $physId > $CAP_TMP 2>/dev/null; chmod 666 $CAP_TMP; echo OK\n")
            out.flush()
            if (inp.readLine()?.trim() != "OK") { closeRootShell(); return null }
            val f = java.io.File(CAP_TMP)
            val len = f.length().toInt()
            if (len < 16) return null
            val buf = rawBuf?.takeIf { it.size >= len } ?: ByteArray(len).also { rawBuf = it }
            java.io.FileInputStream(f).use { ins ->
                var off = 0
                while (off < len) {
                    val n = ins.read(buf, off, len - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < len) return null
            }
            buf to len
        } catch (t: Throwable) {
            Log.w(TAG, "capture via shell persisten gagal: ${t.message}")
            closeRootShell(); null
        }
    }

    // Bitmap full-res dipakai ulang antar capture — alokasi 8.3MB per frame bikin GC churn berat.
    // TIDAK dipakai ulang kalau tingginya sudah kanonik: di kasus itu toCanonical mengembalikan
    // bitmap INI apa adanya, pemanggil me-recycle-nya, dan capture berikutnya akan memakai bitmap
    // mati. Device 720p asli toh tidak menjalankan pass skala, jadi tidak ada yang hilang.
    private var fullResCache: Bitmap? = null
    private fun fullResBitmap(w: Int, h: Int): Bitmap {
        if (h == CANON_H) return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = fullResCache
        if (c != null && !c.isRecycled && c.width == w && c.height == h) return c
        if (c != null && !c.isRecycled) c.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { fullResCache = it }
    }

    /** Recycle yang aman untuk hasil rootScreencap — bitmap cache tidak boleh ikut mati. */
    private fun releaseFullRes(b: Bitmap) {
        if (b !== fullResCache && !b.isRecycled) b.recycle()
    }

    /** Variance luminance dari framebuffer MENTAH (RGBA_8888) — sampling tiap 32 piksel. */
    private fun rawVariance(raw: ByteArray, offset: Int, w: Int, h: Int): Double {
        val step = 32
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var y = 0
        while (y < h) {
            val rowBase = offset + y * w * 4
            var x = 0
            while (x < w) {
                val i = rowBase + x * 4
                val lum = ((raw[i].toInt() and 0xff) +
                           (raw[i + 1].toInt() and 0xff) +
                           (raw[i + 2].toInt() and 0xff)) / 3.0
                sum += lum; sumSq += lum * lum; n++
                x += step
            }
            y += step
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    // Variance frame terakhir, dihitung dari byte mentah saat capture — menggantikan ~2000
    // panggilan JNI getPixel() pada bitmap jadi. Dibaca oleh captureScreen untuk deteksi blank.
    @Volatile private var lastRawVariance = 0.0

    // screencap raw: header 12/16 byte (w,h,format[,colorspace] LE) + piksel RGBA_8888
    private fun rootScreencap(physId: Long): Bitmap? {
        // Jalur cepat: shell persisten + tmpfs. Gagal → fallback ke fork per-capture (jalur lama).
        val fast = rootShellCapture(physId)
        val raw: ByteArray
        val len: Int
        if (fast != null) {
            raw = fast.first; len = fast.second
        } else {
            val legacy = rootExec("screencap -d $physId") ?: return null
            raw = legacy; len = legacy.size
        }
        if (len < 16) return null
        fun i32(off: Int) = (raw[off].toInt() and 0xff) or ((raw[off + 1].toInt() and 0xff) shl 8) or
            ((raw[off + 2].toInt() and 0xff) shl 16) or ((raw[off + 3].toInt() and 0xff) shl 24)
        val w = i32(0); val h = i32(4)
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
        val need = w * h * 4
        val offset = when {
            len - 16 >= need -> 16
            len - 12 >= need -> 12
            else -> return null
        }
        lastRawVariance = rawVariance(raw, offset, w, h)
        return try {
            val bmp = fullResBitmap(w, h)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(raw, offset, need))
            bmp
        } catch (t: Throwable) { null }
    }

    /** Cari display game: dumpsys activity (logical id yg punya task game) + dumpsys display (map physical). */
    private fun probeGameDisplayRoot(): GameDisplay? {
        val act = rootExec("dumpsys activity activities")?.toString(Charsets.UTF_8) ?: return null
        var logical = -1
        for (m in Regex("""Display #(\d+)""").findAll(act)) {
            val start = m.range.first
            val end = Regex("""Display #\d+""").find(act, m.range.last + 1)?.range?.first ?: act.length
            if (act.substring(start, end).contains(GAME_PACKAGE)) {
                logical = m.groupValues[1].toInt(); break
            }
        }
        if (logical < 0) { Log.w(TAG, "probe: game tidak ditemukan di display mana pun"); return null }

        val disp = rootExec("dumpsys display")?.toString(Charsets.UTF_8) ?: return null
        val m = Regex("""mUniqueId=local:(\d+)\s*[\s\S]{0,120}?mCurrentLayerStack=$logical\b""").find(disp)
        if (m == null) { Log.w(TAG, "probe: physical id utk logical $logical tidak ketemu"); return null }
        val phys = m.groupValues[1].toLong()
        Log.d(TAG, "probe: game di logical=$logical physical=$phys")
        return GameDisplay(phys, logical)
    }

    // Variance luminance (sampling kasar) — frame blank/hitam ≈ 0, layar game tinggi.
    private fun frameVariance(b: Bitmap): Double {
        val step = 32
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                val lum = ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)) / 3.0
                sum += lum; sumSq += lum * lum; n++
                x += step
            }
            y += step
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Screenshot display tertentu via API accessibility (API 30+). BLOCKING — panggil dari background. */
    private fun captureViaScreenshot(displayId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Bitmap? = null
        try {
            takeScreenshot(displayId, screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(res: ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                            out = hw?.copy(Bitmap.Config.ARGB_8888, true)   // mutable: perlu utk maskHud
                            hw?.recycle()
                        } catch (t: Throwable) {
                            Log.w(TAG, "screenshot convert error: ${t.message}")
                        } finally {
                            try { res.hardwareBuffer.close() } catch (_: Throwable) {}
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        // errorCode 3 = interval terlalu rapat (min ~333ms antar screenshot)
                        Log.w(TAG, "takeScreenshot display=$displayId gagal code=$errorCode")
                        latch.countDown()
                    }
                })
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot error: ${t.message}")
            return null
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    // ── MULTI-PLANT (CITY NAV) — pilihan tanaman terurut prioritas + lokasi sekarang ──
    @Volatile private var selectedPlants: List<PlantDef> = emptyList()
    @Volatile private var currentPlantId: String? = null
    @Volatile private var currentCity: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Uncaught coroutine exception: ${t.javaClass.simpleName}: ${t.message}", t)
    })

    // Safety stop counter
    private var consecutiveFails = 0
    private val MAX_CONSECUTIVE_FAILS = 5

    // RapidOCR
    private var ocrProcessor: OcrProcessor? = null
    private var ocrReady = false

    // OCR khusus captcha (rec model di-train sendiri untuk digit + - *)
    private var captchaOcrProcessor: OcrProcessor? = null

    // ── WEATHER SCAN (fitur terpisah, bisa dihapus tanpa efek ke lainnya) ──
    private val weatherScanner by lazy {
        WeatherScanner(
            ocrProvider = { ocrProcessor },
            capture = { captureScreen() },
            tap = { x, y -> performTap(x, y) },
            swipe = { x1, y1, x2, y2, dur -> performSwipe(x1, y1, x2, y2, dur) },
            regionPx = { id, bmp -> regionPx(id, bmp) },
            loadAsset = { path ->
                try { android.graphics.BitmapFactory.decodeStream(assets.open(path)) }
                catch (_: Throwable) { null }
            },
            savePrefs = { json ->
                getSharedPreferences("weather_data", MODE_PRIVATE).edit()
                    .putString("forecasts", json).apply()
            },
            loadPrefs = {
                getSharedPreferences("weather_data", MODE_PRIVATE).getString("forecasts", null)
            }
        )
    }
    // ── CITY NAV (fitur terpisah, bisa dihapus tanpa efek ke lainnya) ──
    private val cityNavigator by lazy {
        CityNavigator(
            capture = { captureScreen() },
            tap = { x, y -> performTap(x, y) },
            ocrProvider = { ocrProcessor },
            loadAsset = { path ->
                try { android.graphics.BitmapFactory.decodeStream(assets.open(path)) }
                catch (_: Throwable) { null }
            },
            tapFrac = { fx, fy -> performTap(fx * screenWidth, fy * screenHeight) },
            swipe = { x1, y1, x2, y2, dur -> performSwipe(x1, y1, x2, y2, dur) },
            regionPx = { id, bmp -> regionPx(id, bmp) },
            back = { performGlobalAction(GLOBAL_ACTION_BACK) }
        )
    }

    // ── DAILY MISSION (fitur terpisah, bisa dibuang) ──────────────────────
    private val dailyMissionRunner by lazy {
        DailyMissionRunner(
            capture = { captureScreen() },
            tap = { x, y -> performTap(x, y) },
            swipe = { x1, y1, x2, y2, dur -> performSwipe(x1, y1, x2, y2, dur) },
            ocrProvider = { ocrProcessor },
            loadAsset = { path ->
                try { android.graphics.BitmapFactory.decodeStream(assets.open(path)) }
                catch (_: Throwable) { null }
            },
            hud = { text -> hud(text) },
            checkCaptcha = { shot -> interceptCaptcha(shot) },
            isCaptchaActive = { isHandlingCaptcha },
            closePanels = { cityNavigator.closePanels() },
            markActivity = { markGardenActivity() },
            markRegion = { r -> debugOverlay?.markActiveRect(r) }
        )
    }

    /**
     * Pemicu Daily Mission. KONTRAK WAJIB: gardenLoop.run() TIDAK menjadwalkan ulang dirinya
     * saat gardenPaused true — ia return begitu saja. Jadi siapa pun yang mem-pause WAJIB
     * mem-post ulang loop di finally. Kalau terlewat, gardening MATI DIAM-DIAM sampai app
     * di-restart: tanpa error, tanpa gejala di HUD.
     *
     * markGardenActivity() juga wajib: run bisa 10-30 menit sedangkan GARDEN_STUCK_MS cuma
     * 5 menit, jadi tanpa reset watchdog langsung memicu navigasi ulang yang tak perlu.
     */
    fun startDailyMission(maxMissions: Int, skipNav: Boolean = false) {
        if (gardenPaused || isHandlingCaptcha) {
            Log.w(TAG, "Daily mission ditolak: bot sedang sibuk (paused=$gardenPaused captcha=$isHandlingCaptcha)")
            hud("⚠ daily mission ditolak, bot sibuk")
            return
        }
        gardenPaused = true
        handler.removeCallbacks(gardenLoop)
        scope.launch {
            try {
                dailyMissionRunner.run(maxMissions, skipNav)
            } catch (t: Throwable) {
                Log.e(TAG, "Daily mission crash: ${t.javaClass.simpleName}: ${t.message}")
                hud("💥 daily mission error")
            } finally {
                // Misi harian meninggalkan karakter di map mana pun (Comodo Cave, Sograt
                // Desert, ...). Gardening akan BUTA di sana sampai watchdog 5 menit menyala,
                // jadi navigasi balik dilakukan sekarang selagi gardenPaused masih true.
                //
                // currentCity/currentPlantId WAJIB direset dulu: keduanya mencatat lokasi
                // terakhir untuk GARDENING, dan sesudah misi keduanya basi. Tanpa reset,
                // planAndNavigate() membandingkan tujuan dengan catatan basi itu,
                // menyimpulkan "sudah di lokasi", lalu tidak pindah ke mana-mana.
                try {
                    currentCity = null
                    currentPlantId = null
                    reloadPlantSelection()
                    if (!planAndNavigate()) {
                        Log.w(TAG, "navigasi balik ke kebun tidak jalan (tidak ada boost?) — " +
                            "gardening mengandalkan watchdog")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "navigasi balik ke kebun gagal: ${t.message}")
                }
                markGardenActivity()
                gardenPaused = false
                handler.post { handler.postDelayed(gardenLoop, 6000) }
                Log.d(TAG, "=== DAILY MISSION selesai, gardening lanjut ===")
            }
        }
    }

    // Trigger test via ADB: adb shell am broadcast -a com.roxgarden.DAILY_MISSION --ei max 0
    // Tambah --ez skipnav true untuk langsung ke loop misi (lewati navigasi papan) saat menyetel.
    private val dailyMissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val max = intent?.getIntExtra("max", 10) ?: 10
            val skipNav = intent?.getBooleanExtra("skipnav", false) ?: false
            Log.d(TAG, "DAILY MISSION dipicu manual via broadcast (max=$max skipnav=$skipNav)")
            startDailyMission(max, skipNav)
        }
    }
    // Trigger test via ADB: adb shell am broadcast -a com.roxgarden.SCAN_WEATHER
    private val weatherTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "WEATHER scan dipicu manual via broadcast")
            gardenPaused = true
            handler.removeCallbacks(gardenLoop)
            scope.launch {
                try {
                    reloadPlantSelection()
                    weatherScanner.scan()
                    planAndNavigate()
                } finally {
                    gardenPaused = false
                    handler.post { handler.postDelayed(gardenLoop, 6000) }
                }
            }
        }
    }
    // Test navigasi: adb shell am broadcast -a com.roxgarden.NAV_BOOST [--es plant cactus] [--es city Morroc]
    private val navTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadPlantSelection()
            val plantId = intent?.getStringExtra("plant")
            val cityArg = intent?.getStringExtra("city")
            gardenPaused = true
            handler.removeCallbacks(gardenLoop)
            scope.launch {
                try {
                    if (plantId != null) {
                        val plant = PlantCatalog.byId(plantId) ?: run {
                            Log.w(TAG, "NAV_BOOST: plant '$plantId' tidak ada di katalog"); return@launch
                        }
                        val city = cityArg ?: plant.cities.firstOrNull() ?: run {
                            Log.w(TAG, "NAV_BOOST: '$plantId' tak punya kota"); return@launch
                        }
                        Log.d(TAG, "NAV_BOOST manual → '${plant.displayName}' @ '$city'")
                        if (cityNavigator.navigateTo(plant, city)) { currentPlantId = plant.id; currentCity = city }
                    } else {
                        planAndNavigate()
                    }
                } finally {
                    gardenPaused = false
                    handler.post { handler.postDelayed(gardenLoop, 6000) }
                }
            }
        }
    }
    // Test watchdog nyangkut TANPA menunggu 5 menit:
    //   adb shell am broadcast -a com.roxgarden.STUCK_TEST
    private val stuckTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "STUCK_TEST: paksa pemulihan navigasi sekarang")
            lastGardenActivityMs = System.currentTimeMillis() - GARDEN_STUCK_MS - 1000
            recoverStuckNavigation(gardenLoop)
        }
    }
    // Test panel kanan (skill ↔ menu) TANPA pindah map:
    //   adb shell am broadcast -a com.roxgarden.PANEL_TEST
    private val panelTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            gardenPaused = true
            handler.removeCallbacks(gardenLoop)
            scope.launch {
                try {
                    val ok = cityNavigator.ensureLifeMenuOpen()
                    Log.d(TAG, "PANEL_TEST hasil: ${if (ok) "panel menu SIAP (Life terlihat)" else "GAGAL buka panel menu"}")
                } finally {
                    gardenPaused = false
                    handler.post { handler.postDelayed(gardenLoop, 6000) }
                }
            }
        }
    }
    // ──────────────────────────────────────────────────────────────────────

    // Hint angka dari teks popup captcha (dibaca dari area status yang lebih luas)
    private var captchaStatusHint: String = ""

    // Koordinat tombol Gardening (relatif)
    private val GARDENING_BTN_X = 0.617f
    private val GARDENING_BTN_Y = 0.447f
    // Radius pencarian orb di sekitar titik kalibrasi (fraksi lebar layar). 0.05 ≈ 64px di 1280.
    private val ORB_SEARCH_MARGIN = 0.05f

    // Delay constants (ms) â€" mirip Python
    private val DELAY_LOOP            = 3000L
    private val DELAY_AFTER_TAP       = 100L    // cek cepat setelah tap — status gather langsung terbaca
    // PERCOBAAN 2026-08-18: 5000 → 2500. Nilai 5000 dulu permintaan eksplisit user, tapi
    // log MuMu menunjukkan siklus panen 11,8 dtk = cast ~6,8 dtk (dipaksakan game) + jeda ini
    // 4,87 dtk — 41% waktu bot habis di konstanta yang KITA pilih. Banner "Obtained <item>"
    // terbaca SEBELUM jeda ini mulai, jadi itemnya sudah di tangan saat bot masih menunggu.
    // 20 siklus berturut-turut tanpa satu pun tap no-op → di 4,87 dtk tanaman pasti siap;
    // yang belum diketahui adalah seberapa awal ia siap. Turunkan lalu hitung "tak bereaksi"
    // di log: kalau tap mulai gagal, lantainya ketemu — noopTapStreak yang menahan tap.
    // Percobaan ke-2: 2500 -> 1200. Di 2500 hasilnya 0 no-op dari 8 siklus, artinya lantainya
    // belum tersentuh. Langkahnya sengaja tidak lebih agresif dari ini: tiap tap no-op
    // membangunkan TAP_HOLD_MS (15 dtk), jadi menembak terlalu jauh ke bawah justru lebih
    // lambat daripada nilai yang kekecilan sedikit.
    private val DELAY_AFTER_GATHER    = 1200L

    // JANGAN samakan dengan DELAY_AFTER_GATHER — dua hal yang berbeda, dan menyamakannya
    // sudah pernah bikin regresi (2026-08-18, tertangkap oleh audit template-vs-OCR).
    //
    // DELAY_AFTER_GATHER dipakai SESUDAH batas cast terdeteksi: gather sudah pasti selesai,
    // jeda ini murni untuk pickup. Konstanta di bawah dipakai saat OCR membaca status
    // "Gathering" — di situ kita TIDAK tahu gather sudah berjalan berapa lama, jadi jedanya
    // harus cukup untuk satu cast penuh. Dengan 1200 bot men-tap di tengah gather: 7 dari 20
    // tap fast-path jadi no-op, dan siklus terlihat "cepat" padahal cuma berputar sia-sia.
    private val DELAY_GATHERING_WAIT  = 5000L
    private val DELAY_INSUFFICIENT    = 60_000L
    private val DELAY_PLANT_NOT_READY = 300_000L
    private val DELAY_CAPTCHA_TIMEOUT = 35_000L

    /**
     * Jeda pickup DIKURANGI biaya capture. Urutan sesudah jeda ini selalu: bangun → capture →
     * cek orb → tap. Tanpa kompensasi, tap mendarat di DELAY_AFTER_GATHER + durasi capture,
     * jadi jeda 5 detik yang diminta user sebenarnya berjalan ~5.6 detik di device.
     *
     * Hanya jalur ini yang dikompensasi. `delay(700)` di scheduleAfterTap TIDAK: di situ frame
     * diambil pada awal eksekusi screencap, jadi frame-nya memang mendarat di ~700ms — menguranginya
     * justru membuat pengecekan terlalu dini. Sisa tidur maturity sudah punya kompensasi sendiri.
     *
     * Lantai 60%: EMA yang melonjak (host emulator ngadat) tidak boleh mengkerutkan jeda sampai
     * pickup belum selesai.
     */
    // JEDA PICKUP ADAPTIF (permintaan user: "banyak jeda terbuang karena gardening delay").
    //
    // DELAY_AFTER_GATHER sekarang cuma PLAFON dan titik mulai, bukan nilai tetap. Nilai
    // sebenarnya dicari sendiri: tiap kali tap sesudah jeda ini BERHASIL, jedanya dipangkas
    // sedikit; tiap kali GAGAL, dinaikkan lebih banyak. Ia akan mengendap tepat di atas
    // batas aslinya dan bergoyang ~100ms di sekitarnya.
    //
    // Naik lebih besar daripada turun (300 vs 100) DISENGAJA: ongkos kedua arah tidak sama.
    // Terlalu lambat cuma membuang ~100ms; terlalu cepat membuang satu putaran penuh
    // (tap gagal + capture verifikasi + jadwal ulang 200ms), jadi mundurnya harus tegas.
    //
    // Batas ini beda tiap device dan bisa jadi beda tiap tanaman — itulah kenapa dicari
    // sendiri alih-alih ditebak sekali lalu ditulis mati seperti sebelumnya.
    @Volatile private var pickupLearned = DELAY_AFTER_GATHER
    private val PICKUP_FLOOR_MS = 150L
    private val PICKUP_SHAVE_MS = 100L
    private val PICKUP_BACKOFF_MS = 300L

    // Sampai kapan tap berikutnya dianggap PERCOBAAN pickup. Pakai tenggat, bukan boolean:
    // kalau siklus berikutnya ternyata tidak jadi men-tap (mis. OCR membaca maturity),
    // boolean akan tertinggal menyala dan no-op maturity yang asli salah dituduh sebagai
    // percobaan pickup. Tenggat kedaluwarsa sendiri.
    @Volatile private var pickupProbeUntil = 0L

    private fun pickupDelay(): Long {
        val cap = perf.ema(PerfStats.CAP)
        if (cap <= 0L) return pickupLearned
        return (pickupLearned - cap).coerceAtLeast(PICKUP_FLOOR_MS)
    }
    private val NUMPAD_TEMPLATE_THRESHOLD = 2500.0 // avgSAD max untuk template match valid

    // Debug overlay
    private var debugOverlay: DebugOverlayManager? = null

    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = 1280
    private var screenHeight = 720
    private val SCREEN_DENSITY = 320

    // ── NORMALISASI RESOLUSI ────────────────────────────────────────────────
    // Semua template dicrop di 1280×720 dan SSD mencocokkan piksel 1:1 — di resolusi lain match
    // gagal TOTAL (bukan meleset). Maka semua analisis (template/OCR/region/captcha) bekerja di
    // ruang KANONIK tinggi 720: capture di-scale ke sini (screenWidth/Height = dimensi kanonik),
    // dan performTap/performSwipe memetakan koordinat kanonik balik ke resolusi asli display.
    private val CANON_H = 720
    @Volatile private var realScreenWidth = 1280f
    @Volatile private var realScreenHeight = 720f

    /** Ambil ukuran asli display game dari DisplayManager — dipakai sebelum capture pertama
     *  dan untuk jalur MediaProjection (bitmap mirror-nya bukan ukuran display asli). */
    private fun updateRealDisplaySize() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val d = dm.getDisplay(gameDisplayId) ?: return
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") d.getRealSize(p)
            if (p.x > 0 && p.y > 0) {
                realScreenWidth = p.x.toFloat(); realScreenHeight = p.y.toFloat()
                Log.d(TAG, "display game: ${p.x}×${p.y} (kanonik ${screenWidth}×${screenHeight})")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "updateRealDisplaySize error: ${t.message}")
        }
    }

    // ── HUD STATUS LIVE ─────────────────────────────────────────────────────
    @Volatile private var botHud: BotHud? = null

    /** Update baris status HUD + log. Aman dipanggil dari thread mana pun. */
    private fun hud(text: String) {
        botHud?.status(text)
    }

    /**
     * Tutup area panel HUD dengan hitam di frame capture — bot TIDAK BOLEH membaca HUD-nya
     * sendiri (teks status bisa match fuzzy OCR, mis. nama kota → tap nyasar ke HUD).
     * Bitmap dari beberapa jalur bisa immutable → gagal mask cuma di-log, bukan fatal.
     */
    private fun maskHud(b: Bitmap) {
        if (botHud?.isShowing != true) return
        try {
            val c = Canvas(b)
            c.drawRect(0f, b.height * BotHud.MASK_Y, b.width * BotHud.MASK_X, b.height.toFloat(),
                Paint().apply { color = Color.BLACK })
        } catch (t: Throwable) {
            Log.w(TAG, "maskHud gagal (bitmap immutable?): ${t.message}")
        }
    }

    /** Scale capture ke tinggi kanonik 720 (aspect dipertahankan). Set dimensi real + kanonik. */
    private fun toCanonical(b: Bitmap): Bitmap {
        realScreenWidth = b.width.toFloat(); realScreenHeight = b.height.toFloat()
        if (b.height == CANON_H) {
            screenWidth = b.width; screenHeight = b.height
            return b
        }
        val w = Math.round(b.width * CANON_H.toFloat() / b.height)
        // Bilinear (filter=true) WAJIB dipertahankan: template orb adalah aset yang dicrop di
        // 1280×720 dan dicocokkan SSD dengan ambang keras. Downscale nearest-neighbour
        // menghasilkan piksel berbeda → SSD naik → orb gagal match diam-diam.
        val scaled = Bitmap.createScaledBitmap(b, w, CANON_H, true)
        // b bisa jadi bitmap cache full-res yang dipakai ulang capture berikutnya — jangan recycle.
        if (scaled !== b && b !== fullResCache) b.recycle()
        screenWidth = scaled.width; screenHeight = scaled.height
        return scaled
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // LIFECYCLE
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "GardenService connected")
        debugOverlay = DebugOverlayManager(this) { overlayContextForGameDisplay() }
        // ── DAILY MISSION (removable): daftarkan region fitur ini ke overlay debug ──
        debugOverlay?.registerRegions(
            dailyMissionRunner.debugRegions().mapIndexed { i, (id, label, r) ->
                // Warna dipisah per region supaya kotak yang bertumpang tindih (Fly Wing dan
                // tombol interaksi hanya beda ~20px) masih bisa dibedakan sekilas.
                val palette = intArrayOf(
                    Color.argb(220, 255, 90, 90), Color.argb(220, 90, 200, 255),
                    Color.argb(220, 120, 255, 120), Color.argb(220, 255, 220, 80),
                    Color.argb(220, 255, 130, 255), Color.argb(220, 130, 255, 230),
                    Color.argb(220, 255, 170, 60)
                )
                DebugOverlayManager.Region(id, label, palette[i % palette.size],
                    r[0], r[1], r[2], r[3])
            }
        )
        botHud = BotHud(this) { overlayContextForGameDisplay() }
        initOcr()
        // Service connect tapi bot tidak running = habis boot emulator / reinstall / proses mati.
        // Loop & MediaProjection tak bisa auto-resume → beri tahu user (delay 15s: kalau user
        // memang lagi menekan START, isRunning sudah true dan notifikasi batal).
        handler.postDelayed({
            if (!isRunning) {
                Log.w(TAG, "Service connected tapi bot tidak running → notifikasi user")
                postAlert(
                    "⚠️ Bot tidak jalan",
                    "Service hidup tapi bot belum di-START (habis restart emulator?).",
                    "Emulator/app baru saja restart — bot tidak bisa lanjut otomatis karena izin " +
                    "screen capture hilang. Buka RoxGarden lalu tekan START GARDENING."
                )
            }
        }, 15000)
        // ── WEATHER SCAN test trigger (removable) ──
        val wf = IntentFilter("com.roxgarden.SCAN_WEATHER")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(weatherTestReceiver, wf, Context.RECEIVER_EXPORTED)
        else registerReceiver(weatherTestReceiver, wf)
        // ── CITY NAV test trigger (removable) ──
        val nf = IntentFilter("com.roxgarden.NAV_BOOST")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(navTestReceiver, nf, Context.RECEIVER_EXPORTED)
        else registerReceiver(navTestReceiver, nf)
        val pf = IntentFilter("com.roxgarden.PANEL_TEST")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(panelTestReceiver, pf, Context.RECEIVER_EXPORTED)
        else registerReceiver(panelTestReceiver, pf)
        val sf = IntentFilter("com.roxgarden.STUCK_TEST")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stuckTestReceiver, sf, Context.RECEIVER_EXPORTED)
        else registerReceiver(stuckTestReceiver, sf)
        // ── DAILY MISSION test trigger (removable) ──
        val df = IntentFilter("com.roxgarden.DAILY_MISSION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dailyMissionReceiver, df, Context.RECEIVER_EXPORTED)
        else registerReceiver(dailyMissionReceiver, df)
        // ───────────────────────────────────────
    }

    private fun initOcr() {
        scope.launch {
            try {
                Log.d(TAG, "Initializing RapidOCR...")
                val mm = ModelManager(applicationContext)
                val modelFiles = mm.ensureModels()
                ocrProcessor = OcrProcessor(applicationContext, modelFiles)
                // OCR khusus captcha — reuse det+cls, rec+dict khusus
                try {
                    val capFiles = mm.ensureCaptchaModels(modelFiles)
                    if (capFiles != null) {
                        captchaOcrProcessor = OcrProcessor(applicationContext, capFiles)
                        Log.d(TAG, "Captcha OCR ready (rec=${capFiles.version})")
                    } else {
                        Log.w(TAG, "Captcha OCR model tidak ada, pakai OCR umum")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Captcha OCR init gagal: ${e.message}")
                }
                ocrReady = true
                Log.d(TAG, "RapidOCR ready")
            } catch (e: Exception) {
                Log.e(TAG, "OCR init gagal: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent null = service di-restart sistem (START_STICKY) setelah proses dibunuh.
        // DULU buntu (MediaProjection mati bersama proses → wajib START manual). SEKARANG capture
        // pakai root screencap / screenshot API yang tak butuh izin ulang → bot bangkit sendiri.
        if (intent == null) {
            Log.w(TAG, "Service di-restart sistem (proses sempat mati)")
            startForeground(1, buildNotification())
            val wasRunning = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_RUNNING, false)
            if (wasRunning) {
                // Delay: beri waktu accessibility rebind + OCR init sebelum loop pertama.
                Log.w(TAG, "Bot sedang running sebelum dibunuh → auto-resume dalam 8 detik")
                handler.postDelayed({
                    if (!isRunning) {
                        startBot(null)
                        notifyAutoResumed()
                    }
                }, 8000)
            } else {
                Log.d(TAG, "Bot tidak sedang running sebelum mati — tidak auto-resume")
            }
            return START_STICKY
        }
        when (intent.action) {
            "START" -> {
                startBot(intent.getParcelableExtra("projection_data"))
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_WAS_RUNNING, true).apply()
            }
            "STOP" -> {
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_WAS_RUNNING, false).apply()
                isRunning = false
                isHandlingCaptcha = false
                consecutiveFails = 0
                handler.removeCallbacks(gardenLoop)
                debugOverlay?.hide()
                botHud?.hide()
                mediaProjection?.stop()
                virtualDisplay?.release()
                imageReader?.close()
                ocrProcessor?.close()
                captchaOcrProcessor?.close()
                mediaProjection = null; imageReader = null; virtualDisplay = null; ocrProcessor = null; captchaOcrProcessor = null; ocrReady = false
                stopForeground(true)
                Log.d(TAG, "Stopped")
            }
            "DEBUG" -> {
                debugOverlay?.toggle()
                Log.d(TAG, "Debug overlay: ${if (debugOverlay?.isShowing == true) "ON" else "OFF"}")
            }
            "WATCH" -> {
                debugOverlay?.toggleWatch()
                Log.d(TAG, "Watch overlay: ${if (debugOverlay?.isWatching == true) "ON" else "OFF"}")
            }
            "HUD" -> botHud?.toggle()
            "DEBUG_LOG" -> debugOverlay?.logAll()
        }
        return START_STICKY
    }

    /** Nyalakan bot — dipakai START manual dan auto-resume setelah proses dibunuh sistem. */
    private fun startBot(projectionData: Intent?) {
        startForeground(1, buildNotification())
        botHud?.show()
        hud("▶ bot mulai")
        if (projectionData != null) setupMediaProjection(projectionData)
        else Log.d(TAG, "projection_data null — pakai root/screenshot API")
        refreshGameDisplay()   // cari display tempat game (multi-tab MuMu)
        updateRealDisplaySize()   // ukuran asli display → pemetaan tap kanonik→real

        isRunning = true
        isHandlingCaptcha = false
        consecutiveFails = 0
        captureFailStreak = 0
        markGardenActivity()   // mulai hitungan watchdog dari sekarang
        captureDeadNotified = false
        getSystemService(NotificationManager::class.java).cancel(2)   // bersihkan alert lama
        if (!ocrReady) initOcr()

        // MULTI-PLANT: muat pilihan tanaman & turunkan kota yang perlu di-scan
        reloadPlantSelection()

        // ── DAILY MISSION: cek otomatis saat START (permintaan user) ──────────
        // Bot memeriksa sendiri centang di ikon Mission Board, jadi tidak ada tombol manual
        // yang harus diingat — penting karena target sebenarnya cloud phone tanpa adb.
        //
        // Dijalankan SEBELUM garden loop, dan startDailyMission() sendiri yang memegang
        // kontrak pause/resume: begitu misi selesai ia mengembalikan gardenPaused=false,
        // menavigasikan karakter balik ke kebun, lalu menjadwalkan gardenLoop. Jadi cabang
        // "belum selesai" TIDAK memposting loop di sini — kalau keduanya memposting, akan
        // ada dua rantai loop berjalan bersamaan.
        scope.launch {
            val done = try {
                dailyMissionRunner.dailyMissionsDone()
            } catch (t: Throwable) {
                Log.w(TAG, "cek daily gagal: ${t.message} — lanjut gardening")
                true
            }
            if (done) {
                Log.d(TAG, "Daily sudah selesai → langsung gardening")
                handler.post {
                    Log.d(TAG, "Starting garden loop")
                    handler.postDelayed(gardenLoop, 2000)
                }
            } else {
                Log.d(TAG, "Daily belum selesai → jalankan daily mission dulu")
                hud("🎯 daily belum selesai, kerjakan dulu")
                handler.post { startDailyMission(10) }
            }
        }
    }

    // ── MULTI-PLANT helpers ────────────────────────────────────────────────
    private fun reloadPlantSelection() {
        var sel = PlantConfig.getSelected(this)
        if (sel.isEmpty() && PlantCatalog.ALL.isNotEmpty()) {
            sel = listOf(PlantCatalog.ALL.first())   // fallback: tanaman pertama katalog (cactus)
            Log.d(TAG, "MULTI-PLANT: belum ada pilihan, fallback ke '${sel.first().displayName}'")
        }
        selectedPlants = sel
        val cities = PlantConfig.citiesFor(sel)
        if (cities.isNotEmpty()) weatherScanner.targetCities = cities
        Log.d(TAG, "MULTI-PLANT: ${sel.size} tanaman [${sel.joinToString(", ") { it.displayName }}], scan kota: ${cities.joinToString(", ")}")
    }

    // ── WATCHDOG NAVIGASI ──────────────────────────────────────────────────

    private fun markGardenActivity() { lastGardenActivityMs = System.currentTimeMillis() }

    private fun gardenStuckTooLong(): Boolean {
        val last = lastGardenActivityMs
        if (last == 0L) return false   // belum pernah aktif (baru START) → jangan langsung memaksa
        return System.currentTimeMillis() - last > GARDEN_STUCK_MS
    }

    /** Ke mana harus kembali saat nyangkut: rencana boost → kota tempat berdiri → lokasi terakhir → prioritas tertinggi. */
    private suspend fun stuckRecoveryTarget(): GardenPlanner.Choice? {
        GardenPlanner.choose(selectedPlants, weatherScanner.currentSlots())?.let { return it }
        // Tanpa boost, JANGAN pindah kota tanpa alasan. Kalau kota tempat karakter berdiri sekarang
        // memang menumbuhkan salah satu tanaman terpilih, cukup kembali ke kebun di kota itu —
        // teleport lintas kota cuma membuang waktu (pernah: nyangkut di Sograt, malah dikirim ke Morroc).
        val here = cityNavigator.detectCurrentCity(PlantConfig.citiesFor(selectedPlants))
        if (here != null) {
            selectedPlants.firstOrNull { here in it.cities }?.let { return GardenPlanner.Choice(it, here) }
        }
        val pid = currentPlantId; val city = currentCity
        if (pid != null && city != null) {
            PlantCatalog.byId(pid)?.let { return GardenPlanner.Choice(it, city) }
        }
        val plant = selectedPlants.firstOrNull() ?: return null
        val fallbackCity = plant.cities.firstOrNull() ?: return null
        return GardenPlanner.Choice(plant, fallbackCity)
    }

    private fun recoverStuckNavigation(loop: Runnable) {
        val idleMin = (System.currentTimeMillis() - lastGardenActivityMs) / 60_000
        Log.w(TAG, "WATCHDOG: ${idleMin} menit tanpa aktivitas kebun padahal di dalam window → paksa navigasi ulang")
        gardenPaused = true
        handler.removeCallbacks(loop)
        markGardenActivity()   // reset SEKARANG: kalau pemulihan gagal, tunggu 5 menit lagi (jangan spam)
        scope.launch {
            try {
                reloadPlantSelection()
                val target = stuckRecoveryTarget()
                if (target == null) {
                    Log.w(TAG, "WATCHDOG: tidak ada tanaman terpilih — tidak bisa memulihkan")
                    return@launch
                }
                // force = true: kotanya mungkin SUDAH benar; yang salah posisi karakter. Tanpa force,
                // navigateTo cuma bilang "sudah di kota ini" lalu diam.
                val ok = cityNavigator.navigateTo(target.plant, target.city, force = true)
                if (ok) {
                    currentPlantId = target.plant.id; currentCity = target.city
                    Log.d(TAG, "WATCHDOG: navigasi ulang BERHASIL → '${target.plant.displayName}' @ '${target.city}'")
                } else {
                    Log.w(TAG, "WATCHDOG: navigasi ulang GAGAL — dicoba lagi 5 menit lagi")
                }
            } finally {
                markGardenActivity()
                gardenPaused = false
                handler.post { handler.postDelayed(loop, 5000) }
            }
        }
    }

    /** Pilih (plant,city) by prioritas; navigasi kalau beda dari lokasi sekarang. Return true kalau benar-benar pindah. */
    private suspend fun planAndNavigate(): Boolean {
        val choice = GardenPlanner.choose(selectedPlants, weatherScanner.currentSlots()) ?: run {
            Log.d(TAG, "planAndNavigate: tidak ada boost — tetap di tempat"); return false
        }
        if (choice.plant.id == currentPlantId && choice.city == currentCity) {
            Log.d(TAG, "planAndNavigate: '${choice.plant.displayName}' @ '${choice.city}' = lokasi sekarang, tidak pindah")
            return false
        }
        Log.d(TAG, "planAndNavigate: pindah ke '${choice.plant.displayName}' @ '${choice.city}'")
        hud("🗺 navigasi: ${choice.plant.displayName} @ ${choice.city}")
        // navigateTo hanya true kalau teleport TERVERIFIKASI (nama kota terbaca di layar).
        // Kalau gagal, current TIDAK di-update → siklus berikutnya coba lagi (bukan stuck).
        val ok = cityNavigator.navigateTo(choice.plant, choice.city)
        if (ok) {
            currentPlantId = choice.plant.id; currentCity = choice.city
        } else {
            Log.w(TAG, "planAndNavigate: navigasi GAGAL — current tidak diubah, akan dicoba lagi nanti")
            currentPlantId = null; currentCity = null
        }
        return ok
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt — accessibility service interrupted by system")
        isRunning = false
    }

    override fun onDestroy() {
        instance = null
        Log.w(TAG, "onDestroy — service destroyed")
        try { unregisterReceiver(weatherTestReceiver) } catch (_: Throwable) {}  // WEATHER (removable)
        try { unregisterReceiver(navTestReceiver) } catch (_: Throwable) {}      // CITY NAV (removable)
        try { unregisterReceiver(panelTestReceiver) } catch (_: Throwable) {}    // CITY NAV (removable)
        try { unregisterReceiver(stuckTestReceiver) } catch (_: Throwable) {}    // CITY NAV (removable)
        try { unregisterReceiver(dailyMissionReceiver) } catch (_: Throwable) {}  // DAILY MISSION (removable)
        // Shell root persisten hidup selama service — tanpa ini prosesnya menggantung setelah
        // service mati (dan `su` yatim tetap memegang fd).
        closeRootShell()
        fullResCache?.let { if (!it.isRecycled) it.recycle() }
        fullResCache = null
        rawBuf = null
        super.onDestroy()
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // MEDIA PROJECTION
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    private fun setupMediaProjection(data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(Activity.RESULT_OK, data)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RoxGarden", screenWidth, screenHeight, SCREEN_DENSITY,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            Log.d(TAG, "MediaProjection setup selesai")
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection setup gagal: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "roxgarden_channel"
        val channel = NotificationChannel(channelId, "RoxGarden Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, channelId)
            .setContentTitle("RoxGarden")
            .setContentText("Gardening berjalan...")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .build()
    }

    // Notifikasi alert (importance HIGH) yang membuka app saat di-tap.
    private fun postAlert(title: String, text: String, bigText: String) {
        val channelId = "roxgarden_alert"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "RoxGarden Alert", NotificationManager.IMPORTANCE_HIGH)
        )
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(2, notif)
    }

    // Screen capture mati (projection putus) padahal loop masih jalan.
    private fun notifyCaptureDead() {
        Log.w(TAG, "Screen capture mati $captureFailStreak× beruntun → notifikasi user")
        postAlert(
            "⚠️ Screen capture mati",
            "MediaProjection putus — buka app & tekan START GARDENING lagi.",
            "Bot tidak bisa membaca layar (capture gagal $captureFailStreak× beruntun). " +
            "Buka RoxGarden, tekan STOP lalu START GARDENING, dan izinkan screen capture."
        )
    }

    // Proses bot sempat dibunuh sistem → sudah bangkit sendiri (capture root/screenshot API
    // tak butuh izin ulang). Notifikasi ini informasional — kalau sering muncul, sistemnya
    // memang agresif membunuh (RAM sempit / battery optimization).
    private fun notifyAutoResumed() {
        postAlert(
            "✅ Bot bangkit sendiri",
            "Proses sempat dibunuh sistem — bot sudah jalan lagi otomatis.",
            "Tidak perlu tindakan. Kalau notifikasi ini sering muncul, kecualikan RoxGarden dari " +
            "battery optimization dan pastikan RAM device cukup."
        )
    }

    @Volatile private var lastCaptureSource = "-"

    // Instrumentasi timing — sebelum ini tidak ada satu pun pengukuran di kode, jadi semua
    // konstanta delay adalah tebakan. Dibaca lewat HUD (baris bawah) & dialog DIAGNOSA.
    val perf = PerfStats()

    /**
     * DIAGNOSA untuk device tanpa adb (cloud phone) — jalankan semua pengecekan yang biasanya
     * kubaca dari logcat, kembalikan sebagai teks untuk ditampilkan di layar.
     */
    fun runDiagnostics(cb: (String) -> Unit) {
        scope.launch {
            val sb = StringBuilder()
            sb.appendLine("Android API: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            sb.appendLine("Accessibility: AKTIF (service jalan)")
            sb.appendLine("Bot running: ${if (isRunning) "YA" else "TIDAK — tekan START"}")
            val root = hasRoot()
            sb.appendLine("Root: ${if (root) "ADA" else "TIDAK ADA"}")

            refreshGameDisplay()
            updateRealDisplaySize()
            sb.appendLine("Display game: id=$gameDisplayId, asli ${realScreenWidth.toInt()}×${realScreenHeight.toInt()}")

            val bmp = captureScreen()
            if (bmp == null) {
                sb.appendLine("Capture: GAGAL ← INI MASALAHNYA")
                if (Build.VERSION.SDK_INT < 30 && !root)
                    sb.appendLine("  Android <11 tanpa root: nyalakan ROOT di panel cloud phone, atau STOP→START dan izinkan screen capture")
                if (Build.VERSION.SDK_INT < 30 && root)
                    sb.appendLine("  Root ada tapi screencap gagal — cek apakah su benar-benar di-grant untuk RoxGarden")
            } else {
                val varc = frameVariance(bmp)
                sb.appendLine("Capture: OK via $lastCaptureSource")
                sb.appendLine("  kanonik ${bmp.width}×${bmp.height}, variance=${"%.0f".format(varc)}" +
                    if (varc < 1.0) " ← LAYAR BLANK (capture jalan tapi isinya kosong)" else "")
                val ocr = ocrProcessor
                if (ocr == null) {
                    sb.appendLine("OCR: BELUM SIAP (model masih loading / gagal load)")
                } else {
                    val txt = try {
                        ocr.processImage(bmp, includeAllConfidenceScores = true)
                            .texts.take(12).joinToString(" | ")
                    } catch (t: Throwable) { "ERROR: ${t.message}" }
                    sb.appendLine("OCR baca: ${txt.ifBlank { "(kosong — game tidak terlihat?)" }}")
                }
                bmp.recycle()
            }
            sb.appendLine("Mode: ${currentMode()}, dalam window gardening: ${if (shouldGardenNow()) "YA" else "TIDAK"}")
            // Timing per langkah — di device tanpa adb ini pengganti logcat untuk menilai
            // di mana waktu siklus sebenarnya habis.
            sb.appendLine("Shell root persisten: ${if (rootShell != null) "AKTIF" else "tidak (pakai fork per-capture)"}")
            sb.appendLine("Timing per langkah:")
            sb.appendLine(perf.report())
            sb.appendLine("  jeda pickup efektif=${pickupDelay()}ms (belajar ${pickupLearned}ms, plafon ${DELAY_AFTER_GATHER}ms)")
            val audit = perf.countersReport()
            if (audit.isNotEmpty()) {
                sb.appendLine("Audit template vs OCR (fast-path):")
                sb.appendLine(audit)
            }
            cb(sb.toString())
        }
    }

    /**
     * Pembungkus berinstrumen — SEMUA pemanggil lewat sini, jadi biaya capture (jalur mana pun
     * yang menang) selalu tercatat. Capture gagal tidak dicatat: durasinya bukan biaya normal
     * dan akan merusak EMA yang dipakai kompensasi latensi.
     */
    private fun captureScreen(): Bitmap? {
        val t0 = System.currentTimeMillis()
        val b = captureScreenRaw()
        if (b != null) {
            perf.record(PerfStats.CAP, System.currentTimeMillis() - t0)
            botHud?.stats(perf.hudLine())
        }
        return b
    }

    /**
     * Capture DISERIALKAN. Jalur root sekarang berbagi tiga state: buffer raw, bitmap full-res
     * cache, dan shell persisten. Sebelumnya tiap capture mengalokasikan sendiri, jadi dua capture
     * bersamaan cuma boros memori; dengan reuse, dua capture bersamaan bisa saling menimpa piksel
     * di tengah pass skala atau men-desync handshake shell. Loop utama, scheduleAfterTap, sentinel
     * maturity, dan handleCaptcha memang sudah saling mengunci lewat flag — lock ini penjaga
     * terakhir, dan di jalur normal tidak menambah latensi karena tidak pernah ada kontensi.
     */
    private val captureLock = Any()

    private fun captureScreenRaw(): Bitmap? = synchronized(captureLock) {
        // Overlay WATCH menggambar kotak tepat di atas area yang dianalisis, dan bot
        // memotret layarnya sendiri — jadi overlay harus lepas dari layar dulu. Tanpa ini
        // alat debugnya sendiri yang membuat bot gagal, dan hasil pengamatannya berbohong.
        // Saat mode watch mati, cabang ini tidak berbiaya apa pun.
        val ov = debugOverlay
        if (ov?.isWatching != true) return@synchronized captureScreenLocked()
        ov.suppressForCapture()
        try { captureScreenLocked() } finally { ov.restoreAfterCapture() }
    }

    private fun captureScreenLocked(): Bitmap? {
        // 0. PRIMER: root screencap physical display game (satu-satunya yang andal di MuMu multi-tab).
        //    BLOCKING — semua pemanggil sudah di background thread. Dilewati kalau tidak ada root.
        if (hasRoot()) {
            var gd = rootDisplay
            if (gd == null) {
                gd = probeGameDisplayRoot()
                if (gd != null) { rootDisplay = gd; gameDisplayId = gd.logicalId }
            }
            if (gd != null) {
                var b = rootScreencap(gd.physId)
                if (b != null && lastRawVariance < 1.0) {
                    // frame blank — display game pindah (MuMu recreate saat ganti tab) → probe ulang
                    releaseFullRes(b); b = null
                    val fresh = probeGameDisplayRoot()
                    if (fresh != null) {
                        rootDisplay = fresh; gameDisplayId = fresh.logicalId
                        b = rootScreencap(fresh.physId)
                    }
                }
                if (b != null) {
                    lastCaptureSource = "root screencap"
                    return toCanonical(b).also { maskHud(it) }
                }
            }
        }
        // 1. Fallback: screenshot API per-display
        if (Build.VERSION.SDK_INT >= 30) {
            val b = captureViaScreenshot(gameDisplayId)
            if (b != null) {
                lastCaptureSource = "screenshot API"
                return toCanonical(b).also { maskHud(it) }
            }
        }
        // 2. Fallback terakhir: MediaProjection (kalau di-grant & masih hidup).
        // Bitmap-nya SUDAH berukuran kanonik (reader dibuat 1280×720; VirtualDisplay men-scale
        // mirror-nya) — jangan lewat toCanonical, dimensi real diambil dari updateRealDisplaySize().
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        lastCaptureSource = "MediaProjection"
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            (if (rowPadding > 0) Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
             else bitmap).also { maskHud(it) }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen error: ${e.message}")
            null
        } finally {
            image.close()
        }
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // MAIN LOOP â€" flow mirip Python
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    private val gardenLoop: Runnable = object : Runnable {
        override fun run() {
            val loop: Runnable = this
            if (!isRunning || isHandlingCaptcha || gardenPaused) return

            if (debugOverlay?.isShowing == true) {
                handler.postDelayed(this, DELAY_LOOP)
                return
            }

            if (!ocrReady || ocrProcessor == null) {
                Log.d(TAG, "OCR belum ready")
                handler.postDelayed(this, DELAY_LOOP)
                return
            }

            // Cegah OCR concurrent â€" kalau iterasi sebelumnya masih jalan, tunggu.
            if (ocrInProgress) {
                Log.d(TAG, "OCR sebelumnya masih jalan, skip iterasi ini")
                handler.postDelayed(this, DELAY_LOOP)
                return
            }
            ocrInProgress = true

            scope.launch {
                // capture di background — captureScreen sekarang blocking (screenshot API latch)
                val img = captureScreen()
                // Waktu potret frame — patokan hitung ulang sisa tunggu (OCR+antrian memakan waktu;
                // detik yang terbaca di layar mengacu ke saat INI, bukan saat cabang status jalan).
                val capturedAt = System.currentTimeMillis()
                if (img == null) {
                    ocrInProgress = false
                    captureFailStreak++
                    Log.e(TAG, "captureScreen gagal, retry... (streak=$captureFailStreak)")
                    if (captureFailStreak >= CAPTURE_DEAD_THRESHOLD && !captureDeadNotified) {
                        captureDeadNotified = true
                        notifyCaptureDead()
                    }
                    handler.post { handler.postDelayed(loop, DELAY_LOOP) }
                    return@launch
                }
                // capture sukses → reset deteksi mati
                if (captureFailStreak > 0) {
                    captureFailStreak = 0
                    captureDeadNotified = false
                }
                // TEMPLATE-FIRST (permintaan user): keberadaan captcha dicek via template Confirm
                // (~30ms) SEBELUM OCR apa pun — OCR tinggal membaca isinya di handleCaptcha.
                // Sebelum template pertama terbentuk ini no-op dan deteksi tetap via OCR status.
                if (!isHandlingCaptcha && !gardenPaused && interceptCaptcha(img)) {
                    ocrInProgress = false
                    return@launch   // img milik handleCaptcha
                }
                // FAST-PATH satu siklus: habis maturity/pickup, tombol hampir pasti siap →
                // template orb (~30ms) menggantikan OCR (~1-2s). Tidak match (popup/captcha
                // menutupi, atau belum benar-benar siap) → jatuh ke OCR normal di bawah.
                // Salah tap saat ternyata masih cooldown = no-op; siklus berikutnya SELALU
                // full OCR, jadi salah tebak terkoreksi dalam satu putaran.
                if (expectReadyNext) {
                    expectReadyNext = false
                    val tmpl = gardeningOrbTmpl
                    if (tmpl != null && shouldGardenNow() && !isHandlingCaptcha && !gardenPaused &&
                        System.currentTimeMillis() >= tapHoldUntil &&
                        fastPathStreak < FAST_PATH_MAX_STREAK) {
                        val center = gardeningCenter(img.width, img.height)
                        val pos = findGardeningOrb(img, tmpl, center)
                        if (pos != null) {
                            ocrInProgress = false
                            fastPathStreak++
                            Log.d(TAG, "FAST-PATH: orb siap → tap tanpa OCR (streak=$fastPathStreak)")
                            hud("⚡ tap Gardening (cepat)")
                            markGardenActivity()
                            markGardenTap()
                            performTap(pos.first + (-8..8).random(), pos.second + (-8..8).random())
                            scheduleAfterTap(loop)
                            // AUDIT template vs OCR — frame yang SAMA dibaca OCR secara async.
                            // img jadi milik auditFastPath; dia yang me-recycle.
                            auditFastPath(img)
                            return@launch
                        }
                    }
                }
                val state = try {
                    perf.time(PerfStats.OCR) { analyzeScreen(img) }
                } catch (t: Throwable) {
                    Log.e(TAG, "analyzeScreen error: ${t.javaClass.simpleName}: ${t.message}")
                    GameStatus(StatusType.NORMAL)
                } finally {
                    ocrInProgress = false
                }
                fastPathStreak = 0   // OCR penuh barusan jalan — rantai tanpa-OCR terputus
                handler.post {
                    // Dimensi disimpan dulu — img di-recycle untuk cegah kebocoran memori
                    // (tiap iterasi loop bikin bitmap ~3.7MB; tanpa recycle → OOM/lowmemorykill).
                    val imgW = img.width; val imgH = img.height
                    // Guard race condition: analyzeScreen ini mungkin start sebelum
                    // iterasi lain set isHandlingCaptcha=true. Tanpa guard, dua
                    // handleCaptcha bisa jalan paralel â†’ OOM / service di-kill OS.
                    if (isHandlingCaptcha || gardenPaused) {
                        Log.d(TAG, "Hasil analyze diabaikan (captcha/pause aktif)")
                        if (!img.isRecycled) img.recycle()
                        return@post
                    }
                    // handleCaptcha butuh pixel img; GARDENING_READY juga (frame dipakai ulang utk
                    // cari orb — hemat satu capture per tap). Cabang lain cukup dimensi → recycle.
                    if (state.type != StatusType.CAPTCHA_PRESENT &&
                        state.type != StatusType.GARDENING_READY && !img.isRecycled) img.recycle()
                    // Status apa pun selain "buta" membuktikan karakter ADA di kebun → watchdog direset.
                    // Status gardening SUNGGUHAN (bukan captcha) juga mereset counter gagal-captcha:
                    // false positive sporadis tidak boleh MENUMPUK berhari-hari menuju hard-stop.
                    // CAPTCHA_PRESENT sengaja TIDAK mereset — captcha asli yang gagal berulang
                    // harus tetap bisa mencapai hard-stop 5×.
                    when (state.type) {
                        StatusType.NORMAL, StatusType.GAME_POPUP, StatusType.PANEL_STUCK -> {}
                        StatusType.CAPTCHA_PRESENT -> markGardenActivity()
                        // GARDENING_READY TIDAK membuka tahanan tap: teks tombol selalu terbaca,
                        // termasuk saat maturity — justru itu kasus yang dilawan noopTapStreak.
                        StatusType.GARDENING_READY -> { markGardenActivity(); consecutiveFails = 0 }
                        else -> {
                            markGardenActivity(); consecutiveFails = 0
                            // OCR memberi status definitif (maturity/gathering/stamina/period) —
                            // sinyal no-op tap sudah terjelaskan → buka tahanan tap.
                            noopTapStreak = 0; tapHoldUntil = 0L
                        }
                    }
                    when (state.type) {
                        StatusType.CAPTCHA_PRESENT -> {
                            Log.d(TAG, "CAPTCHA terdeteksi via OCR")
                            hud("🔢 CAPTCHA terdeteksi")
                            isHandlingCaptcha = true
                            handler.removeCallbacks(loop)
                            handleCaptcha(img)
                        }
                        StatusType.INSUFFICIENT_STAMINA -> {
                            Log.d(TAG, "Insufficient Stamina, tunggu ${DELAY_INSUFFICIENT}ms")
                            hud("😴 stamina habis")
                            handler.postDelayed(loop, DELAY_INSUFFICIENT)
                        }
                        StatusType.PLANT_NOT_READY -> {
                            Log.d(TAG, "Plant not ready, tunggu ${DELAY_PLANT_NOT_READY}ms")
                            hud("🌱 tanaman belum siap")
                            handler.postDelayed(loop, DELAY_PLANT_NOT_READY)
                        }
                        StatusType.UNTIL_MATURITY -> {
                            // Target matang ABSOLUT (dihitung dari saat capture, bukan saat cabang
                            // ini jalan). Sisa tidur dihitung ulang TEPAT sebelum dipasang — menutup
                            // dua kebocoran: (1) weather scan 60-90s di tengah cooldown → dulu tetap
                            // tidur penuh dari hitungan basi; (2) biaya OCR+antrian + capture siklus
                            // berikut → bangun 1.2s lebih awal supaya pembacaan mendarat pas matang.
                            val ripeAtMs = capturedAt + state.seconds * 1000L
                            Log.d(TAG, "Until Maturity: ${state.seconds}s (presisi, tanpa jitter)")
                            hud("⏳ maturity ${state.seconds}s")
                            // ── WEATHER SCAN + MULTI-PLANT NAV (fitur terpisah) ──
                            if (weatherScanner.shouldScan()) {
                                gardenPaused = true
                                scope.launch {
                                    try {
                                        reloadPlantSelection()
                                        hud("🌦 scan cuaca...")
                                        weatherScanner.scan()
                                        val moved = planAndNavigate()
                                        handler.post {
                                            if (moved) handler.postDelayed(loop, 6000)
                                            else {
                                                expectReadyNext = true   // bangun → fast-path
                                                sleepUntilRipeWithSentinel(loop, ripeAtMs)
                                            }
                                        }
                                    } finally {
                                        gardenPaused = false
                                    }
                                }
                            } else {
                                // Forecast sudah ada — cek boost tanpa scan ulang
                                gardenPaused = true
                                scope.launch {
                                    try {
                                        reloadPlantSelection()
                                        val moved = planAndNavigate()
                                        handler.post {
                                            if (moved) handler.postDelayed(loop, 6000)
                                            else {
                                                expectReadyNext = true   // bangun → fast-path
                                                sleepUntilRipeWithSentinel(loop, ripeAtMs)
                                            }
                                        }
                                    } finally { gardenPaused = false }
                                }
                            }
                            // ──────────────────────────────────────────────────────────
                        }
                        StatusType.GAME_POPUP -> {
                            // Popup "Welcome back to Midgard" dkk → tutup via tombol X (posisi tetap)
                            val xX = imgW * 0.738f; val xY = imgH * 0.396f
                            Log.d(TAG, "Popup game → tap X (${xX.toInt()},${xY.toInt()})")
                            hud("✖ tutup popup game")
                            performTap(xX, xY)
                            handler.postDelayed(loop, 2500)
                        }
                        StatusType.PANEL_STUCK -> {
                            // Pemulihan: closePanels() cuma menekan Back kalau panel TERBUKTI terbuka,
                            // jadi aman — tidak akan memunculkan dialog "Leave game?".
                            Log.w(TAG, "Panel nyangkut → coba tutup (recovery)")
                            hud("🧹 tutup panel nyangkut")
                            gardenPaused = true
                            handler.removeCallbacks(loop)
                            scope.launch {
                                try {
                                    cityNavigator.closePanels()
                                } finally {
                                    gardenPaused = false
                                    handler.post { handler.postDelayed(loop, 3000) }
                                }
                            }
                        }
                        StatusType.GATHERING -> {
                            Log.d(TAG, "Gathering, tunggu ${DELAY_GATHERING_WAIT}ms")
                            // Pakai DELAY_GATHERING_WAIT, BUKAN pickupDelay(): posisi kita di
                            // tengah gather yang entah sudah sejauh mana, bukan sesudahnya.
                            hud("🌾 gathering → tunggu ${DELAY_GATHERING_WAIT / 1000.0}s")
                            expectReadyNext = true   // habis gather, tombol biasanya langsung siap → fast-path
                            handler.postDelayed(loop, DELAY_GATHERING_WAIT)
                        }
                        StatusType.GARDENING_READY -> {
                            if (!shouldGardenNow()) {
                                if (!img.isRecycled) img.recycle()
                                val wait = millisUntilNextWindow()
                                Log.d(TAG, "Mode=${currentMode()}, di luar window — tunggu ${wait / 1000}s")
                                hud("⏸ luar window ${currentMode()} — ${wait / 1000}s")
                                handler.postDelayed(loop, wait)
                                return@post
                            }
                            // OCR baca "Gardening" BUKAN bukti tanaman siap — teks tombol selalu
                            // ada, termasuk saat maturity. Kalau tap barusan terbukti tak bereaksi
                            // (noopTapStreak ≥2), tahan dulu: tunggu hold habis lalu satu probe.
                            if (System.currentTimeMillis() < tapHoldUntil) {
                                if (!img.isRecycled) img.recycle()
                                Log.d(TAG, "Tahan tap (no-op streak=$noopTapStreak) — anggap maturity, OCR terus mencoba")
                                hud("🔒 maturity (via tombol) — tunggu")
                                handler.postDelayed(loop, 3000)
                                return@post
                            }
                            Log.d(TAG, "Tombol Gardening terlihat, tap (template)")
                            hud("✋ tap Gardening")
                            scope.launch {
                                // Pakai ulang frame analisis — tombol Gardening statis, frame umur
                                // ~1-2s masih valid; hemat satu root screencap (~0.5-1s) per tap.
                                try { tapGardeningByTemplate(img) }
                                finally { if (!img.isRecycled) img.recycle() }
                                scheduleAfterTap(loop)
                            }
                        }
                        StatusType.NORMAL -> {
                            // Buta terlalu lama padahal jam gardening → karakter nyangkut di tengah map.
                            if (shouldGardenNow() && gardenStuckTooLong()) {
                                hud("🚨 nyangkut → navigasi ulang")
                                recoverStuckNavigation(loop)
                                return@post
                            }
                            // Buta tepat setelah tap = transisi animasi (jalan ke petak / gather
                            // baru mulai) → cek ulang CEPAT, jangan buang 3 detik penuh.
                            val sinceTap = System.currentTimeMillis() - lastGardenTapMs
                            if (sinceTap < 8000) {
                                Log.d(TAG, "Tidak ada teks (${sinceTap}ms setelah tap) → retry cepat")
                                hud("👁 transisi, cek cepat...")
                                handler.postDelayed(loop, 1200)
                            } else {
                                // PENYELAMAT TEMPLATE. OCR gagal MENGKLASIFIKASI, tapi itu
                                // sama sekali bukan bukti tombolnya tidak ada.
                                //
                                // Terukur di device (21:20-21:33): panel menu kanan terbuka
                                // menindih area teks, OCR membaca 'Pack Battle Reward
                                // Collection...' di SETIAP siklus dan tak pernah bisa
                                // menyimpulkan apa pun — padahal orb-nya terlihat jelas dan
                                // petaknya siap panen. Selama 10 menit bot tetap jalan, karena
                                // rantai panen memasang expectReadyNext dan fast-path yang
                                // memakai orb. Satu captcha memutus rantai itu, dan sesudahnya
                                // SATU-SATUNYA jalan kembali adalah OCR — yang di layar ini
                                // buta permanen. Hasilnya diam 3 menit tanpa satu pun findOrb()
                                // dipanggil.
                                //
                                // Jadi cabang ini bukan optimasi, melainkan pemulihan: begitu
                                // OCR menyerah, template (~24ms) jadi pendapat kedua. Tanpa
                                // ini, kebutaan OCR bersifat menyerap — sekali masuk, tidak
                                // ada yang mengeluarkan.
                                Log.d(TAG, "Tidak ada teks dikenali → coba template orb (penyelamat)")
                                hud("\ud83d\udc41 teks buntu \u2192 cek orb")
                                if (!shouldGardenNow() || System.currentTimeMillis() < tapHoldUntil) {
                                    // Di luar window, atau tap sedang ditahan karena maturity:
                                    // jangan dipaksa. Penyelamat ini melawan KEBUTAAN, bukan
                                    // melawan penilaian yang sudah benar.
                                    handler.postDelayed(loop, DELAY_LOOP)
                                } else scope.launch {
                                    val s2 = captureScreen()
                                    val pos = if (s2 == null) null else
                                        withContext(Dispatchers.Default) { findOrb(s2) }
                                    if (pos != null) {
                                        Log.d(TAG, "penyelamat: orb terlihat walau OCR buntu → tap (${pos.first.toInt()},${pos.second.toInt()})")
                                        hud("\u270b orb terlihat (OCR buntu) → tap")
                                        s2?.recycle()
                                        markGardenTap()
                                        performTap(pos.first, pos.second)
                                        scheduleAfterTap(loop)
                                    } else {
                                        s2?.recycle()
                                        handler.post { handler.postDelayed(loop, DELAY_LOOP) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // REGION HELPERS â€" koordinat dari DebugOverlayManager (live, auto-saved)
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    // Audit hanya boleh SATU yang jalan: diagnosa boleh sampling, tapi menumpuk inferensi ONNX
    // di emulator akan memperlambat capture yang justru sedang kita optimalkan.
    @Volatile private var observeOcrBusy = false

    /**
     * AUDIT TEMPLATE vs OCR (permintaan user: "cek template dan OCR bersamaan, bukan sequence").
     *
     * Fast-path menyerahkan keputusan tap sepenuhnya ke template dan MELEWATI OCR — di siklus itu
     * bot benar-benar buta, dan kalau template salah tidak ada apa pun yang bisa memberitahu.
     * Itulah akar siklus phantom dulu, dan alasan lahirnya fastPathStreak/noopTapStreak.
     * Fungsi ini membaca frame YANG SAMA dengan OCR lalu mencatat apakah keduanya sepakat.
     *
     * MURNI MENGAMATI: hasilnya TIDAK PERNAH mengubah perilaku bot (pilihan user: amati dulu).
     * analyzeScreen sudah diperiksa bebas efek samping — tidak nge-tap, tidak menjadwal, tidak
     * mengubah state, tidak me-recycle.
     *
     * Async, supaya tap tidak menunggu OCR — inti permintaannya: ongkos jadi max(), bukan jumlah.
     * Sesi ONNX aman dipanggil konkuren (TextDetector/TextRecognizer cuma memegang referensi
     * sesi; seluruh state kerjanya lokal per panggilan), yang naik hanya beban CPU.
     *
     * Kepemilikan bitmap: frame diserahkan ke fungsi ini, dan fungsi ini yang me-recycle —
     * termasuk di jalur dilewati, supaya tidak ada frame 3.7MB yang bocor.
     */
    private fun auditFastPath(shot: Bitmap) {
        if (observeOcrBusy) {
            perf.bump("audit-dilewati")
            if (!shot.isRecycled) shot.recycle()
            return
        }
        observeOcrBusy = true
        scope.launch {
            try {
                val v = try { perf.time(PerfStats.OCR) { analyzeScreen(shot) } }
                        catch (t: Throwable) { null }
                when (v?.type) {
                    null -> perf.bump("audit-error")
                    // Template dan OCR sepakat tombol memang siap dipanen.
                    StatusType.GARDENING_READY -> perf.bump("audit-sepakat")
                    // Tidak konklusif: OCR tak membaca apa pun yang menentukan (layar transisi).
                    StatusType.NORMAL, StatusType.GAME_POPUP, StatusType.PANEL_STUCK ->
                        perf.bump("audit-tak-jelas")
                    // Sisanya = OCR punya status definitif yang BERTENTANGAN dengan "siap panen":
                    // maturity, gathering, stamina, period, atau captcha. Artinya tap barusan
                    // kemungkinan besar no-op — persis kasus yang dulu tak terlihat.
                    else -> {
                        perf.bump("audit-beda")
                        perf.bump("audit-beda-${v.type}")
                        Log.w(TAG, "AUDIT: template=orb siap, OCR=${v.type} → tap tadi kemungkinan no-op")
                        hud("⚠ template≠OCR: ${v.type}")
                    }
                }
            } finally {
                if (!shot.isRecycled) shot.recycle()
                observeOcrBusy = false
            }
        }
    }

    private fun regionPx(id: String, bitmap: Bitmap): IntArray? {
        debugOverlay?.markActive(id)
        val r = debugOverlay?.getRegion(id) ?: return null
        val x1 = (bitmap.width  * r.x1f.coerceIn(0f, 1f)).toInt()
        val y1 = (bitmap.height * r.y1f.coerceIn(0f, 1f)).toInt()
        val x2 = (bitmap.width  * r.x2f.coerceIn(0f, 1f)).toInt()
        val y2 = (bitmap.height * r.y2f.coerceIn(0f, 1f)).toInt()
        return intArrayOf(x1, y1, x2, y2)
    }

    private fun gardeningCenter(screenW: Int, screenH: Int): Pair<Float, Float> {
        val r = debugOverlay?.getRegion("gardening")
        val cxPct = if (r != null) (r.x1f + r.x2f) / 2f else GARDENING_BTN_X
        val cyPct = if (r != null) (r.y1f + r.y2f) / 2f else GARDENING_BTN_Y
        return Pair(cxPct * screenW, cyPct * screenH)
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // GARDENING MODE â€" time-based gating
    // Gardening 1: jam genap (0,2,4,...,22), menit 1-40
    // Gardening 2: waktu sisanya (jam ganjil, atau menit 0/41-59 di jam genap)
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    private enum class GardeningMode { BOTH, G1_ONLY, G2_ONLY }

    private fun currentMode(): GardeningMode {
        val prefs = getSharedPreferences("roxgarden_prefs", Context.MODE_PRIVATE)
        val str = prefs.getString("gardening_mode", "BOTH") ?: "BOTH"
        return try { GardeningMode.valueOf(str) } catch (e: Exception) { GardeningMode.BOTH }
    }

    // Mekanik game: G1 = jam genap menit :01–:39. Pergantian mode terjadi DI menit :40
    // (dulu `1..40` — menit :40 masih dihitung G1, telat satu menit dari mekanik aslinya).
    private fun isG1Window(hour: Int, minute: Int): Boolean =
        hour % 2 == 0 && minute in 1..39

    private fun shouldGardenNow(): Boolean {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return when (currentMode()) {
            GardeningMode.BOTH -> true
            GardeningMode.G1_ONLY -> isG1Window(h, m)
            GardeningMode.G2_ONLY -> !isG1Window(h, m)
        }
    }

    /** Hitung delay ms sampai window mode aktif berikutnya. Mencari max 150 menit ke depan. */
    private fun millisUntilNextWindow(): Long {
        val mode = currentMode()
        if (mode == GardeningMode.BOTH) return DELAY_LOOP
        if (shouldGardenNow()) return DELAY_LOOP

        val now = System.currentTimeMillis()
        val target = Calendar.getInstance()
        for (i in 1..150) {
            target.add(Calendar.MINUTE, 1)
            val h = target.get(Calendar.HOUR_OF_DAY)
            val m = target.get(Calendar.MINUTE)
            val isG1 = isG1Window(h, m)
            val valid = when (mode) {
                GardeningMode.G1_ONLY -> isG1
                GardeningMode.G2_ONLY -> !isG1
                else -> true
            }
            if (valid) {
                target.set(Calendar.SECOND, 0)
                target.set(Calendar.MILLISECOND, 0)
                return (target.timeInMillis - now).coerceAtLeast(DELAY_LOOP)
            }
        }
        return 60 * 60 * 1000L  // fallback 1 jam
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // GAME STATUS DETECTION via ONNX OCR
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    enum class StatusType {
        NORMAL,
        CAPTCHA_PRESENT,
        INSUFFICIENT_STAMINA,
        PLANT_NOT_READY,
        UNTIL_MATURITY,
        GATHERING,
        GARDENING_READY,
        GAME_POPUP,        // popup game (mis. "Welcome back to Midgard") → tutup via tombol X
        PANEL_STUCK,       // panel bot sendiri (world map / encyclopedia) nyangkut → tutup via Back
    }
    data class GameStatus(val type: StatusType, val seconds: Int = 0)

    /**
     * Analisis 1 frame via OCR penuh. Urutan cek:
     *   1. OCR captcha region â†’ match operator matematika atau "confirm" â†’ CAPTCHA_PRESENT
     *   2. OCR area gardening (diperluas, untuk teks status di sekitar tombol):
     *      - "insufficient" / "stamina" â†’ INSUFFICIENT_STAMINA
     *      - "not grow" / "not fully grown" / "current period" â†’ PLANT_NOT_READY
     *      - "maturity" / "until" â†’ UNTIL_MATURITY (parse detik)
     *      - "gathering" â†’ GATHERING
     *      - "gardening" â†’ GARDENING_READY
     */
    private fun analyzeScreen(bitmap: Bitmap): GameStatus {
        val processor = ocrProcessor ?: return GameStatus(StatusType.NORMAL)

        // Deteksi captcha via looksLikeCaptcha (member; ≥2 sinyal independen — lihat definisinya).

        // OCR region captcha per-siklus DIBUANG (hemat ~1 inferensi tiap 3 detik): popup captcha
        // selalu menutupi area status juga, jadi teks soal/"Confirm" tertangkap oleh OCR status di
        // bawah — deteksi tetap di siklus yang sama. Pembacaan soal presisi tetap di parseMathQuestion.
        // CATATAN: regex math JANGAN dipakai di teks full-screen — chat player ("runa 1-2-3") bisa
        // match pola digit-op-digit → false captcha. Cukup di area status (chat sudah di luar region).

        // OCR area gardening (diperluas) untuk teks tombol + label status
        val statusText = ocrGardeningArea(processor, bitmap)
        if (statusText.isNotEmpty()) Log.d(TAG, "OCR status: '$statusText'")
        val lower = statusText.lowercase()

        // Captcha popup biasanya menutupi area gardening juga â€" cek juga di sini
        if (looksLikeCaptcha(lower)) {
            captchaStatusHint = statusText
            return GameStatus(StatusType.CAPTCHA_PRESENT)
        }

        val state = when {
            "insufficient" in lower || "stamina" in lower ->
                GameStatus(StatusType.INSUFFICIENT_STAMINA)
            "maturity" in lower || "until" in lower -> {
                val match = Regex("""(\d+)\s*s""").find(lower)
                val sec = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // Mekanik game: maturity MAKSIMAL 60 detik. Angka di atas itu pasti salah baca
                // (mis. bar petak tetangga / digit tergabung) — dulu diterima sampai 300 dan bot
                // tidur 5 menit penuh tanpa cek ulang, padahal tanaman sendiri sudah siap.
                if (sec in 1..60) GameStatus(StatusType.UNTIL_MATURITY, sec)
                else GameStatus(StatusType.NORMAL)
            }
            // Salah PERIODE (G1/G2): "The plant will not grow in the current period" → tunggu lama.
            "will not grow" in lower || "current period" in lower ->
                GameStatus(StatusType.PLANT_NOT_READY)
            // Toast tap-saat-maturity: "Plant is not fully growing/grown" (temuan user — toast ini
            // juga MENUTUPI tombol, lihat scheduleAfterTap). Tanaman SEDANG maturity; toast tak
            // menyebut detik → coba bar fokus, gagal → probe 15s. JANGAN ke PLANT_NOT_READY
            // (dulu "not fully grown" masuk sana → tidur 5 MENIT untuk cooldown ≤60 dtk).
            "not fully grow" in lower || "not grow" in lower -> {
                val sec = readMaturityBarFocused(processor, bitmap) ?: 15
                GameStatus(StatusType.UNTIL_MATURITY, sec)
            }
            "cannot garden" in lower || "can't garden" in lower -> {
                val sec = (Regex("""(\d+)\s*s""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 60)
                    .coerceIn(1, 60)   // mekanik game: cooldown tak pernah > 60s
                Log.d(TAG, "Cannot garden, cooldown ~${sec}s")
                GameStatus(StatusType.UNTIL_MATURITY, sec)
            }
            isGamePopup(lower) -> GameStatus(StatusType.GAME_POPUP)
            "gathering" in lower || "gather" in lower -> GameStatus(StatusType.GATHERING)
            "gardening" in lower -> GameStatus(StatusType.GARDENING_READY)
            else -> {
                // Fallback: teks status kosong berkali-kali (bot "diam") — mungkin ada popup
                // yang tidak menutupi region status. Cek full-screen tiap beberapa siklus.
                normalStreak++
                if (normalStreak == 3) {
                    // Atau display game pindah (MuMu recreate display saat ganti tab) →
                    // paksa probe ulang di capture berikutnya
                    Log.d(TAG, "OCR kosong 3× — reset display game, probe ulang")
                    rootDisplay = null
                }
                // Buta 2 siklus = kemungkinan besar ADA yang menutupi tombol (popup/panel) —
                // dulu baru dicek setelah 5 siklus (~15 detik buta percuma, watchdog bisa
                // keburu memicu navigasi ulang yang tidak perlu).
                if (normalStreak >= 2) {
                    normalStreak = 0
                    val fullText = try {
                        processor.processImage(bitmap, includeAllConfidenceScores = true)
                            .texts.joinToString(" ").lowercase()
                    } catch (_: Throwable) { "" }
                    if (isGamePopup(fullText)) {
                        Log.d(TAG, "Popup game terdeteksi via full-screen OCR")
                        return GameStatus(StatusType.GAME_POPUP)
                    }
                    if (isStuckPanel(fullText)) {
                        Log.w(TAG, "Panel nyangkut terdeteksi via full-screen OCR")
                        return GameStatus(StatusType.PANEL_STUCK)
                    }
                }
                GameStatus(StatusType.NORMAL)
            }
        }
        // JARING KEDUA BAR MATURITY: tap barusan terbukti no-op (noopTapStreak > 0) tapi OCR
        // lebar bilang "siap"/kosong → hampir pasti ada bar yang tak kebaca (teks ~10px putih
        // di pill gelap — gagal terus di lokasi/pencahayaan tertentu). Baca ulang FOKUS.
        if (noopTapStreak > 0 &&
            (state.type == StatusType.GARDENING_READY || state.type == StatusType.NORMAL)) {
            val sec = readMaturityBarFocused(processor, bitmap)
            if (sec != null) {
                Log.d(TAG, "Maturity via pembacaan fokus: ${sec}s")
                return GameStatus(StatusType.UNTIL_MATURITY, sec)
            }
        }
        return state
    }

    /**
     * Pembacaan FOKUS bar "Until Maturity: Xs" — jaring kedua saat OCR area lebar gagal.
     * Bar itu teks putih ~10px di pill gelap yang menempel di atas tanaman (kamera menengahkan
     * karakter → selalu di pita tengah layar). Di beberapa lokasi (Underwater Cave, Sograt
     * malam hari) OCR lebar TIDAK PERNAH berhasil membacanya. Resep teks-mungil standar:
     * crop kecil pita tengah → upscale 3× → binarisasi teks putih → OCR.
     * Hanya dipanggil di jalur gagal (no-op tap / toast) — nol biaya di panen normal.
     */
    private fun readMaturityBarFocused(processor: OcrProcessor, bitmap: Bitmap): Int? {
        val x1 = (bitmap.width * 0.32f).toInt(); val y1 = (bitmap.height * 0.30f).toInt()
        val w = (bitmap.width * 0.36f).toInt();  val h = (bitmap.height * 0.25f).toInt()
        if (w <= 0 || h <= 0 || x1 + w > bitmap.width || y1 + h > bitmap.height) return null
        var crop: Bitmap? = null; var big: Bitmap? = null; var bin: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bitmap, x1, y1, w, h)
            big = Bitmap.createScaledBitmap(crop, w * 3, h * 3, true)
            // teks putih → hitam pekat di latar putih (kontras maksimal utk glyph mungil)
            val px = IntArray(big.width * big.height)
            big.getPixels(px, 0, big.width, 0, 0, big.width, big.height)
            for (i in px.indices) {
                val p = px[i]
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                px[i] = if (lum >= 180) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            bin = Bitmap.createBitmap(px, big.width, big.height, Bitmap.Config.ARGB_8888)
            val text = processor.processImage(bin, includeAllConfidenceScores = true)
                .texts.joinToString(" ").lowercase()
            if (text.isNotEmpty()) Log.d(TAG, "OCR bar fokus: '$text'")
            // wajib ada jejak kata bar-nya — angka polos bisa datang dari mana saja (damage/chat)
            if ("matur" !in text && "until" !in text) return null
            Regex("""(\d+)\s*s""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..60 }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR bar fokus gagal: ${t.message}")
            null
        } finally {
            crop?.let { if (!it.isRecycled) it.recycle() }
            big?.let { if (!it.isRecycled) it.recycle() }
            bin?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * OCR pita tengah layar — tempat toast peringatan game ("Plant is not fully growing" dkk)
     * muncul. Teks toast seukuran teks tombol (tak perlu upscale). Dipakai scheduleAfterTap
     * untuk membedakan "tombol hilang karena gather" vs "tombol TERTUTUP toast" (temuan user).
     */
    private fun readCenterBand(bitmap: Bitmap): String =
        perf.time(PerfStats.BAND) { readCenterBandImpl(bitmap) }

    private fun readCenterBandImpl(bitmap: Bitmap): String {
        val processor = ocrProcessor ?: return ""
        val x1 = (bitmap.width * 0.20f).toInt(); val y1 = (bitmap.height * 0.32f).toInt()
        val w = (bitmap.width * 0.60f).toInt();  val h = (bitmap.height * 0.26f).toInt()
        if (w <= 0 || h <= 0) return ""
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bitmap, x1, y1, w, h)
            processor.processImage(crop, includeAllConfidenceScores = true)
                .texts.joinToString(" ").lowercase()
        } catch (_: Throwable) { "" }
        finally { crop?.let { if (!it.isRecycled) it.recycle() } }
    }

    /**
     * Banner hadiah panen ("Obtained <item> × 1"). TEMUAN USER: banner ini menyapu naik dari
     * bawah layar dan sempat menutupi tombol Gardening tepat saat panen selesai — lihat
     * pemakaiannya di scheduleAfterTap. Kebalikan dari isNoGardenToast: yang ini bukti panen
     * BERHASIL, bukan tap yang gagal.
     */
    private fun isObtainedToast(lower: String): Boolean =
        "obtained" in lower || "diperoleh" in lower || "didapat" in lower

    /** Teks toast yang berarti tap TIDAK memicu gather (belum matang / stamina / period). */
    private fun isNoGardenToast(lower: String): Boolean =
        "not fully" in lower || "not grow" in lower ||
        "cannot garden" in lower || "can't garden" in lower ||
        "will not" in lower || "current period" in lower ||
        "insufficient" in lower || "stamina" in lower

    // Keyword popup game yang harus ditutup (X). "Welcome back to Midgard ... unclaimed rewards ... Retrieve Now"
    // + varian klien bahasa Indonesia: "Wah! Kamu sudah absen lama sekali! Jangan lupa reclaim rewardmu yah!"
    private fun isGamePopup(lower: String): Boolean =
        "welcome back" in lower || "midgard" in lower ||
        "unclaimed reward" in lower || "retrieve" in lower ||
        "absen lama" in lower || "reclaim" in lower || "jangan tampilkan" in lower

    /**
     * Panel yang DIBUKA BOT SENDIRI (world map saat weather scan, encyclopedia saat navigasi) dan
     * gagal tertutup. Tanpa ini garden loop cuma bilang "Tidak ada teks dikenali, retry" SELAMANYA —
     * pernah terjadi: map Sograt Desert tertinggal terbuka & bot diam berjam-jam.
     * Sengaja TIDAK memasukkan panel buatan user (Exchange Center dll) — bot tak boleh menutup itu.
     */
    private fun isStuckPanel(lower: String): Boolean =
        // region view (setelah tap minimap)
        "monsters on current map" in lower || "quests on current map" in lower ||
        "lifeskill area" in lower || "map info" in lower ||
        // world map (grid kota) — teksnya BEDA dari region view; keduanya harus terdaftar
        "affiliated areas" in lower || "defending guild" in lower ||
        // panel gardening
        "encyclopedia" in lower || "common area" in lower || "growth time" in lower ||
        // panel Carnival & Mission Board — supaya panel yang tertinggal terbuka dari
        // Daily Mission juga bisa dipulihkan lewat jalur PANEL_STUCK gardening
        "mission board" in lower || "bounty quest" in lower || "trial illusion" in lower ||
        "mission progress" in lower || "quests refresh" in lower

    // Counter status NORMAL beruntun — untuk fallback deteksi popup full-screen
    private var normalStreak = 0

    private fun ocrRegion(processor: OcrProcessor, bitmap: Bitmap, regionId: String): String {
        val px = regionPx(regionId, bitmap) ?: return ""
        val w = px[2] - px[0]; val h = px[3] - px[1]
        if (w <= 0 || h <= 0) return ""
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bitmap, px[0], px[1], w, h)
            processor.processImage(crop, includeAllConfidenceScores = true).texts.joinToString(" ")
        } catch (e: Exception) {
            Log.e(TAG, "OCR $regionId error: ${e.message}")
            ""
        } finally {
            crop?.let { if (!it.isRecycled) it.recycle() }   // WAJIB: dipanggil tiap loop → bocor kalau tidak
        }
    }

    /** OCR area gardening yang diperluas â€" termasuk teks status di atas/kiri tombol. */
    private fun ocrGardeningArea(processor: OcrProcessor, bitmap: Bitmap): String {
        val gr = debugOverlay?.getRegion("gardening")
        val x1f = if (gr != null) (gr.x1f - 0.20f).coerceAtLeast(0f) else 0.30f
        val y1f = if (gr != null) (gr.y1f - 0.15f).coerceAtLeast(0f) else 0.20f
        val x2f = if (gr != null) (gr.x2f + 0.05f).coerceAtMost(1f) else 0.80f
        // Bar "Gathering"/"Until Maturity" menempel ke KARAKTER (kamera selalu menengahkan
        // karakter → bar ada di pita tengah-bawah, y ~0.55-0.70). Perluasan lama (+0.10) tidak
        // sampai ke sana → bar luput, bot buta 1 siklus tiap panen ("layar tak terbaca").
        // Cap 0.75: JANGAN sampai kotak chat (y>0.78) — chat player bisa berisi kata "gardening".
        val y2f = if (gr != null) (gr.y2f + 0.25f).coerceAtMost(0.75f) else 0.70f

        val x1 = (bitmap.width  * x1f).toInt()
        val y1 = (bitmap.height * y1f).toInt()
        val x2 = (bitmap.width  * x2f).toInt()
        val y2 = (bitmap.height * y2f).toInt()
        val w = x2 - x1; val h = y2 - y1
        if (w <= 0 || h <= 0) return ""

        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bitmap, x1, y1, w, h)
            processor.processImage(crop, includeAllConfidenceScores = true).texts.joinToString(" ")
        } catch (e: Exception) {
            Log.e(TAG, "OCR gardening area error: ${e.message}")
            ""
        } finally {
            crop?.let { if (!it.isRecycled) it.recycle() }   // WAJIB: dipanggil tiap loop → bocor kalau tidak
        }
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // CAPTCHA POPUP CHECK (dipakai tapConfirmUntilDone untuk verifikasi popup hilang)
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    // Pola & kata kunci deteksi captcha — dipakai analyzeScreen (region status lebar),
    // detectCaptchaPopup (region kecil), dan detectCaptchaWide (jaring cadangan penjaga).
    // Catatan: "gardening" sengaja tidak masuk keyword — kata itu ada di tombol Gardening juga.
    private val captchaMathPattern = Regex("""(?<!\d)\d{1,2}\s*[+\-*/]\s*\d{1,2}(?!\d)""")
    private val captchaKeywords = listOf("activity", "verify", "playing", "question", "answer")

    // Normalisasi simbol operator hasil OCR — subset ringan dari parseMathQuestion, cukup untuk
    // DETEKSI (pembacaan presisi tetap di parseMathQuestion). Tanpa ini soal perkalian yang
    // terbaca "5×2"/"5x2" TIDAK match pola math → deteksi region kecil meleset (terjadi live:
    // captcha baru ketahuan lewat timeout 15 dtk, waktu captcha keburu habis).
    private fun normalizeMathSymbols(s: String): String = s
        .replace(Regex("""\d{2}:\d{2}"""), " ")   // timer popup "00:14" bukan soal
        .replace("×", "*").replace("÷", "/")
        .replace("•", "*").replace("·", "*")
        .replace(Regex("""[xX](?=\s*\d)"""), "*")
        .replace("—", "-").replace("–", "-").replace("一", "-")

    // Butuh ≥2 SINYAL INDEPENDEN. Popup captcha asli selalu membawa banyak sekaligus
    // (soal math + tombol "Confirm" + deskripsi verify/question/answer/playing). Salah baca
    // OCR di scene game (tekstur kaktus → "4-4") cuma 1 sinyal — dulu satu sinyal langsung
    // dianggap captcha → false positive saat baru START (terjadi live), 45 dtk terbuang
    // + counter menuju hard-stop. (detectCaptchaPopup TIDAK diperketat begini — region
    // kecilnya memang cuma berisi baris soal, satu sinyal adalah normal di sana.)
    private fun looksLikeCaptcha(text: String): Boolean {
        val norm = normalizeMathSymbols(text)
        var signals = 0
        if (captchaMathPattern.containsMatchIn(norm)) signals++
        if ("confirm" in norm) signals++
        signals += captchaKeywords.count { it in norm }
        return signals >= 2
    }

    /**
     * Deteksi CAPTCHA via OCR teks dalam region "captcha" (kecil, ~0.3s).
     * True jika ada operator matematika, teks "confirm", atau keyword deskripsi popup.
     * BLOCKING â€" harus dipanggil dari background thread (scope.launch).
     */
    private fun detectCaptchaPopup(bitmap: Bitmap): Boolean {
        val processor = ocrProcessor ?: return false
        val lower = normalizeMathSymbols(ocrRegion(processor, bitmap, "captcha").lowercase())
        return captchaMathPattern.containsMatchIn(lower) || "confirm" in lower ||
            captchaKeywords.any { it in lower }
    }

    /**
     * Jaring cadangan: cek captcha di region STATUS yang lebar — jalur deteksi yang sama dengan
     * analyzeScreen (satu-satunya yang terbukti menangkap popup ketika region kecil meleset).
     * Mahal (~1-1.5s) → hanya dipanggil penjaga, bukan tiap frame.
     */
    private fun detectCaptchaWide(bitmap: Bitmap): Boolean {
        val processor = ocrProcessor ?: return false
        return looksLikeCaptcha(ocrGardeningArea(processor, bitmap).lowercase())
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // TAP HELPERS
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    // Kapan terakhir tombol Gardening di-tap — dipakai utk retry cepat saat layar tak terbaca
    // sesaat setelah tap (animasi jalan/gather baru mulai, teks belum stabil).
    @Volatile private var lastGardenTapMs = 0L

    /**
     * Catat tap panen + ukur panjang siklus (tap → tap berikutnya). Semua jalur tap Gardening
     * lewat sini supaya "cycle" di HUD benar-benar mewakili ritme panen.
     */
    private fun markGardenTap() {
        val now = System.currentTimeMillis()
        val prev = lastGardenTapMs
        // >90 detik = bukan siklus panen beruntun (maturity panjang, navigasi kota, captcha di
        // tengah) — jangan cemari EMA yang justru dipakai membaca ritme panen normal.
        if (prev > 0) {
            val d = now - prev
            if (d in 1_000L..90_000L) perf.record(PerfStats.CYCLE, d)
        }
        lastGardenTapMs = now
    }

    // FAST-PATH: siklus TEPAT setelah tidur maturity / jeda pickup selesai — tombol hampir pasti
    // siap, tak perlu bayar OCR (~1-2s) untuk memastikannya. Template orb (~30ms) cukup.
    @Volatile private var expectReadyNext = false

    // ── SINYAL MATURITY VIA TOMBOL (usulan user) ─────────────────────────────
    // Tap yang TIDAK membuat tombol hilang = tanaman belum bisa dipanen (maturity/cooldown).
    // Sinyal ini bebas-lokasi & bebas-OCR — penting: di lokasi tertentu (Underwater Cave) bar
    // "Until Maturity" tak terbaca OCR tapi teks tombol "Gardening" terbaca terus → bot pernah
    // spam tap 3+× sepanjang maturity. Dua no-op beruntun (satu bisa gesture nyasar/lag) →
    // tahan semua tap TAP_HOLD_MS; OCR tetap jalan tiap siklus, jadi begitu bar kebaca jalur
    // tidur presisi mengambil alih. Hold kedaluwarsa → satu tap "probe" menguji lagi.
    // Kenapa tidak langsung tidur 60s: durasi maturity variabel (weather boost mempersingkat).
    private val NOOP_TAP_THRESHOLD = 2
    private val TAP_HOLD_MS = 15_000L
    @Volatile private var noopTapStreak = 0
    @Volatile private var tapHoldUntil = 0L

    // Anti siklus-phantom: fast-path (tap tanpa OCR) maks 3 rantai beruntun — rantai ke-4 wajib
    // OCR penuh. Jaring terakhir kalau toast no-op lolos dari semua cek (lihat scheduleAfterTap:
    // toast yang menutupi tombol membuat rantai panen palsu yang tak pernah menyentuh OCR).
    private val FAST_PATH_MAX_STREAK = 3
    @Volatile private var fastPathStreak = 0

    private fun tapGardening(screenW: Int, screenH: Int) {
        val (cx, cy) = gardeningCenter(screenW, screenH)
        val x = cx + (-8..8).random()
        val y = cy + (-8..8).random()
        markGardenTap()
        performTap(x, y)
        Log.d(TAG, "Tap Gardening ($x, $y)")
    }

    // ── Tap Gardening via template match (orb glowing) — posisi tap presisi walau tombol geser.
    //    Deteksi status tetap OCR; ini hanya menentukan TITIK tap. Gagal match → fallback koordinat.
    private val gardeningOrbTmpl: Bitmap? by lazy {
        try { BitmapFactory.decodeStream(assets.open("map_icons/gardening_orb.png")) }
        catch (_: Throwable) { null }
    }

    // ── TEMPLATE CONFIRM CAPTCHA (self-learning, usulan user "template matching di tengah") ──
    // Dibuat OTOMATIS dari captcha pertama yang terdeteksi via OCR: crop tombol Confirm disimpan
    // ke filesDir + posisinya (fraksi kanonik) ke prefs — pas skala & posisi di device apa pun.
    // Sesudahnya deteksi captcha cuma ~30ms. JANGAN crop template ini manual dari screenshot chat
    // (skala berubah → SSD tak pernah match).
    @Volatile private var captchaConfirmTmpl: Bitmap? = null
    @Volatile private var captchaTmplLoaded = false

    // Sehabis FALSE ALARM (handleCaptcha masuk tapi popup tak terkonfirmasi), template dicurigai
    // 60 dtk: hit-nya wajib lolos konfirmasi OCR dulu. Mencegah dialog lain yang kebetulan mirip
    // tombol Confirm menjebak loop template→false-alarm→resume selamanya — dialog begitu justru
    // butuh jalur GAME_POPUP/PANEL_STUCK (OCR) untuk dikenali dan ditutup.
    @Volatile private var captchaTmplSuppressedUntil = 0L

    private fun confirmTemplate(): Bitmap? {
        if (!captchaTmplLoaded) {
            captchaTmplLoaded = true
            captchaConfirmTmpl = try {
                val f = java.io.File(filesDir, "captcha_confirm.png")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            } catch (_: Throwable) { null }
            captchaConfirmTmpl?.let { Log.d(TAG, "template Confirm captcha dimuat ${it.width}×${it.height}") }
        }
        return captchaConfirmTmpl
    }

    private fun saveConfirmTemplate(cropped: Bitmap, box: android.graphics.RectF, cxAbs: Float, cyAbs: Float) {
        try {
            val pad = 4
            val l = (box.left.toInt() - pad).coerceAtLeast(0)
            val t = (box.top.toInt() - pad).coerceAtLeast(0)
            val r = (box.right.toInt() + pad).coerceAtMost(cropped.width)
            val b = (box.bottom.toInt() + pad).coerceAtMost(cropped.height)
            if (r - l < 20 || b - t < 10) return   // terlalu kecil untuk jadi template andal
            val tmpl = Bitmap.createBitmap(cropped, l, t, r - l, b - t)
            java.io.FileOutputStream(java.io.File(filesDir, "captcha_confirm.png")).use {
                tmpl.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat("captcha_confirm_cx", cxAbs / screenWidth)
                .putFloat("captcha_confirm_cy", cyAbs / screenHeight)
                .apply()
            captchaConfirmTmpl = tmpl   // langsung aktif; dipegang cache, jangan recycle
            captchaTmplLoaded = true
            Log.d(TAG, "template Confirm captcha disimpan ${tmpl.width}×${tmpl.height} @ (${cxAbs.toInt()},${cyAbs.toInt()})")
        } catch (t: Throwable) {
            Log.w(TAG, "gagal simpan template confirm: ${t.message}")
        }
    }

    /** Deteksi cepat popup captcha via template Confirm (~30ms). false kalau template belum ada. */
    private fun captchaConfirmHit(shot: Bitmap): Boolean {
        val tmpl = confirmTemplate() ?: return false
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val cx = prefs.getFloat("captcha_confirm_cx", -1f)
        val cy = prefs.getFloat("captcha_confirm_cy", -1f)
        if (cx < 0 || cy < 0) return false
        val center = cx * shot.width to cy * shot.height
        return findTemplateNear(shot, tmpl, center, (shot.width * 0.03f).toInt(), 6000.0, "captchaConfirm") != null
    }

    /**
     * PENJAGA CAPTCHA untuk jendela buta + cek murah di loop utama.
     *
     * TEMPLATE = DETEKTOR PRIMER keberadaan captcha (permintaan user); OCR hanya membaca ISI
     * soal di handleCaptcha. Template Confirm hit (~30ms) → langsung tangani TANPA menunggu
     * konfirmasi OCR — OCR pernah gagal membaca captcha asli (insiden 15 dtk), jadi dia tidak
     * boleh memveto template. Salah tebak template pun murah: handleCaptcha keluar via jalur
     * false-alarm tanpa counter, dan template lalu dicurigai 60 dtk (captchaTmplSuppressedUntil,
     * hit wajib dikonfirmasi OCR) supaya dialog lain yang mirip tak menjebak loop.
     *
     * Sebelum template pertama terbentuk: jalur forceOcr = OCR region kecil (~0.3s), meleset →
     * region status lebar (~1.2s) — region kecil SAJA pernah meleset sampai deteksi jatuh ke
     * timeout 15 dtk dan waktu captcha keburu habis.
     * Return true = bitmap DISERAHKAN ke handleCaptcha (caller JANGAN recycle & harus berhenti).
     */
    private fun interceptCaptcha(shot: Bitmap, forceOcr: Boolean = false): Boolean {
        val hit = captchaConfirmHit(shot)
        if (!hit && !forceOcr) return false
        val trusted = System.currentTimeMillis() >= captchaTmplSuppressedUntil
        if (!(hit && trusted) && !detectCaptchaPopup(shot) && !detectCaptchaWide(shot)) return false
        Log.w(TAG, "PENJAGA: captcha terdeteksi di jendela buta → tangani sekarang")
        hud("🔢 CAPTCHA terdeteksi (penjaga)")
        isHandlingCaptcha = true
        handler.removeCallbacks(gardenLoop)
        handleCaptcha(shot)
        return true
    }

    /**
     * Tidur menunggu matang SAMBIL berjaga: cek captcha tiap ~3 detik (template ~30ms;
     * OCR tiap 2 cek (~6 dtk) sbg jaring pengaman sebelum template pertama terbentuk —
     * murah karena kita memang sedang idle menunggu matang).
     * Menggantikan postDelayed panjang yang buta total — dulu captcha yang muncul di tengah
     * tidur maturity baru ketahuan saat bangun (bisa ~60 detik).
     */
    private fun sleepUntilRipeWithSentinel(loop: Runnable, ripeAtMs: Long) {
        scope.launch {
            var tick = 0
            while (true) {
                val remain = ripeAtMs - System.currentTimeMillis() - 1200L
                if (remain <= 300L) break
                kotlinx.coroutines.delay(minOf(remain, 3000L).coerceAtLeast(300L))
                if (!isRunning || isHandlingCaptcha || gardenPaused) return@launch
                if (ripeAtMs - System.currentTimeMillis() - 1200L > 300L) {
                    tick++
                    // Template tiap tick (~30ms); OCR sbg detektor hanya sebelum template ada
                    // (tiap 2 tick ~6s) — sesudahnya jaring tipis tiap 4 tick (~12s).
                    val s = captureScreen() ?: continue
                    val ocrEvery = if (confirmTemplate() != null) 4 else 2
                    if (interceptCaptcha(s, forceOcr = tick % ocrEvery == 0)) return@launch   // s milik handleCaptcha
                    s.recycle()
                }
            }
            handler.post {
                handler.removeCallbacks(loop)   // hindari chain loop ganda dari broadcast/pause
                handler.postDelayed(loop, 300L)
            }
        }
    }

    /** Cek keberadaan orb Gardening di posisi kalibrasi (~30ms) — null kalau tak ada/tertutup. */
    private fun findOrb(shot: Bitmap): Pair<Float, Float>? {
        val tmpl = gardeningOrbTmpl ?: return null
        return findGardeningOrb(shot, tmpl, gardeningCenter(shot.width, shot.height))
    }

    /**
     * VERIFIKASI TAP via hilang-munculnya tombol (usulan user) — tombol Gardening MENGHILANG
     * selama gather dan muncul lagi saat cast selesai. Posisinya TETAP, jadi cek template di situ
     * jauh lebih andal daripada OCR bar "Gathering" yang menempel ke karakter (sering luput).
     *
     * Alur: tap → 700ms → tombol hilang? → lacak via template (tanpa OCR) sampai muncul lagi
     * (= cast selesai) → jeda pickup DELAY_AFTER_GATHER dihitung dari batas TAJAM itu → siklus baru.
     *
     * Batas kemampuan (sengaja): sinyal ini TIDAK menggantikan OCR — tombol hilang bisa juga
     * berarti captcha/popup menutupi. Maka ada timeout 15s: tombol tak muncul lagi → serahkan
     * ke jalur OCR yang bisa mengklasifikasi. Tap tak berefek (tombol tetap ada) → hitung
     * noopTapStreak (usulan user: itu SINYAL maturity) + OCR segera; streak ≥2 → tahan tap.
     */
    // Kadens poll adaptif (lihat loop pelacakan di bawah).
    private val POLL_COARSE_MS = 800L      // durasi cast belum dipelajari: kadens lama
    private val POLL_NEAR_MS = 300L        // dekat batas cast: rapat, supaya batasnya tajam
    private val POLL_BLIND_MAX_MS = 2000L  // jauh dari batas: tidur, tapi jendela buta ≤2 detik
    // Sebelum ambang ini, banner "Obtained" yang terbaca kemungkinan dari sumber lain (drop,
    // quest) dan TIDAK boleh memotong cast yang masih berjalan. Dipakai saat durasi cast belum
    // dipelajari; sesudahnya castFloor yang jadi ambang.
    private val OBTAINED_MIN_ELAPSED_MS = 1500L

    private fun scheduleAfterTap(loop: Runnable) {
        scope.launch {
            // Patokan durasi cast diukur dari SINI — dipanggil tepat setelah performTap.
            val tapAt = System.currentTimeMillis()
            kotlinx.coroutines.delay(700)
            if (isHandlingCaptcha || gardenPaused || !isRunning) return@launch
            val shot = captureScreen()
            var gone = false
            if (shot != null) {
                gone = findOrb(shot) == null
                // Captcha paling sering muncul TEPAT saat tap Gardening — popup menutupi tombol,
                // jadi "tombol hilang" bisa berarti captcha, bukan gathering. Orb masih TERLIHAT =
                // mustahil ada popup menutupinya → lewati cek captcha sama sekali.
                // forceOcr hanya selama template Confirm BELUM ada (momen paling kritis, bayar
                // OCR ~1.5s); sesudah ada, template ~30ms yang berjaga — OCR tinggal baca isi.
                if (gone && interceptCaptcha(shot, forceOcr = confirmTemplate() == null)) return@launch   // shot milik handleCaptcha
                if (gone) {
                    // TEMUAN USER: tap saat maturity memunculkan toast "Plant is not fully
                    // growing" yang MENUTUPI tombol → "hilang"-nya PALSU. Dulu dianggap
                    // gathering → pickup 5s → fast-path tap lagi → siklus phantom tanpa OCR
                    // sama sekali (maturity tak pernah terbaca). Cek teks pita tengah dulu.
                    val band = readCenterBand(shot)
                    if (isNoGardenToast(band)) {
                        Log.d(TAG, "toast no-op: '$band' → BUKAN gathering")
                        noopTapStreak++
                        if (noopTapStreak >= NOOP_TAP_THRESHOLD) {
                            tapHoldUntil = System.currentTimeMillis() + TAP_HOLD_MS
                            hud("🔒 toast 'belum siap' ${noopTapStreak}× → tahan tap ${TAP_HOLD_MS / 1000}s")
                        } else {
                            hud("🚫 toast: tanaman belum siap (bukan gathering)")
                        }
                        shot.recycle()
                        // OCR segera mengklasifikasi (toast masih terbaca / bar fokus ambil alih)
                        handler.post { handler.postDelayed(loop, 200) }
                        return@launch
                    }
                }
                shot.recycle()
            }
            if (!gone) {
                // Tap tidak memicu gather (tombol TETAP ADA). Streak dihitung hanya kalau frame
                // benar-benar terverifikasi — capture gagal (shot null) ≠ tombol tak bereaksi.
                val probing = System.currentTimeMillis() < pickupProbeUntil
                pickupProbeUntil = 0L
                if (shot != null && probing) {
                    // Tap ini SENGAJA lebih awal dari jeda yang sudah terbukti aman, jadi
                    // no-op di sini adalah hasil percobaan — BUKAN sinyal maturity.
                    // Menghitungnya sebagai streak akan membuat bot menahan tap 15 detik
                    // justru karena ia mencoba lebih cepat: makin agresif, makin lambat.
                    pickupLearned = (pickupLearned + PICKUP_BACKOFF_MS).coerceAtMost(DELAY_AFTER_GATHER)
                    Log.d(TAG, "pickup kecepatan -> naikkan jeda ke ${pickupLearned}ms")
                    hud("\u23F1 pickup naik \u2192 ${pickupLearned}ms")
                } else if (shot != null) {
                    noopTapStreak++
                    if (noopTapStreak >= NOOP_TAP_THRESHOLD) {
                        tapHoldUntil = System.currentTimeMillis() + TAP_HOLD_MS
                        Log.w(TAG, "tap tak bereaksi ${noopTapStreak}× → anggap maturity, tahan tap ${TAP_HOLD_MS}ms")
                        hud("🔒 tombol tak bereaksi ${noopTapStreak}× → anggap maturity, tahan tap ${TAP_HOLD_MS / 1000}s")
                    }
                }
                // OCR tetap segera menilai — kalau bar maturity kebaca, tidur presisi ambil alih.
                handler.post { handler.postDelayed(loop, 200) }
                return@launch
            }
            // Tap TERBUKTI bekerja → tombol memang siap; sinyal no-op sebelumnya kadaluarsa.
            noopTapStreak = 0
            tapHoldUntil = 0L
            if (System.currentTimeMillis() < pickupProbeUntil &&
                pickupLearned > PICKUP_FLOOR_MS) {
                pickupLearned = (pickupLearned - PICKUP_SHAVE_MS).coerceAtLeast(PICKUP_FLOOR_MS)
                Log.d(TAG, "pickup ${pickupLearned + PICKUP_SHAVE_MS}ms berhasil -> coba ${pickupLearned}ms")
            }
            pickupProbeUntil = 0L
            hud("🌾 gathering (tombol hilang)")
            Log.d(TAG, "verifikasi tap: tombol hilang → lacak sampai muncul lagi")
            markGardenActivity()
            val start = System.currentTimeMillis()
            var reappeared = false
            var poll = 0
            // POLL ADAPTIF: durasi cast konstan per tanaman, jadi sebagian besar penantian ini
            // sebenarnya sudah bisa diprediksi. Lantai dari sampel TERKECIL (bukan EMA — lihat
            // PerfStats.min: EMA berisi overshoot deteksi dan akan merangkak naik tiap siklus).
            // Butuh ≥3 sampel supaya tanaman/kota yang baru berganti tidak dijadwal dari data basi.
            val castFloor = if (perf.samples(PerfStats.CAST) >= 3) perf.min(PerfStats.CAST) else 0L
            // Ambang mulai curiga oklusi banner. Lantai 1000ms: deteksi lewat banner memberi batas
            // lebih awal → castFloor (min) ikut turun tiap siklus, dan tanpa lantai ambang ini bisa
            // merosot sampai memotong cast yang masih jalan.
            val obtainedAfterMs =
                if (castFloor > 0) maxOf(castFloor - 500, 1000L) else OBTAINED_MIN_ELAPSED_MS
            var occludedByBanner = false
            while (System.currentTimeMillis() - start < 15_000) {
                // Jauh dari batas → tidur lebih panjang, TAPI dibatasi POLL_BLIND_MAX_MS: tiap
                // bangun tetap menjalankan template captcha (~30ms), jadi jendela butanya pendek.
                // Dekat batas (atau durasi cast belum dipelajari) → poll rapat supaya titik
                // "tombol muncul lagi" tajam; jeda pickup dihitung dari titik itu.
                // Durasi cast belum dipelajari → kadens lama 800ms apa adanya. Poll rapat tanpa
                // tahu batasnya cuma menambah capture (300ms + biaya capture, 15 detik penuh)
                // tanpa menajamkan apa pun.
                val wait = if (castFloor <= 0) POLL_COARSE_MS else {
                    val untilBoundary = castFloor - 500 - (System.currentTimeMillis() - tapAt)
                    if (untilBoundary > POLL_NEAR_MS) minOf(untilBoundary, POLL_BLIND_MAX_MS)
                    else POLL_NEAR_MS
                }
                kotlinx.coroutines.delay(wait)
                if (isHandlingCaptcha || gardenPaused || !isRunning) return@launch
                val s = captureScreen() ?: continue
                poll++
                // Orb dicek DULU dari frame yang sama: terlihat = cast selesai & mustahil ada
                // popup menutupi → nol OCR, batas pickup tetap tajam. Masih hilang = jendela
                // paling rawan captcha → template tiap poll (~30ms); OCR sbg detektor hanya
                // SEBELUM template ada (tiap 2 poll ~1.6s — dulu tiap 4: captcha pertama pernah
                // lolos sampai timeout 15s). Sesudah template ada: OCR jaring tipis tiap 6 poll,
                // jaga-jaga template basi (mis. tampilan tombol berubah setelah update game).
                val found = findOrb(s) != null
                if (found) { s.recycle(); reappeared = true; break }
                // BANNER PANEN (temuan user): "Obtained <item> × 1" menyapu NAIK dari bawah layar
                // dan sempat menutupi orb tepat saat panen selesai. Akibatnya orb dianggap "belum
                // muncul" padahal cast sudah kelar, dan jeda pickup baru mulai setelah banner
                // lewat — ongkos itu dibayar sekali tiap panen. Banner itu sendiri bukti panen
                // selesai, jadi dipakai sebagai batas alternatif (bukan pengganti: panen tanpa
                // item mungkin tidak memunculkan banner sama sekali).
                //
                // OCR-nya sangat tersasar: hanya saat orb TIDAK terlihat DAN waktu sudah lewat
                // ambang. Di siklus normal orb keburu ketemu duluan → nol OCR tambahan.
                // Sengaja TIDAK memakai template posisi tetap: banner-nya bergerak.
                // Chat di dasar layar juga memuat "Obtained ..." tapi ada di y>0.78, di luar
                // jangkauan readCenterBand (0.32–0.58) — jadi bukan sumber salah picu.
                if (System.currentTimeMillis() - tapAt >= obtainedAfterMs &&
                    isObtainedToast(readCenterBand(s))) {
                    Log.d(TAG, "banner 'Obtained' terbaca → panen selesai walau orb tertutup")
                    s.recycle(); reappeared = true; occludedByBanner = true; break
                }
                val ocrEvery = if (confirmTemplate() != null) 6 else 2
                if (interceptCaptcha(s, forceOcr = poll % ocrEvery == 0)) return@launch   // s milik handleCaptcha
                s.recycle()
            }
            if (reappeared) {
                // Durasi cast diukur dari tap sampai tombol terlihat lagi. Batas 500..15000
                // membuang pengukuran yang jelas bukan cast (frame nyasar / timeout hampir kena).
                val castMs = System.currentTimeMillis() - tapAt
                if (castMs in 500L..15_000L) perf.record(PerfStats.CAST, castMs)
                val pickup = pickupDelay()
                Log.d(TAG, "cast ${castMs}ms (${if (occludedByBanner) "via banner" else "via orb"})" +
                    " → pickup ${pickup}ms (belajar ${pickupLearned}ms, plafon ${DELAY_AFTER_GATHER}ms)")
                // Penanda 📦 vs ✅ sengaja dibedakan: dari HUD langsung terlihat seberapa sering
                // orb benar-benar tertutup banner, tanpa perlu adb.
                hud(if (occludedByBanner) "📦 banner panen ${castMs / 1000.0}s → pickup ${pickup / 1000.0}s"
                    else "✅ cast ${castMs / 1000.0}s → pickup ${pickup / 1000.0}s")
                markGardenActivity()
                expectReadyNext = true
                // Tap berikutnya adalah PERCOBAAN jeda ini. 3 detik cukup longgar untuk
                // menampung jeda + capture + tap, tapi tetap kedaluwarsa kalau siklus
                // berikutnya ternyata tidak jadi men-tap.
                pickupProbeUntil = System.currentTimeMillis() + pickup + 3000
                handler.post { handler.postDelayed(loop, pickup) }
            } else {
                Log.w(TAG, "tombol tak muncul lagi setelah 15s — mungkin popup/captcha → jalur OCR")
                handler.post { handler.postDelayed(loop, 200) }
            }
        }
    }

    /** Tap tombol Gardening pakai frame yang SUDAH ada (dari analyzeScreen) — caller yang recycle. */
    private suspend fun tapGardeningByTemplate(screen: Bitmap) {
        val tmpl = gardeningOrbTmpl
        if (tmpl == null) { tapGardening(screen.width, screen.height); return }
        val center = gardeningCenter(screen.width, screen.height)
        val pos = withContext(Dispatchers.Default) { findGardeningOrb(screen, tmpl, center) }
        if (pos != null) {
            val x = pos.first + (-8..8).random()
            val y = pos.second + (-8..8).random()
            Log.d(TAG, "Gardening orb match → tap ($x, $y)")
            markGardenTap()
            performTap(x, y)
        } else {
            Log.d(TAG, "Gardening orb tidak match → fallback koordinat kalibrasi")
            tapGardening(screen.width, screen.height)
        }
    }

    /**
     * SSD match orb, DIBATASI ke sekitar titik kalibrasi "gardening".
     *
     * Tombol Gardening adalah elemen HUD — posisinya nyaris tetap. Mencari se-tengah-layar (dulu:
     * x 30–90%, y 10–80%) cuma mengundang false positive: pernah match di langit padang pasir Morroc
     * (SSD 4769, lolos ambang 8000) dan bot mengetuk udara tiap 3 detik tanpa pernah menanam.
     * Dengan kotak sempit ini, match terburuk pun masih mendarat di atas tombolnya.
     */
    private fun findGardeningOrb(screen: Bitmap, tmpl: Bitmap, center: Pair<Float, Float>): Pair<Float, Float>? =
        findTemplateNear(screen, tmpl, center, (screen.width * ORB_SEARCH_MARGIN).toInt(), 8000.0, "orb")

    /** SSD template match dibatasi kotak sekitar center (margin px). Generik utk orb & confirm captcha. */
    private fun findTemplateNear(
        screen: Bitmap, tmpl: Bitmap, center: Pair<Float, Float>,
        margin: Int, threshold: Double, tag: String
    ): Pair<Float, Float>? = perf.time(tag) { findTemplateNearImpl(screen, tmpl, center, margin, threshold, tag) }

    private fun findTemplateNearImpl(
        screen: Bitmap, tmpl: Bitmap, center: Pair<Float, Float>,
        margin: Int, threshold: Double, tag: String
    ): Pair<Float, Float>? {
        val sw = screen.width; val sh = screen.height
        val tw = tmpl.width;  val th = tmpl.height
        if (sw - tw < 0 || sh - th < 0) return null
        val rl = (center.first  - tw / 2f - margin).toInt().coerceIn(0, sw - tw)
        val rt = (center.second - th / 2f - margin).toInt().coerceIn(0, sh - th)
        val rr = (center.first  + tw / 2f + margin).toInt().coerceIn(rl + tw, sw)
        val rb = (center.second + th / 2f + margin).toInt().coerceIn(rt + th, sh)
        val rw = rr - rl; val rh = rb - rt
        // Baca piksel HANYA kotak pencarian — dulu full screen (IntArray 3.7MB per tap → GC churn).
        val screenPx = IntArray(rw * rh)
        screen.getPixels(screenPx, 0, rw, rl, rt, rw, rh)
        val tmplPx = IntArray(tw * th)
        tmpl.getPixels(tmplPx, 0, tw, 0, 0, tw, th)
        val maxDx = rw - tw; val maxDy = rh - th

        var bestSsd = Long.MAX_VALUE
        var bestDx = -1; var bestDy = -1
        val step = 2
        for (dy in 0..maxDy step step) {
            for (dx in 0..maxDx step step) {
                var ssd = 0L
                outer@ for (ty in 0 until th step step) {
                    for (tx in 0 until tw step step) {
                        val sp = screenPx[(dy + ty) * rw + (dx + tx)]
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
        // bestDx/bestDy relatif ke kotak pencarian → tambah offset (rl,rt) balik ke koordinat layar
        Log.d(TAG, "$tag SSD=${"%.0f".format(avg)} pos=(${rl + bestDx},${rt + bestDy})")
        return if (avg <= threshold) (rl + bestDx + tw / 2f) to (rt + bestDy + th / 2f) else null
    }

    // Koordinat masuk dalam ruang KANONIK (tinggi 720, sama dengan bitmap capture) —
    // dipetakan ke piksel asli display di sini. Di 1280×720 faktornya 1 (tanpa perubahan perilaku).
    private fun toRealX(x: Float) = x * realScreenWidth / screenWidth
    private fun toRealY(y: Float) = y * realScreenHeight / screenHeight

    private fun performTap(x: Float, y: Float) {
        // Penanda visual di HUD — koordinat kanonik → fraksi (overlay pakai fraksi layar view-nya)
        botHud?.flashTap(x / screenWidth, y / screenHeight)
        val path = Path().apply { moveTo(toRealX(x), toRealY(y)) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(gameDisplayId)
        dispatchGesture(builder.build(), null, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply { moveTo(toRealX(x1), toRealY(y1)); lineTo(toRealX(x2), toRealY(y2)) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(gameDisplayId)
        dispatchGesture(builder.build(), null, null)
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // CAPTCHA HANDLER
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    private fun saveCaptchaCrop(bitmap: Bitmap) {
        val cp = regionPx("captcha", bitmap) ?: run {
            // Jika region "captcha" tidak dikalibrasi, simpan full screenshot
            val dir = File(getExternalFilesDir(null), "captcha_crops")
            dir.mkdirs()
            val file = File(dir, "captcha_${System.currentTimeMillis()}.png")
            try {
                FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                Log.d(TAG, "Captcha crop saved (full): ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Captcha crop save gagal: ${e.message}")
            }
            return
        }
        val w = cp[2] - cp[0]; val h = cp[3] - cp[1]
        if (w <= 0 || h <= 0) return
        val crop = try {
            Bitmap.createBitmap(bitmap, cp[0], cp[1], w, h)
        } catch (e: Exception) { return }
        val dir = File(getExternalFilesDir(null), "captcha_crops")
        dir.mkdirs()
        val file = File(dir, "captcha_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "Captcha crop saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Captcha crop save gagal: ${e.message}")
        } finally {
            crop.recycle()
        }
    }

    private var captchaTemplateCache: Map<Char, Bitmap>? = null

    private fun getCaptchaTemplates(): Map<Char, Bitmap> {
        captchaTemplateCache?.let { return it }
        val paths = mapOf(
            '1' to "captcha_tmpl/d1.png", '2' to "captcha_tmpl/d2.png",
            '3' to "captcha_tmpl/d3.png", '4' to "captcha_tmpl/d4.png",
            '5' to "captcha_tmpl/d5.png", '6' to "captcha_tmpl/d6.png",
            '7' to "captcha_tmpl/d7.png", '8' to "captcha_tmpl/d8.png",
            '9' to "captcha_tmpl/d9.png",
            '+' to "captcha_tmpl/op_add.png",
            '-' to "captcha_tmpl/op_sub.png",
            '*' to "captcha_tmpl/op_mul.png"
        )
        val loaded = paths.mapNotNull { (ch, path) ->
            try { ch to assets.open(path).use { BitmapFactory.decodeStream(it) } }
            catch (e: Exception) { Log.w(TAG, "Captcha template gagal: $path"); null }
        }.toMap()
        captchaTemplateCache = loaded
        return loaded
    }

    // Template matching untuk baca soal captcha (A op B).
    // Pakai region "captcha" yang sudah di-calibrate tepat di atas ekspresi.
    // Template di-scale turun otomatis kalau lebih besar dari tinggi region.
    private fun parseMathQuestionByTemplate(bitmap: Bitmap): Triple<Int, String, Int>? {
        val cp = regionPx("captcha", bitmap) ?: return null
        val sx = cp[0]; val sy = cp[1]
        val rw = cp[2] - cp[0]; val rh = cp[3] - cp[1]
        if (rw <= 0 || rh <= 0) return null

        Log.d(TAG, "CaptchaTmpl region: rw=$rw rh=$rh")

        val regionPixels = IntArray(rw * rh)
        bitmap.getPixels(regionPixels, 0, rw, sx, sy, rw, rh)

        val BRIGHT_PENALTY = 200.0  // per pixel: template bright tapi screen gelap
        val candidates = mutableListOf<Triple<Char, Int, Double>>()

        for ((ch, origTmpl) in getCaptchaTemplates()) {
            // Scale template turun agar muat di region (jaga aspek ratio)
            val scale = if (origTmpl.height > rh) rh.toFloat() / origTmpl.height else 1f
            val tmpl = if (scale < 1f) {
                val sw = (origTmpl.width * scale).toInt().coerceAtLeast(1)
                val sh = (origTmpl.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(origTmpl, sw, sh, true)
            } else origTmpl

            val tw = tmpl.width; val th = tmpl.height
            val maxDx = rw - tw; val maxDy = rh - th
            if (maxDx < 0 || maxDy < 0) {
                if (scale < 1f) tmpl.recycle()
                continue
            }

            val tPixels = IntArray(tw * th)
            tmpl.getPixels(tPixels, 0, tw, 0, 0, tw, th)

            // darkMask: template pixel gelap (outline karakter)
            // brightMask: template pixel terang (background — seharusnya juga terang di screen)
            val darkMask = BooleanArray(tPixels.size) { i ->
                val tp = tPixels[i]
                if ((tp ushr 24) < 128) return@BooleanArray false
                val g = ((tp shr 16 and 0xff) * 77 + (tp shr 8 and 0xff) * 150 + (tp and 0xff) * 29) shr 8
                g < 140
            }
            val brightMask = BooleanArray(tPixels.size) { i ->
                val tp = tPixels[i]
                if ((tp ushr 24) < 128) return@BooleanArray false
                val g = ((tp shr 16 and 0xff) * 77 + (tp shr 8 and 0xff) * 150 + (tp and 0xff) * 29) shr 8
                g > 200
            }
            val darkCount = darkMask.count { it }.coerceAtLeast(1)
            var bestScoreScaled = Long.MAX_VALUE
            var bestScore = Double.MAX_VALUE
            var bestDx = -1

            for (dy in 0..maxDy) {
                for (dx in 0..maxDx) {
                    var darkSad = 0L
                    var bPenalty = 0
                    outer@ for (ty in 0 until th step 2) {
                        for (tx in 0 until tw step 2) {
                            val idx = ty * tw + tx
                            when {
                                darkMask[idx] -> {
                                    val tp = tPixels[idx]
                                    val sp = regionPixels[(dy + ty) * rw + (dx + tx)]
                                    val dr = (sp shr 16 and 0xff) - (tp shr 16 and 0xff)
                                    val dg = (sp shr 8 and 0xff) - (tp shr 8 and 0xff)
                                    val db = (sp and 0xff) - (tp and 0xff)
                                    darkSad += (dr * dr + dg * dg + db * db).toLong()
                                    if (darkSad >= bestScoreScaled) break@outer
                                }
                                brightMask[idx] -> {
                                    val sp = regionPixels[(dy + ty) * rw + (dx + tx)]
                                    val sg = ((sp shr 16 and 0xff) * 77 + (sp shr 8 and 0xff) * 150 + (sp and 0xff) * 29) shr 8
                                    if (sg < 120) bPenalty++
                                }
                            }
                        }
                    }
                    val score = darkSad.toDouble() / darkCount + bPenalty * BRIGHT_PENALTY
                    if (score < bestScore) {
                        bestScore = score
                        bestScoreScaled = (score * darkCount).toLong()
                        bestDx = dx
                    }
                }
            }

            if (scale < 1f) tmpl.recycle()
            if (bestDx < 0) continue
            candidates.add(Triple(ch, bestDx + tw / 2, bestScore))
            Log.d(TAG, "CaptchaTmpl '$ch': cx=$bestDx score=${"%.0f".format(bestScore)}")
        }

        // NMS overlap threshold: ~1/6 lebar region (sesuai jarak antar karakter di region sempit)
        val nmsOverlap = (rw / 6).coerceIn(15, 50)
        candidates.sortBy { it.third }
        val selected = mutableListOf<Triple<Char, Int, Double>>()
        for (c in candidates) {
            if (selected.size >= 3) break
            if (selected.none { s -> Math.abs(s.second - c.second) < nmsOverlap }) selected.add(c)
        }

        if (selected.size != 3) {
            Log.w(TAG, "CaptchaTmpl: ${selected.size} match, butuh 3")
            return null
        }

        selected.sortBy { it.second }
        val (left, mid, right) = selected
        Log.d(TAG, "CaptchaTmpl kandidat: '${left.first}'@${left.second}(${left.third.toInt()}) '${mid.first}'@${mid.second}(${mid.third.toInt()}) '${right.first}'@${right.second}(${right.third.toInt()})")

        val operators = setOf('+', '-', '*')
        if (mid.first !in operators || left.first !in '1'..'9' || right.first !in '1'..'9') {
            Log.w(TAG, "CaptchaTmpl: struktur salah: ${left.first} ${mid.first} ${right.first}")
            return null
        }

        val a = left.first.digitToInt()
        val op = mid.first.toString()
        val b = right.first.digitToInt()
        Log.d(TAG, "CaptchaTmpl soal: $a $op $b")
        return Triple(a, op, b)
    }

    private fun parseMathQuestion(bitmap: Bitmap): Triple<Int, String, Int>? {
        val processor = ocrProcessor ?: return null
        val mathRegex = Regex("""(?<!\d)(\d{1,2})\s*([+\-*/])\s*(\d{1,2})(?!\d)""")

        // 0. PRIMARY: model rec khusus captcha (di-train sendiri, akurasi tinggi utk digit + - *).
        //    det isolasi baris ekspresi, rec captcha baca; ambil hasil yg match pola math.
        val capProcessor = captchaOcrProcessor
        if (capProcessor != null) {
            val cp0 = regionPx("captcha", bitmap)
            if (cp0 != null) {
                val cw0 = cp0[2] - cp0[0]; val ch0 = cp0[3] - cp0[1]
                if (cw0 > 0 && ch0 > 0) {
                    val crop0 = try { Bitmap.createBitmap(bitmap, cp0[0], cp0[1], cw0, ch0) } catch (_: Throwable) { null }
                    if (crop0 != null) {
                        // upscale 3× — det butuh input cukup besar utk deteksi baris ekspresi
                        val up0 = Bitmap.createScaledBitmap(crop0, cw0 * 3, ch0 * 3, true)
                        try {
                            val texts = capProcessor.processImage(up0, includeAllConfidenceScores = true).texts
                            Log.d(TAG, "CAPTCHA model rec: ${texts.joinToString(" | ")}")
                            for (t in texts) {
                                val norm = t.replace("·", "*").replace("×", "*").replace(" ", "")
                                mathRegex.find(norm)?.let { m ->
                                    val a = m.groupValues[1].toInt(); val op = m.groupValues[2]; val b = m.groupValues[3].toInt()
                                    Log.d(TAG, "CAPTCHA soal dari model rec: $a $op $b")
                                    if (!up0.isRecycled) try { up0.recycle() } catch (_: Throwable) {}
                                    if (!crop0.isRecycled) try { crop0.recycle() } catch (_: Throwable) {}
                                    return Triple(a, op, b)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "CAPTCHA model rec error: ${e.message}")
                        }
                        if (!up0.isRecycled) try { up0.recycle() } catch (_: Throwable) {}
                        if (!crop0.isRecycled) try { crop0.recycle() } catch (_: Throwable) {}
                    }
                }
            }
        }

        // 1. Direct OCR dari region "captcha" yang dikalibrasi — crop bersih, upscale 2× agar OCR akurat
        val cp = regionPx("captcha", bitmap)
        if (cp != null) {
            val cw = cp[2] - cp[0]; val ch2 = cp[3] - cp[1]
            if (cw > 0 && ch2 > 0) {
                val cropped = try { Bitmap.createBitmap(bitmap, cp[0], cp[1], cw, ch2) } catch (_: Throwable) { null }
                if (cropped != null) {
                    val upscaled = Bitmap.createScaledBitmap(cropped, cw * 2, ch2 * 2, true)
                    // Preprocessing (binarize/contrast) merusak font stylized — pakai raw saja
                    val variants = mutableListOf<Pair<String, Bitmap>>()
                    variants.add("raw2x" to upscaled)

                    var found: Triple<Int, String, Int>? = null
                    for ((label, img) in variants) {
                        if (found != null) break
                        try {
                            val t = processor.processImage(img, includeAllConfidenceScores = true).texts.joinToString(" ")
                            Log.d(TAG, "CAPTCHA OCR [$label]: '$t'")
                            val normalized = t.replace("·", "*").replace("×", "*").replace("÷", "/")
                                .replace(Regex("""(?<![a-zA-Z])j(?![a-zA-Z])"""), "1")
                                .replace(Regex("""\d{2}:\d{2}"""), " ")
                            mathRegex.find(normalized)?.let { m ->
                                val a = m.groupValues[1].toInt(); val op = m.groupValues[2]; val b = m.groupValues[3].toInt()
                                Log.d(TAG, "CAPTCHA soal dari OCR [$label]: $a $op $b")
                                found = Triple(a, op, b)
                            }
                        } catch (_: Throwable) {}
                    }
                    for ((_, img) in variants) { if (img !== cropped && img !== upscaled && !img.isRecycled) try { img.recycle() } catch (_: Throwable) {} }
                    if (!upscaled.isRecycled) try { upscaled.recycle() } catch (_: Throwable) {}
                    if (!cropped.isRecycled) try { cropped.recycle() } catch (_: Throwable) {}
                    if (found != null) return found
                }
            }
        }

        // 3. Hint OCR dari status text popup
        val hint = captchaStatusHint
        if (hint.isNotEmpty()) {
            mathRegex.find(hint)?.let { m ->
                val hintA = m.groupValues[1].toInt(); val hintOp = m.groupValues[2]; val hintB = m.groupValues[3].toInt()
                Log.d(TAG, "CAPTCHA soal dari hint: $hintA $hintOp $hintB")
                return Triple(hintA, hintOp, hintB)
            }
        }

        // 4. Fallback: multi-crop OCR
        val cpFb = regionPx("captcha", bitmap) ?: return null
        val chFb = cpFb[3] - cpFb[1]
        val cropRegions = listOf(
            intArrayOf(cpFb[0], cpFb[1] + (chFb * 0.30f).toInt(), cpFb[2], cpFb[1] + (chFb * 0.65f).toInt()),
            intArrayOf(cpFb[0], cpFb[1], cpFb[2], cpFb[3])
        )

        for ((idx, a) in cropRegions.withIndex()) {
            val w = a[2] - a[0]; val h = a[3] - a[1]
            if (w <= 0 || h <= 0) continue
            val cropped = try {
                Bitmap.createBitmap(bitmap, a[0], a[1], w, h)
            } catch (t: Throwable) { continue }

            // PaddleOCR preprocessed (candidate pertama, lebih dipercaya), lalu raw sebagai fallback
            val candidates = mutableListOf<String>()
            val forOcr = preprocessForOcr(cropped)
            try {
                val t = processor.processImage(forOcr, includeAllConfidenceScores = true).texts.joinToString(" ")
                if (t.isNotEmpty()) candidates.add(t)
            } catch (_: Throwable) {} finally {
                if (!forOcr.isRecycled) try { forOcr.recycle() } catch (_: Throwable) {}
            }
            try {
                val t = processor.processImage(cropped, includeAllConfidenceScores = true).texts.joinToString(" ")
                if (t.isNotEmpty() && t !in candidates) candidates.add(t)
            } catch (_: Throwable) {}

            if (!cropped.isRecycled) try { cropped.recycle() } catch (_: Throwable) {}
            if (candidates.isEmpty()) continue

            for (candidate in candidates) {
                Log.d(TAG, "CAPTCHA OCR [crop=$idx]: '$candidate'")
                val normalized = candidate
                    .replace(Regex("""\d{2}:\d{2}"""), " ")
                    .replace("#", "+")
                    .replace("•", "*").replace("·", "*")
                    .replace(Regex("""(?<=\d)\s*no\s*(?=\d)"""), "-")
                    .replace("×", "*").replace("÷", "/")
                    .replace(Regex("""[xX](?=\s*\d)"""), "*")
                    .replace("米", "*")
                    .replace("—", "-").replace("–", "-").replace("一", "-")
                    .replace(Regex("""(?<=\d)\s*=\s*(?=\d)"""), "-")
                    .replace("=", " ")
                    // 'j' sering dibaca OCR sebagai '1' (visual mirip di font game)
                    .replace(Regex("""(?<![a-zA-Z])j(?![a-zA-Z])"""), "1")

                // Parse ekspresi lengkap dari crop
                val match = mathRegex.find(normalized) ?: continue
                val cropA = match.groupValues[1].toInt()
                val op = match.groupValues[2]
                val cropB = match.groupValues[3].toInt()
                Log.d(TAG, "CAPTCHA soal: $cropA $op $cropB")
                return Triple(cropA, op, cropB)
            }
        }
        return null
    }

    private fun handleCaptcha(bitmap: Bitmap) {
        if (!ocrReady || ocrProcessor == null) {
            Log.e(TAG, "OCR belum ready, tunggu 10s")
            resumeAfterDelay(10000)
            return
        }

        scope.launch {
            val bmpH = bitmap.height   // simpan dimensi — bitmap di-recycle sebelum callback bersarang
            try {
                saveCaptchaCrop(bitmap)
                var question = parseMathQuestion(bitmap)
                var retryBitmap = bitmap
                var retryCount = 0
                while (question == null && retryCount < 3) {
                    retryCount++
                    Log.d(TAG, "CAPTCHA: OCR gagal, retry $retryCount/3 dalam 1500ms...")
                    Thread.sleep(1500)
                    val fresh = captureScreen()
                    if (fresh != null) {
                        if (retryBitmap !== bitmap) retryBitmap.recycle()
                        retryBitmap = fresh
                        question = parseMathQuestion(retryBitmap)
                    }
                }
                if (question == null) {
                    // Soal tak terbaca — cek ulang: popup-nya masih ADA tidak? Kalau tidak ada,
                    // ini FALSE POSITIVE (OCR scene game salah baca) atau popup keburu hilang —
                    // keluar murah, JANGAN naikkan counter hard-stop, jangan tunggu 35 detik.
                    // WAJIB cek region kecil DAN lebar: region kecil saja pernah meleset pada
                    // captcha ASLI → captcha hidup bisa salah dicap false alarm & dibiarkan
                    // tak terjawab tanpa counter (loop tanpa hard-stop).
                    val stillThere = detectCaptchaPopup(retryBitmap) || detectCaptchaWide(retryBitmap)
                    // Captcha asli tapi soal gagal dibaca: tetap PELAJARI template Confirm dari
                    // frame ini — kalau captcha ini keburu expired, minimal captcha berikutnya
                    // terdeteksi ~30ms sejak muncul, bukan menunggu jaring OCR berkala.
                    if (stillThere && confirmTemplate() == null) findAndLearnConfirm(retryBitmap)
                    if (retryBitmap !== bitmap && !retryBitmap.isRecycled) retryBitmap.recycle()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (!stillThere) {
                        Log.w(TAG, "CAPTCHA: false alarm (soal tak terbaca & popup tak terkonfirmasi) → lanjut normal")
                        hud("↩ false alarm captcha, lanjut")
                        // Template dicurigai sebentar: kalau pemicunya template yang salah kenal
                        // dialog lain, hit berikutnya wajib lolos OCR dulu — beri kesempatan
                        // jalur GAME_POPUP mengenali & menutup dialognya.
                        captchaTmplSuppressedUntil = System.currentTimeMillis() + 60_000L
                        handler.post { resumeAfterDelay(3000) }
                        return@launch
                    }
                    Log.d(TAG, "CAPTCHA: soal tidak terbaca setelah $retryCount retry, tunggu ${DELAY_CAPTCHA_TIMEOUT}ms")
                    consecutiveFails++
                    handler.post { resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT) }
                    return@launch
                }

                // Cari tombol Confirm + pelajari template-nya (self-learning) — findAndLearnConfirm.
                val (confirmX, confirmY) = findAndLearnConfirm(bitmap)

                val (a, op, b) = question
                val answer = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b != 0) a / b else -1
                    else -> -1
                }

                saveCaptchaScreenshot(retryBitmap, a, op, b, answer)
                // pixel bitmap/retryBitmap tidak dipakai lagi (callback bawah cuma butuh bmpH) → recycle
                if (retryBitmap !== bitmap && !retryBitmap.isRecycled) retryBitmap.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()

                if (answer < 0) {
                    Log.d(TAG, "CAPTCHA: gagal solve, tunggu ${DELAY_CAPTCHA_TIMEOUT}ms")
                    consecutiveFails++
                    handler.post { resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT) }
                    return@launch
                }

                Log.d(TAG, "CAPTCHA: $a $op $b = $answer")

                val fConfirmX = confirmX; val fConfirmY = confirmY

                handler.post {
                    try {
                        // Tap input field (~13% di atas Confirm)
                        val inputX = fConfirmX + (-6..6).random()
                        val inputY = fConfirmY - (bmpH * 0.13f) + (-6..6).random()
                        Log.d(TAG, "Tap input field ($inputX, $inputY)")
                        performTap(inputX, inputY)

                        handler.postDelayed({
                            // capture blocking → jalankan di background, jangan di main
                            scope.launch {
                                try {
                                    val numpadBitmap = captureScreen()
                                    if (numpadBitmap == null) {
                                        consecutiveFails++
                                        handler.post { resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT) }
                                        return@launch
                                    }
                                    detectAndTapNumpad(answer.toString(), numpadBitmap, fConfirmX, fConfirmY)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Numpad handling crash: ${t.javaClass.simpleName}: ${t.message}")
                                    consecutiveFails++
                                    handler.post { resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT) }
                                }
                            }
                        }, 1200)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Captcha input tap crash: ${t.javaClass.simpleName}: ${t.message}")
                        consecutiveFails++
                        resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT)
                    }
                }

            } catch (t: Throwable) {
                Log.e(TAG, "CAPTCHA error: ${t.javaClass.simpleName}: ${t.message}")
                consecutiveFails++
                handler.post { resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT) }
            }
        }
    }

    /**
     * OCR area popup (region captcha + 30% ke bawah — robust ke kalibrasi region yang terlalu
     * kecil sampai tombol Confirm tak tertangkap) untuk mencari tombol Confirm. Ketemu →
     * crop-nya disimpan sebagai template deteksi cepat (self-learning, ~30ms per cek).
     * Return posisi Confirm, atau default tengah-bawah region kalau tidak ketemu.
     * BLOCKING — panggil dari background thread.
     */
    private fun findAndLearnConfirm(bitmap: Bitmap): Pair<Float, Float> {
        val cp = regionPx("captcha", bitmap) ?: intArrayOf(
            (bitmap.width * 0.330).toInt(), (bitmap.height * 0.280).toInt(),
            (bitmap.width * 0.720).toInt(), (bitmap.height * 0.850).toInt()
        )
        val x1 = cp[0]; val y1 = cp[1]; val x2 = cp[2]; val y2 = cp[3]
        var confirmX = (x1 + x2) / 2f
        var confirmY = y1 + (y2 - y1) * 0.90f
        val processor = ocrProcessor ?: return confirmX to confirmY
        val searchY2 = minOf(bitmap.height, y2 + (bitmap.height * 0.30f).toInt())
        var cropped: Bitmap? = null
        try {
            cropped = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, searchY2 - y1)
            val result = processor.processImage(cropped, includeAllConfidenceScores = true)
            for (i in result.texts.indices) {
                if (result.texts[i].lowercase().contains("confirm")) {
                    val box = result.boxes[i].boundingRect()
                    confirmX = box.centerX() + x1
                    confirmY = box.centerY() + y1
                    Log.d(TAG, "Found 'Confirm' at ($confirmX, $confirmY)")
                    saveConfirmTemplate(cropped, box, confirmX, confirmY)
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cari Confirm gagal: ${t.message}")
        } finally {
            cropped?.let { if (!it.isRecycled) it.recycle() }
        }
        return confirmX to confirmY
    }

    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // NUMPAD DETECTION + TAP
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    // Fallback hardcoded untuk 1280x720 jika template matching gagal.
    private val numpadFallback: Map<Char, Pair<Float, Float>> = mapOf(
        '1' to Pair(831f, 410f), '2' to Pair(907f, 410f), '3' to Pair(983f, 410f),
        '4' to Pair(831f, 484f), '5' to Pair(907f, 484f), '6' to Pair(983f, 484f), '0' to Pair(1059f, 484f),
        '7' to Pair(831f, 558f), '8' to Pair(907f, 558f), '9' to Pair(983f, 558f), '✓' to Pair(1059f, 558f)
    )

    private var numpadTemplateCache: Map<Char, Bitmap>? = null

    private fun getNumpadTemplates(): Map<Char, Bitmap> {
        numpadTemplateCache?.let { return it }
        val paths = mapOf(
            '0' to "numpad/btn_0.png", '1' to "numpad/btn_1.png",
            '2' to "numpad/btn_2.png", '3' to "numpad/btn_3.png",
            '4' to "numpad/btn_4.png", '5' to "numpad/btn_5.png",
            '6' to "numpad/btn_6.png", '7' to "numpad/btn_7.png",
            '8' to "numpad/btn_8.png", '9' to "numpad/btn_9.png",
            '✓' to "numpad/btn_check.png"
        )
        val loaded = paths.mapNotNull { (ch, path) ->
            try {
                val bm = assets.open(path).use { BitmapFactory.decodeStream(it) }
                ch to bm
            } catch (e: Exception) {
                Log.w(TAG, "Template load gagal: $path")
                null
            }
        }.toMap()
        numpadTemplateCache = loaded
        Log.d(TAG, "Numpad templates loaded: ${loaded.keys.sorted()}")
        return loaded
    }

    // Cari posisi tombol via SAD template matching.
    // Hanya match digit yang dibutuhkan (needed) agar lebih cepat.
    // Region pencarian dibatasi ke area kanan layar tempat numpad muncul.
    private fun findNumpadByTemplate(screen: Bitmap, needed: Set<Char>): Map<Char, Pair<Float, Float>> {
        val templates = getNumpadTemplates()
        val result = mutableMapOf<Char, Pair<Float, Float>>()

        // Numpad selalu muncul di kanan layar — batasi area pencarian
        val rx = (screen.width * 0.50f).toInt()
        val ry = (screen.height * 0.42f).toInt()
        val rw = screen.width - rx
        val rh = (screen.height * 0.90f).toInt() - ry
        if (rw <= 0 || rh <= 0) return result

        val regionPixels = IntArray(rw * rh)
        screen.getPixels(regionPixels, 0, rw, rx, ry, rw, rh)

        for (ch in needed) {
            val tmpl = templates[ch] ?: continue
            val tw = tmpl.width; val th = tmpl.height
            val maxDx = rw - tw; val maxDy = rh - th
            if (maxDx < 0 || maxDy < 0) continue

            val tPixels = IntArray(tw * th)
            tmpl.getPixels(tPixels, 0, tw, 0, 0, tw, th)

            var bestSad = Long.MAX_VALUE
            var bestDx = -1; var bestDy = -1
            val step = 2

            for (dy in 0..maxDy step step) {
                for (dx in 0..maxDx step step) {
                    var sad = 0L
                    outer@ for (ty in 0 until th step step) {
                        for (tx in 0 until tw step step) {
                            val sp = regionPixels[(dy + ty) * rw + (dx + tx)]
                            val tp = tPixels[ty * tw + tx]
                            val dr = (sp shr 16 and 0xff) - (tp shr 16 and 0xff)
                            val dg = (sp shr 8 and 0xff) - (tp shr 8 and 0xff)
                            val db = (sp and 0xff) - (tp and 0xff)
                            sad += (dr * dr + dg * dg + db * db).toLong()
                            if (sad >= bestSad) break@outer
                        }
                    }
                    if (sad < bestSad) { bestSad = sad; bestDx = dx; bestDy = dy }
                }
            }

            if (bestDx < 0) continue
            val sampledPx = ((tw + step - 1) / step) * ((th + step - 1) / step)
            val avgSad = bestSad.toDouble() / sampledPx
            Log.d(TAG, "Template '$ch': pos=(${rx+bestDx},${ry+bestDy}) avgSAD=${"%.0f".format(avgSad)}")
            if (avgSad <= NUMPAD_TEMPLATE_THRESHOLD) {
                result[ch] = Pair((rx + bestDx + tw / 2).toFloat(), (ry + bestDy + th / 2).toFloat())
            }
        }

        Log.d(TAG, "Template match result: ${result.keys.sorted()} / needed: ${needed.sorted()}")
        return result
    }

    private fun detectAndTapNumpad(answer: String, bitmap: Bitmap, confirmX: Float, confirmY: Float) {
        scope.launch {
            try {
            val needed = answer.toSet() + '✓'
            val matched = findNumpadByTemplate(bitmap, needed)
            if (!bitmap.isRecycled) bitmap.recycle()   // hanya dipakai utk template match di atas
            val allFound = needed.all { it in matched }
            if (allFound) {
                Log.d(TAG, "Numpad: template matching OK — pakai hardcoded positions (template hanya untuk verifikasi)")
            } else {
                Log.w(TAG, "Numpad: template matching tidak lengkap (${matched.keys}), pakai hardcoded fallback")
            }
            // Selalu pakai hardcoded positions — template positions bisa false-match ke tombol yang salah
            val positions: Map<Char, Pair<Float, Float>> = numpadFallback

            handler.post {
                var delay = 0L
                for (digit in answer) {
                    val pos = positions[digit] ?: run {
                        Log.e(TAG, "Numpad: digit '$digit' tidak ditemukan")
                        consecutiveFails++
                        resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT)
                        return@post
                    }
                    handler.postDelayed({
                        val jitter = (-5..5).random().toFloat()
                        performTap(pos.first + jitter, pos.second + jitter)
                        Log.d(TAG, "Tap digit '$digit' at (${pos.first.toInt()}, ${pos.second.toInt()})")
                    }, delay)
                    delay += (300..700).random().toLong()
                }
                delay += (400..800).random().toLong()
                val checkPos = positions['✓'] ?: numpadFallback['✓']!!
                handler.postDelayed({
                    val j = (-5..5).random().toFloat()
                    performTap(checkPos.first + j, checkPos.second + j)
                    Log.d(TAG, "Tap numpad checkmark at (${checkPos.first.toInt()}, ${checkPos.second.toInt()})")
                }, delay)
                delay += (400..600).random().toLong()
                tapConfirmUntilDone(confirmX, confirmY, 0, delay)
            }
            } catch (t: Throwable) {
                Log.e(TAG, "detectAndTapNumpad crash: ${t.javaClass.simpleName}: ${t.message}", t)
                consecutiveFails++
                handler.post { resumeAfterDelayOrStop(DELAY_CAPTCHA_TIMEOUT) }
            }
        }
    }

    private fun tapConfirmUntilDone(confirmX: Float, confirmY: Float, attempt: Int, delay: Long) {
        if (attempt >= 4) {
            Log.e(TAG, "CAPTCHA: max attempts, resume")
            consecutiveFails++
            resumeAfterDelayOrStop(2000)
            return
        }
        handler.postDelayed({
            performTap(confirmX + (-8..8).random(), confirmY + (-8..8).random())
            Log.d(TAG, "CAPTCHA: Tap Confirm #${attempt + 1}")

            handler.postDelayed({
                scope.launch {
                    // capture blocking → background
                    val bitmap = captureScreen() ?: run {
                        consecutiveFails++
                        handler.post { resumeAfterDelayOrStop(2000) }
                        return@launch
                    }
                    val stillCaptcha = detectCaptchaPopup(bitmap)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    handler.post {
                        if (!stillCaptcha) {
                            Log.d(TAG, "CAPTCHA: Sukses! Popup hilang setelah ${attempt + 1} tap")
                            consecutiveFails = 0
                            resumeAfterDelay(1000)
                            return@post
                        }
                        tapConfirmUntilDone(confirmX, confirmY, attempt + 1, 800)
                    }
                }
            }, 1200)
        }, delay)
    }


    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
    // HELPERS
    // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    
    private fun saveCaptchaScreenshot(bitmap: Bitmap, a: Int, op: String, b: Int, answer: Int) {
        try {
            val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "captcha")
            if (!dir.exists()) dir.mkdirs()
            val opName = when (op) { "+" -> "add"; "-" -> "sub"; "*" -> "mul"; "/" -> "div"; else -> "unk" }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "captcha_${ts}_${a}${opName}${b}_ans${answer}.png")
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "Captcha screenshot saved: ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save captcha screenshot: ${e.message}")
        }
    }

    private fun preprocessForOcr(bitmap: Bitmap, scaleFactor: Float = 3.0f): Bitmap {
        val w = (bitmap.width  * scaleFactor).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (!scaled.sameAs(bitmap)) scaled.recycle()

        var sum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            sum += (0.299f * r + 0.587f * g + 0.114f * b).toLong()
        }
        val threshold = (sum / pixels.size).toInt()
        for (i in pixels.indices) {
            val p = pixels[i]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            pixels[i] = if (gray > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    // Binarize dengan contrast stretch dulu + threshold variabel, opsional invert
    private fun binarizeWithContrast(bitmap: Bitmap, thresholdPct: Float = 0.5f, invert: Boolean = false): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Konversi ke grayscale + cari min/max untuk contrast stretch
        val gray = IntArray(pixels.size)
        var minG = 255; var maxG = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = ((0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))).toInt()
            gray[i] = g
            if (g < minG) minG = g
            if (g > maxG) maxG = g
        }
        val range = (maxG - minG).coerceAtLeast(1)
        val threshold = (minG + range * thresholdPct).toInt()

        for (i in pixels.indices) {
            val isLight = gray[i] > threshold
            val isFg = if (invert) isLight else !isLight
            pixels[i] = if (isFg) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun resumeAfterDelay(delayMs: Long) {
        isHandlingCaptcha = false
        handler.removeCallbacks(gardenLoop)
        handler.postDelayed(gardenLoop, delayMs)
        Log.d(TAG, "Resume loop setelah ${delayMs}ms")
    }

    private fun resumeAfterDelayOrStop(delayMs: Long) {
        if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
            Log.e(TAG, "STOP: CAPTCHA gagal ${consecutiveFails}x berturut-turut")
            // Stop DISENGAJA — matikan juga auto-resume, jangan bangkit ke kegagalan yang sama.
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WAS_RUNNING, false).apply()
            isRunning = false
            isHandlingCaptcha = false
            handler.removeCallbacks(gardenLoop)
            stopForeground(true)
            return
        }
        resumeAfterDelay(delayMs)
    }
}




