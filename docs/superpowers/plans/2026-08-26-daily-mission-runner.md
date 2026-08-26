# Daily Mission Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bot menjalankan misi harian Mission Board (Carnival) secara otomatis dari navigasi sampai Submit, dipicu manual.

**Architecture:** Satu file baru `DailyMissionRunner.kt` yang dikonstruksi murni dari lambda (pola `WeatherScanner`/`CityNavigator`), tidak pernah mereferensikan `GardenService` balik. `GardenService` menyediakan kemampuan (capture, tap, OCR, captcha) dan memegang kontrak pause/resume gardening. Terminasi berbasis perilaku, bukan hitungan tetap.

**Tech Stack:** Kotlin, Android AccessibilityService, onnxruntime-android (OCR), template matching SSD manual (tanpa OpenCV).

**Spec:** `docs/superpowers/specs/2026-08-26-daily-mission-design.md`

## Global Constraints

- **Ruang koordinat kanonik 1280×720.** Semua analisis (template, OCR, region) bekerja di ruang kanonik; `performTap` yang memetakan balik ke resolusi asli. Kode baru harus tetap di ruang kanonik sampai titik tap.
- **Komentar kode ditulis dalam bahasa Indonesia**, padat menjelaskan *kenapa* sebuah nilai/urutan dipilih. Jangan hapus komentar semacam itu saat refactor.
- **Tidak ada test suite.** CLAUDE.md: "Verifikasi dilakukan langsung di device." Siklus tes tiap task = build → install → broadcast → assert logcat. Jangan menambahkan framework tes; itu di luar cakupan.
- **Build:** PowerShell, `$env:ANDROID_HOME = "C:\Android"; & "C:\Gradle\gradle-8.0\bin\gradle.bat" :app:assembleDebug --no-daemon --console=plain`
- **Device uji:** MuMu di `127.0.0.1:16384` (native 720p, root tersedia). ADB di `C:\Android\platform-tools\adb.exe`.
- **Template WAJIB di-crop dari frame kanonik device lewat adb**, tidak boleh dari screenshot chat (skala berbeda → SSD tidak match).
- **Disiplin bitmap:** tiap capture ~3.7MB. Setiap cabang harus punya pemilik yang jelas dan me-`recycle()`. `checkCaptcha` MENGAMBIL ALIH bitmap kalau mengembalikan `true` — pemanggil tidak boleh recycle lagi.
- **Duplikasi matcher SSD adalah konvensi, bukan kelalaian.** `CityNavigator.findTemplate` dan `WeatherScanner.findTemplateOnScreen` sudah masing-masing punya salinan sendiri, konsekuensi aturan "fitur terpisah = hapus file + blok pemanggil = fitur hilang bersih". `DailyMissionRunner` mengikuti pola yang sama. JANGAN mengekstrak jadi util bersama dalam rencana ini.

---

## File Structure

| File | Tanggung jawab |
|---|---|
| `app/src/main/java/com/roxgarden/DailyMissionRunner.kt` (baru) | Seluruh fitur: navigasi ke Mission Board, accept, loop misi, deteksi selesai. Self-contained, dapat dihapus bersih. |
| `app/src/main/java/com/roxgarden/GardenService.kt` (modifikasi) | Instansiasi runner + wiring lambda; `startDailyMission()` yang memegang kontrak `gardenPaused`; broadcast receiver debug. |
| `app/src/main/java/com/roxgarden/MainActivity.kt` (modifikasi) | Tombol pemicu untuk device tanpa adb. |

Aset yang sudah ada dan dipakai: `assets/map_icons/carnival.png` (74×51), `assets/map_icons/mission_board.png` (74×72). **Tidak ada aset baru** — "Go Now", "Accept All", "Use", "Submit" semuanya teks, dicari lewat OCR (pola `tapLocationButton` di `CityNavigator`), bukan template. Ini menyimpang dari dugaan di spec bagian "Aset template", dan menyimpangnya menguntungkan: lebih sedikit aset, lebih tahan terhadap pergeseran posisi tombol.

