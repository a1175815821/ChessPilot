package com.chessassist

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.chessassist.engine.ChessMath

/**
 * Companion-side overlay: persistent dark status banner (top center by default).
 *
 * Layout (horizontal): [spinner | title+winrate column | play button].
 * - Container: dark translucent rounded rect, draggable (position persisted).
 * - Win-rate line recolors live (green/yellow/red).
 * - Play button is the ONLY tap target (banner body never consumes taps):
 *   enabled + "走棋" in manual mode with a fresh bestmove, disabled + "自动"
 *   in auto mode, disabled otherwise. Tapping it plays via EngineLoop.
 * - Spinner shows while auto mode is actively working a position.
 *
 * NOTE: the companion cannot see Duolingo's pixels (no MediaProjection in this design —
 * that's deliberate: no screenshots as primary or fallback). Without board coordinates
 * this overlay can only show text, never arrows.
 *
 * The *precise* arrow is drawn by the hook process (ArrowPainter via cl5.s(...)), which
 * has the live Rive view.
 */
object OverlayManager {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var titleTv: TextView? = null
    private var detailTv: TextView? = null
    private var playBtn: Button? = null
    private var spinner: SpinView? = null
    private var added = false
    @Volatile private var initScheduled = false

    private const val PREFS = "chessassist"
    private const val K_OX = "overlay_x"
    private const val K_OY = "overlay_y"
    private const val K_OG = "overlay_gravity"
    private const val NO_POS = -100000
    private const val DEFAULT_GRAVITY = Gravity.TOP or Gravity.CENTER_HORIZONTAL

    /** Called when the user taps the play button (wired by AssistService). */
    var onPlay: (() -> Unit)? = null

