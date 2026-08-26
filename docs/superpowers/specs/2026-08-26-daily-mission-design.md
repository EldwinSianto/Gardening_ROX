# Desain: Otomasi Daily Mission (Mission Board)

Status: disetujui user 2026-08-26, belum diimplementasikan.

## Tujuan

Menjalankan 10 misi harian Mission Board (Carnival) secara otomatis untuk mendapat Odin. Sebelumnya bot hanya bisa gardening; ini subsistem kedua.

## Kenapa ini layak dikerjakan tanpa combat AI

Bot TIDAK punya kemampuan combat apa pun — tidak ada deteksi target, rotasi skill, atau pathing ke monster. Fitur ini tetap realistis karena game menyediakan auto-travel + auto-battle bawaan: item "wing" membawa karakter ke lokasi misi DAN menjalankan pertarungannya sendiri.

Konsekuensi penting untuk desain: **bot tidak perlu tahu jenis misi sama sekali.** Ada tiga tipe (kill / gather / talk, terlihat dari ikon di kartu misi), tapi ketiganya diselesaikan game secara otomatis sampai dialog Submit muncul. Loop-nya identik untuk ketiganya.

Pengecualian yang dikonfirmasi user: misi tipe TALK auto-jalan ke NPC dan auto-membuka percakapan, tapi **isi obrolannya harus dilanjutkan manusia**. Karena trigger v1 manual (user mengawasi), menunggu manusia di tengah loop adalah perilaku yang diterima, bukan bug.

## Alur yang diamati (dikonfirmasi user lewat screenshot + percobaan manual)

1. Tap ikon Carnival (toolbar kanan-atas)
2. Tap ikon Mission Board di layar Carnival
3. Tap "Go Now" di popup info → layar County Quest (10 kartu misi)
4. Tap "Accept All" → 10 misi masuk quest tracker
5. Tutup panel
6. Scroll quest tracker (kiri) sampai bagian `[Daily]` terlihat
7. Tap entry misi Daily pertama → item wing muncul di kanan
8. Ulangi: tap "Use" pada wing → tunggu dialog Submit muncul SENDIRI → tap Submit → wing misi berikutnya muncul otomatis
9. Tidak ada wing baru → selesai

Langkah 6-7 hanya sekali di awal. Reward (termasuk Odin) masuk lewat tiap Submit; tidak ada langkah klaim bonus terpisah setelah misi ke-10.

Quest refresh jam 05:00 (UTC+7), terbaca di layar County Quest.

## Arsitektur

`DailyMissionRunner.kt` (file baru), dikonstruksi murni dari lambda — pola yang sama dengan `WeatherScanner` dan `CityNavigator`, sesuai aturan di CLAUDE.md: komponen menerima kemampuan lewat lambda dan TIDAK PERNAH mereferensikan service balik. Hapus file + blok pemanggil = fitur hilang bersih.

```kotlin
class DailyMissionRunner(
    private val capture: () -> Bitmap?,
    private val tap: (Float, Float) -> Unit,
    private val swipe: (Float, Float, Float, Float, Long) -> Unit,
    private val ocrProvider: () -> OcrProcessor?,
    private val loadAsset: (String) -> Bitmap?,
    private val hud: (String) -> Unit,
    private val checkCaptcha: (Bitmap) -> Boolean,   // interceptCaptcha; ambil alih bitmap kalau true
    private val isCaptchaActive: () -> Boolean,      // tunggu sampai false sebelum lanjut
    private val closePanels: suspend () -> Unit,     // cityNavigator.closePanels
    private val markActivity: () -> Unit             // reset watchdog gardening
)

suspend fun run(maxMissions: Int = 10): Boolean
```

Alasan menolak alternatif:

- **Menambal langsung ke `gardenLoop`/`CityNavigator`**: `GardenService` sudah ~2700 baris, dan menempelkan fitur baru yang belum matang ke loop yang sudah presisi disetel membuat bug baru terlihat seperti regresi gardening.
- **AccessibilityService terpisah**: harus membangun ulang capture/OCR dari nol dan melanggar prinsip "hub tunggal" tanpa manfaat.

Versi pertama tidak memakai `regionPx`/kalibrasi overlay. Tombol-tombolnya berposisi tetap; kalau ternyata perlu digeser, itu penyesuaian kecil belakangan.

## Terminasi berbasis perilaku, BUKAN hitungan 10

Jumlah misi tidak boleh di-hardcode 10. Saat desain ini dibuat, Mission Board user sudah menunjukkan `(1/10)` dari percobaan manual — kalau bot memaksa 10 iterasi, ia akan mencoba mengerjakan slot yang sudah selesai.

Aturannya: setelah Submit, kalau tidak ada wing baru muncul dalam waktu wajar, run dianggap SELESAI (sukses), bukan error. Ini benar untuk 10 penuh maupun sisa sebagian. `maxMissions` hanya batas atas pengaman, juga dipakai untuk pengetesan bertahap.

## Integrasi ke GardenService

Instance dibuat sekali, sama seperti `weatherScanner`/`cityNavigator`.

### Kontrak wajib: mengembalikan gardenLoop

`gardenLoop.run()` TIDAK menjadwalkan ulang dirinya saat `gardenPaused` true — ia `return` begitu saja (GardenService.kt, awal blok `run()`). Jadi pemanggil WAJIB:

