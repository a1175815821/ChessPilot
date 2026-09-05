package com.chessassist.engine

import android.content.Context
import android.util.Log
import com.chessassist.BridgeServer
import com.chessassist.BridgeSettings
import com.chessassist.OverlayManager
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real-time loop:
 *
 *   hook publishes position ──► [onPosition] ──► dedupe ──► Stockfish analyse
 *        ▲                                                        │
 *        │                                                        ▼
 *   hook draws/autoplays ◄── BridgeServer pushes bestmove ──► draw + optional autoplay
 *
 * Transport is loopback TCP (BridgeServer/BridgeClient): Duolingo cannot resolve our
 * ContentProvider authority on Android 11+ (package visibility), so the provider is
 * diagnostics-only.
 *
 * Reliability rules implemented here:
 *  - Analysis is skipped when it's not our turn (turnColor != userColor) or the
 *    position didn't change — keeps CPU and battery at zero while idle.
 *  - A single analysis slot; a newer position cancels interest in the older one.
 *  - Engine crashes are contained: restart with backoff, request the hook to re-send.
 *  - Arrow + autoplay only ever target the FEN they were computed for (staleness guard).
 */
object EngineLoop {

    private const val TAG = "EngineLoop"
    private const val MAX_RESTARTS = 5
    private const val MAX_EVENTS = 30

    const val STATE_STOPPED = "stopped"
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_FAILED = "failed"

    private val running = AtomicBoolean(false)
    private var client: StockfishClient? = null
    private var appContext: Context? = null
    private var worker: Thread? = null

    // Latest position, guarded by worker-thread confinement (only worker reads/writes).
    @Volatile var currentFen: String? = null
        private set

    /** Engine lifecycle for the status screen: stopped|starting|running|failed. */
    @Volatile var engineState: String = STATE_STOPPED
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastBestMove: String? = null
        private set
    @Volatile var lastBestMoveAt: Long = 0
        private set
    @Volatile var lastScoreCp: Int? = null
        private set
    @Volatile var lastScoreStm: String? = null
        private set
    @Volatile var lastScoreUser: String? = null
        private set

    /** Last time the hook published a position (0 = never: LSPosed side not working). */
    @Volatile var lastPositionAt: Long = 0
        private set
    @Volatile var lastPositionFen: String? = null
        private set

    /**
     * Loop heartbeat (updated every iteration + after every analysis). If the worker
     * ever wedges, the status screen shows a stale heartbeat instead of a
     * plausible-looking frozen state — that distinction is the whole diagnosis.
     */
    @Volatile var lastLoopAt: Long = 0
        private set

    /** Stale-position annotation throttle (so a stuck game diagnoses itself). */
    @Volatile private var lastStaleBannerAt: Long = 0
    @Volatile private var lastStaleNoteAt: Long = 0

    /** No new position for this long => banner/status say so explicitly. */
    private const val STALE_AFTER_MS = 45_000L

    @Volatile private var pendingFen: String? = null
    @Volatile private var pendingUserColor: String? = null
    @Volatile private var pendingTurnColor: String? = null
    private val pendingLock = Any()

