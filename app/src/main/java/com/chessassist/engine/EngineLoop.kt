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
    private var client: EngineClient? = null
    /**
     * Ponder's own engine process (P0 isolation): ponder search/ponderhit/stop
     * traffic NEVER touches [client]'s stdin/stdout. Lifecycle mirrors the
     * main client (lazy start on first ponder, dropped on stop/restart/kill).
     * Guarded by worker-thread confinement, like [client].
     */
    private var ponderClient: EngineClient? = null
    private var appContext: Context? = null
    private var worker: Thread? = null

    /**
     * Lifecycle generation (B4): start()/stop() run on the main thread, but an old
     * worker stuck in blocking I/O (readLine is not interruptible) can outlive
     * stop(). Without this, a quick stop→start lets the stale worker observe
     * running==true again and continue as a DUPLICATE loop — second engine process,
     * duplicate bestmove pushes, battery drain. Every loop/checkpoint verifies it
     * still owns the current generation.
     */
    @Volatile private var workerGen = 0L

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

    /** Final search depth of the last analysis (0 = unknown): engine-health readout. */
    @Volatile var lastDepth: Int = 0
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
    /** Move history for the pending position (ponder-hit matching). */
    @Volatile private var pendingMoves: List<String> = emptyList()
    private val pendingLock = Any()

    // ---------------------------------------------------------- ponder -----
    // Think on the opponent's time: while it's their turn, search our reply to
    // the engine's predicted move (BestMove.ponder from the last analysis). If
    // they play it, ponderhit returns the already-searched answer instantly;
    // anything else (or any failure) falls back to a normal search.
    //
    // ISOLATION (P0 fix for the wrong-move/freeze saga): ponder runs on a
    // DEDICATED engine process (ponderClient) with its own stdout reader. The
    // old design shared one BufferedReader between analyse() and ponder() with
    // no mutual exclusion — whichever thread woke first stole the line. A
    // ponder bestmove (computed for a position that never happened) landed in
    // analyse() and got auto-played as our move; conversely a stolen analyse
    // bestmove stalled the worker for movetime+grace per cycle (frozen banner).
    // With separate processes a stuck ponder can only waste one ponder — the
    // analysis path is unreachable to it by construction.
    //
    // All ponder reads still block, so the ponder search owns a second thread;
    // the worker only ever joins it with timeouts, and a thread that survives
    // the join gets its whole PROCESS killed (never left as a zombie reader).
    private const val PONDERHIT_TIMEOUT_MS = 3000L
    private const val PONDER_STOP_JOIN_MS = 1500L

    /** Engine's predicted opponent reply from the last analysis (null = none). */
    @Volatile private var lastPonderMove: String? = null
    /** In-flight ponder job (null = none). Guarded by worker-thread confinement. */
    @Volatile private var ponderThread: Thread? = null
    /** Single stop flag instance (set true to abort, false to arm). Never reassigned. */
    private val ponderStop = AtomicBoolean(false)
    @Volatile private var ponderFen: String? = null
    @Volatile private var ponderMove: String? = null
    @Volatile private var ponderGen = 0L
    @Volatile private var ponderResult: BestMove? = null
    @Volatile private var ponderResGen = -1L
    /**
     * Last (fen|prediction) pushed to the hook's red arrow. Change-gated so the
     * opponent-turn branch (every 200 ms) doesn't spam the bridge; null clears.
     */
    @Volatile private var lastPushedPonderKey: String? = null
    /** Last failed ponder-engine start: cooldown so a missing binary doesn't respawn every 200 ms. */
    @Volatile private var lastPonderStartFailAt: Long = 0
    private const val PONDER_START_RETRY_MS = 30_000L

    private val eventsLock = Any()
    private val events = ArrayDeque<String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        appContext = context.applicationContext
        engineState = STATE_STARTING
        lastError = null
        note("正在启动引擎…")
        val gen = ++workerGen
        worker = Thread({ runLoop(gen) }, "engine-loop").apply { start() }
    }

    fun stop() {
        running.set(false)
        // Bump FIRST: a stale worker must fail their next generation check even if
        // someone restarts the service before they finish unwinding.
        workerGen++
        note("正在停止…")
        ensurePonderStopped()
        try { ponderClient?.stop() } catch (_: Throwable) {}
        ponderClient = null
        try { client?.stop() } catch (_: Throwable) {}
        client = null
        try { worker?.interrupt() } catch (_: Throwable) {}
        worker = null
        engineState = STATE_STOPPED
    }

    /**
     * Ponder helpers. Everything here runs on the worker thread EXCEPT
     * ensurePonderStopped(), which stop() also calls from main (signal only,
     * no join — main must never block on a socket reader).
     */

    /** True while a ponder search thread is actually running. */
    private fun isPonderLive(): Boolean {
        return try {
            ponderThread?.isAlive == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Clear the in-flight ponder job identity + any unconsumed result.
     * Deliberately keeps [ponderGen] monotonic and [lastPonderMove] (the
     * prediction for the NEXT ponder round) — those are not job state.
     */
    private fun clearPonderState() {
        ponderFen = null
        ponderMove = null
        ponderResult = null
        ponderResGen = -1L
    }

    /** Abort any in-flight ponder (idempotent, fast). */
    private fun ensurePonderStopped() {
        val t = ponderThread
        ponderThread = null
        clearPonderState()
        if (t == null) return
        // FIRST: tell the reader loop to exit on its next line. WITHOUT this the
        // thread parks in readLine forever on its (now dead-end) reader.
        ponderStop.set(true)
        if (!t.isAlive) return
        // Signal the PONDER process only — the main client must never see a
        // ponder stop/isready pair (cross-talk on a shared stream was the old
        // wrong-move vector; separate processes make it impossible).
        try { ponderClient?.stopPonder() } catch (_: Throwable) {}
        // Best-effort join: the stop+isready pair pushes a readyok line through
        // the ponder reader, which then sees the flag and exits.
        if (Thread.currentThread() !== t) {
            try { t.join(PONDER_STOP_JOIN_MS) } catch (_: Throwable) {}
            if (t.isAlive) {
                // P0: NEVER leave a zombie reader behind (the old code did —
                // "leaving it (daemon)" — and the zombie then stole bestmove
                // lines from analyse(), the exact wrong-move/freeze mechanism).
                // Killing the ponder process unblocks its reader (EOF/throw)
                // AND guarantees no stale ponder output can ever surface.
                // Main analysis continues untouched.
                Log.w(TAG, "ponder thread parked after stop; killing ponder engine")
                note("极限思考线程卡住，已丢弃重建（对局不受影响）")
                try { ponderClient?.stop() } catch (_: Throwable) {}
                ponderClient = null
            }
        }
    }

    /** UCI move match for ponder-hit: 4-char stem + promotion piece when present. */
    private fun matchUci(historyMove: String?, predicted: String): Boolean {
        if (historyMove == null) return false
        if (historyMove.take(4) != predicted.take(4)) return false
        if (predicted.length >= 5) {
            val hp = historyMove.getOrNull(4) ?: return false
            if (hp.lowercaseChar() != predicted[4].lowercaseChar()) return false
        }
        return true
    }

    /**
     * Push the red prediction arrow (or retire it) on change only. The bridge
     * is latest-only, so a dropped push just means "same as displayed" — the
     * hook repaints from its stored pair on every render anyway.
     */
    private fun pushPonderGuessOrClear(fen: String, pred: String?) {
        val key = pred?.let { "$fen|$it" }
        if (key == lastPushedPonderKey) return
        lastPushedPonderKey = key
        if (key != null) BridgeServer.pushPonderGuess(pred!!, fen)
        else BridgeServer.pushClearPonder()
    }

    /**
     * Opponent's turn: start/keep pondering our reply to the predicted move.
     * Per-fen gated (a ponder job is one position); disabling the switch or a
     * missing prediction stops any running job. Never throws.
     */
    /**
     * @param gen the worker generation that called us; the ponder-engine
     *   handshake blocks for seconds, and stop() (or a newer worker) may run
     *   mid-handshake — creating the process without this check orphans it
     *   (nobody ever stops it again). Mirrors the main-client guard in runLoop.
     */
    private fun maybePonder(fen: String, gen: Long) {
        try {
            if (!BridgeSettings.ponderEnabled) {
                ensurePonderStopped()
                pushPonderGuessOrClear(fen, null)
                return
            }
            val r = lastPonderMove
            if (r == null) {
                ensurePonderStopped()
                pushPonderGuessOrClear(fen, null)
                return
            }
            // Push the guess FIRST, before the (possibly slow: 1-3 s first-time
            // engine handshake) ponder job spins up. The prediction is known
            // already; the red arrow must not wait for the search. Key-gated,
            // so this costs one message per (fen, prediction).
            pushPonderGuessOrClear(fen, r)
            val t = ponderThread
            if (fen == ponderFen && t != null && t.isAlive) return // already on it
            ensurePonderStopped()
            // P0 isolation: ponder gets its OWN engine (process or remote
            // connection). A ponder-engine failure only costs one ponder
            // round — the main client (and the analysis path) is never
            // touched here.
            var pc = ponderClient
            if (pc == null || !pc.isRunning()) {
                if (System.currentTimeMillis() - lastPonderStartFailAt < PONDER_START_RETRY_MS) return
                pc = createPonderClient(gen) ?: return
                ponderClient = pc
            }
            val c = pc
            val gen = ++ponderGen
            ponderResult = null
            ponderResGen = -1L
            ponderFen = fen
            ponderMove = r
            ponderStop.set(false)
            val movetime = BridgeSettings.movetimeMs
            val depth = BridgeSettings.searchDepth
            Log.i(TAG, "ponder start (opp to move, our reply to $r)")
            ponderThread = Thread({
                try {
                    val res = c.ponder(fen, r, movetime, depth, ponderStop)
                    if (res != null) {
                        ponderResult = res
                        ponderResGen = gen
                        Log.i(TAG, "ponder finished: ${res.uci} (depth ${res.depth})")
                    } else {
                        Log.i(TAG, "ponder thread exit without result (stopped/deadline/eof)")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ponder thread died: ${t.message?.take(80)}")
                }
            }, "ponder").apply { isDaemon = true; start() }
        } catch (_: Throwable) {
        }
    }

    /**
     * Our turn: if the opponent played the predicted move and the ponder result
     * is ready, take it (no new search). Returns the result to publish, or null
     * to fall through to a normal analyse. Never throws; always leaves no live
     * ponder thread behind on the null path.
     */
    private fun tryConsumePonder(fen: String, moves: List<String>): BestMove? {
        try {
            val t = ponderThread ?: return null
            val r = ponderMove
            if (r == null) {
                ensurePonderStopped()
                return null
            }
            if (!t.isAlive && ponderResGen != ponderGen) {
                // Died with nothing (engine crash mid-ponder): nothing to take.
                ensurePonderStopped()
                return null
            }
            if (!matchUci(moves.lastOrNull(), r)) {
                Log.i(TAG, "ponder miss (opp played ${moves.lastOrNull()}, expected $r)")
                ensurePonderStopped()
                return null
            }
            // Ponder-hit goes to the PONDER process only. The main client keeps
            // whatever state it has (usually idle) — a hit must never disturb
            // an analysis or force a main-engine restart.
            val cc = ponderClient
            if (cc == null || !cc.isRunning()) {
                // Ponder engine died/was dropped mid-ponder: nothing to hit, search fresh.
                ensurePonderStopped()
                return null
            }
            Log.i(TAG, "ponderhit! ($r)")
            try { cc.signalPonderHit() } catch (_: Throwable) {}
            try { t.join(PONDERHIT_TIMEOUT_MS) } catch (_: Throwable) {}
            val hung = t.isAlive
            Log.i(TAG, "ponderhit join done (threadAlive=$hung resGen=$ponderResGen gen=$ponderGen)")
            val res = if (ponderResGen == ponderGen) ponderResult else null
            ensurePonderStopped()
            if (res == null) {
                // Healthy ponderhit answers in milliseconds (verified against the
                // bare binary). Silence past the join means the engine is wedged
                // in ponder state — never trust it for the fallback search: drop
                // it so the loop recreates fresh. (Seen live: thread parked,
                // engine idle-but-mute, all later commands swallowed.)
                if (hung) {
                    // P0: only the PONDER engine dies here. The old code dropped
                    // the MAIN client — a ponder incident restarted analysis too.
                    Log.w(TAG, "ponderhit hung ponder engine; dropping it (main engine untouched)")
                    try { ponderClient?.stop() } catch (_: Throwable) {}
                    ponderClient = null
                } else {
                    Log.w(TAG, "ponderhit produced nothing, falling back to search")
                }
                return null
            }
            // A newer our-turn position may have arrived during the join: never
            // publish a result against a fen it wasn't verified for.
            val fresh = synchronized(pendingLock) { pendingFen }
            if (fresh != null && fresh != fen) {
                Log.i(TAG, "ponder result stale (newer position), re-analysing")
                return null
            }
            return res
        } catch (_: Throwable) {
            return null
        }
    }

    // ------------------------------------------------- backend policy -----
    // Auto backend (remote preferred, local fallback): remote is tried first;
    // ANY failure sticks to local until the next game (remoteDownSticky) with
    // a 15 s retry floor (remoteCoolUntil) so a dead PC doesn't hammer
    // reconnects every loop iteration. A fresh game re-prefers remote.
    private const val REMOTE_RETRY_MS = 15_000L
    @Volatile private var remoteDownSticky: Boolean = false
    @Volatile private var remoteCoolUntil: Long = 0L

    /** Which engine actually served the last analysis (status screen). */
    @Volatile private var lastBackendUsed: String = "本地"
        private set

    private fun useRemote(): Boolean =
        BridgeSettings.backend != BridgeSettings.BACKEND_LOCAL &&
            !remoteDownSticky &&
            System.currentTimeMillis() >= remoteCoolUntil

    /** Remote failed: stick to local, note once (per game) why. */
    private fun markRemoteDown(cause: String?) {
        remoteCoolUntil = System.currentTimeMillis() + REMOTE_RETRY_MS
        if (!remoteDownSticky) {
            remoteDownSticky = true
            lastBackendUsed = "本地"
            val why = cause?.take(100) ?: "连接中断"
            note("远程算力不可用，已切本地：$why（下局重连）")
            Log.w(TAG, "remote down, local fallback: $why")
        } else {
            lastBackendUsed = "本地"
        }
    }

    /**
     * Main engine factory (worker thread): remote first when wanted, local
     * fallback that never throws out of here for remote causes (a throwing
     * local start still propagates to the loop's restart machinery).
     */
    private fun createMainClient(): EngineClient {
        if (useRemote()) {
            try {
                val rc = RemoteEngineClient().also { it.start() }
                lastBackendUsed = "远程"
                note("远程算力已连接（${BridgeSettings.pcHost}:${BridgeSettings.pcPort}，" +
                    "${rc.loadedNnue ?: ""}）")
                return rc
            } catch (e: Throwable) {
                markRemoteDown(e.message)
            }
        }
        lastBackendUsed = "本地"
        return StockfishClient(appContext!!).also { it.start() }
    }

    /**
     * Ponder engine factory (worker thread): separate connection when remote
     * (reader discipline: one reader per socket, same as separate processes
     * locally), local dedicated process as fallback. Null = skip this round.
     */
    private fun createPonderClient(gen: Long): EngineClient? {
        if (useRemote()) {
            try {
                val rc = RemoteEngineClient().also { it.start() }
                if (!running.get() || gen != workerGen) {
                    try { rc.stop() } catch (_: Throwable) {}
                    return null
                }
                Log.i(TAG, "remote ponder up")
                return rc
            } catch (e: Throwable) {
                markRemoteDown(e.message)
                // fall through to the local ponder engine below
            }
        }
        return try {
            val lc = StockfishClient(appContext!!).also { it.start() }
            // Same orphan guard as the old inline code (v0.2 Bug 2).
            if (!running.get() || gen != workerGen) {
                try { lc.stop() } catch (_: Throwable) {}
                return null
            }
            Log.i(TAG, "ponder engine up (dedicated process)")
            lc
        } catch (e: Throwable) {
            lastPonderStartFailAt = System.currentTimeMillis()
            Log.w(TAG, "ponder engine start failed: ${e.message?.take(80)}")
            null
        }
    }

    /** One-line backend state for the status screen. */
    fun backendLine(): String {
        val want = BridgeSettings.backendShort()
        return if (want == "本地") "本地引擎"
        else "$want（当前$lastBackendUsed）"
    }

    /**
     * Test the PC connection (call on a background thread): connects, runs
     * the info+ping handshake, reports engine name + RTT. Never throws.
     */
    fun testRemote(): String {
        if (BridgeSettings.pcHost.trim().isEmpty()) return "先填电脑IP（高级设置→算力后端）"
        return try {
            val rc = RemoteEngineClient().also { it.start() }
            val msg = "远程可用：${rc.loadedNnue ?: "?"} · " +
                "${rc.lastThreads}线 ${rc.lastHashMb}MB · RTT ${rc.lastRttMs}ms"
            try { rc.stop() } catch (_: Throwable) {}
            note("测试远程连接：$msg")
            msg
        } catch (e: Throwable) {
            val msg = "远程不通：${e.message?.take(120)}"
            note("测试远程连接：$msg")
            msg
        }
    }

    /**
     * Apply changed engine settings (e.g. berserk toggle) live: drop the client
     * and let the same-generation loop recreate it on the next iteration —
     * no duplicate worker/engine (B4), 1~2 s gap, game untouched. No-op while
     * stopped (settings take effect on the next start instead).
     */
    fun restartEngine(reason: String) {
        if (!running.get()) return
        note("引擎设置变更（$reason），重启引擎…")
        // Settings changed: lift the remote quarantine too — a fresh attempt
        // is cheap and this is exactly when the user changed something.
        remoteDownSticky = false
        remoteCoolUntil = 0L
        // Ponder process FIRST: stop() destroys it, which unblocks a parked
        // ponder reader instantly — the join inside ensurePonderStopped() then
        // returns fast even though this runs on the main thread (settings UI).
        // Also clears any pre-restart ponder result so it can never "hit" on
        // the fresh engine (stale-hit wrong-move vector, now closed).
        try { ponderClient?.stop() } catch (_: Throwable) {}
        ponderClient = null
        ensurePonderStopped()
        try { client?.stop() } catch (_: Throwable) {}
        client = null
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
            pendingMoves = moves
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
     * Shown DIRECTLY on the floating overlay (title = the plea itself) — there
     * is no separate help-banner state: help only ever fires on the user's turn
     * with a static board, where neither clear() (opponent's turn only) nor the
     * stale annotator (silent on the user's turn) can overwrite it. The next
     * fresh analysis replaces it naturally. Event log kept for diagnostics.
     */
    fun onNeedHelp(code: String, uci: String) {
        if (code == "promotion") {
            note("升变请手动选皇后：$uci")
            OverlayManager.showStatus("升变请手动选皇后", uci)
        } else {
            note("请手动走棋：$uci")
            OverlayManager.showStatus("请手动走棋", uci)
        }
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

    /**
     * One-line ponder state for the status screen. Ponder used to be a black box
     * here, which is exactly why the wrong-move/freeze saga took so long to
     * localize — never again: any future ponder incident shows its face here.
     */
    fun ponderLine(): String {
        if (!BridgeSettings.ponderEnabled) return "关"
        val t = ponderThread
        return when {
            t != null && t.isAlive -> "ponder中（预测${ponderMove ?: lastPonderMove ?: "?"}）"
            ponderClient?.isRunning() == true -> "待机（引擎在线）"
            else -> "开·引擎未启动"
        }
    }

    /** Engine resource line for the status screen: mode + actual threads/hash + depth. */
    private fun engineResLine(c: EngineClient?): String {
        val res = if (c != null && c.lastThreads > 0) {
            "${c.lastThreads}线 · ${c.lastHashMb}MB"
        } else "—"
        val depth = if (lastDepth > 0) " · 深度$lastDepth" else ""
        return "${BridgeSettings.modeShort()} · $res$depth"
    }

    /**
     * Publish one analysed bestmove: shared by the normal search and the
     * ponder-hit fast path (which skips searching entirely). Also stores the
     * engine's predicted reply ([BestMove.ponder]) for the next ponder round.
     * Caller owns generation checks around this (see loop).
     */
    private fun publishBest(fen: String, stm: String?, user: String?, best: BestMove, ponderHit: Boolean) {
        // NOTE: the loop-local lastAnalysedFen is set by the caller (it shadows
        // nothing here — this function cannot see runLoop's locals).
        // Terminal position (side to move has no legal moves): `bestmove (none)`
        // is game-over, NOT a move. Never push/draw/autoplay it (it used to
        // surface as "推荐：(none)" + 50% win rate, trip the shallow-depth
        // tripwire at depth 0, and — length 6 — detonate the promotion plea).
        // Mate 0 vs stalemate rides BestMove.isMate (both encode scoreCp 0).
        if (best.uci == "(none)") {
            analysedFen = fen
            lastPushedMove = null
            lastPushedFen = null
            lastBestMove = null
            lastBestMoveAt = 0
            lastScoreCp = null
            lastScoreStm = null
            lastScoreUser = null
            lastDepth = 0
            lastPonderMove = null
            lastError = null
            val msg = if (best.isMate) "被将死，对方M0" else "无合法着法（逼和或终局）"
            note("终局：$msg")
            OverlayManager.showStatus("对局结束", msg)
            BridgeServer.pushClear()
            return
        }
        analysedFen = fen
        lastPushedMove = best.uci
        lastPushedFen = fen
        lastBestMove = best.uci
        lastBestMoveAt = System.currentTimeMillis()
        lastScoreCp = best.scoreCp
        lastScoreStm = stm
        lastScoreUser = user
        lastDepth = best.depth
        lastPonderMove = best.ponder
        // A fresh result clears stale failure text (e.g. an old "Stream closed"
        // lingering on the status card long after recovery confuses everyone).
        lastError = null
        val wr = ChessMath.formatWinRate(best.scoreCp, stm, user)
        val ponderNote = if (best.ponder != null) "，ponder ${best.ponder}" else ""
        note("最佳招法 ${best.uci}（深度${best.depth}，cp=${best.scoreCp}，$wr$ponderNote）")
        if (ponderHit) {
            note("ponder 命中：对手走了预测招，即时应招")
        }
        // One combined command: the hook draws AND (if enabled) auto-plays it.
        BridgeServer.pushBestmove(
            best.uci, fen,
            BridgeSettings.autoPlayEnabled, BridgeSettings.autoPlayDelayMs
        )
        OverlayManager.showBestMove(
            best.uci, fen, best.scoreCp, BridgeSettings.autoPlayEnabled, user,
            best.depth
        )
    }

    /**
     * Re-render the cached bestmove under corrected colors (Bug 4 fix): the
     * engine result is still valid, only the perspective changed — no new
     * search, just re-derive the display triple and repaint the banner.
     * No-op when there is no cached result for this fen (e.g. terminal).
     */
    private fun refreshPerspective(fen: String, user: String?) {
        val m = lastPushedMove
        val f = lastPushedFen
        if (m == null || f == null || f != fen || f != analysedFen) return
        lastScoreUser = user
        lastError = null
        OverlayManager.showBestMove(
            m, fen, lastScoreCp, BridgeSettings.autoPlayEnabled, user, lastDepth
        )
        note("颜色修正：胜率视角已更新")
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
        if (now - lastStaleBannerAt > 30_000) {
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

    /** Structured status for the control screen (single source of truth). */
    data class StatusSnapshot(
        val engineLabel: String,
        val engineColor: Int,
        val hookLine: String,
        val nnue: String,
        val engineResLine: String,
        val ponderLine: String,
        val backendLine: String,
        val fen: String,
        val bestMove: String?,
        val bestAgo: String,
        val winText: String,
        val winRate01: Double?,
        val settingsLine: String,
        val autoLine: String,
        val heartbeatAgo: String,
        val staleMinOrNull: Long?,
        val oursTurnWaiting: Boolean,
        val error: String?,
    )

    fun snapshot(): StatusSnapshot {
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
        val engineColor = when (engineState) {
            STATE_RUNNING -> ChessMath.C_WIN
            STATE_STARTING -> ChessMath.C_MID
            STATE_FAILED -> ChessMath.C_LOSE
            else -> ChessMath.C_DIM
        }
        return StatusSnapshot(
            engineLabel = engineLabel,
            engineColor = engineColor,
            hookLine = hookState,
            nnue = c?.loadedNnue ?: "-",
            engineResLine = engineResLine(c),
            ponderLine = ponderLine(),
            backendLine = backendLine(),
            fen = currentFen ?: "-",
            bestMove = lastBestMove,
            bestAgo = if (lastBestMove != null) ago(lastBestMoveAt) else "",
            winText = if (lastBestMove != null) {
                ChessMath.formatWinRate(lastScoreCp, lastScoreStm, lastScoreUser)
            } else "",
            winRate01 = if (lastBestMove != null) {
                ChessMath.userWinRate01(lastScoreCp, lastScoreStm, lastScoreUser)
            } else null,
            settingsLine = BridgeSettings.describe(),
            autoLine = if (BridgeSettings.autoPlayEnabled) {
                "开（${BridgeSettings.autoPlayDelayMs}ms）"
            } else "关",
            heartbeatAgo = ago(lastLoopAt),
            staleMinOrNull = staleMinutes().let { if (it >= 0) it else null },
            oursTurnWaiting = (staleMinutes() >= 0) &&
                lastTurnColor != null && lastTurnColor == lastUserColor,
            error = lastError,
        )
    }

    /** Last (move, fen) pair pushed to the hook — the only pair play may replay. */
    @Volatile private var lastPushedMove: String? = null
    @Volatile private var lastPushedFen: String? = null

    /** Mirror of the loop-local lastAnalysedFen for cross-thread freshness checks. */
    @Volatile private var analysedFen: String? = null
        private set

    /**
     * Semi-auto entry: play the latest pushed bestmove on user tap (banner button).
     * Same freshness rules as autoplay — never plays a stale move.
     */
    fun playPendingBestmove() {
        val m = lastPushedMove
        val f = lastPushedFen
        if (m == null || f == null || f != analysedFen) {
            note("点走棋：当前无可走招法")
            return
        }
        BridgeServer.pushPlay(m, f, BridgeSettings.autoPlayDelayMs)
        note("手动走棋已发送：$m")
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
            appendLine("引擎配置：${engineResLine(c)}")
            appendLine("算力后端：${backendLine()}")
            appendLine("极限思考：${ponderLine()}")
            appendLine("当前 FEN：${currentFen ?: "-"}")
            appendLine("最佳招法：${lastBestMove ?: "-"}${if (lastBestMove != null) "（${ago(lastBestMoveAt)}${if (lastDepth > 0) " · 深度$lastDepth" else ""}）" else ""}")
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

    /** Exact initial position: consecutive lessons often restart here. */
    private const val STARTPOS_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun runLoop(gen: Long) {
        var restarts = 0
        var lastAnalysedFen: String? = null
        // Colors the last analysis was published under (Bug 4: same-fen color
        // corrections refresh the display instead of re-searching).
        var lastAnalysedUser: String? = null
        var lastAnalysedTurn: String? = null
        var prevFen: String? = null
        var lastSkippedFen: String? = null
        fun isStale(): Boolean = gen != workerGen
        while (running.get() && !isStale()) {
            lastLoopAt = System.currentTimeMillis()
            try {
                // Local snapshot: stop() (main thread) nulls `client` and destroys
                // the process concurrently — `client!!` here raced that and threw
                // a misleading NPE that flashed STATE_FAILED on every normal stop.
                // isRunning() also covers the watchdog kill (stopped but non-null):
                // without it the loop would spin analyse()->null forever.
                var c = client
                if (c == null || !c.isRunning()) {
                    if (isStale()) return
                    if (c != null) {
                        try { c.stop() } catch (_: Throwable) {}
                        client = null
                    }
                    engineState = STATE_STARTING
                    c = createMainClient()
                    // Lost the race with a newer generation while starting (handshake
                    // takes seconds): shut our engine down, don't clobber its client.
                    if (isStale()) {
                        try { c.stop() } catch (_: Throwable) {}
                        return
                    }
                    client = c
                    restarts = 0
                    engineState = STATE_RUNNING
                    note("引擎已启动，等待对局局面…")
                    OverlayManager.showStatus("ChessPilot", "等待对局…")
                }
                val (fen, user, turn) = synchronized(pendingLock) {
                    Triple(pendingFen, pendingUserColor, pendingTurnColor)
                }
                // Volatile snapshot: onPosition replaces the whole list atomically.
                val moves: List<String> = pendingMoves
                if (fen == null) {
                    maybeNoteStale()
                    Thread.sleep(60)
                    continue
                }
                // Game boundary BEFORE the idle check: a fresh startpos after any
                // other position means a NEW game. The idle check below would match
                // lastAnalysedFen from the previous game and the white opening would
                // be silently ignored forever (same class of stuck as cross-game
                // userColor, but engine-side and therefore never self-healing).
                // (Caught by the back-to-back startpos test, not by review.)
                if (fen == STARTPOS_FEN && prevFen != STARTPOS_FEN) {
                    lastAnalysedFen = null
                    lastAnalysedUser = null
                    lastAnalysedTurn = null
                    analysedFen = null
                    lastBestMove = null
                    lastBestMoveAt = 0
                    lastScoreCp = null
                    lastScoreStm = null
                    lastScoreUser = null
                    lastDepth = 0
                    lastSkippedFen = null
                    lastPonderMove = null
                    ensurePonderStopped()
                    // New game lifts the remote quarantine: if the previous
                    // game fell back to local, retry remote now. Drop local
                    // clients so the loop recreates through the policy.
                    remoteDownSticky = false
                    remoteCoolUntil = 0L
                    if (BridgeSettings.backend != BridgeSettings.BACKEND_LOCAL) {
                        if (client is StockfishClient) {
                            try { client?.stop() } catch (_: Throwable) {}
                            client = null
                        }
                        if (ponderClient is StockfishClient) {
                            try { ponderClient?.stop() } catch (_: Throwable) {}
                            ponderClient = null
                        }
                    }
                    note("新开局，已清空上局残留")
                }
                prevFen = fen
                if (fen == lastAnalysedFen) {
                    // Same-FEN color correction: the hook republishes an
                    // unchanged position when lagging turn/user fields catch
                    // up. Perspective is pure display — refresh it from the
                    // cached result instead of burning a re-search.
                    if (user != lastAnalysedUser || turn != lastAnalysedTurn) {
                        val perspectiveChanged =
                            user?.lowercase() != lastAnalysedUser?.lowercase()
                        lastAnalysedUser = user
                        lastAnalysedTurn = turn
                        if (perspectiveChanged) refreshPerspective(fen, user)
                    }
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
                // Deliberately NOT marking lastAnalysedFen here: a skip decided on
                // lagging turn/user fields must be re-evaluated when corrected values
                // arrive (same fen). Marking it poisoned the position forever.
                if (!userIsToMove && user != null) {
                    // Opponent's clock is free thinking time when ponder is on.
                    // Start the job BEFORE painting so the overlay suffix below
                    // reflects the live job, not last iteration's state.
                    maybePonder(fen, gen)
                    if (fen != lastSkippedFen) {
                        lastSkippedFen = fen
                        // Turn flipped to the opponent: retire OUR yellow arrow
                        // from the board NOW. Otherwise it lingers through fast
                        // opponent turns (loop never sees a settled position,
                        // ponder never engages) and reads as a "yellow
                        // prediction" — the exact confusion reported live.
                        // Text conclusion survives in the overlay; the board
                        // goes blank until the red prediction lands (or our
                        // next analysis repaints yellow). Once per fen.
                        BridgeServer.pushClear()
                        // Mirror the ponder state on the floating overlay, only
                        // while it's the opponent's turn: a live job appends
                        // 极限思考中 to the waiting line. Title untouched.
                        val conclusion = lastConclusionLine()
                        // Show WHAT we're pondering, on its OWN second line: the
                        // predicted enemy move (e.g. 极限思考（预测e7e5）).
                        // ponderMove is the live job's prediction; fall back to
                        // the last known prediction while a job spins up.
                        val guess = (ponderMove ?: lastPonderMove)?.let { "极限思考（预测$it）" }
                            ?: "极限思考中"
                        val detail = if (isPonderLive()) {
                            if (conclusion.isEmpty()) guess
                            else "$conclusion\n$guess"
                        } else conclusion
                        OverlayManager.clear("等待对手走棋…", detail)
                    }
                    // Reachable even when positions keep arriving: a perpetual
                    // opponent-turn (bot stuck, lesson fetching) must still age
                    // into the stale annotation instead of looking like waiting.
                    maybeNoteStale()
                    Thread.sleep(200)
                    continue
                }

                // Ponder hit? The opponent played our predicted reply and the
                // answer is already searched — publish it instead of searching.
                // Anything unconsumed (miss/stale/failure) stops the job so the
                // normal search below owns the engine alone.
                val pondered = tryConsumePonder(fen, moves)
                if (pondered != null) {
                    lastLoopAt = System.currentTimeMillis()
                    if (isStale()) return
                    lastAnalysedFen = fen
                    lastAnalysedUser = user
                    lastAnalysedTurn = turn
                    publishBest(fen, stm, user, pondered, ponderHit = true)
                    continue
                }
                // tryConsume may have taken seconds (ponderhit join) and may even
                // have dropped a hung engine: re-read everything — a newer
                // our-turn position must not be skipped for this stale snapshot,
                // and a dropped client must recreate instead of being analysed on.
                val freshFen = synchronized(pendingLock) { pendingFen }
                if (freshFen != null && freshFen != fen) continue
                c = client
                if (c == null || !c.isRunning()) {
                    // Remote died mid-game (timeout streak / broken socket):
                    // quarantine it so the recreate above falls back to local
                    // instead of hammering reconnects every iteration.
                    if (c is RemoteEngineClient) markRemoteDown("连接中断")
                    try { c?.stop() } catch (_: Throwable) {}
                    client = null
                    continue
                }

                OverlayManager.showThinking(BridgeSettings.autoPlayEnabled)
                val best = c.analyse(fen, BridgeSettings.movetimeMs, BridgeSettings.searchDepth)
                lastLoopAt = System.currentTimeMillis()
                // Stale generation waking from a blocking analyse: never publish —
                // the current worker already owns this position (or a newer one).
                if (isStale()) return
                if (best != null) {
                    lastAnalysedFen = fen
                    lastAnalysedUser = user
                    lastAnalysedTurn = turn
                    // Tripwire for stream cross-talk (the ponder saga class of bug):
                    // a bestmove from a search murdered at depth<=1 with a real
                    // time budget means something else talked to the engine mid-
                    // search (a racing stop/info leak). Loud in events, so the
                    // next incident localizes itself instead of showing up as a
                    // "mystery depth-1 + flipped winrate" banner.
                    // Terminal (none) reports depth 0 by construction — exempt, it
                    // has its own game-over path in publishBest, not this alarm.
                    if (best.uci != "(none)" && best.depth <= 1 && BridgeSettings.movetimeMs >= 500) {
                        Log.w(TAG, "suspicious shallow result: depth=${best.depth} " +
                            "for ${BridgeSettings.movetimeMs}ms budget (uci=${best.uci})")
                        note("分析深度异常：${BridgeSettings.movetimeMs}ms 只搜到深度${best.depth}（${best.uci}），结果仍采用")
                    }
                    publishBest(fen, stm, user, best, ponderHit = false)
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
                // Same for a superseded generation after a quick stop→start.
                if (!running.get() || isStale()) {
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
                        if (!running.get() || isStale()) return
                    }
                    restarts = 0
                } else {
                    try {
                        Thread.sleep(1_000L * restarts)
                    } catch (ie: InterruptedException) {
                        if (!running.get() || isStale()) return
                    }
                }
                if (running.get()) engineState = STATE_STARTING
            }
        }
    }
}