```kotlin
gardenPaused = true
handler.removeCallbacks(gardenLoop)
scope.launch {
    try { dailyMissionRunner.run(max) }
    finally {
        markGardenActivity()          // lihat "Watchdog" di bawah
        gardenPaused = false
        handler.post { handler.postDelayed(gardenLoop, 6000) }
    }
}
```

Kalau `finally` ini terlewat, **gardening mati diam-diam sampai app di-restart** — tanpa error, tanpa gejala di HUD. Ini mode kegagalan paling mahal di fitur ini.

### Watchdog

Selama run, `markGardenActivity()` tidak pernah terpanggil, sedangkan `GARDEN_STUCK_MS` hanya 5 menit dan run bisa 10-30 menit. Tanpa reset, begitu gardening resume `gardenStuckTooLong()` langsung menyala dan memicu navigasi ulang yang tidak perlu. Karena itu `markGardenActivity()` dipanggil di `finally`.

### Captcha di tengah misi

Belum ada bukti captcha muncul di luar konteks gardening, tapi ongkos memeriksanya murah (~30ms, template yang sudah ada), jadi dicek di tiap titik poll — bukan diasumsikan tidak terjadi.

Interaksi halus yang harus dipahami sebelum mengubah kode ini: `handleCaptcha` memanggil `resumeAfterDelay` yang mem-post `gardenLoop`. Karena `gardenPaused` masih true (milik runner), loop itu langsung `return` tanpa menjadwal ulang — jadi gardening tetap diam selama misi dan runner tinggal menunggu `isCaptchaActive()` bersih lalu lanjut. Ini AMAN, tapi aman secara kebetulan; jangan diandalkan tanpa membaca ulang kalau salah satu sisi berubah.

### Biaya yang disadari

Run memblokir gardening 10-30 menit, cukup untuk melewatkan satu window G1/G2. Karena trigger v1 manual, waktunya dipilih user. HUD harus membuat ini terlihat.

## Trigger (v1: manual)

User memilih manual dulu; otomatis terjadwal menyusul setelah terbukti.

1. Tombol "Jalankan Daily Mission" di `MainActivity`, mengikuti pola tombol DIAGNOSA / HUD STATUS yang sudah ada.
2. Broadcast receiver debug, didaftarkan di `onServiceConnected` bersama `SCAN_WEATHER` / `NAV_BOOST` / `PANEL_TEST` / `STUCK_TEST`:

   ```bash
   adb shell am broadcast -a com.roxgarden.DAILY_MISSION --ei max 1
   ```

   Murni alat iterasi (MuMu punya adb, cloud phone tidak) supaya tiap percobaan tidak perlu install ulang + buka app + tap tombol.

## Penanganan error

Tiap langkah mengembalikan Boolean. Gagal = batal bersih, BUKAN retry selamanya (pola `tapTemplate()` di `CityNavigator`). Saat batal: `closePanels()` supaya tidak meninggalkan panel terbuka, lalu HUD + notifikasi.

### Timeout menunggu Submit

Misi TALK butuh manusia, dan secara teknis itu tidak bisa dibedakan dari "bot nyangkut". Timeout per misi 5 menit, dengan HUD menampilkan nomor misi dan lama menunggu.

Timeout ini juga yang menyelamatkan kasus karakter MATI saat auto-battle: karakter respawn di kota, quest tidak selesai, dialog Submit tidak akan pernah muncul. Deteksi kematian spesifik sengaja TIDAK dibuat untuk v1 — butuh template baru, dan user sedang mengawasi langsung.

## Aset template

Di-crop dari frame kanonik device (1280×720) lewat adb, BUKAN dari screenshot chat. CLAUDE.md melarang crop dari chat: skala berbeda → SSD tidak akan match. Jebakan ini sudah pernah kena di template captcha.

Sudah ada di `app/src/main/assets/map_icons/`:

- `carnival.png` (74×51) — ikon toolbar. Label teks "Carnival" di bawahnya sengaja dibuang dari crop.
- `mission_board.png` (74×72) — ikon di layar Carnival. Teks progress `(1/10)` DIBUANG dari crop karena dinamis (berubah tiap misi selesai); badge "Activity 40" ikut di-crop karena dekorasi tetap.

Belum dibuat, akan di-capture live saat implementasi: "Go Now", "Accept All", item wing, penanda dialog Submit. Sebagian mungkin cukup lewat OCR teks tanpa template.

Risiko yang diketahui: ikon Carnival bisa memunculkan badge notifikasi merah saat ada event baru. Di frame acuan tidak ada. Kalau SSD mulai gagal, re-crop dari frame baru.

## Rencana pengetesan

Tidak ada test suite (CLAUDE.md: verifikasi langsung di device). Bertahap, supaya kegagalan bisa diatribusikan ke satu langkah:

1. `max = 0` — navigasi saja: Carnival → Mission Board → Go Now → Accept All → tutup panel, lalu berhenti. Menguji 4 template + penutupan panel TANPA mengorbankan slot misi.
2. `max = 1` — tambah scroll tracker + wing + Submit untuk satu misi.
3. `max = 10` — full run, diawasi user.

Verifikasi tiap tahap: HUD di device, plus `adb logcat -s GardenService` di MuMu.

## Di luar cakupan v1

- Trigger otomatis terjadwal (menyusul setelah terbukti stabil).
- Deteksi kematian karakter saat auto-battle.
- Menyelesaikan dialog NPC misi tipe TALK.
- Aktivitas Carnival lain (COC Mission, Guild Orders, Daily Instance, dll).
