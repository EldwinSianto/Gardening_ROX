# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Apa ini

Bot gardening Ragnarok X (`com.play.rosea`) berbentuk **AccessibilityService** Android. Bot memotret layar game, membaca statusnya via ONNX OCR + template matching, lalu men-tap lewat gesture accessibility. Menyelesaikan captcha matematika sendiri.

Komentar kode ditulis dalam bahasa Indonesia — ikuti gaya itu. Komentar di sini padat menjelaskan **kenapa** sebuah nilai/urutan dipilih (biasanya hasil debugging live di device). Jangan hapus komentar semacam itu saat refactor; itu satu-satunya catatan hasil eksperimen.

## Build

Tidak ada `local.properties`; SDK diambil dari env `ANDROID_HOME=C:\Android`.

```powershell
& "C:\Gradle\gradle-8.0\bin\gradle.bat" :app:assembleDebug --no-daemon
```

APK keluar di `app/build/outputs/apk/debug/app-debug.apk`.

- `settings.gradle` hanya `include ':app'` — direktori `OcrLibrary/` **tidak ikut ter-build**. Itu kode referensi RapidOCR (pakai OpenCV + JNI), bukan dependensi. Implementasi OCR yang benar-benar dipakai ada di `app/src/main/java/com/roxgarden/ocr/`, murni Kotlin + onnxruntime-android.
- Tidak ada test suite. Verifikasi dilakukan langsung di device (lihat "Testing di device").

## Testing di device

Target utama adalah **cloud phone (Redfinger) tanpa akses adb**, jadi ada dua alat debug on-device di `MainActivity`:

- **🔍 DIAGNOSA** — `GardenService.runDiagnostics()`, menampilkan state service dalam dialog.
- **👁 HUD STATUS** — panel live langkah bot + penanda titik tap.

Kalau adb tersedia (MuMu lokal), ada broadcast receiver khusus test yang di-register di `onServiceConnected`:

```bash
adb shell am broadcast -a com.roxgarden.SCAN_WEATHER   # paksa scan cuaca + navigasi
adb shell am broadcast -a com.roxgarden.NAV_BOOST --es plant cactus --es city Morroc
adb shell am broadcast -a com.roxgarden.PANEL_TEST     # tes buka panel Life
adb shell am broadcast -a com.roxgarden.STUCK_TEST     # picu watchdog tanpa nunggu 5 menit
```

## Arsitektur

### GardenService — hub tunggal

[GardenService.kt](app/src/main/java/com/roxgarden/GardenService.kt) (~2700 baris) memegang capture, OCR, gesture, state machine, dan captcha. Semua komponen lain menerima kemampuan itu **lewat lambda**, tidak pernah mereferensikan service balik.

Loop utamanya adalah `Runnable` yang me-`postDelayed` dirinya sendiri di main Handler — bukan coroutine loop. Kerja berat (capture + OCR) dilempar ke `scope` (Dispatchers.IO), hasilnya di-`handler.post` balik. Tiga flag menjaga agar tidak ada dua alur jalan bersamaan: `ocrInProgress`, `isHandlingCaptcha`, `gardenPaused`.

### Ruang koordinat kanonik 720p — invarian paling penting

Semua template dicrop di 1280×720 dan dicocokkan piksel 1:1 (SSD/SAD). Di resolusi lain match **gagal total**, bukan meleset sedikit. Karena itu:

- `toCanonical()` men-scale tiap capture ke tinggi 720; `screenWidth/screenHeight` = dimensi kanonik.
- Semua analisis (template, OCR, region, captcha) bekerja di ruang kanonik.
- `performTap`/`performSwipe` memetakan balik ke resolusi asli lewat `toRealX/toRealY`.

Kode baru yang menyentuh koordinat harus tetap di ruang kanonik sampai titik tap.

### Capture berjenjang

`captureScreen()` mencoba beberapa jalur karena tiap host emulator beda perilaku: root `screencap -d <physId>` (MuMu multi-tab, display privat) → `takeScreenshot(displayId)` → MediaProjection. `hasRoot()` dicache sekali; tanpa itu device non-root memanggil `su` tiap iterasi.

`refreshGameDisplay()` mencari display yang menampilkan `GAME_PACKAGE` — tab MuMu adalah display terpisah, jadi id-nya tidak boleh diasumsikan 0.

### Disiplin bitmap

Tiap iterasi membuat bitmap ~3.7MB. Pernah menyebabkan OOM/lowmemorykill, jadi kepemilikan bitmap eksplisit: cabang yang butuh piksel (`CAPTCHA_PRESENT`, `GARDENING_READY`) memegang `img`, sisanya `recycle()` segera setelah dimensi disalin. Saat menambah cabang status baru, tentukan pemiliknya.

### Kalibrasi region

