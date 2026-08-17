package com.roxgarden

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

class DebugOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val TAG = "DebugOverlay"
    private val PREFS_NAME = "roxgarden_regions"

    data class Region(
        val id: String,
        val label: String,
        val color: Int,
        var x1f: Float, var y1f: Float,
        var x2f: Float, var y2f: Float
    )

    // Region kalibrasi. Default = HASIL KALIBRASI LIVE di MuMu 1280×720 (dump roxgarden_regions.xml
    // 2026-07-15) — fraksi layar, jadi langsung terpakai di device baru (cloud phone) tanpa adb.
    // Region tanpa nilai dump (minimap dll) memang tak pernah digeser user → default lama sudah pas.
    val regions = mutableListOf(
        Region("gardening", "Gardening Button", Color.argb(220, 255, 200,   0),
            0.5760835f, 0.4076214f, 0.6789938f, 0.4678433f),
        Region("captcha",   "CAPTCHA Popup",    Color.argb(220, 255, 140,   0),
            0.4666120f, 0.4680030f, 0.5318657f, 0.5226768f),
        Region("numpad",    "Numpad",           Color.argb(220,  80, 220,  80),
            0.5523419f, 0.2971568f, 0.8548010f, 0.6850901f),
        // ── WEATHER SCAN (removable: hapus 4 region ini kalau fitur dibuang) ──
        Region("minimap_btn",  "Minimap (buka map)", Color.argb(220,  60, 160, 255),
            0.860f, 0.030f, 0.990f, 0.240f),
        Region("worldmap_btn", "World Map button",   Color.argb(220,  60, 160, 255),
            0.910f, 0.760f, 0.995f, 0.950f),
        Region("map_close_x",  "Map close X",        Color.argb(220, 255,  80,  80),
            0.950f, 0.030f, 1.000f, 0.150f),
        Region("weather_row",  "Weather forecast 4-slot", Color.argb(220, 180, 80, 255),
            0.6398128f, 0.0015257f, 0.9957612f, 0.1004300f),
        // ── CITY NAV (removable) — grid encyclopedia tanaman (utk scroll-search ikon) ──
        Region("garden_grid",  "Encyclopedia grid", Color.argb(220, 80, 255, 180),
            0.1163934f, 0.2902913f, 0.4679547f, 0.8389044f),
    )

    init { loadFromPrefs() }

    fun getRegion(id: String): Region? = regions.find { it.id == id }

    private fun loadFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (r in regions) {
            r.x1f = prefs.getFloat("${r.id}_x1", r.x1f)
            r.y1f = prefs.getFloat("${r.id}_y1", r.y1f)
            r.x2f = prefs.getFloat("${r.id}_x2", r.x2f)
            r.y2f = prefs.getFloat("${r.id}_y2", r.y2f)
        }
        logAll()
    }

    private fun savePrefs(r: Region) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putFloat("${r.id}_x1", r.x1f)
            putFloat("${r.id}_y1", r.y1f)
            putFloat("${r.id}_x2", r.x2f)
            putFloat("${r.id}_y2", r.y2f)
            apply()
        }
        Log.d(TAG, "Saved ${r.id}: x1f=${r.x1f}f, y1f=${r.y1f}f, x2f=${r.x2f}f, y2f=${r.y2f}f")
    }

    private var overlayView: RegionOverlayView? = null
    var isShowing = false
        private set

    fun toggle() { if (isShowing) hide() else show() }

    fun show() {
        if (isShowing) return
        val dm = context.resources.displayMetrics
        val view = RegionOverlayView(
            context, regions, dm.widthPixels, dm.heightPixels,
            onRegionReleased = { r -> savePrefs(r) },
            onHidePressed = { hide() }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(view, params)
        overlayView = view
        isShowing = true
        Log.d(TAG, "Debug overlay ON — loop dijeda, koordinat auto-save")
    }

    fun hide() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        isShowing = false
        Log.d(TAG, "Debug overlay OFF — loop lanjut")
    }

    fun logAll() {
        Log.d(TAG, "=== KOORDINAT REGION ===")
        for (r in regions) {
            Log.d(TAG, "${r.id.padEnd(10)}: x1f=${r.x1f}f, y1f=${r.y1f}f, x2f=${r.x2f}f, y2f=${r.y2f}f")
        }
        Log.d(TAG, "========================")
    }
}