---

### Task 1: Runner skeleton + fase navigasi + kontrak pause gardening

Menghasilkan: `--ei max 0` menavigasi Carnival → Mission Board → Go Now → Accept All → tutup panel, lalu berhenti, dan gardening jalan lagi sesudahnya.

**Files:**
- Create: `app/src/main/java/com/roxgarden/DailyMissionRunner.kt`
- Modify: `app/src/main/java/com/roxgarden/GardenService.kt` (tambah instance, `startDailyMission`, receiver, registrasi di `onServiceConnected`, unregister di `onDestroy`)

**Interfaces:**
- Consumes: `captureScreen(): Bitmap?`, `performTap(Float, Float)`, `performSwipe(Float, Float, Float, Float, Long)`, `ocrProcessor: OcrProcessor?`, `interceptCaptcha(Bitmap, Boolean): Boolean`, `isHandlingCaptcha: Boolean`, `cityNavigator.closePanels()`, `markGardenActivity()`, `hud(String)` — semuanya sudah ada di `GardenService`.
- Produces: `DailyMissionRunner.run(maxMissions: Int): Boolean` dan `GardenService.startDailyMission(maxMissions: Int)`. Task 2 menambah isi loop misi ke file yang sama; Task 3 memanggil `startDailyMission` dari UI.

- [ ] **Step 1: Buat `DailyMissionRunner.kt` dengan helper capture/template/OCR**

```kotlin
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

        // Ambang SSD sama dengan CityNavigator: match benar ~1800, match salah ~10000+.
        private const val TEMPLATE_THRESHOLD = 6000.0

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

    // SSD template matching. Salinan sendiri — SENGAJA, lihat "Global Constraints":
    // CityNavigator & WeatherScanner juga masing-masing punya salinannya.
    private fun findTemplate(screen: Bitmap, tmpl: Bitmap): Pair<Float, Float>? {
        val sw = screen.width; val sh = screen.height
        val tw = tmpl.width;  val th = tmpl.height
        if (sw - tw < 0 || sh - th < 0) return null

        val screenPx = IntArray(sw * sh)
        screen.getPixels(screenPx, 0, sw, 0, 0, sw, sh)
        val tmplPx = IntArray(tw * th)
        tmpl.getPixels(tmplPx, 0, tw, 0, 0, tw, th)

        var bestSsd = Long.MAX_VALUE
        var bestDx = -1; var bestDy = -1
        val step = 2
        for (dy in 0..(sh - th) step step) {
            for (dx in 0..(sw - tw) step step) {
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

    private suspend fun tapTemplate(assetPath: String, label: String): Boolean {
        val tmpl = getTemplate(assetPath) ?: return false
        val screen = captureStable() ?: return false
        val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl) }
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
        if (!tapTemplate("map_icons/carnival.png", "Carnival")) return abort("ikon Carnival tak ketemu")
        delay(D_NAV)

        hud("📋 buka Mission Board")
        if (!tapTemplate("map_icons/mission_board.png", "Mission Board")) return abort("ikon Mission Board tak ketemu")
        delay(D_NAV)

        hud("▶ Go Now")
        if (!tapTextButton("go now", 0.45f)) return abort("tombol Go Now tak ketemu")
        delay(D_NAV)

        hud("✅ Accept All")
        if (!tapTextButton("accept all", 0.45f)) return abort("tombol Accept All tak ketemu")
        delay(D_NAV)

        hud("🚪 tutup panel")
        closePanels()
        delay(D_NAV)
        return true
    }

    /**
     * @param maxMissions batas ATAS pengaman; 0 = hanya navigasi + Accept All (untuk pengetesan).
     *   Terminasi sebenarnya BERBASIS PERILAKU, bukan hitungan — lihat Task 2.
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
```

- [ ] **Step 2: Wiring instance di `GardenService.kt`**

