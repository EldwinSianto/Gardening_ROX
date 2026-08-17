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
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "BotHud"
        // Batas area panel (fraksi layar) — dipakai juga oleh mask di GardenService.
        const val MASK_X = 0.34f   // 0 .. MASK_X
        const val MASK_Y = 0.86f   // MASK_Y .. 1.0
        private const val TAP_MARK_MS = 600L
        private const val MAX_LINES = 3
    }

    private data class TapMark(val xFrac: Float, val yFrac: Float, val expireAt: Long)

    private var view: HudView? = null
    var isShowing = false
        private set

    private val lines = ArrayDeque<String>()   // baris terbaru di depan
    private val marks = mutableListOf<TapMark>()

    fun show() {
        if (isShowing) return
        val v = HudView(context)
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
            windowManager.addView(v, params)
            view = v
            isShowing = true
            Log.d(TAG, "HUD ON")
        } catch (t: Throwable) {
            Log.w(TAG, "HUD gagal tampil: ${t.message}")
        }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
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
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 60, 60); style = Paint.Style.STROKE; strokeWidth = 5f
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()

            // Panel status pojok kiri-bawah — dalam batas MASK_X/MASK_Y
            val top = h * MASK_Y
            canvas.drawRoundRect(6f, top + 4f, w * MASK_X - 6f, h - 6f, 10f, 10f, bgPaint)
            val snapshot = synchronized(lines) { lines.toList() }
            var y = top + 32f
            snapshot.forEachIndexed { i, line ->
                canvas.drawText(line, 18f, y, if (i == 0) txtPaint else dimPaint)
                y += if (i == 0) 26f else 22f
            }

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
