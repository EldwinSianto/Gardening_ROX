package com.roxgarden

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * HUD status live — panel kecil di pojok kiri-bawah yang menampilkan langkah yang sedang
 * dikerjakan bot, plus penanda lingkaran sesaat di titik yang di-tap.
 *
 * PENTING — bot memotret layarnya sendiri: area panel ini DI-MASK HITAM dari setiap capture
 * (lihat GardenService.maskHud) supaya teks HUD tidak pernah terbaca OCR bot. Tanpa mask,
 * teks seperti "navigasi Morroc" bisa match fuzzy tapLocationButton → bot men-tap HUD-nya
 * sendiri. Karena itu pula posisi/ukuran panel HARUS sinkron dengan MASK_X/MASK_Y di sini.
 *
 * Penanda tap TIDAK di-mask (posisinya di konten game yang justru perlu dibaca) — aman karena
 * umurnya cuma TAP_MARK_MS dan capture berikutnya selalu ≥1 detik setelah tap.
 */
class BotHud(
    private val context: Context,
    // Context/WindowManager DIAMBIL SAAT overlay ditampilkan, bukan saat objek ini dibuat.
    //
    // Sebabnya terukur di device (dumpsys window): overlay mendarat di mDisplayId=0
    // sedangkan game jalan di display 14 - MuMu memberi tiap tab display virtual sendiri.
    // Window-nya ADA dan tidak error sama sekali; ia cuma tergambar di layar yang tidak
    // dilihat siapa pun. Ini masalah yang sama dengan refreshGameDisplay() pada capture
    // ("id display game tidak boleh diasumsikan 0"), tapi di sisi PENEMPATAN window.
    //
    // Diambil belakangan karena id display game belum diketahui saat service tersambung -
    // baru terisi sesudah refreshGameDisplay() berjalan.
    private val overlayContext: () -> Context = { context }
) {
    companion object {
        private const val TAG = "BotHud"
        // Batas area panel (fraksi layar) — dipakai juga oleh mask di GardenService.
        const val MASK_X = 0.34f   // 0 .. MASK_X
        const val MASK_Y = 0.86f   // MASK_Y .. 1.0
        private const val TAP_MARK_MS = 600L
        // 3 → 2: baris ketiga panel dipakai permanen untuk angka timing (PerfStats.hudLine).
        // Geometri panel & MASK_X/MASK_Y sengaja TIDAK diubah — memperbesar panel berarti
        // memperbesar area yang dihitamkan dari capture, bisa menelan teks yang perlu OCR.
        private const val MAX_LINES = 2
    }

    private data class TapMark(val xFrac: Float, val yFrac: Float, val expireAt: Long)

    private var view: HudView? = null
    var isShowing = false
        private set

    private val lines = ArrayDeque<String>()   // baris terbaru di depan
    private val marks = mutableListOf<TapMark>()
    @Volatile private var statsLine = ""       // baris timing permanen (paling bawah panel)

    private var usedWm: WindowManager? = null

    fun show() {
        if (isShowing) return
        val ctx = overlayContext()
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = HudView(ctx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or   // semua sentuhan tembus ke game
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(v, params)
            usedWm = wm
            view = v
            isShowing = true
            Log.d(TAG, "HUD ON")
        } catch (t: Throwable) {
            Log.w(TAG, "HUD gagal tampil: ${t.message}")
        }
    }

    fun hide() {
        // WAJIB dilepas lewat WindowManager yang SAMA dengan yang memasangnya - wm milik
        // display lain tidak mengenal view ini dan removeView-nya melempar.
        view?.let { v -> runCatching { (usedWm ?: context.getSystemService(WindowManager::class.java)).removeView(v) } }
        usedWm = null
        view = null
        isShowing = false
        Log.d(TAG, "HUD OFF")
    }

    fun toggle() { if (isShowing) hide() else show() }

    /** Update baris status (dipanggil dari thread mana pun). */
    fun status(text: String) {
        val v = view ?: return
        v.post {
            synchronized(lines) {
                if (lines.firstOrNull() == text) return@post   // jangan spam baris sama
                lines.addFirst(text)
                while (lines.size > MAX_LINES) lines.removeLast()
            }
            v.invalidate()
        }
    }

    /**
     * Baris timing permanen di dasar panel (dari PerfStats.hudLine). Terpisah dari status()
     * supaya tidak ikut tergeser keluar oleh langkah bot yang berganti cepat — di device tanpa
     * adb ini satu-satunya cara membaca biaya tiap langkah saat bot jalan.
     */
    fun stats(text: String) {
        if (statsLine == text) return
        statsLine = text
        view?.post { view?.invalidate() }
    }

    /** Penanda lingkaran sesaat di titik tap (fraksi layar). */
    fun flashTap(xFrac: Float, yFrac: Float) {
        val v = view ?: return
        v.post {
            synchronized(marks) {
                marks.add(TapMark(xFrac, yFrac, System.currentTimeMillis() + TAP_MARK_MS))
            }
            v.invalidate()
            v.postDelayed({ v.invalidate() }, TAP_MARK_MS + 50)
        }
    }

    private inner class HudView(ctx: Context) : View(ctx) {
        private val bgPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 22f
            typeface = Typeface.MONOSPACE
        }
        private val dimPaint = Paint(txtPaint).apply { color = Color.argb(150, 255, 255, 255); textSize = 18f }
        // Baris timing: warna beda supaya tidak tertukar dengan langkah bot saat dibaca sekilas
        private val statsPaint = Paint(txtPaint).apply { color = Color.argb(220, 120, 230, 255); textSize = 17f }
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 60, 60); style = Paint.Style.STROKE; strokeWidth = 5f
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()

            // Panel status pojok kiri-bawah — dalam batas MASK_X/MASK_Y
            val top = h * MASK_Y
            canvas.drawRoundRect(6f, top + 4f, w * MASK_X - 6f, h - 6f, 10f, 10f, bgPaint)
            val snapshot = synchronized(lines) { lines.toList() }
            var y = top + 30f
            snapshot.forEachIndexed { i, line ->
                canvas.drawText(line, 18f, y, if (i == 0) txtPaint else dimPaint)
                y += if (i == 0) 24f else 22f
            }
            // Timing di-anchor ke DASAR panel, bukan setelah baris status — posisinya tetap
            // walau jumlah baris status berubah, jadi angkanya gampang dibaca sekilas.
            if (statsLine.isNotEmpty()) canvas.drawText(statsLine, 18f, h - 12f, statsPaint)

            // Penanda tap (di luar panel, tidak di-mask — berumur pendek)
            val now = System.currentTimeMillis()
            synchronized(marks) {
                marks.removeAll { it.expireAt < now }
                for (m in marks) {
                    val cx = m.xFrac * w; val cy = m.yFrac * h
                    canvas.drawCircle(cx, cy, 26f, markPaint)
                    canvas.drawCircle(cx, cy, 6f, markPaint)
                }
            }
            if (synchronized(marks) { marks.isNotEmpty() }) postInvalidateDelayed(120)
        }
    }
}