Sisipkan tepat setelah blok `private val cityNavigator by lazy { ... }` (cari `back = { performGlobalAction(GLOBAL_ACTION_BACK) }` lalu baris `)` dan `}` penutupnya):

```kotlin
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
            markActivity = { markGardenActivity() }
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
    fun startDailyMission(maxMissions: Int) {
        if (gardenPaused || isHandlingCaptcha) {
            Log.w(TAG, "Daily mission ditolak: bot sedang sibuk (paused=$gardenPaused captcha=$isHandlingCaptcha)")
            hud("⚠ daily mission ditolak, bot sibuk")
            return
        }
        gardenPaused = true
        handler.removeCallbacks(gardenLoop)
        scope.launch {
            try {
                dailyMissionRunner.run(maxMissions)
            } catch (t: Throwable) {
                Log.e(TAG, "Daily mission crash: ${t.javaClass.simpleName}: ${t.message}")
                hud("💥 daily mission error")
            } finally {
                markGardenActivity()
                gardenPaused = false
                handler.post { handler.postDelayed(gardenLoop, 6000) }
                Log.d(TAG, "=== DAILY MISSION selesai, gardening lanjut ===")
            }
        }
    }

    // Trigger test via ADB: adb shell am broadcast -a com.roxgarden.DAILY_MISSION --ei max 0
    private val dailyMissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val max = intent?.getIntExtra("max", 10) ?: 10
            Log.d(TAG, "DAILY MISSION dipicu manual via broadcast (max=$max)")
            startDailyMission(max)
        }
    }
```

- [ ] **Step 3: Daftarkan receiver di `onServiceConnected` dan `onDestroy`**

Di `onServiceConnected`, sisipkan setelah blok registrasi `stuckTestReceiver` (cari `IntentFilter("com.roxgarden.STUCK_TEST")`):

```kotlin
        // ── DAILY MISSION test trigger (removable) ──
        val df = IntentFilter("com.roxgarden.DAILY_MISSION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dailyMissionReceiver, df, Context.RECEIVER_EXPORTED)
        else registerReceiver(dailyMissionReceiver, df)
```

Di `onDestroy`, sisipkan bersama unregister lainnya (cari `unregisterReceiver(stuckTestReceiver)`):

```kotlin
        try { unregisterReceiver(dailyMissionReceiver) } catch (_: Throwable) {}   // DAILY MISSION (removable)
```

- [ ] **Step 4: Build**

```powershell
$env:ANDROID_HOME = "C:\Android"; & "C:\Gradle\gradle-8.0\bin\gradle.bat" :app:assembleDebug --no-daemon --console=plain
```

Harapan: `BUILD SUCCESSFUL`. Kalau gagal karena `registerReceiver` else-branch, cek apakah pola `else registerReceiver(...)` memang ada di receiver lain di file itu; ikuti persis pola yang ada di sana.

- [ ] **Step 5: Install + bersihkan log**

```bash
ADB=/c/Android/platform-tools/adb.exe; D=127.0.0.1:16384
"$ADB" -s $D logcat -c
"$ADB" -s $D install -r app/build/outputs/apk/debug/app-debug.apk
```

Harapan: `Success`. **Reinstall mematikan AccessibilityService** — minta user menekan START GARDENING lagi sebelum lanjut.

- [ ] **Step 6: Jalankan uji navigasi**

Pastikan karakter sedang di kota (layar utama, tidak ada panel terbuka), lalu:

```bash
ADB=/c/Android/platform-tools/adb.exe; D=127.0.0.1:16384
"$ADB" -s $D shell am broadcast -a com.roxgarden.DAILY_MISSION --ei max 0
sleep 25
"$ADB" -s $D logcat -d -v time -s GardenService:D DailyMission:D | tail -40
```

Harapan di log, berurutan:

