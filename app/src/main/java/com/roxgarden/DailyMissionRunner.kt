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
    private val markActivity: () -> Unit,
    // Beri tahu overlay debug region mana yang SEDANG dicari. Default kosong supaya fitur
    // ini tetap bisa dikonstruksi tanpa overlay sama sekali.
    private val markRegion: (FloatArray) -> Unit = {}
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

        // LANGKAH TURUN saat menyisir daftar — sengaja dipisah dari sapuan reset di atas.
        // Keduanya punya tuntutan yang berlawanan: reset boleh (malah sebaiknya) melewati
        // batas, sedangkan langkah turun TIDAK BOLEH melewatkan apa pun.
        //
        // Versi lama memakai sapuan yang sama, 187px dalam 700ms. Dua hal membuatnya
        // melewati entry (dilaporkan user, dan terlihat di log sebagai entry yang muncul
        // di satu scroll lalu HILANG SAMA SEKALI di scroll berikutnya):
        //   1. 187px sudah lebih dari separuh tinggi pita baca (~300px), jadi sisa
        //      tumpangnya tipis.
        //   2. 267px/detik masih di atas ambang FLING Android, jadi daftar meluncur lebih
        //      jauh daripada jarak jari. Inilah sebab utamanya, dan yang membuat gejalanya
        //      terasa acak: jauhnya luncuran tidak tetap.
        // Entry misi yang teksnya PENDEK paling rentan — tingginya kecil, jadi ia bisa
        // jatuh utuh di dalam celah yang terlewat. Gejalanya paling jelas saat 9/10,
        // ketika tinggal satu entry [Daily] di antara belasan [Side].
        //
        // 100px dalam 1400ms (~71px/detik) ada di bawah ambang fling, jadi daftar bergerak
        // persis sejauh jari — dan menyisakan ~200px tumpang tindih, cukup untuk membuat
        // tiap entry muncul di beberapa pembacaan berturut-turut.
        private const val TRACKER_STEP_Y_FROM = 0.52f
        private const val TRACKER_STEP_Y_TO = 0.38f
        private const val TRACKER_STEP_MS = 1400L

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

        // Berapa kali mencoba menemukan dialog BUTTERFLY Wing. Fly Wing tidak lagi lewat
        // sini — jalur cepatnya ada di tapFlyWing() yang berbasis template, bukan OCR.
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
        // Batas MENGANGGUR: 5 menit tanpa satu pun tanda kemajuan. BUKAN batas total misi.
        // Terukur di device: misi 5 ("Kill Roween 0/20" di Rachel Plains) dibatalkan pada
        // menit ke-5 padahal log di detik yang SAMA berbunyi "tracker berubah -> misi masih
        // maju" tiga kali berturut-turut. Jam mati membuang bukti kemajuan yang sudah ada
        // di tangan; misi kill wajar berjalan lebih dari 5 menit tanpa ada yang salah.
        private const val SUBMIT_TIMEOUT_MS = 5 * 60_000L
        // Pagar terakhir supaya satu misi tidak bisa menahan seluruh daily selamanya —
        // mis. karakter mati berulang kali sementara tracker tetap bergoyang.
        private const val SUBMIT_HARD_CAP_MS = 25 * 60_000L
        private const val SUBMIT_POLL_MS = 2000L

        // Bot bisa terjebak diam: misi menunjuk map LAIN (terukur di device — karakter di
        // Sograt Desert sedangkan sasarannya Comodo Cave), tapi tidak ada apa pun di layar
        // yang bisa ditekan karena tawaran Fly Wing hanya muncul saat quest di-tap.
        // Kalau sekian lama tidak ada aksi, tap ulang quest untuk memancing tawaran itu.
        // 90 detik, bukan 45: misi tipe KILL wajar tidak menyentuh UI sama sekali selama
        // puluhan detik (terukur di device: "Kill Megalodon 8/10" sementara layar diam).
        // Ambang 45 detik memicu nudge pada misi yang justru sedang berjalan sehat.
        private const val NUDGE_AFTER_MS = 90_000L
        private const val NUDGE_MAX = 3

        // Centang hijau di ikon Mission Board = SEMUA misi harian sudah selesai.
        // Terukur di frame kanonik pada (633,469)..(669,503). Saat belum selesai, di tempat
        // itu yang tampil hitungan "(x/10)", bukan centang.
        private val MB_DONE_RECT = floatArrayOf(0.478f, 0.628f, 0.549f, 0.727f)
        // Ambang ini SEKARANG diturunkan dari pengukuran, bukan dari perbandingan dengan
        // template lain. Nilai lama 2500 ditebak dari pelajaran ikon tangan/Fly Wing yang
        // match palsu di 5000+ — padahal keduanya melayang di atas adegan 3D yang HIDUP,
        // sedangkan centang ini duduk di panel Carnival yang latarnya DIAM. Nilainya jadi
        // jauh lebih stabil, dan menyalin ambang ketat dari kasus yang tidak sebanding
        // justru memotong tepat di bawah match yang benar (meleset 155 dari 2655).
        //
        // Terukur di device, 7 run, dua kelompok yang terpisah 4,5x:
        //   centang ADA        : 2655  (persis sama 4x berturut-turut, pos 633,470)
        //   centang TIDAK ada  : 11892 .. 11954              (pos 633,468)
        // 6000 kira-kira di tengah secara geometris: 2,26x di atas match asli dan 1,98x di
        // bawah yang palsu, jadi longgar ke kedua arah.
        private const val MB_DONE_THRESHOLD = 6000.0

        // Kotak teks hitungan "(x/10)" pada ikon Mission Board. Sengaja LEBIH LONGGAR dari
        // MB_DONE_RECT: keduanya menempati tempat yang sama (yang satu menggantikan yang
        // lain), tapi titik tengah kotak teks OCR tidak selalu jatuh persis di tengah ikon.
        private val MB_COUNT_RECT = floatArrayOf(0.44f, 0.60f, 0.60f, 0.76f)

        // Tombol X popup Fly Wing, terukur di frame kanonik ~(935,250).
        private const val FLY_WING_CLOSE_X = 0.730f
        private const val FLY_WING_CLOSE_Y = 0.347f

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
        // Kotak SANGAT sempit di sumbu x, dan itu disengaja. Baris toolbar atas ("Good
        // Tidings / GVG League / Companion UP Gacha / Amazing Events") berada di pita y YANG
        // SAMA dengan label tombol interaksi, jadi posisi vertikal tidak bisa memisahkan
        // keduanya. Yang memisahkan: item toolbar berjarak ~77px pada x = 737, 814, 892, 968,
        // sedangkan label tombol interaksi selalu terpusat di x=866 — tepat di celah antara
        // 814 dan 892.
        //
        // Riwayat: 0.62 menangkap 'GV' (dari "GVG League"), 0.65 masih menangkap 'npanion'
        // (dari "Companion UP Gacha") dan membuat bot menekan titik yang sama 12x berturut-turut.
        // Pita pencarian ikon tangan: SEMPIT, hanya +-20px di sekitar posisi sebenarnya
        // (ikon di (847,251)..(885,289) kanonik). Versi longgar sebelumnya (0.62..0.75 x
        // 0.28..0.47) membuat template mendarat di ikon toolbar dan bot MEMBUKA panel
        // "Companion UP Gacha".
        private val INTERACT_HAND_RECT = floatArrayOf(0.646f, 0.322f, 0.712f, 0.432f)

        // Ambang KHUSUS ikon tangan, jauh lebih ketat dari TEMPLATE_THRESHOLD umum.
        // Terukur di device: match PALSU menghasilkan SSD 5000-12000, semuanya lolos ambang
        // umum 14000. Template ini kecil (38x38) dan mayoritas putih, jadi ia mudah cocok
        // dengan sembarang area terang kalau ambangnya longgar.
        private const val INTERACT_HAND_THRESHOLD = 2500.0

        // Pengaman: label interaksi yang sama ditemukan berkali-kali tanpa Submit muncul
        // berarti yang terbaca BUKAN tombol interaksi. Berhenti menekannya alih-alih
        // berputar diam-diam sampai timeout 5 menit.
        private const val INTERACT_MAX_REPEAT = 3

        // Titik tap tombol interaksi, BUKAN titik teksnya. Teks yang ditemukan hanyalah LABEL
        // di bawah tombol (terukur: label "Search" di y=304, lingkaran emasnya di y=275) —
        // menekan label tidak memicu apa pun, terlihat di log sebagai 'Search' ditekan 4×
        // berturut-turut tanpa reaksi. Posisi tombolnya sendiri konsisten di kedua misi.

        // Ikon Fly Wing di popup, terukur di device pada (828,267)..(902,341) kanonik.
        // Pita hanya +-15px dari itu: versi longgar (0.58..0.78) membuat template mendarat
        // di (742,288) dengan SSD 7372 padahal popupnya TIDAK ADA, dan bot mengira ada misi
        // siap terbang.
        private val FLY_WING_RECT = floatArrayOf(0.635f, 0.351f, 0.717f, 0.497f)

        // Ambang khusus Fly Wing. Terukur: match ASLI 257-320, match PALSU 7372. Ambang umum
        // 14000 meloloskan keduanya; 2000 memberi jarak lebar ke kedua sisi.
        private const val FLY_WING_THRESHOLD = 2000.0

        // Poll popup Fly Wing. 60ms hanyalah jeda TAMBAHAN antar poll: satu poll sendiri
        // sudah memakan ~160ms (capture ~130ms + match ~30ms), jadi reaksi sebenarnya
        // ~220ms. Itu pun sudah jauh di bawah jeda buta 1200ms yang digantikannya.
        private const val WING_POLL_MS = 60L
        // Batas menyimpulkan "entry ini bukan misi papan". 1500ms cukup karena popup ini
        // dipicu tap lokal, bukan loading map.
        private const val WING_APPEAR_TIMEOUT_MS = 1500L
        // Batas menunggu popup tertutup sesudah Use ditekan = bukti tap kena sasaran.
        private const val WING_GONE_TIMEOUT_MS = 1200L
    }

    /**
     * Region pencarian fitur ini, untuk digambar overlay debug.
     *
     * Mengembalikan data POLOS (id, label, warna, fraksi) dan TIDAK menyebut tipe milik
     * DebugOverlayManager sama sekali — pemanggil yang menerjemahkannya. Itu menjaga aturan
     * fitur-terpisah: hapus file ini + blok pemanggilnya di GardenService, dan tidak ada
     * sisa apa pun di file overlay.
     */
    fun debugRegions(): List<Triple<String, String, FloatArray>> = listOf(
        Triple("dm_carnival", "Carnival", CARNIVAL_RECT),
        Triple("dm_board", "Mission Board", MISSION_BOARD_RECT),
        Triple("dm_done", "Centang selesai", MB_DONE_RECT),
        Triple("dm_count", "Hitungan x/10", MB_COUNT_RECT),
        Triple("dm_flywing", "Fly Wing", FLY_WING_RECT),
        Triple("dm_hand", "Tombol interaksi", INTERACT_HAND_RECT),
        Triple("dm_tracker", "Pita tracker quest",
            floatArrayOf(TRACKER_X_MIN, TRACKER_Y_MIN, TRACKER_X_MAX, TRACKER_Y_MAX))
    )

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
        screen: Bitmap, tmpl: Bitmap, rectFrac: FloatArray? = null,
        threshold: Double = TEMPLATE_THRESHOLD
    ): Pair<Float, Float>? {
        val sw = screen.width; val sh = screen.height
        val tw = tmpl.width;  val th = tmpl.height
        if (sw - tw < 0 || sh - th < 0) return null
        // Satu kait di sini mencakup SEMUA pencarian template fitur ini: Carnival, Mission
        // Board, centang, Fly Wing, tombol interaksi.
        if (rectFrac != null) markRegion(rectFrac)

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
        return if (avg <= threshold) (bestDx + tw / 2f) to (bestDy + th / 2f) else null
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
        assetPath: String, label: String, rectFrac: FloatArray? = null,
        threshold: Double = TEMPLATE_THRESHOLD
    ): Pair<Float, Float>? {
        val tmpl = getTemplate(assetPath) ?: return null
        val screen = captureStable() ?: return null
        val pos = withContext(Dispatchers.Default) { findTemplate(screen, tmpl, rectFrac, threshold) }
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

    // FLY WING JALUR CEPAT — sasaran: tap misi sampai tap "Use" di bawah 1 detik.
    //
    // Versi lama memakan ~4-7 detik untuk SATU popup: jeda buta 1200ms, lalu OCR SATU LAYAR
    // PENUH dua kali (sekali memastikan popupnya Fly Wing, sekali lagi mencari tombol Use)
    // padahal pencarian pertama SUDAH mengembalikan koordinat tombolnya. Tiga sumber
    // pemborosan itu dibuang di sini:
    //
    //   1. Jeda buta 1200ms -> poll. Bot bereaksi saat popup benar-benar muncul, bukan
    //      selalu menunggu selama kasus terburuk.
    //   2. OCR layar penuh -> template ikon Fly Wing. Bukan sekadar lebih murah: SSD asli
    //      257-320 vs palsu 7372 membuatnya pembeda yang lebih TEGAS daripada teks.
    //   3. captureStable -> SATU capture per poll. captureStable ada supaya template tidak
    //      dicocokkan di frame yang masih beranimasi; di dalam poll, frame beranimasi cuma
    //      berarti "belum" dan 60ms lagi diulang. Animasi menaikkan SSD, jadi salahnya
    //      selalu ke arah aman (meleset, bukan match palsu).
    //
    // Sisa OCR-nya dibayar SEKALI SEUMUR PROSES. Popup ini modal berposisi tetap — buktinya
    // FLY_WING_CLOSE_X/Y sudah lama dipakai sebagai koordinat mati — jadi begitu posisi
    // "Use" terbaca sekali, posisi itu berlaku untuk sisa misi hari itu.
    // Nilai awal = hasil kalibrasi TERUKUR di device (log: "kalibrasi tombol Use Fly Wing:
    // (864,417)"), dinyatakan sebagai fraksi supaya tetap benar di ruang kanonik mana pun.
    // Ini hanya TITIK MULAI, bukan kebenaran mati: kalau tap di situ tidak menutup popup,
    // koordinatnya dibuang dan OCR mengkalibrasi ulang. Gunanya cuma satu — misi PERTAMA
    // ikut lewat jalur cepat, tidak perlu membayar satu OCR layar penuh untuk belajar
    // sesuatu yang sudah kita ukur.
    @Volatile private var flyUseX = 0.675f * 1280f
    @Volatile private var flyUseY = 0.579f * 720f

    /** Satu capture + satu template match, TANPA captureStable. Inti dari poll. */
    private suspend fun flyWingIconNow(): Pair<Float, Float>? {
        val tmpl = getTemplate("map_icons/fly_wing.png") ?: return null
        // Capture gagal = anggap belum ada; poll berikutnya yang mengulang, bukan retry di sini.
        val screen = capture() ?: return null
        val pos = withContext(Dispatchers.Default) {
            findTemplate(screen, tmpl, FLY_WING_RECT, FLY_WING_THRESHOLD)
        }
        screen.recycle()
        return pos
    }

    /**
     * Tunggu popup Fly Wing muncul. timeoutMs = 0 berarti "cek sekali, jangan menunggu" —
     * dipakai di jalur yang dilewati berulang-ulang (poll Submit tiap 2 detik), di mana
     * menunggu di sini akan memperlambat semua pemeriksaan lain.
     */
    private suspend fun awaitFlyWing(timeoutMs: Long = WING_APPEAR_TIMEOUT_MS): Boolean {
        val start = System.currentTimeMillis()
        var polls = 0
        while (true) {
            polls++
            if (flyWingIconNow() != null) {
                if (timeoutMs > 0) Log.d(TAG,
                    "Fly Wing muncul setelah ${System.currentTimeMillis() - start}ms ($polls poll)")
                return true
            }
            if (System.currentTimeMillis() - start >= timeoutMs) return false
            delay(WING_POLL_MS)
        }
    }

    /** Popup sudah tertutup? Inilah bukti tap "Use" benar-benar mengenai sasaran. */
    private suspend fun flyWingGone(): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < WING_GONE_TIMEOUT_MS) {
            if (flyWingIconNow() == null) return true
            delay(WING_POLL_MS)
        }
        return false
    }

    /**
     * Tekan "Use" di popup Fly Wing. false = popupnya memang tidak ada.
     *
     * Koordinat tersimpan TIDAK dipercaya buta: sesudah ditekan, bot memastikan popupnya
     * benar-benar hilang. Kalau masih ada, koordinat dibuang dan OCR mengkalibrasi ulang —
     * jadi koordinat yang salah berongkos satu putaran, bukan misi yang macet diam-diam.
     */
    private suspend fun tapFlyWing(timeoutMs: Long = WING_APPEAR_TIMEOUT_MS): Boolean {
        if (!awaitFlyWing(timeoutMs)) return false

        if (flyUseX > 0f) {
            tap(flyUseX, flyUseY)
            if (flyWingGone()) {
                Log.d(TAG, "tap Use Fly Wing lewat koordinat tersimpan -> popup tertutup")
                return true
            }
            Log.w(TAG, "koordinat Use tersimpan tidak menutup popup -> kalibrasi ulang lewat OCR")
            flyUseX = -1f
        }

        val hit = findWingDialog()
        if (hit == null || hit.kind != WingKind.FLY) {
            Log.w(TAG, "ikon Fly Wing terlihat tapi tombol Use tidak terbaca OCR")
            return false
        }
        flyUseX = hit.x; flyUseY = hit.y
        Log.d(TAG, "kalibrasi tombol Use Fly Wing: (${hit.x.toInt()},${hit.y.toInt()})" +
            " -> misi berikutnya lewat jalur cepat")
        tap(hit.x, hit.y)
        return true
    }

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
    private data class TrackerEntry(val x: Float, val y: Float, val text: String)

    private data class TrackerRead(
        val dailyEntries: List<TrackerEntry>,
        val fingerprint: String,
        // Hanya ANGKA dari pita tracker ("Kill Roween 0/20" -> "020"). Inilah sidik yang
        // dipakai sebagai bukti kemajuan, BUKAN fingerprint di atas.
        //
        // Alasannya terukur di device: OCR membaca teks DIAM yang sama dengan sedikit
        // berbeda tiap frame ("Talk to Mor" / "Talk to Mort" / "Talk to Morts."), sehingga
        // fingerprint huruf berubah walau tidak ada apa pun yang terjadi di layar. Bukti
        // palsu itu menahan misi 25 menit penuh pada run 18:03. Angka jauh lebih tenang,
        // dan justru angka-lah yang benar-benar bergerak saat misi maju (hitungan kill,
        // "Distance to target").
        val digits: String,
        // Seluruh teks yang terbaca di pita tracker, apa adanya. WAJIB ikut di-log: tanpa
        // ini "0 entry [Daily] terlihat" tidak bisa dibedakan antara daftar yang memang
        // tidak berisi Daily, tracker yang tertutup panel lain, dan OCR yang gagal baca.
        // Setiap terobosan diagnosis di fitur ini datang dari melihat bacaan MENTAH-nya.
        val rawTexts: List<String>
    )

    /**
     * Baca pita quest tracker SEKALI. Selain titik entry [Daily], kembalikan juga sidik teks
     * seluruh pita — dipakai untuk mendeteksi daftar sudah mentok tanpa capture tambahan.
     */
    private suspend fun readTracker(): TrackerRead {
        val empty = TrackerRead(emptyList(), "", "", emptyList())
        val ocr = ocrProvider() ?: return empty
        val screen = captureStable() ?: return empty
        val w = screen.width.toFloat(); val h = screen.height.toFloat()
        val res = try {
            ocr.processImage(screen, includeAllConfidenceScores = true)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR tracker error: ${t.message}"); screen.recycle()
            return empty
        }
        screen.recycle()

        markRegion(floatArrayOf(TRACKER_X_MIN, TRACKER_Y_MIN, TRACKER_X_MAX, TRACKER_Y_MAX))
        val out = mutableListOf<TrackerEntry>()
        val raw = mutableListOf<String>()
        val fp = StringBuilder()
        for (i in res.texts.indices) {
            val r = res.boxes[i].boundingRect()
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            if (cx < w * TRACKER_X_MIN || cx > w * TRACKER_X_MAX) continue
            if (cy < h * TRACKER_Y_MIN || cy > h * TRACKER_Y_MAX) continue
            val txt = res.texts[i].trim()
            raw.add(txt)
            fp.append(norm(txt))
            if (norm(txt).contains("daily")) out.add(TrackerEntry(cx, cy, txt))
        }
        return TrackerRead(
            out.sortedBy { it.y }, fp.toString(),
            fp.toString().filter { it.isDigit() }, raw
        )
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
        // 10x, bukan 6: melewati batas atas sama sekali tidak berbahaya (daftar berhenti
        // sendiri di puncak), sedangkan BELUM sampai puncak berarti entry teratas tidak
        // pernah disisir. Ongkos salahnya timpang, jadi dilebihkan.
        repeat(10) {
            swipeFrac(TRACKER_SWIPE_X, TRACKER_SWIPE_Y_TOP, TRACKER_SWIPE_X, TRACKER_SWIPE_Y_BOT, 600L)
            delay(350)
        }
        delay(D_SCROLL)
    }

    // Berapa entry [Daily] yang TERLIHAT di sepanjang penyisiran terakhir — beda dari
    // "berapa yang memunculkan Fly Wing". Dipakai memisahkan dua keadaan yang sebelumnya
    // tercampur: "ada misi jalan tapi tak butuh terbang" vs "tidak ada misi sama sekali".
    @Volatile private var lastScanDailySeen = 0

    private suspend fun selectMissionBoardQuest(): Boolean {
        lastScanDailySeen = 0
        scrollTrackerToTop()
        val tried = mutableSetOf<Int>()   // entry yang sudah dicoba, dikunci per posisi y (10px)
        var prevFp = ""
        var scroll = 0
        while (scroll < TRACKER_SCROLL_HARD_CAP) {
            val t = readTracker()
            lastScanDailySeen += t.dailyEntries.size
            Log.d(TAG, "tracker: ${t.dailyEntries.size} entry [Daily] terlihat (scroll $scroll) " +
                "| baca: ${t.rawTexts.joinToString(" | ")}")
            for ((idx, e) in t.dailyEntries.withIndex()) {
                // Setelah scroll, entry yang sama bisa muncul lagi di posisi berbeda; kunci
                // kasar per-10px sudah cukup untuk tidak mencoba entry yang persis sama dua kali
                // berturut-turut tanpa scroll di antaranya.
                val key = (e.y / 10f).toInt()
                if (!tried.add(key)) continue
                Log.d(TAG, "coba entry [Daily] #${idx + 1} '${e.text}' di (${e.x.toInt()},${e.y.toInt()})")
                tap(e.x, e.y)
                // Jeda buta D_SETTLE + OCR diganti poll template. Entry yang BENAR terjawab
                // dalam ~200ms alih-alih ~3 detik; entry yang SALAH pun lebih cepat
                // ditinggalkan karena tak ada lagi OCR layar penuh yang harus dibayar.
                if (awaitFlyWing()) {
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
            swipeFrac(TRACKER_SWIPE_X, TRACKER_STEP_Y_FROM,
                      TRACKER_SWIPE_X, TRACKER_STEP_Y_TO, TRACKER_STEP_MS)
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
    // Pelacak label interaksi — direset tiap misi, dipakai pengaman anti-tekan-berulang.
    private var interactRepeats = 0

    private suspend fun waitForSubmit(missionNo: Int): Boolean {
        interactRepeats = 0
        val start = System.currentTimeMillis()
        var lastAction = start
        var nudges = 0
        // Sidik teks tracker saat pemeriksaan nudge terakhir. Hitungan kill di tracker
        // ("8/10") berubah saat misi maju, jadi perubahan sidik = BUKTI kemajuan.
        var lastTrackerFp = ""
        // lastAction diperbarui setiap ada BUKTI kemajuan (sidik tracker berubah, Fly Wing
        // ditekan, tombol interaksi ditekan, cutscene dilewati) — jadi hitungannya adalah
        // "sudah berapa lama TIDAK ada kemajuan", bukan "sudah berapa lama misi berjalan".
        while (System.currentTimeMillis() - lastAction < SUBMIT_TIMEOUT_MS &&
               System.currentTimeMillis() - start < SUBMIT_HARD_CAP_MS) {
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

            // FLY WING DI TENGAH MISI (temuan user): satu misi bisa punya beberapa titik
            // tujuan — sesudah bicara dengan NPC, game menawarkan terbang ke titik
            // berikutnya. Versi sebelumnya hanya menekan Fly Wing SEKALI di awal misi,
            // sehingga tawaran berikutnya diabaikan dan karakter terpaksa jalan kaki
            // (atau diam sampai timeout).
            //
            // Cek SEKALI tanpa menunggu (timeout 0): blok ini dilewati tiap 2 detik selama
            // misi berjalan, jadi menunggu di sini memperlambat seluruh poll Submit.
            if (tapFlyWing(0)) {
                Log.d(TAG, "Fly Wing di tengah misi -> terbang ke titik berikutnya")
                hud("terbang ke titik berikutnya")
                lastAction = System.currentTimeMillis()
                delay(D_TRANSPORT)
                continue
            }

            // Misi tipe ambil/interaksi memunculkan tombol bulat di kanan-tengah saat karakter
            // sampai ("Distance to target: 0 m"). LABELNYA BERBEDA-BEDA per misi — terukur di
            // device: "Check" untuk Morning Glory, bukan "Take" seperti dugaan awal. Karena
            // labelnya tidak bisa ditebak, semua kata kerja interaksi yang diketahui didaftar
            // di sini; menambah satu label baru cukup menambah satu entri.
            //
            // minXFrac 0.45 penting: teks quest di tracker kiri bisa memuat kata yang sama
            // ("Check the ...") dan tap ke situ hanya akan memilih ulang quest, bukan interaksi.
            // Tombol interaksi dicari lewat TEMPLATE IKON TANGAN, bukan teks. Alasannya
            // terukur di device: labelnya berganti tiap misi ("Check", "Search", "Gather"),
            // dan yang menutup jalan pendekatan berbasis teks adalah letaknya — label itu
            // MENINDIH teks toolbar "Companion UP Gacha" di pita y yang sama, sehingga OCR
            // di titik itu saling bertabrakan dan bot berhenti mengenalinya.
            //
            // Yang di-template hanya IKON TANGANNYA, bukan lingkaran emasnya: lingkaran itu
            // semi-transparan (teks toolbar tembus di baliknya) sehingga SSD-nya bergoyang,
            // sedangkan ikon tangan opak dan stabil.
            val btn = locateTemplate(
                "map_icons/interact_hand.png", "tombol interaksi",
                INTERACT_HAND_RECT, INTERACT_HAND_THRESHOLD
            )
            if (btn != null) {
                interactRepeats++
                if (interactRepeats > INTERACT_MAX_REPEAT) {
                    if (interactRepeats == INTERACT_MAX_REPEAT + 1) {
                        Log.w(TAG, "tombol interaksi ditekan ${INTERACT_MAX_REPEAT}x tanpa hasil -> berhenti menekannya")
                        hud("interaksi tak bereaksi, berhenti menekan")
                    }
                } else {
                    Log.d(TAG, "tombol interaksi (ke-$interactRepeats) -> tap (${btn.first.toInt()},${btn.second.toInt()})")
                    hud("interaksi objek misi")
                    tap(btn.first, btn.second)
                    lastAction = System.currentTimeMillis()
                    delay(D_SETTLE)
                    continue
                }
            }

            // Tidak ada aksi sekian lama -> kemungkinan misi menunjuk map lain dan tawaran
            // Fly Wing belum muncul. Tap ulang entry [Daily] teratas untuk memancingnya.
            if (nudges < NUDGE_MAX && System.currentTimeMillis() - lastAction > NUDGE_AFTER_MS) {
                val t = readTracker()

                // Diam di layar BUKAN berarti macet. Kalau teks tracker berubah sejak
                // pemeriksaan terakhir, misi sedang maju (hitungan kill bertambah) —
                // jangan diganggu.
                if (t.digits.isNotEmpty() && t.digits != lastTrackerFp) {
                    Log.d(TAG, "angka tracker berubah ('$lastTrackerFp' -> '${t.digits}') -> misi maju")
                    lastTrackerFp = t.digits
                    lastAction = System.currentTimeMillis()
                    continue
                }
                lastTrackerFp = t.digits

                nudges++
                val e = t.dailyEntries.firstOrNull()
                if (e != null) {
                    Log.d(TAG, "diam ${NUDGE_AFTER_MS / 1000}s -> tap ulang quest (nudge $nudges/$NUDGE_MAX)")
                    hud("misi $missionNo: pancing tujuan berikutnya")
                    tap(e.x, e.y)
                    lastAction = System.currentTimeMillis()
                    delay(D_SETTLE)

                    // Tap ini biasanya memunculkan tawaran FLY WING. SENGAJA TIDAK DIPAKAI
                    // (permintaan user): bot sampai di sini justru karena macet, dan
                    // penyebabnya map tujuan BELUM TERBUKA — terbang ke sana tidak menolong
                    // dan hanya menghabiskan item. Perjalanan ke map baru harus manual.
                    //
                    // Bedakan dengan Fly Wing yang muncul SENDIRI di tengah misi: yang itu
                    // tetap dipakai, karena menandakan tujuan berikutnya memang terjangkau.
                    val w = findWingDialog()
                    if (w != null && w.kind == WingKind.FLY) {
                        Log.d(TAG, "Fly Wing muncul akibat nudge -> TIDAK dipakai, ditutup")
                        hud("lewati Fly Wing (map terkunci?)")
                        tap(FLY_WING_CLOSE_X * canonW(), FLY_WING_CLOSE_Y * canonH())
                        delay(D_SETTLE)
                    }
                    continue
                }
                Log.d(TAG, "nudge $nudges: tidak ada entry [Daily] terlihat")
                lastAction = System.currentTimeMillis()
            }

            if (findTextBox("submit", 0.30f, 0.95f) != null) {
                Log.d(TAG, "dialog Submit muncul (misi $missionNo, ${System.currentTimeMillis() - start}ms)")
                return true
            }
            val waited = (System.currentTimeMillis() - start) / 1000
            hud("misi $missionNo - menunggu ${waited}s")
        }
        val idleS = (System.currentTimeMillis() - lastAction) / 1000
        val totalS = (System.currentTimeMillis() - start) / 1000
        Log.w(TAG, "TIMEOUT menunggu Submit misi $missionNo " +
            "(${idleS}s tanpa kemajuan, total ${totalS}s)")
        return false
    }

    /**
     * Loop misi. TERMINASI BERBASIS PERILAKU, bukan hitungan 10: kalau wing tidak muncul lagi,
     * berarti tidak ada misi tersisa -> SELESAI, bukan error. Benar baik untuk 10 misi penuh
     * maupun sisa sebagian (mis. sebagian sudah dikerjakan manual).
     */
    private suspend fun runMissionLoop(maxMissions: Int): Int {
        var done = 0
        // Jalur "sudah di lokasi" hanya dicoba SEKALI per run: kalau ternyata memang tidak ada
        // misi berjalan, mengulanginya berarti membakar 5 menit timeout tiap putaran.
        var submitWaitedOnce = false
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
            if (tapFlyWing(0)) {
                Log.d(TAG, "popup Fly Wing sudah ada -> langsung terbang, lewati pencarian tracker")
                flying = true
            }

            if (!flying) {
                // TERMINASI BERBASIS PERILAKU: tidak ada entry [Daily] yang memunculkan wing =
                // tidak ada misi papan tersisa = SELESAI, bukan error. Benar untuk 10 misi penuh
                // maupun sisa sebagian (mis. sebagian sudah dikerjakan manual).
                hud("misi $no: pilih quest")
                if (!selectMissionBoardQuest()) {
                    // Tidak ada entry yang memunculkan Fly Wing. Itu BELUM tentu berarti misi
                    // habis: wing hanya muncul kalau perlu berpindah map, sedangkan misi yang
                    // karakternya SUDAH di lokasi (terlihat di tracker sebagai "Distance to
                    // target: 19 m", kill 8/10) tidak memunculkannya sama sekali.
                    //
                    // Jadi sebelum menyerah, tunggu Submit sekali untuk quest yang sedang
                    // terpilih. Kalau memang tidak ada misi berjalan, timeout yang menutupnya.
                    // ...TAPI kalau di seluruh penyisiran TIDAK ADA SATU PUN entry [Daily],
                    // tidak ada misi yang bisa sedang berjalan, jadi tidak ada yang perlu
                    // ditunggu. Terukur di device (run 18:03): tracker hanya berisi [Side],
                    // dan menunggu Submit tetap dijalankan sampai pagar 25 menit — 25 menit
                    // untuk misi yang memang tidak ada.
                    if (lastScanDailySeen == 0) {
                        Log.d(TAG, "tidak ada entry [Daily] sama sekali di tracker -> " +
                            "tidak ada misi berjalan, tidak perlu menunggu Submit")
                        return done
                    }
                    if (done == 0 || !submitWaitedOnce) {
                        submitWaitedOnce = true
                        Log.d(TAG, "tidak ada wing -> mungkin misi sudah berjalan di lokasi, tunggu Submit")
                        hud("misi $no: sudah di lokasi?")
                        if (waitForSubmit(no)) {
                            hud("misi $no: Submit")
                            if (tapTextButton("submit", 0.30f, 0.95f)) {
                                done++; markActivity()
                                Log.d(TAG, "misi $no SELESAI via jalur sudah-di-lokasi (total $done)")
                                delay(D_NAV)
                                continue
                            }
                        }
                    }
                    Log.d(TAG, "tidak ada misi Mission Board tersisa -> SELESAI ($done misi)")
                    return done
                }
                hud("misi $no: Fly Wing")
                if (!tapFlyWing()) { abort("Fly Wing tak bisa ditekan, misi $no"); return done }
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
     * Apakah SEMUA misi harian sudah selesai? Dibaca dari centang hijau di ikon Mission
     * Board (permintaan user: bot memeriksa sendiri, bukan lewat tombol manual).
     *
     * Mengembalikan false kalau tidak bisa dipastikan — mis. panel Carnival gagal dibuka.
     * Sengaja begitu: menganggap "belum selesai" hanya berisiko satu run yang berakhir
     * dengan "tidak ada misi tersisa", sedangkan menganggap "sudah selesai" secara keliru
     * berarti misi hari itu dilewati diam-diam.
     */
    private data class BoardStatus(
        val panelOpen: Boolean,     // panel Carnival benar-benar terbuka
        val checkmark: Boolean,     // centang hijau terlihat
        val counter: String?        // hitungan "x/10" kalau masih ada misi tersisa
    )

    /**
     * Baca ketiga sinyal status Mission Board dari SATU frame.
     *
     * Satu frame, bukan tiga pemeriksaan terpisah: kalau panel sempat tertutup di antara
     * pemeriksaan, sinyal-sinyalnya berasal dari keadaan layar yang berbeda dan
     * kesimpulannya jadi campuran yang tidak pernah benar-benar ada.
     */
    private suspend fun readMissionBoardStatus(): BoardStatus {
        val screen = captureStable() ?: return BoardStatus(false, false, null)
        val w = screen.width.toFloat(); val h = screen.height.toFloat()

        val tmpl = getTemplate("map_icons/mission_board_done.png")
        val check = if (tmpl == null) null else withContext(Dispatchers.Default) {
            findTemplate(screen, tmpl, MB_DONE_RECT, MB_DONE_THRESHOLD)
        }

        var panelOpen = false
        var counter: String? = null
        val ocr = ocrProvider()
        if (ocr != null) {
            val res = try {
                ocr.processImage(screen, includeAllConfidenceScores = true)
            } catch (t: Throwable) {
                Log.e(TAG, "OCR status board error: ${t.message}"); null
            }
            if (res != null) for (i in res.texts.indices) {
                val raw = res.texts[i].trim()
                if (norm(raw).contains("missionboard")) panelOpen = true
                // Hitungan hanya diakui kalau letaknya di ikon Mission Board. Tanpa batas
                // posisi, "x/10" milik misi lain di panel yang sama ikut terhitung.
                if (!raw.contains("/")) continue
                val r = res.boxes[i].boundingRect()
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                markRegion(MB_COUNT_RECT)
                if (cx < w * MB_COUNT_RECT[0] || cx > w * MB_COUNT_RECT[2]) continue
                if (cy < h * MB_COUNT_RECT[1] || cy > h * MB_COUNT_RECT[3]) continue
                counter = raw
            }
        }
        screen.recycle()
        return BoardStatus(panelOpen, check != null, counter)
    }

    /**
     * SUDAH selesai hanya kalau TIGA sinyal sepakat: panel terbuka, centang ada, dan tidak
     * ada lagi hitungan "x/10". Semua ketidakjelasan jatuh ke "BELUM".
     *
     * Kenapa seketat itu: kedua arah kesalahan TIDAK setara. Salah bilang "belum" hanya
     * berongkos satu putaran yang langsung berakhir sendiri (loop misi berhenti begitu tak
     * ada entry [Daily] yang memunculkan Fly Wing). Salah bilang "sudah" membuang misi
     * sehari penuh TANPA JEJAK, dan baru ketahuan besok.
     *
     * Syarat panelOpen adalah perbaikan atas kegagalan nyata yang dilaporkan user: bot
     * kadang menyimpulkan "sudah selesai" padahal belum. Tanpa syarat itu, centang dicari
     * di kotak layar tetap — dan kalau panelnya ternyata TIDAK terbuka, kotak itu berisi
     * dunia 3D yang hidup. Template centang hijau di atas rumput hijau adalah persis jenis
     * match palsu yang sudah dua kali menggigit fitur ini (lihat catatan ambang pada
     * INTERACT_HAND_THRESHOLD dan FLY_WING_THRESHOLD).
     */
    suspend fun dailyMissionsDone(): Boolean {
        hud("🎪 cek status daily")
        if (!tapTemplate("map_icons/carnival.png", "Carnival", CARNIVAL_RECT, 3)) {
            Log.w(TAG, "cek status: ikon Carnival tak ketemu -> anggap BELUM selesai")
            return false
        }
        delay(D_NAV)

        var st = readMissionBoardStatus()
        if (!st.panelOpen) {
            // Panel bisa saja masih beranimasi. Mencoba ulang sekali lebih jujur daripada
            // menaikkan D_NAV: jeda tetap hanya tebakan, percobaan ulang menyesuaikan diri.
            Log.d(TAG, "panel Carnival belum terbaca, coba sekali lagi")
            delay(D_NAV)
            st = readMissionBoardStatus()
        }

        // CENTANG = SELESAI, titik (keterangan user). Centang muncul justru MENGGANTIKAN
        // hitungan x/10 begitu semua misi beres, jadi keduanya tidak pernah ada bersamaan.
        //
        // Hitungan tetap dibaca dan di-log — ia keterangan diagnosis terbaik saat verdict
        // salah — tapi TIDAK lagi jadi syarat. Sebagai syarat, satu teks ber-"/" yang salah
        // terbaca OCR akan memaksa satu putaran daily sia-sia di SETIAP START, dan
        // pemeriksaan ini berjalan tiap kali bot dinyalakan.
        //
        // panelOpen tetap WAJIB. Itulah yang memperbaiki kegagalan nyata sebelumnya: tanpa
        // bukti panel terbuka, centang hijau dicari di dunia 3D yang hidup, dan rumput
        // hijau lolos ambang.
        val done = st.panelOpen && st.checkmark
        Log.d(TAG, "cek status daily: panel=${st.panelOpen} centang=${st.checkmark} " +
            "hitungan='${st.counter ?: "-"}' -> ${if (done) "SUDAH selesai" else "BELUM selesai"}")
        if (!st.panelOpen) Log.w(TAG, "panel Carnival tidak terbukti terbuka -> verdict TIDAK dipercaya")
        // Keduanya muncul bersamaan itu MUSTAHIL menurut cara game menggambarnya, jadi kalau
        // terjadi, salah satu pembacaannya bohong — hampir pasti centangnya match palsu.
        if (st.checkmark && st.counter != null)
            Log.w(TAG, "JANGGAL: centang DAN hitungan '${st.counter}' terbaca bersamaan -> " +
                "curigai match palsu template centang (turunkan MB_DONE_THRESHOLD)")
        closePanels()
        delay(D_NAV)
        return done
    }

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
