package com.chessassist

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Companion-side overlay fallback — now a persistent status banner.
 *
 * The banner is added once (when the service starts) and never hidden while the
 * service lives; every engine state transition rewrites its two lines:
 *
 *   手动己方回合 : 等待走棋中… / 正在计算…      → 推荐：e2e4 / 白胜率62%
 *   自动己方回合 : 自动走棋中… / 正在计算…      → 自动走棋中：e2e4 / 白胜率62%
 *   对方回合     : 等待对手走棋… / （上步结论）
 *
 * NOTE: the companion cannot see Duolingo's pixels (no MediaProjection in this design —
 * that's deliberate: no screenshots as primary or fallback). Without board coordinates
 * this overlay can only show text, never arrows.
 *
 * The *precise* arrow is drawn by the hook process (ArrowPainter via cl5.s(...)), which
 * has the live Rive view. This banner keeps the user informed when even that fails.
 */
object OverlayManager {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var added = false
    @Volatile private var initScheduled = false

    fun init(context: Context) {
        if (added || initScheduled) return
        initScheduled = true
        val appContext = context.applicationContext
        main.post {
            try {
                wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = TextView(appContext).apply {
                    text = "象棋助手就绪\n等待对局…"
                    setTextColor(Color.WHITE)
                    setShadowLayer(6f, 0f, 0f, Color.BLACK)
                    textSize = 16f
                }
                view = tv
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                wm?.addView(tv, lp)
                added = true
            } catch (_: Throwable) {
                // overlay permission missing — allow a later retry (e.g. after the
                // user grants it and returns to MainActivity, which calls init again).
                initScheduled = false
                // status bar notification still shows bestmove
            }
        }
    }

    /** Core writer: persistent two-line banner. */
    @Volatile private var lastTitle: String = ""
    fun showStatus(title: String, detail: String = "") {
        lastTitle = title
        main.post {
            view?.text = if (detail.isEmpty()) title else "$title\n$detail"
        }
    }

    /** Stale annotator must not wipe an explicit manual-help request. */
    fun isHelpShown(): Boolean = lastTitle == "需要手动操作"

    /** Analysis running for the user's turn. */
    fun showThinking(autoplay: Boolean) {
        showStatus(if (autoplay) "自动走棋中…" else "等待走棋中…", "正在计算…")
    }

    fun showBestMove(
        uci: String, fen: String, scoreCp: Int? = null,
        autoplay: Boolean = false, userColor: String? = null,
    ) {
        val stm = fen.split(' ').getOrNull(1)
        val line2 = com.chessassist.engine.ChessMath.formatWinRate(scoreCp, stm, userColor)
        showStatus(if (autoplay) "自动走棋中：$uci" else "推荐：$uci", line2)
    }

    /** Opponent's turn (or idle): keep the banner resident, show last conclusion. */
    fun clear(reason: String = "等待对手走棋…", detail: String = "") {
        main.post {
            view?.let {
                // Don't wipe a fresher state that arrived out of order; only replace
                // move-related lines or the initial text.
                val cur = it.text.toString()
                if (cur.startsWith("推荐") || cur.startsWith("自动走棋中") ||
                    cur.startsWith("等待走棋中") || cur.startsWith("象棋助手")
                ) {
                    it.text = if (detail.isEmpty()) reason else "$reason\n$detail"
                }
            }
        }
    }

    fun release() {
        main.post {
            try { view?.let { wm?.removeView(it) } } catch (_: Throwable) {}
            view = null
            added = false
            initScheduled = false
        }
    }
}