```
DAILY MISSION dipicu manual via broadcast (max=0)
=== DAILY MISSION mulai (maxMissions=0) ===
tap 'Carnival' → (...)
tap 'Mission Board' → (...)
tap teks 'go now' → (...)
tap teks 'accept all' → (...)
maxMissions=0 → berhenti setelah Accept All (mode uji navigasi)
=== DAILY MISSION selesai, gardening lanjut ===
```

- [ ] **Step 7: Verifikasi gardening benar-benar hidup lagi**

Ini menguji kontrak yang paling mahal kalau salah. Setelah log "selesai":

```bash
ADB=/c/Android/platform-tools/adb.exe; D=127.0.0.1:16384
sleep 30
"$ADB" -s $D logcat -d -v time -s GardenService | grep -cE "FAST-PATH|Tombol Gardening|Until Maturity|orb SSD"
```

Harapan: angka **> 0**. Kalau `0`, gardening mati — berarti `finally` tidak mengembalikan loop. Jangan lanjut ke Task 2 sebelum ini hijau.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/roxgarden/DailyMissionRunner.kt app/src/main/java/com/roxgarden/GardenService.kt
git commit -m "Daily Mission: skeleton + navigasi ke Mission Board

Subsistem kedua di samping gardening, dikonstruksi murni dari lambda
(pola WeatherScanner/CityNavigator) sehingga bisa dihapus bersih.

Fase navigasi: Carnival -> Mission Board -> Go Now -> Accept All -> tutup
panel. Tombol teks dicari lewat OCR dengan pencocokan PERSIS, bukan
substring, dan pita pencarian mengecualikan chat yang isinya tak
terkendali.

startDailyMission memegang kontrak gardenPaused: gardenLoop.run() tidak
menjadwalkan ulang dirinya saat paused, jadi finally WAJIB mem-post ulang
loop. Terlewat = gardening mati diam-diam sampai app restart.
markGardenActivity() juga di finally karena run bisa 10-30 menit
sedangkan GARDEN_STUCK_MS cuma 5 menit.

Diuji di MuMu dengan max=0 (navigasi saja, tidak mengorbankan slot misi)."
```

---

### Task 2: Loop misi — scroll tracker, wing, tunggu Submit

Menghasilkan: `--ei max 1` menyelesaikan satu misi penuh dari wing sampai Submit.

**Files:**
- Modify: `app/src/main/java/com/roxgarden/DailyMissionRunner.kt` (tambah metode privat + isi cabang `maxMissions > 0` di `run()`)

**Interfaces:**
- Consumes: dari Task 1 — `tapTextButton(needle: String, minYFrac: Float, maxYFrac: Float): Boolean`, `captureStable(maxWaitMs: Long): Bitmap?`, `captureRetry(): Bitmap?`, `norm(s: String): String`, `abort(reason: String): Boolean`, konstanta `D_NAV`/`D_SETTLE`/`CHAT_TOP_FRAC`, lambda `checkCaptcha`/`isCaptchaActive`/`markActivity`/`swipe`.
- Produces: `run(maxMissions: Int): Boolean` yang lengkap. Task 3 hanya memanggilnya lewat `GardenService.startDailyMission`.

- [ ] **Step 1: Tambah konstanta pita quest tracker dan timeout**

Tambahkan ke `companion object` di `DailyMissionRunner.kt`:

```kotlin
        // Quest tracker di kiri layar. Angka dari frame kanonik 1280×720:
        // entry quest terbaca di x≈60..275, y≈205..430.
        private const val TRACKER_X_MIN = 0.03f
        private const val TRACKER_X_MAX = 0.26f
        private const val TRACKER_Y_MIN = 0.25f
        private const val TRACKER_Y_MAX = 0.66f
        private const val TRACKER_SCROLL_MAX = 6
        private const val D_SCROLL = 900L

        // Menunggu dialog Submit. Misi TALK butuh MANUSIA melanjutkan obrolan NPC, dan itu
        // tidak bisa dibedakan secara teknis dari "bot nyangkut" — jadi timeoutnya longgar.
        // Timeout yang sama juga menyelamatkan kasus karakter MATI saat auto-battle:
        // respawn di kota, quest tak selesai, Submit tak akan pernah muncul.
        private const val SUBMIT_TIMEOUT_MS = 5 * 60_000L
        private const val SUBMIT_POLL_MS = 2000L