// ─────────────────────────────────────────────────────────
// View: gambar region + handle + panel kontrol
// ─────────────────────────────────────────────────────────

private class RegionOverlayView(
    context: Context,
    private val regions: MutableList<DebugOverlayManager.Region>,
    private val screenW: Int,
    private val screenH: Int,
    private val onRegionReleased: (DebugOverlayManager.Region) -> Unit,
    private val onHidePressed: () -> Unit
) : View(context) {

    private enum class Handle { TL, TR, BL, BR, CENTER }
    private data class Drag(
        val idx: Int, val handle: Handle,
        val startTx: Float, val startTy: Float,
        val ox1: Float, val oy1: Float, val ox2: Float, val oy2: Float
    )

    private val HANDLE_R = 20f
    private val HIT_R    = HANDLE_R * 1.8f

    private var drag: Drag? = null
    private var selectedIdx = 0   // hanya region terpilih yang menampilkan handle & bisa di-resize

    private val fillP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val handleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val handleBorderP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.DKGRAY }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; isFakeBoldText = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val coordP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val btnLog  = RectF()
    private val btnHide = RectF()
    private val btnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 30, 30) }
    private val btnTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        btnLog.set(w - 240f, 20f, w - 130f, 80f)
        btnHide.set(w - 120f, 20f, w - 10f, 80f)
    }

    override fun onDraw(canvas: Canvas) {
        // gambar region tidak-terpilih dulu (tipis), region terpilih terakhir (di atas)
        for ((idx, r) in regions.withIndex()) {
            if (idx == selectedIdx) continue
            drawRegion(canvas, r, selected = false)
        }
        regions.getOrNull(selectedIdx)?.let { drawRegion(canvas, it, selected = true) }

        canvas.drawRoundRect(btnLog,  8f, 8f, btnP)
        canvas.drawRoundRect(btnHide, 8f, 8f, btnP)
        canvas.drawText("LOG", btnLog.centerX(),  btnLog.centerY()  + 11f, btnTextP)
        canvas.drawText("✕",   btnHide.centerX(), btnHide.centerY() + 11f, btnTextP)
    }

    private fun drawRegion(canvas: Canvas, r: DebugOverlayManager.Region, selected: Boolean) {
        val x1 = r.x1f * screenW; val y1 = r.y1f * screenH
        val x2 = r.x2f * screenW; val y2 = r.y2f * screenH

        if (selected) {
            fillP.color = Color.argb(45, Color.red(r.color), Color.green(r.color), Color.blue(r.color))
            canvas.drawRect(x1, y1, x2, y2, fillP)
        }
        borderP.color = r.color
        borderP.strokeWidth = if (selected) 5f else 2f
        canvas.drawRect(x1, y1, x2, y2, borderP)

        canvas.drawText(r.label, x1 + 6, y1 + 34, labelP)
        if (selected) {
            val pct = "x:${pct(r.x1f)}-${pct(r.x2f)}%  y:${pct(r.y1f)}-${pct(r.y2f)}%"
            canvas.drawText(pct, x1 + 6, y1 + 62, coordP)
            // handle hanya di region terpilih
            drawHandle(canvas, x1, y1, r.color)
            drawHandle(canvas, x2, y1, r.color)
            drawHandle(canvas, x1, y2, r.color)
            drawHandle(canvas, x2, y2, r.color)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        canvas.drawCircle(cx, cy, HANDLE_R, handleP)
        handleBorderP.color = color
        canvas.drawCircle(cx, cy, HANDLE_R, handleBorderP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x in btnLog.left..btnLog.right && e.y in btnLog.top..btnLog.bottom) {
                    logAll(); return true
                }
                if (e.x in btnHide.left..btnHide.right && e.y in btnHide.top..btnHide.bottom) {
                    performClick()
                    onHidePressed()
                    return true
                }
                // 1. Region terpilih: cek handle sudut dulu (resize)
                regions.getOrNull(selectedIdx)?.let { r ->
                    val x1 = r.x1f * screenW; val y1 = r.y1f * screenH
                    val x2 = r.x2f * screenW; val y2 = r.y2f * screenH
                    cornerHandle(e.x, e.y, x1, y1, x2, y2)?.let { h ->
                        drag = Drag(selectedIdx, h, e.x, e.y, r.x1f, r.y1f, r.x2f, r.y2f)
                        return true
                    }
                }
                // 2. Tap di dalam region mana pun (topmost) → pilih + langsung geser (CENTER)
                for (idx in regions.indices.reversed()) {
                    val r = regions[idx]
                    val x1 = r.x1f * screenW; val y1 = r.y1f * screenH
                    val x2 = r.x2f * screenW; val y2 = r.y2f * screenH
                    if (e.x in x1..x2 && e.y in y1..y2) {
                        selectedIdx = idx
                        drag = Drag(idx, Handle.CENTER, e.x, e.y, r.x1f, r.y1f, r.x2f, r.y2f)
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drag ?: return false
                val dx = (e.x - d.startTx) / screenW
                val dy = (e.y - d.startTy) / screenH
                val r  = regions[d.idx]
                val MIN = 0.02f
                when (d.handle) {
                    Handle.TL -> { r.x1f = (d.ox1 + dx).coerceIn(0f, r.x2f - MIN); r.y1f = (d.oy1 + dy).coerceIn(0f, r.y2f - MIN) }
                    Handle.TR -> { r.x2f = (d.ox2 + dx).coerceIn(r.x1f + MIN, 1f); r.y1f = (d.oy1 + dy).coerceIn(0f, r.y2f - MIN) }
                    Handle.BL -> { r.x1f = (d.ox1 + dx).coerceIn(0f, r.x2f - MIN); r.y2f = (d.oy2 + dy).coerceIn(r.y1f + MIN, 1f) }
                    Handle.BR -> { r.x2f = (d.ox2 + dx).coerceIn(r.x1f + MIN, 1f); r.y2f = (d.oy2 + dy).coerceIn(r.y1f + MIN, 1f) }
                    Handle.CENTER -> {
                        val w = d.ox2 - d.ox1; val h = d.oy2 - d.oy1
                        r.x1f = (d.ox1 + dx).coerceIn(0f, 1f - w); r.y1f = (d.oy1 + dy).coerceIn(0f, 1f - h)
                        r.x2f = r.x1f + w; r.y2f = r.y1f + h
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                drag?.let { onRegionReleased(regions[it.idx]) }
                drag = null
            }
        }
        return false
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // Hanya cek 4 sudut (resize). Pemindahan (CENTER) ditangani terpisah di ACTION_DOWN.
    private fun cornerHandle(tx: Float, ty: Float, x1: Float, y1: Float, x2: Float, y2: Float): Handle? {
        fun d(ax: Float, ay: Float) = hypot((tx - ax).toDouble(), (ty - ay).toDouble()).toFloat()
        return when {
            d(x1, y1) < HIT_R -> Handle.TL
            d(x2, y1) < HIT_R -> Handle.TR
            d(x1, y2) < HIT_R -> Handle.BL
            d(x2, y2) < HIT_R -> Handle.BR
            else -> null
        }
    }

    private fun logAll() {
        Log.d("DebugOverlay", "=== KOORDINAT REGION ===")
        for (r in regions) {
            Log.d("DebugOverlay", "${r.id.padEnd(10)}: x1f=${r.x1f}f, y1f=${r.y1f}f, x2f=${r.x2f}f, y2f=${r.y2f}f")
        }
        Log.d("DebugOverlay", "========================")
    }

    private fun pct(f: Float) = (f * 100).toInt()
}