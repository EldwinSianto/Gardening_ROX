package com.roxgarden

/**
 * Pencatat durasi tiap langkah bot — sebelum ini TIDAK ADA satu pun pengukuran waktu di kode,
 * jadi semua konstanta delay adalah hasil tebakan + pengamatan mata. Target utama bot adalah
 * cloud phone TANPA adb, jadi angkanya harus bisa dibaca dari layar (HUD + dialog DIAGNOSA),
 * bukan dari logcat.
 *
 * Murni Kotlin, tidak mereferensikan GardenService — ikut disiplin "fitur terpisah" repo ini:
 * hapus file + pemanggilnya = instrumentasi hilang bersih tanpa menyentuh logika bot.
 *
 * EMA (bukan rata-rata penuh) karena yang kita butuhkan adalah biaya SEKARANG: capture melambat
 * saat host emulator sibuk, dan kompensasi latensi harus mengikuti kondisi terakhir, bukan
 * rata-rata sejak bot start berjam-jam lalu.
 */
class PerfStats {

    companion object {
        // Label langkah — dipakai juga sebagai urutan tampil di HUD/DIAGNOSA.
        const val CAP   = "cap"     // captureScreen penuh (root screencap + skala kanonik + mask)
        const val OCR   = "ocr"     // analyzeScreen (OCR status)
        const val ORB   = "orb"     // template match orb Gardening
        const val BAND  = "band"    // readCenterBand: OCR pita tengah (cek toast) — tiap panen
        const val CAST  = "cast"    // durasi cast gather: tap → tombol muncul lagi
        const val CYCLE = "cycle"   // tap panen → tap panen berikutnya

        private const val ALPHA = 0.25   // bobot sampel baru; ~4 sampel terakhir yang dominan
        private val ORDER = listOf(CAP, OCR, BAND, ORB, CAST, CYCLE)
    }

    private class Stat {
        var ema = 0.0
        var n = 0L
        var min = Long.MAX_VALUE
        var max = 0L
        var last = 0L
    }

    // Ditulis dari Dispatchers.IO (capture/OCR) DAN main handler (fase siklus) → wajib synchronized.
    private val stats = LinkedHashMap<String, Stat>()

    fun record(label: String, ms: Long) {
        if (ms < 0) return
        synchronized(stats) {
            val s = stats.getOrPut(label) { Stat() }
            s.ema = if (s.n == 0L) ms.toDouble() else s.ema * (1 - ALPHA) + ms * ALPHA
            s.n++
            if (ms < s.min) s.min = ms
            if (ms > s.max) s.max = ms
            s.last = ms
        }
    }

    /** Ukur blok yang mengembalikan nilai. Durasi dicatat walau blok melempar. */
    fun <T> time(label: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            record(label, System.currentTimeMillis() - t0)
        }
    }

    /** EMA dalam ms, 0 kalau belum ada sampel — pemanggil pakai 0 sebagai "belum tahu". */
    fun ema(label: String): Long = synchronized(stats) {
        stats[label]?.let { Math.round(it.ema) } ?: 0L
    }

    fun samples(label: String): Long = synchronized(stats) { stats[label]?.n ?: 0L }

    /**
     * Sampel TERKECIL yang pernah tercatat, 0 kalau belum ada.
     *
     * Dipakai poll adaptif untuk durasi cast, dan sengaja BUKAN ema(): durasi cast yang tercatat
     * selalu berisi overshoot deteksi (kita baru tahu tombol muncul saat poll berikutnya). Kalau
     * jadwal tidur buta dihitung dari EMA, overshoot itu masuk balik ke pengukuran berikutnya dan
     * angkanya merangkak naik tiap siklus — bot makin lama tidur melewati batas aslinya. Minimum
     * adalah batas bawah sejati, jadi bebas dari umpan balik itu.
     */
    fun min(label: String): Long = synchronized(stats) {
        stats[label]?.takeIf { it.n > 0 }?.min ?: 0L
    }

    fun reset() = synchronized(stats) { stats.clear() }

    /**
     * Satu baris padat untuk HUD (panel cuma selebar 34% layar, font monospace 18px) —
     * hanya langkah yang sudah punya sampel yang ditampilkan.
     */
    fun hudLine(): String {
        val snap = synchronized(stats) { stats.mapValues { (_, s) -> Math.round(s.ema) } }
        if (snap.isEmpty()) return "…"
        return ORDER.filter { snap.containsKey(it) }
            .joinToString(" ") { "$it${compact(snap[it]!!)}" }
    }

    /** Rincian penuh untuk dialog DIAGNOSA — ada ruang, jadi tampilkan n/min/max juga. */
    fun report(): String {
        val snap = synchronized(stats) {
            stats.map { (k, s) -> Triple(k, Math.round(s.ema), listOf(s.n, s.min, s.max, s.last)) }
        }
        if (snap.isEmpty()) return "  (belum ada sampel — bot baru start?)"
        val sb = StringBuilder()
        for (label in ORDER) {
            val row = snap.firstOrNull { it.first == label } ?: continue
            val (n, min, max, last) = row.third
            sb.appendLine("  ${label.padEnd(6)} ema=${row.second}ms  n=$n  min=${min}ms  max=${max}ms  akhir=${last}ms")
        }
        // Label tak terduga (mis. tag template lain) tetap ditampilkan, jangan sampai hilang diam-diam
        for (row in snap) if (row.first !in ORDER) {
            val (n, min, max, last) = row.third
            sb.appendLine("  ${row.first.padEnd(6)} ema=${row.second}ms  n=$n  min=${min}ms  max=${max}ms  akhir=${last}ms")
        }
        return sb.toString().trimEnd()
    }

    // 480ms → "480", 1640ms → "1.6s" — HUD harus muat di satu baris pendek
    private fun compact(ms: Long): String =
        if (ms < 1000) "$ms" else "${"%.1f".format(ms / 1000.0)}s"
}