```

- [ ] **Step 2: Tulis `scrollTrackerToDaily()`**

Tambahkan sebagai metode privat di `DailyMissionRunner`:

```kotlin
    /**
     * Scroll quest tracker (kiri layar) sampai entry "[Daily]" terlihat, lalu tap.
     * HANYA SEKALI di awal: setelah misi pertama di-tap, wing misi berikutnya muncul
     * sendiri tanpa perlu balik ke tracker.
     */
    private suspend fun scrollTrackerToDaily(): Boolean {
        val ocr = ocrProvider() ?: return false
        for (attempt in 0..TRACKER_SCROLL_MAX) {
            val screen = captureStable() ?: return false
            val w = screen.width.toFloat(); val h = screen.height.toFloat()
            val res = try {
                ocr.processImage(screen, includeAllConfidenceScores = true)
            } catch (t: Throwable) {
                Log.e(TAG, "OCR tracker error: ${t.message}"); screen.recycle(); return false
            }
            screen.recycle()

            for (i in res.texts.indices) {
                if (!norm(res.texts[i]).contains("daily")) continue
                val r = res.boxes[i].boundingRect()
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                if (cx < w * TRACKER_X_MIN || cx > w * TRACKER_X_MAX) continue
                if (cy < h * TRACKER_Y_MIN || cy > h * TRACKER_Y_MAX) continue
                Log.d(TAG, "entry [Daily] ketemu (scroll $attempt) → tap (${cx.toInt()},${cy.toInt()})")
                tap(cx, cy)
                return true
            }

            if (attempt >= TRACKER_SCROLL_MAX) break
            // Belum ketemu → swipe ke atas di dalam pita tracker (daftar bergulir ke bawah).
            val gx = w * (TRACKER_X_MIN + TRACKER_X_MAX) / 2f
            Log.d(TAG, "[Daily] belum terlihat, scroll $attempt")
            swipe(gx, h * TRACKER_Y_MAX, gx, h * TRACKER_Y_MIN, 700L)
            delay(D_SCROLL)
        }
        Log.w(TAG, "[Daily] tidak ketemu setelah $TRACKER_SCROLL_MAX scroll")
        return false
    }
```

- [ ] **Step 3: Tulis `waitForSubmit()`**

```kotlin
    /**
     * Tunggu dialog Submit muncul SENDIRI (auto-battle / auto-interact selesai).
     * Captcha tetap dicek tiap poll — ongkosnya ~30ms lewat template yang sudah ada,
     * jadi tidak diasumsikan captcha mustahil muncul di luar konteks gardening.
     *
     * @return true kalau dialog Submit terdeteksi sebelum timeout.
     */
    private suspend fun waitForSubmit(missionNo: Int): Boolean {
        val ocr = ocrProvider() ?: return false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < SUBMIT_TIMEOUT_MS) {
            delay(SUBMIT_POLL_MS)

            // Captcha sedang ditangani GardenService → tunggu bersih dulu, jangan menyela.
            if (isCaptchaActive()) {
                Log.d(TAG, "captcha aktif, menunggu selesai")
                hud("🔢 captcha, daily mission menunggu")
                continue
            }

            val screen = captureRetry() ?: continue
            // checkCaptcha MENGAMBIL ALIH bitmap kalau true — jangan recycle lagi.
            if (checkCaptcha(screen)) {
                Log.d(TAG, "captcha terdeteksi saat menunggu Submit")
                continue
            }

            val res = try {
                ocr.processImage(screen, includeAllConfidenceScores = true)
            } catch (t: Throwable) {
                Log.e(TAG, "OCR submit error: ${t.message}"); screen.recycle(); continue
            }
            val h = screen.height.toFloat()
            screen.recycle()

            for (i in res.texts.indices) {
                if (norm(res.texts[i]) != "submit") continue
                val r = res.boxes[i].boundingRect()
                val cy = (r.top + r.bottom) / 2f
                if (cy > h * CHAT_TOP_FRAC) continue
                Log.d(TAG, "dialog Submit muncul (misi $missionNo, ${System.currentTimeMillis() - start}ms)")
                return true
            }

            val waited = (System.currentTimeMillis() - start) / 1000
            hud("⏳ misi $missionNo — menunggu ${waited}s")
        }
        Log.w(TAG, "TIMEOUT menunggu Submit misi $missionNo (${SUBMIT_TIMEOUT_MS}ms)")
        return false
    }