[DebugOverlayManager.kt](app/src/main/java/com/roxgarden/DebugOverlayManager.kt) menyimpan region sebagai **fraksi layar** (`x1f/y1f/x2f/y2f`), bisa digeser lewat overlay dan auto-save ke SharedPreferences `roxgarden_regions`. Default di kode adalah hasil kalibrasi live — makanya angkanya panjang. Kode membaca region via `regionPx(id, bitmap)`; kalau region belum dikalibrasi, sebagian jalur fallback ke konstanta (`GARDENING_BTN_X/Y`).

### Fitur terpisah (sengaja bisa dibuang)

[WeatherScanner.kt](app/src/main/java/com/roxgarden/WeatherScanner.kt) (baca forecast cuaca per kota lewat world map) dan [CityNavigator.kt](app/src/main/java/com/roxgarden/CityNavigator.kt) (pindah kota lewat Life → Gardening → Encyclopedia) dikonstruksi murni dari lambda. Blok pemanggilnya di GardenService ditandai komentar `WEATHER SCAN` / `CITY NAV`. Pertahankan properti ini — hapus file + blok bertanda = fitur hilang bersih.

[GardenPlanner.kt](app/src/main/java/com/roxgarden/GardenPlanner.kt) memilih pasangan (tanaman, kota) pertama yang cuacanya **sedang** boost, menurut prioritas user. Pakai `WeatherScanner.currentSlots`, bukan slot pertama hasil scan — slot basi setelah 20 menit.

### OCR

[ModelManager.kt](app/src/main/java/com/roxgarden/ocr/ModelManager.kt) memilih model berjenjang:

1. `assets/ocr_english/` (det+rec+cls+dict, bundled) — dipakai kalau keempat file ada.
2. PP-OCRv5 di-download dari `models.ente.io` dengan verifikasi SHA-256.
3. `assets/ocr_captcha/` — rec model **khusus captcha** hasil training sendiri (digit + `+ - *`); det & cls di-reuse dari model utama.

Pembagian peran: **OCR membaca state, template matching mengkonfirmasi**. Ada fast-path yang mengganti OCR (~1-2s) dengan template orb (~30ms) saat tombol hampir pasti siap; `fastPathStreak` membatasi rantai tanpa-OCR supaya salah tebak selalu terkoreksi.

### HUD self-capture

Bot memotret layarnya sendiri, jadi `maskHud()` menghitamkan area panel HUD di tiap frame. Tanpa itu teks HUD ("navigasi Morroc") bisa match fuzzy dan bot men-tap HUD-nya sendiri. Batas mask (`BotHud.MASK_X/MASK_Y`) **harus** sinkron dengan posisi panel.

### Watchdog & mode waktu

- Karakter bisa nyangkut di tengah map dengan kota yang benar — navigasi biasa mengira "sudah sampai". `GARDEN_STUCK_MS` (5 menit tanpa aktivitas kebun) memicu `recoverStuckNavigation()`.
- Mode G1/G2 (`shouldGardenNow`, `isG1Window`): G1 = jam genap menit :01–:39. Pergantian di menit :40 — batas ini pernah salah (`1..40`) dan telat satu menit.

## Menambah tanaman baru

1. Crop thumbnail encyclopedia → `app/src/main/assets/plants/<id>.png`
2. Kalau kotanya baru: crop ikon world-map → `app/src/main/assets/map_icons/<kota>.png`, daftarkan di `WeatherScanner.CITY_ASSETS` **dan** `PlantCatalog.CITY_ORDER` (urut utara→selatan agar sweep pendek).
3. Tambah satu `PlantDef` di `PlantCatalog.ALL`.

Nama kota di `PlantDef.cities` harus sama persis dengan key `CITY_ASSETS`.

## Training model captcha

`captcha_train/` berisi pipeline PaddleOCR (fine-tune `en_PP-OCRv4_rec` → SVTR_LCNet, 13 karakter, val 100%):

- `prep_dataset.py` — split train/val stratified per operator, generate `rec_gt_*.txt` + `captcha_dict.txt`
- `captcha_rec.yml` — config training (path absolut `c:/RoxGarden/...`; dataset mentah di `c:/captcha_data/16384/`)
- `eval_infer.py` — hitung akurasi dari `infer.log`
- `verify_onnx.py` — verifikasi ONNX hasil export dengan CTC decode manual

Hasilnya (`rec.onnx` + `dict.txt`) di-copy ke `app/src/main/assets/ocr_captcha/` dan versinya dinaikkan lewat `CAPTCHA_VERSION` di ModelManager agar cache di device ter-refresh.

## Catatan encoding

Sebagian komentar di `GardenService.kt` mengandung mojibake dari penyimpanan encoding campuran (`â"€â"€` untuk garis pemisah, `â€"` untuk em-dash). Jangan perbaiki massal — hasilnya diff besar tanpa nilai. Tulis file baru sebagai UTF-8 dan cukup benahi baris yang memang sedang kamu sentuh.