    fun init(context: Context) {
        if (added || initScheduled) return
        initScheduled = true
        val appContext = context.applicationContext
        main.post {
            try {
                val density = appContext.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                titleTv = TextView(appContext).apply {
                    text = "象棋助手就绪"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    paint.isFakeBoldText = true
                }
                detailTv = TextView(appContext).apply {
                    text = "等待对局…"
                    setTextColor(Color.parseColor("#BDBDBD"))
                    textSize = 14f
                }
                val textCol = LinearLayout(appContext).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleTv)
                    addView(detailTv)
                }
                // Self-drawn arc spinner. History: a framework ProgressBar's
                // indeterminate drawable never rendered in this overlay window, and
                // an ASCII frame ticker (|/-\ every 150 ms) rendered but looked
                // steppy with jittery widths. Canvas arcs + a vsync animator are
                // smooth by construction and depend on neither drawables nor fonts.
                spinner = SpinView(appContext).apply {
                    visibility = View.GONE
                }
                playBtn = Button(appContext).apply {
                    text = "走棋"
                    textSize = 14f
                    isEnabled = false
                    setOnClickListener { onPlay?.invoke() }
                }
                root = LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#CC1E1E1E"))
                        cornerRadius = dp(16).toFloat()
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(spinner, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        marginEnd = dp(8)
                    })
                    addView(textCol, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ))
                    addView(playBtn, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) })
                    // Touch listener attached after lp exists (see below).
                }

                lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    // Offsets are only meaningful together with the gravity they were
                    // recorded under — restore the exact triple, or the banner jumps
                    // to a corner on next start.
                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    gravity = when {
                        prefs.contains(K_OG) -> prefs.getInt(K_OG, DEFAULT_GRAVITY)
                        // Pre-gravity records were always stored under START|TOP.
                        prefs.contains(K_OX) -> Gravity.TOP or Gravity.START
                        else -> DEFAULT_GRAVITY
                    }
                    x = prefs.getInt(K_OX, 0)
                    y = prefs.getInt(K_OY, 0)
                }
                root?.setOnTouchListener(DragTouchListener(wm, root, lp) { x, y ->
                    try {
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(K_OX, x)
                            .putInt(K_OY, y)
                            .putInt(K_OG, lp?.gravity ?: DEFAULT_GRAVITY)
                            .apply()
                    } catch (_: Throwable) {
                    }
                })
                wm?.addView(root, lp)
                added = true
            } catch (_: Throwable) {
                // overlay permission missing — allow a later retry (e.g. after the
                // user grants it and returns to MainActivity, which calls init again).
                initScheduled = false
                // status bar notification still shows bestmove
            }
        }
    }

    /**
     * Drag-to-move (banner body only; button keeps its own clicks).
     *
     * Offset-only dragging: WindowManager x/y are offsets from the gravity
     * position, so a drag is just base + finger delta — gravity is NEVER touched
     * after addView. The previous version re-anchored to absolute screen coords
     * and flipped gravity mid-drag, which forced a full relayout every drag start
     * (the visible jump) and lagged behind the finger.
     */
    private class DragTouchListener(
        private val wm: WindowManager?,
        private val root: LinearLayout?,
        private val lp: WindowManager.LayoutParams?,
        private val onDrop: (Int, Int) -> Unit,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var baseX = 0
        private var baseY = 0
        private var dragging = false
        private var slop = -1

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            if (slop < 0) {
                slop = try {
                    ViewConfiguration.get(v.context).scaledTouchSlop
                } catch (_: Throwable) {
                    12
                }
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    baseX = lp?.x ?: 0
                    baseY = lp?.y ?: 0
                    dragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(ev.rawX - downRawX) > slop ||
                            kotlin.math.abs(ev.rawY - downRawY) > slop)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        lp?.x = baseX + (ev.rawX - downRawX).toInt()
                        lp?.y = baseY + (ev.rawY - downRawY).toInt()
                        try {
                            wm?.updateViewLayout(root, lp)
                        } catch (_: Throwable) {
                        }
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        onDrop(lp?.x ?: 0, lp?.y ?: 0)
                        return true
                    }
                    return false
                }
            }
            return false
        }
    }

    /** Core writer: persistent two-line banner. */
    @Volatile private var lastTitle: String = ""
    fun showStatus(title: String, detail: String = "") {
        lastTitle = title
        main.post {
            titleTv?.text = title
            detailTv?.text = detail
            detailTv?.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
            setSpinner(false)
            setPlayButton(false, "走棋")
        }
    }

    /** Stale annotator must not wipe an explicit manual-help request. */
    fun isHelpShown(): Boolean = lastTitle == "需要手动操作"

    /** Analysis running for the user's turn. */
    fun showThinking(autoplay: Boolean) {
        lastTitle = if (autoplay) "自动走棋中…" else "等待走棋中…"
        main.post {
            titleTv?.text = lastTitle
            detailTv?.text = "正在计算…"
            detailTv?.visibility = View.VISIBLE
            setSpinner(autoplay)
            setPlayButton(false, if (autoplay) "自动" else "走棋")
        }
    }

    fun showBestMove(
        uci: String, fen: String, scoreCp: Int? = null,
        autoplay: Boolean = false, userColor: String? = null,
    ) {
        val stm = fen.split(' ').getOrNull(1)
        val line2 = ChessMath.formatWinRate(scoreCp, stm, userColor)
        val rate = ChessMath.userWinRate01(scoreCp, stm, userColor)
        val title = if (autoplay) "自动走棋中：$uci" else "推荐：$uci"
        lastTitle = title
        main.post {
            titleTv?.text = title
            detailTv?.text = line2
            detailTv?.visibility = View.VISIBLE
            detailTv?.setTextColor(ChessMath.winRateColor(rate))
            setSpinner(autoplay)
            if (autoplay) setPlayButton(false, "自动") else setPlayButton(true, "走棋")
        }
    }

    /** Opponent's turn (or idle): keep the banner resident, show last conclusion. */
    fun clear(reason: String = "等待对手走棋…", detail: String = "") {
        main.post {
            // Don't wipe a fresher state that arrived out of order.
            val cur = titleTv?.text.toString()
            if (cur.startsWith("推荐") || cur.startsWith("自动走棋中") ||
                cur.startsWith("等待走棋中") || cur.startsWith("象棋助手")
            ) {
                lastTitle = reason
                titleTv?.text = reason
                detailTv?.text = detail
                detailTv?.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
                setSpinner(false)
                setPlayButton(false, "走棋")
            }
        }
    }

    private fun setPlayButton(enabled: Boolean, text: String) {
        playBtn?.isEnabled = enabled
        playBtn?.text = text
    }

    /** Fixed-size arc spinner: 270° round-capped arc rotating at 1 rev/s. */
    private class SpinView(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val rect = android.graphics.RectF()

        // Animated property (ObjectAnimator reflection target must stay).
        @Suppress("unused")
        var sweep = 0f
            set(v) {
                field = v
                invalidate()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val density = resources.displayMetrics.density
            paint.strokeWidth = 3f * density
            val pad = paint.strokeWidth
            rect.set(pad, pad, w - pad, h - pad)
        }

        override fun onDraw(c: android.graphics.Canvas) {
            c.drawArc(rect, sweep, 270f, false, paint)
        }
    }

    private var spinAnim: android.animation.ObjectAnimator? = null

    private fun setSpinner(spinning: Boolean) {
        val v = spinner ?: return
        spinAnim?.cancel()
        spinAnim = null
        if (spinning) {
            v.visibility = View.VISIBLE
            spinAnim = android.animation.ObjectAnimator.ofFloat(v, "sweep", 0f, 360f).apply {
                duration = 1000L
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        } else {
            v.visibility = View.GONE
        }
    }

    /** 1 when the spinner is currently shown (adb diagnostics). */
    fun isSpinning(): Boolean = spinner?.visibility == View.VISIBLE

    fun release() {
        main.post {
            try { spinAnim?.cancel() } catch (_: Throwable) {}
            spinAnim = null
            try { root?.let { wm?.removeView(it) } } catch (_: Throwable) {}
            root = null
            titleTv = null
            detailTv = null
            playBtn = null
            spinner = null
            added = false
            initScheduled = false
        }
    }
}