```

- [ ] **Step 4: Tulis `runMissionLoop()` dan sambungkan ke `run()`**

Tambahkan metode:

```kotlin
    /**
     * Loop misi. TERMINASI BERBASIS PERILAKU, bukan hitungan 10: kalau tombol "Use" (wing)
     * tidak muncul lagi, berarti tidak ada misi tersisa → SELESAI, bukan error. Ini benar
     * baik untuk 10 misi penuh maupun sisa sebagian (mis. user sudah mengerjakan sebagian
     * manual dan board menunjukkan 1/10).
     *
     * @return jumlah misi yang berhasil di-Submit.
     */
    private suspend fun runMissionLoop(maxMissions: Int): Int {
        if (!scrollTrackerToDaily()) { abort("entry [Daily] tak ketemu di tracker"); return 0 }
        delay(D_SETTLE)

        var done = 0
        while (done < maxMissions) {
            val no = done + 1
            hud("🪽 misi $no: pakai wing")
            if (!tapTextButton("use", 0.30f, 0.75f)) {
                Log.d(TAG, "tombol Use tidak ada → tidak ada misi tersisa, SELESAI ($done misi)")
                return done
            }
            delay(D_SETTLE)

            if (!waitForSubmit(no)) { abort("timeout menunggu Submit misi $no"); return done }

            hud("📨 misi $no: Submit")
            if (!tapTextButton("submit", 0.30f, 0.95f)) { abort("tombol Submit tak bisa di-tap misi $no"); return done }

            done++
            markActivity()
            Log.d(TAG, "misi $no SELESAI (total $done)")
            delay(D_NAV)
        }
        Log.d(TAG, "batas maxMissions=$maxMissions tercapai")
        return done
    }
```

Lalu ganti dua baris placeholder di akhir `run()`:

```kotlin
        // Loop misi diisi di Task 2.
        Log.d(TAG, "loop misi belum diimplementasikan (Task 2)")
        return true
```

menjadi:

```kotlin
        val done = runMissionLoop(maxMissions)
        Log.d(TAG, "=== DAILY MISSION selesai: $done misi ===")
        hud("🎯 daily mission selesai: $done misi")
        return done > 0
