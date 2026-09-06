package com.chessassist.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Fallback visualization when the Rive controller can't be reached (e.g. Duolingo
 * redesigns cl5). Draws a translucent arrow on a TYPE_APPLICATION_OVERLAY window.
 *
 * NOTE: this runs inside Duolingo's process/UID, so the overlay permission is checked
 * against com.duolingo — NOT the companion (com.chessassist). Granting overlay to the
 * companion does not help here; Duolingo practically never holds SYSTEM_ALERT_WINDOW,
 * so this path is best-effort only. The reliable fallback is the companion banner
 * (OverlayManager) driven by the same bestmove command. Primary path stays
 * ArrowPainter via cl5.s(...).
 */
object OverlayFallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var added = false
    private var overlay: FrameLayout? = null
    private var arrowSpec: Pair<Pair<Float, Float>, Pair<Float, Float>>? = null
    private var arrowColor: Int = Color.argb(190, 255, 210, 0)

    fun ensure() {
        if (added) return
        val ctx = BridgeAccess.context ?: return
        mainHandler.post {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = object : View(ctx) {
                    override fun onDraw(c: Canvas) {
                        arrowSpec?.let { (from, to) -> drawArrow(c, from, to, arrowColor) }
                    }
                }
                overlay = FrameLayout(ctx).also { it.addView(v) }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm.addView(overlay, lp)
                added = true
            } catch (t: Throwable) {
                MainHook.log("overlay add failed: $t " +
                    "(hook-process overlay needs SYSTEM_ALERT_WINDOW for com.duolingo; " +
                    "use the companion banner instead)")
            }
        }
    }

    fun drawArrow(
        fromSquare: String, toSquare: String,
        color: Int = Color.argb(190, 255, 210, 0),
    ) {
        // Square names → screen coords requires the board's view bounds; captured lazily
        // from the RiveAnimationView instance held by the controller. Without it we cannot
        // map squares → pixels, so this fallback only runs when controller exists but
        // cl5.s resolution failed (we have bounds but not the method).
        val view = RiveViewFinder.bestRiveView() ?: BridgeAccess.riveView ?: return
        mainHandler.post {
            overlay?.let { o ->
                arrowSpec = squaresToPoints(view, fromSquare, toSquare)
                arrowColor = color
                o.invalidate()
            }
        }
    }

    private fun squaresToPoints(view: android.view.View, from: String, to: String):
        Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        val size = minOf(view.width, view.height).toFloat()
        val cell = size / 8f
        // Mirror AutoPlayer.squareToScreen: black boards are rotated 180°.
        val flipped = BridgeAccess.lastUserColor == "b"
        fun pt(sq: String): Pair<Float, Float> {
            val file = sq[0] - 'a'; val rank = sq[1] - '1'
            val col = if (flipped) 7 - file else file
            val rowFromTop = if (flipped) rank else 7 - rank
            val x = loc[0] + col * cell + cell / 2
            val y = loc[1] + rowFromTop * cell + cell / 2
            return x to y
        }
        return pt(from) to pt(to)
    }

    private fun drawArrow(c: Canvas, from: Pair<Float, Float>, to: Pair<Float, Float>, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
        }
        val head = Paint(paint).apply { style = Paint.Style.FILL }
        val path = Path()
        path.moveTo(from.first, from.second)
        path.lineTo(to.first, to.second)
        c.drawPath(path, paint)
        // arrowhead
        val ang = Math.atan2(
            (to.second - from.second).toDouble(),
            (to.first - from.first).toDouble()
        )
        val hl = 34f
        val a1 = Math.toDegrees(ang) + 150f
        val a2 = Math.toDegrees(ang) - 150f
        c.drawLine(to.first, to.second,
            (to.first + hl * Math.cos(Math.toRadians(a1))).toFloat(),
            (to.second + hl * Math.sin(Math.toRadians(a1))).toFloat(), head)
        c.drawLine(to.first, to.second,
            (to.first + hl * Math.cos(Math.toRadians(a2))).toFloat(),
            (to.second + hl * Math.sin(Math.toRadians(a2))).toFloat(), head)
    }
}