    private val eventsLock = Any()
    private val events = ArrayDeque<String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        appContext = context.applicationContext
        engineState = STATE_STARTING
        lastError = null
        note("正在启动引擎…")
        worker = Thread(::runLoop, "engine-loop").apply { start() }
    }

    fun stop() {
        running.set(false)
        note("正在停止…")
        try { client?.stop() } catch (_: Throwable) {}
        client = null
        try { worker?.interrupt() } catch (_: Throwable) {}
        worker = null
        engineState = STATE_STOPPED
    }

    /** Last known colors (for the stale banner: whose turn are we waiting on?). */
    @Volatile private var lastUserColor: String? = null
    @Volatile private var lastTurnColor: String? = null

    /**
     * Settle filter: hooks (and Duolingo itself during animations/retries) can emit
     * a burst of micro-different states within milliseconds. Analysing each burns
     * 700 ms+ and the resulting command/tap chains cross-fire into a board that has
     * already moved on — the wedge users see as a frozen game. A fen must survive
     * this long before it reaches analysis.
     */
    private const val SETTLE_MS = 250L
    @Volatile private var settleFen: String? = null
    @Volatile private var settleSince: Long = 0

    /** Called by BridgeProvider whenever the hook publishes a position. */
    fun onPosition(fen: String, userColor: String?, turnColor: String?, moves: List<String>) {
        lastPositionAt = System.currentTimeMillis()
        lastPositionFen = fen
        lastUserColor = userColor?.lowercase()
        lastTurnColor = turnColor?.lowercase()
        synchronized(pendingLock) {
            pendingFen = fen
            pendingUserColor = userColor
            pendingTurnColor = turnColor
        }
    }

    fun onArrowAck(uci: String) {
        Log.d(TAG, "hook confirmed arrow drawn: $uci")
        note("对局端已画出箭头：$uci")
    }

    /** Called by [BridgeServer] when the hook (re)connects over loopback TCP. */
    fun onHookConnected() {
        note("对局端已连接（socket 在线）")
    }

    /** Called by [BridgeServer] on fatal bridge errors (e.g. port occupied). */
    fun onBridgeError(msg: String) {
        lastError = msg
        note(msg)
    }

    /**
     * The hook gave up auto-playing and asks the user to finish manually.
     * Banner + event guide instead of dying silent (which used to deadlock the
     * game: same position forever, no retrigger, no next move).
     */
    fun onNeedHelp(code: String, uci: String) {
        val detail = when (code) {
            "promotion" -> "升变请手动选皇后（${uci}）"
            else -> "自动走棋未落子，请手动走 ${uci}"
        }
        note("需要手动操作：$detail")
        OverlayManager.showStatus("需要手动操作", detail)
    }

    /** Recent lifecycle events for the in-app status screen (newest last). */
    fun recentEvents(): List<String> = synchronized(eventsLock) { events.toList() }

    private fun note(msg: String) {
        Log.i(TAG, msg)
        // SimpleDateFormat is not thread-safe: note() is called from the engine
        // worker, bridge and main threads — serialize the format call.
        val line = synchronized(timeFmt) { "${timeFmt.format(Date())} $msg" }
        synchronized(eventsLock) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** Second banner line while waiting: keep the last conclusion visible. */
    private fun lastConclusionLine(): String {
        val m = lastBestMove ?: return ""
        return "$m · ${ChessMath.formatWinRate(lastScoreCp, lastScoreStm, lastScoreUser)}"
    }

    private fun ago(ts: Long): String {
        if (ts <= 0) return "从未"
        val s = (System.currentTimeMillis() - ts) / 1000
        return if (s < 60) "$s 秒前" else "${s / 60} 分钟前"
    }

    /** Minutes since the last published position, or -1 when fresh/never. */
    fun staleMinutes(): Long {
        if (lastPositionAt <= 0) return -1
        val idle = System.currentTimeMillis() - lastPositionAt
        return if (idle > STALE_AFTER_MS) idle / 60_000 else -1
    }

    /**
     * Called from the idle branch: if no NEW position arrived for a while while a
     * game was active, say so on the banner/event instead of showing a frozen
     * "waiting" line that looks identical to healthy waiting. Throttled.
     */
    private fun maybeNoteStale() {
        if (lastPositionAt <= 0 || !running.get()) return
        val idle = System.currentTimeMillis() - lastPositionAt
        if (idle <= STALE_AFTER_MS) return
        val now = System.currentTimeMillis()
        if (now - lastStaleBannerAt > 30_000 && !OverlayManager.isHelpShown()) {
            lastStaleBannerAt = now
            // No nagging: when it's the user's turn we stay silent on the banner
            // (the last analysis stays visible) — demanding taps was rightly called
            // out. Only annotate opponent-side/unknown stalls, which otherwise look
            // identical to a healthy wait.
            val waitingOurs = lastTurnColor != null && lastTurnColor == lastUserColor
            if (!waitingOurs) {
                val m = idle / 60_000
                OverlayManager.showStatus(
                    "局面停滞", "${m}分无新局面" + lastConclusionLine().let {
                        if (it.isEmpty()) "" else "（上步$it）"
                    }
                )
            }
        }
        if (now - lastStaleNoteAt > 300_000) {
            lastStaleNoteAt = now
            note("局面停滞：${idle / 60_000} 分钟无新局面（对局结束/切后台/钩子上报停止其一）")
        }
    }

    fun statusText(): String {
        val c = client
        val hookState = when {
            BridgeServer.hookConnected() -> "已连接（socket 在线）"
            lastPositionAt > 0 -> "已连接（${ago(lastPositionAt)}收到局面）"
            else -> "未连接（LSPosed 模块未上报局面）"
        }
        val engineLabel = when (engineState) {
            STATE_RUNNING -> "运行中"
            STATE_STARTING -> "启动中…"
            STATE_FAILED -> "启动失败"
            else -> "已停止"
        }
        return buildString {
            appendLine("引擎状态：$engineLabel")
            appendLine("Hook 连接：$hookState")
            appendLine("NNUE：${c?.loadedNnue ?: "-"}")
            appendLine("当前 FEN：${currentFen ?: "-"}")
            appendLine("最佳招法：${lastBestMove ?: "-"}${if (lastBestMove != null) "（${ago(lastBestMoveAt)}）" else ""}")
            if (lastBestMove != null) {
                appendLine("${ChessMath.formatWinRate(lastScoreCp, lastScoreStm, lastScoreUser)}")
            }
            appendLine("思考设置：${BridgeSettings.describe()}")
            appendLine(
                "自动走棋：${if (BridgeSettings.autoPlayEnabled) "开（${BridgeSettings.autoPlayDelayMs}ms）" else "关"}"
            )
            appendLine("循环心跳：${ago(lastLoopAt)}（停住不动=工作线程卡死）")
            val stale = staleMinutes()
            if (stale >= 0) appendLine("局面停滞：${stale}分无新局面")
            if (stale >= 0 && lastTurnColor != null && lastTurnColor == lastUserColor) {
                appendLine("轮到你走棋")
            }
            if (lastError != null) appendLine("最近错误：$lastError")
        }
    }

    // ------------------------------------------------------------------ loop

    private fun runLoop() {
        var restarts = 0
        var lastAnalysedFen: String? = null
        while (running.get()) {
            lastLoopAt = System.currentTimeMillis()
            try {
                // Local snapshot: stop() (main thread) nulls `client` and destroys
                // the process concurrently — `client!!` here raced that and threw
                // a misleading NPE that flashed STATE_FAILED on every normal stop.
                // isRunning() also covers the watchdog kill (stopped but non-null):
                // without it the loop would spin analyse()->null forever.
                var c = client
                if (c == null || !c.isRunning()) {
                    if (c != null) {
                        try { c.stop() } catch (_: Throwable) {}
                        client = null
                    }
                    engineState = STATE_STARTING
                    c = StockfishClient(appContext!!).also { it.start() }
                    client = c
                    restarts = 0
                    engineState = STATE_RUNNING
                    note("引擎已启动，等待对局局面…")
                    OverlayManager.showStatus("象棋助手就绪", "等待对局…")
                }
                val (fen, user, turn) = synchronized(pendingLock) {
                    Triple(pendingFen, pendingUserColor, pendingTurnColor)
                }
                if (fen == null || fen == lastAnalysedFen) {
                    maybeNoteStale()
                    Thread.sleep(60)
                    continue
                }
                // Let micro-flaps settle: a fen that reverts within SETTLE_MS never
                // reaches analysis (nor the tap chains downstream of it).
                val now = System.currentTimeMillis()
                if (fen != settleFen) {
                    settleFen = fen
                    settleSince = now
                    Thread.sleep(60)
                    continue
                }
                if (now - settleSince < SETTLE_MS) {
                    Thread.sleep(60)
                    continue
                }
                currentFen = fen

                val parts = fen.split(' ')
                val stm = parts.getOrNull(1)
                // Colors arrive in mixed case (hook getSymbol() yields "B"/"W", FEN
                // yields "b"/"w") — compare case-insensitively, or black/white gets
                // mis-skipped as "opponent's turn" forever.
                val t = turn?.lowercase() ?: stm
                val u = user?.lowercase() ?: turn?.lowercase()
                val userIsToMove = t == u
                // Only analyse when it's the user's move (their decision is what we assist).
                if (!userIsToMove && user != null) {
                    lastAnalysedFen = fen
                    OverlayManager.clear("等待对手走棋…", lastConclusionLine())
                    continue
                }

                OverlayManager.showThinking(BridgeSettings.autoPlayEnabled)
                val best = c.analyse(fen, BridgeSettings.movetimeMs, BridgeSettings.searchDepth)
                lastLoopAt = System.currentTimeMillis()
                if (best != null) {
                    lastAnalysedFen = fen
                    lastBestMove = best.uci
                    lastBestMoveAt = System.currentTimeMillis()
                    lastScoreCp = best.scoreCp
                    lastScoreStm = stm
                    lastScoreUser = user
                    val wr = ChessMath.formatWinRate(best.scoreCp, stm, user)
                    note("最佳招法 ${best.uci}（cp=${best.scoreCp}，$wr）")
                    // One combined command: the hook draws AND (if enabled) auto-plays it.
                    BridgeServer.pushBestmove(
                        best.uci, fen,
                        BridgeSettings.autoPlayEnabled, BridgeSettings.autoPlayDelayMs
                    )
                    OverlayManager.showBestMove(
                        best.uci, fen, best.scoreCp, BridgeSettings.autoPlayEnabled, user
                    )
                } else {
                    // Transient failure (timeout/crash): do NOT mark as analysed or the
                    // position would never be retried until the next move. Back off
                    // briefly; a newer position preempts us via pendingFen.
                    Log.w(TAG, "analyse returned null for ${fen.take(30)}…, will retry")
                    Thread.sleep(500)
                }
            } catch (t: Throwable) {
                // Any failure after stop() is a shutdown race (stop() interrupted
                // our sleep or destroyed the engine process under us): exit
                // quietly instead of flashing ERROR + STATE_FAILED on every stop.
                if (!running.get()) {
                    Log.i(TAG, "loop stopped (${t.javaClass.simpleName})")
                    return
                }
                lastError = t.toString().take(200)
                engineState = STATE_FAILED
                Log.e(TAG, "loop error: $t")
                try { client?.stop() } catch (_: Throwable) {}
                client = null
                if (++restarts > MAX_RESTARTS) {
                    lastError = "引擎反复启动失败，已退避 30 秒（NNUE 缺失？）"
                    Log.e(TAG, "engine keeps failing; backing off 30 s")
                    try {
                        Thread.sleep(30_000)
                    } catch (ie: InterruptedException) {
                        if (!running.get()) return
                    }
                    restarts = 0
                } else {
                    try {
                        Thread.sleep(1_000L * restarts)
                    } catch (ie: InterruptedException) {
                        if (!running.get()) return
                    }
                }
                if (running.get()) engineState = STATE_STARTING
            }
        }
    }
}