```

- [ ] **Step 5: Build**

```powershell
$env:ANDROID_HOME = "C:\Android"; & "C:\Gradle\gradle-8.0\bin\gradle.bat" :app:assembleDebug --no-daemon --console=plain
```

Harapan: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Install + uji satu misi**

```bash
ADB=/c/Android/platform-tools/adb.exe; D=127.0.0.1:16384
"$ADB" -s $D logcat -c
"$ADB" -s $D install -r app/build/outputs/apk/debug/app-debug.apk
```

Minta user menekan START GARDENING, pastikan karakter di kota tanpa panel terbuka, lalu:

```bash
"$ADB" -s $D shell am broadcast -a com.roxgarden.DAILY_MISSION --ei max 1
```

Tunggu sampai satu misi selesai (bisa beberapa menit — auto-travel + auto-battle), lalu:

```bash
"$ADB" -s $D logcat -d -v time -s GardenService:D DailyMission:D | tail -50
```

Harapan: setelah baris navigasi Task 1, muncul

```
entry [Daily] ketemu (scroll N) → tap (...)
tap teks 'use' → (...)
dialog Submit muncul (misi 1, ...ms)
tap teks 'submit' → (...)
misi 1 SELESAI (total 1)
=== DAILY MISSION selesai: 1 misi ===
```

Kalau `[Daily] tidak ketemu setelah 6 scroll`: pita `TRACKER_*` salah untuk layar ini. Ambil frame kanonik dan ukur ulang:

```bash
"$ADB" -s $D exec-out screencap -p > /tmp/tracker.png
```

Buka file itu, baca koordinat piksel entry quest, konversi ke fraksi (bagi 1280 untuk x, 720 untuk y), perbarui konstanta.

- [ ] **Step 7: Uji full run**

```bash
ADB=/c/Android/platform-tools/adb.exe; D=127.0.0.1:16384
"$ADB" -s $D logcat -c
"$ADB" -s $D shell am broadcast -a com.roxgarden.DAILY_MISSION --ei max 10
```

Minta user mengawasi — misi tipe TALK akan berhenti menunggu mereka menyelesaikan obrolan NPC (HUD menampilkan `⏳ misi N — menunggu Ns`). Setelah selesai:

```bash
"$ADB" -s $D logcat -d -v time -s DailyMission:D | grep -E "SELESAI|BATAL|TIMEOUT"
```

Harapan: sederet `misi N SELESAI`, ditutup `=== DAILY MISSION selesai: N misi ===`. Terminasi lewat "tombol Use tidak ada" adalah SUKSES, bukan kegagalan.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/roxgarden/DailyMissionRunner.kt
git commit -m "Daily Mission: loop misi (wing -> tunggu Submit -> Submit)

Scroll quest tracker cari entry [Daily] lalu tap — HANYA sekali di awal,
karena wing misi berikutnya muncul sendiri setelah Submit.

Terminasi BERBASIS PERILAKU, bukan hitungan 10: tombol Use tidak muncul
lagi = tidak ada misi tersisa = SELESAI, bukan error. Board user sudah
menunjukkan (1/10) dari percobaan manual, jadi memaksa 10 iterasi akan
mengerjakan slot yang sudah selesai.

Timeout Submit 5 menit: misi TALK butuh manusia melanjutkan obrolan NPC
dan itu tak bisa dibedakan secara teknis dari bot nyangkut. Timeout yang
sama menyelamatkan kasus karakter mati saat auto-battle.

Captcha dicek tiap poll (~30ms lewat template yang sudah ada) — tidak
diasumsikan mustahil muncul di luar konteks gardening. checkCaptcha
mengambil alih bitmap kalau true, jadi jalur itu tidak recycle lagi."
```

---

### Task 3: Tombol pemicu di MainActivity

Menghasilkan: user bisa menjalankan daily mission tanpa adb (syarat untuk cloud phone).

**Files:**
- Modify: `app/src/main/java/com/roxgarden/MainActivity.kt`

**Interfaces:**
- Consumes: `GardenService.startDailyMission(maxMissions: Int)` dari Task 1, `GardenService.instance`.
- Produces: tidak ada; ini daun.

- [ ] **Step 1: Tambah tombol**

Di `MainActivity.onCreate`, sisipkan setelah blok pembuatan `btnHud` (cari `val btnHud = Button(this).apply { text = "👁 HUD STATUS" }` dan baris `addView` sesudahnya):

```kotlin
        // Pemicu Daily Mission — target utama cloud phone yang TIDAK punya adb, jadi
        // broadcast debug saja tidak cukup. Ditambah programatik seperti tombol lain.
        val btnDaily = Button(this).apply { text = "🎯 DAILY MISSION" }
        (btnHud.parent as ViewGroup).let { p -> p.addView(btnDaily, p.indexOfChild(btnHud) + 1) }
        btnDaily.setOnClickListener {
            val svc = GardenService.instance
            if (svc == null) {
                tvStatus.text = "Status: Aktifkan Accessibility dulu"
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Jalankan Daily Mission?")
                    .setMessage(
                        "Bot akan membuka Carnival → Mission Board, accept semua misi, " +
                        "lalu menjalankannya satu per satu.\n\n" +
                        "GARDENING BERHENTI selama proses ini (bisa 10-30 menit) — cukup " +
                        "lama untuk melewatkan satu window G1/G2.\n\n" +
                        "Misi tipe 'bicara ke NPC' akan berhenti menunggu kamu menyelesaikan " +
                        "obrolannya secara manual."
                    )
                    .setPositiveButton("Jalankan") { _, _ ->
                        svc.startDailyMission(10)
                        tvStatus.text = "Status: daily mission jalan"
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
```

- [ ] **Step 2: Build**

```powershell
$env:ANDROID_HOME = "C:\Android"; & "C:\Gradle\gradle-8.0\bin\gradle.bat" :app:assembleDebug --no-daemon --console=plain
```

Harapan: `BUILD SUCCESSFUL`. Kalau `ViewGroup` tidak dikenal, pastikan `import android.view.ViewGroup` sudah ada (tombol lain memakainya, jadi seharusnya sudah).

- [ ] **Step 3: Install dan uji lewat UI**

```bash
ADB=/c/Android/platform-tools/adb.exe; D=127.0.0.1:16384
"$ADB" -s $D logcat -c
"$ADB" -s $D install -r app/build/outputs/apk/debug/app-debug.apk
```

Minta user: buka RoxGarden → tekan START GARDENING → tekan 🎯 DAILY MISSION → baca dialog peringatan → Jalankan.

```bash
"$ADB" -s $D logcat -d -v time -s DailyMission:D | head -20
```

Harapan: `=== DAILY MISSION mulai (maxMissions=10) ===`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/roxgarden/MainActivity.kt
git commit -m "Daily Mission: tombol pemicu di MainActivity

Target utama bot adalah cloud phone TANPA adb, jadi broadcast debug saja
tidak cukup sebagai pemicu.

Dialog konfirmasi menyebutkan dua hal yang tidak terlihat dari UI dan
akan mengejutkan kalau ditemukan sendiri: gardening BERHENTI 10-30 menit
(cukup untuk melewatkan satu window G1/G2), dan misi tipe TALK akan
berhenti menunggu user menyelesaikan obrolan NPC secara manual."
```

---

## Catatan untuk pelaksana

**Asumsi yang belum diverifikasi di game** (spec mencatatnya sebagai asumsi, bukan fakta). Kalau salah satu meleset saat Task 1/2, itu bukan bug kode — perbaiki nilainya, catat temuannya di commit message:

1. **Jumlah lapis panel setelah "Accept All".** Rencana ini mengandalkan `cityNavigator.closePanels()` yang menekan Back hanya kalau panel TERBUKTI terbuka. Kalau ternyata butuh dua kali, panggil dua kali dengan `delay(D_NAV)` di antaranya.
2. **Wing misi berikutnya muncul otomatis setelah Submit**, tanpa balik ke tracker. Ini dari jawaban user, yang mereka sendiri tandai sebagai dugaan. Kalau ternyata perlu balik ke tracker tiap misi, pindahkan `scrollTrackerToDaily()` ke dalam `while` di `runMissionLoop`.
3. **Timeout Submit 5 menit** dipilih tanpa pengukuran. Kalau ada misi kill yang wajar memakan lebih lama (monster jarang spawn / lokasi jauh), naikkan `SUBMIT_TIMEOUT_MS`.
4. **Pita `TRACKER_*`** diukur dari satu frame kanonik saat karakter di Prontera dengan 3 quest terlihat. Jumlah quest aktif berubah-ubah, jadi pita ini yang paling mungkin butuh penyetelan.

**Jangan** menambahkan deteksi kematian karakter, penyelesaian dialog NPC, atau trigger terjadwal — semuanya eksplisit di luar cakupan v1 di spec.
