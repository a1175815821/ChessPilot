package com.chessassist.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.chessassist.BridgeSettings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UCI client over stdin/stdout of the packaged Stockfish executable
 * (`<nativeLibraryDir>/libstockfish.so`). Chosen over JNI linking because:
 *   - engine crash => process exit, app survives (stability requirement)
 *   - zero stdio hijacking, plain UCI protocol
 *   - identical behavior to desktop Stockfish, easy to debug with `adb shell`
 *
 * NNUE: both nets (big + small) are embedded into the binary at build time
 * (INCBIN, see CMakeLists.txt), so no external net files are needed on device.
 */
class StockfishClient(private val context: Context) {

    companion object {
        private const val TAG = "StockfishClient"
        private const val IDLE_TIMEOUT_MS = 15_000L
        /** Hard backstop for a ponder read whose stop signal got lost (about never). */
        private const val PONDER_MAX_MS = 180_000L
    }

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private val lock = Any()
    private val running = AtomicBoolean(false)

    /**
     * Serializes ALL socket writes (search commands, ponderhit, stop, quit).
     * The ponder flow has two threads (worker signalling, ponder thread
     * reading) that could otherwise interleave bytes mid-command. Single lock,
     * always leaf (never held while acquiring [lock]) — no lock ordering risk.
     */
    private val writeLock = Any()

    @Volatile var loadedNnue: String? = null
        private set

    /** Actual Threads/Hash sent at the last configure() — shown on the status screen. */
    @Volatile var lastThreads: Int = 0
        private set
    @Volatile var lastHashMb: Int = 0
        private set
    @Volatile var lastBerserk: Boolean = false
        private set

    /** False after stop()/failed start: the loop must restart, not reuse, this client. */
    fun isRunning(): Boolean = running.get() && synchronized(lock) { process != null }

    /** Start engine; throws on failure so the caller can restart-loop. */
    fun start() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        check(binary.exists()) { "stockfish binary missing at ${binary.absolutePath}" }
        synchronized(lock) {
            check(!running.get()) { "already running" }
            val pb = ProcessBuilder(binary.absolutePath)
                .redirectErrorStream(true)
            val p = pb.start()
            process = p
            reader = BufferedReader(InputStreamReader(p.inputStream))
            running.set(true)
        }
        // Handshake outside the lock: awaitLine spawns a reader thread, and holding
        // the lock here would block stop() for up to IDLE_TIMEOUT_MS (ANR on main).
        try {
            send("uci")
            check(awaitLine("uciok", IDLE_TIMEOUT_MS)) { "uciok timeout" }
            configure()
        } catch (t: Throwable) {
            // Don't leak the half-started process: unblock the handshake reader
            // thread and let the caller restart cleanly.
            try { stop() } catch (_: Throwable) {}
            throw t
        }
        Log.i(TAG, "engine up")
    }

    private fun configure() {
        // Nets are embedded at build time (see CMakeLists.txt), so no EvalFile /
        // EvalFileSmall setoptions are needed: SF loads the embedded defaults itself.
        // Skill/Contempt untouched: defaults are full strength (Skill 20, no limit).
        val cores = Runtime.getRuntime().availableProcessors()
        val memMb = totalMemMb()
        val b = BridgeSettings.berserk
        val threads = BridgeSettings.threadsFor(b, cores)
        val hashMb = BridgeSettings.hashMbFor(b, memMb)
        lastThreads = threads
        lastHashMb = hashMb
        lastBerserk = b
        Log.i(TAG, "engine config: mode=${if (b) "berserk" else "normal"} threads=$threads hash=${hashMb}MB (cores=$cores mem=${memMb}MB)")
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("setoption name MultiPV value 1")
        loadedNnue = "(embedded nn-1a298aa575a0.nnue)"
        send("isready")
        awaitLine("readyok", IDLE_TIMEOUT_MS)
    }

    /**
     * FEN sanity check: structure (6 fields, 8×8, legal tokens) AND material
     * counts (exactly one king each, ≤16 men and ≤8 pawns per side, no pawns on
     * the back ranks). SF 19 terminates the process on invalid input, and a
     * structurally-valid-but-illegal FEN (e.g. 9 pawns from a test harness typo —
     * caught live) would otherwise crash-loop through the whole backoff ladder.
     * Real hook positions are always legal; this is pure defense in depth.
     */
    private fun isSaneFen(fen: String): Boolean {
        return try {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.size != 6) return false
            val rows = parts[0].split('/')
            if (rows.size != 8) return false
            var whiteMen = 0
            var blackMen = 0
            var whitePawns = 0
            var blackPawns = 0
            var whiteKings = 0
            var blackKings = 0
            for ((ri, r) in rows.withIndex()) {
                var w = 0
                for (ch in r) {
                    when {
                        ch in '1'..'8' -> w += ch - '0'
                        ch == 'p' -> { w += 1; blackMen++; blackPawns++; if (ri == 0 || ri == 7) return false }
                        ch == 'n' || ch == 'b' || ch == 'r' || ch == 'q' -> { w += 1; blackMen++ }
                        ch == 'k' -> { w += 1; blackMen++; blackKings++ }
                        ch == 'P' -> { w += 1; whiteMen++; whitePawns++; if (ri == 0 || ri == 7) return false }
                        ch == 'N' || ch == 'B' || ch == 'R' || ch == 'Q' -> { w += 1; whiteMen++ }
                        ch == 'K' -> { w += 1; whiteMen++; whiteKings++ }
                        else -> return false
                    }
                }
                if (w != 8) return false
            }
            if (whiteKings != 1 || blackKings != 1) return false
            if (whiteMen > 16 || blackMen > 16) return false
            if (whitePawns > 8 || blackPawns > 8) return false
            if (parts[1] != "w" && parts[1] != "b") return false
            if (!parts[2].matches(Regex("-|[KQkq]+"))) return false
            if (parts[3] != "-" && !parts[3].matches(Regex("[a-h][36]"))) return false
            parts[4].toIntOrNull() ?: return false
            parts[5].toIntOrNull() ?: return false
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun totalMemMb(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024 * 1024)
        } catch (_: Throwable) {
            4096L // unknown: assume a small phone so berserk stays conservative
        }
    }

    /**
     * Analyze `fen`. With [depth] > 0 uses `go depth` (ignores [movetimeMs]);
     * otherwise `go movetime`. Returns BestMove(uci, ponder, scoreCp) or null.
     *
     * The blocking read loop intentionally does NOT hold [lock]: holding it would
     * block stop() (called on the main thread) for up to movetime+grace and ANR.
     * stop() destroys the process, which unblocks readLine (returns null) here.
     */
    fun analyse(fen: String, movetimeMs: Long, depth: Int = 0): BestMove? {
        if (!running.get()) return null
        // SF 19 validates strictly: a malformed FEN prints CRITICAL ERROR and
        // TERMINATES the process. A poisoned position would then crash-loop
        // (restart, same FEN, die) through the whole backoff ladder, so reject
        // junk here — the loop simply waits for the next position instead.
        if (!isSaneFen(fen)) {
            Log.w(TAG, "refusing malformed FEN: ${fen.take(80)}")
            return null
        }
        val r: BufferedReader?
        synchronized(lock) { r = reader }
        if (r == null) return null
        send("position fen $fen")
        if (depth > 0) send("go depth $depth") else send("go movetime $movetimeMs")
        var best: BestMove? = null
        var lastScoreCp: Int? = null
        // Final search depth (last `info depth` before bestmove): the engine-health
        // diagnostic shown on the banner/status screen. A healthy engine reaches
        // 20+ here at 1500ms+; single digits mean something is throttling it.
        // NOTE: the local counter must NOT shadow the `depth` parameter — the old
        // `var depth = 0` made the budget check below always take the movetime
        // branch, so `go depth` searches waited movetime+grace instead of 180 s.
        val wantDepth = depth
        var reached = 0
        val budget = if (wantDepth > 0) 180_000L else movetimeMs + IDLE_TIMEOUT_MS
        val deadline = System.currentTimeMillis() + budget
        // Watchdog: readLine() below blocks with NO timeout, so the deadline check in
        // the loop condition cannot fire while stuck inside it (hung search with no
        // output = frozen banner forever). The watchdog kills the process, which
        // unblocks the read; the caller then restarts the engine.
        val active = AtomicBoolean(true)
        val watchdog = Thread({
            try {
                Thread.sleep(budget)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (active.get()) {
                Log.w(TAG, "analyse watchdog: no bestmove in ${budget}ms, killing engine")
                try { stop() } catch (_: Throwable) {}
            }
        }, "sf-watchdog").apply { isDaemon = true; start() }
        try {
            while (running.get() && System.currentTimeMillis() < deadline) {
                val line = try {
                    r.readLine()
                } catch (_: Throwable) {
                    break
                } ?: break
                when {
                    line.startsWith("info depth ") -> {
                        line.substringAfter("info depth ").substringBefore(' ')
                            .toIntOrNull()?.let { if (it > reached) reached = it }
                        if (line.contains(" score cp ")) {
                            val cp = line.substringAfter(" score cp ").substringBefore(' ').toIntOrNull()
                            if (cp != null && line.contains(" pv ")) lastScoreCp = cp
                        } else if (line.contains(" score mate ")) {
                            val mate = line.substringAfter(" score mate ").substringBefore(' ').toIntOrNull()
                            if (mate != null) lastScoreCp = mate * 10000
                        }
                    }
                    line.startsWith("bestmove") -> {
                        val uci = line.split(' ').getOrNull(1)
                        val ponder = line.split(" ponder ").getOrNull(1)?.trim()
                        best = uci?.let { BestMove(it, ponder, lastScoreCp, reached) }
                        break
                    }
                }
            }
        } finally {
            active.set(false)
            watchdog.interrupt()
        }
        // Best-effort: after bestmove the engine is idle so "stop" is a no-op;
        // after a timeout it cancels the lingering search. Never throw from here.
        try { send("stop") } catch (_: Throwable) {}
        return best
    }

    fun stop() {
        // Set flag first so a concurrent analyse() loop can exit promptly.
        running.set(false)
        val p: Process?
        val r: BufferedReader?
        synchronized(lock) {
            p = process
            r = reader
            process = null
            reader = null
        }
        synchronized(writeLock) {
            try { p?.outputStream?.write("quit\n".toByteArray()); p?.outputStream?.flush() } catch (_: Throwable) {}
        }
        try { r?.close() } catch (_: Throwable) {}
        try { p?.destroy() } catch (_: Throwable) {}
    }

    private fun send(cmd: String) {
        val p: Process?
        synchronized(lock) { p = process }
        val proc = p ?: throw IllegalStateException("engine not running")
        synchronized(writeLock) {
            proc.outputStream.write((cmd + "\n").toByteArray())
            proc.outputStream.flush()
        }
        // Full-duplex trace: every command logged (commands are rare — a few per
        // move — while engine `info` output stays untraced). Invaluable when the
        // engine goes silent: distinguishes "never sent" from "sent, ignored".
        // `go` lines carry the search shape; positions are truncated (FENs are long).
        val short = if (cmd.startsWith("position ")) "position " + cmd.takeLast(20) else cmd.take(80)
        Log.d(TAG, "-> $short")
    }

    /**
     * Ponder the predicted opponent reply: search OUR next move from
     * (fen + ponderMove) until ponderhit/stop. Shares the UCI stream discipline
     * with analyse(); [stopFlag] is checked on every line so the owner can
     * abort promptly (it then pushes a readyok through to unblock this read).
     * No watchdog here — lifetime is stop-driven, bounded by PONDER_MAX_MS.
     */
    fun ponder(
        fen: String, ponderMove: String, movetimeMs: Long, depth: Int,
        stopFlag: AtomicBoolean
    ): BestMove? {
        if (!running.get()) return null
        // Same poison-FEN defense as analyse(): SF 19 terminates the process on
        // invalid input, and a dead ponder engine must not take the main one
        // down with it (separate processes now, but a crash-loop still wastes
        // the opponent's clock and spams the event log).
        if (!isSaneFen(fen)) {
            Log.w(TAG, "ponder refusing malformed FEN: ${fen.take(80)}")
            return null
        }
        val r: BufferedReader?
        synchronized(lock) { r = reader }
        if (r == null) return null
        try {
            send("position fen $fen moves $ponderMove")
        } catch (_: Throwable) {
            return null
        }
        try {
            if (depth > 0) send("go ponder depth $depth") else send("go ponder")
        } catch (_: Throwable) {
            return null
        }
        var best: BestMove? = null
        var lastScoreCp: Int? = null
        var pdepth = 0
        val deadline = System.currentTimeMillis() + PONDER_MAX_MS
        try {
            while (running.get() && !stopFlag.get() && System.currentTimeMillis() < deadline) {
                val line = try {
                    r.readLine()
                } catch (_: Throwable) {
                    break
                } ?: break
                when {
                    line.startsWith("info depth ") -> {
                        line.substringAfter("info depth ").substringBefore(' ')
                            .toIntOrNull()?.let { if (it > pdepth) pdepth = it }
                        if (line.contains(" score cp ")) {
                            val cp = line.substringAfter(" score cp ").substringBefore(' ').toIntOrNull()
                            if (cp != null && line.contains(" pv ")) lastScoreCp = cp
                        } else if (line.contains(" score mate ")) {
                            val mate = line.substringAfter(" score mate ").substringBefore(' ').toIntOrNull()
                            if (mate != null) lastScoreCp = mate * 10000
                        }
                    }
                    line.startsWith("bestmove") -> {
                        val uci = line.split(' ').getOrNull(1)
                        val ponder = line.split(" ponder ").getOrNull(1)?.trim()
                        best = uci?.let { BestMove(it, ponder, lastScoreCp, pdepth) }
                        break
                    }
                }
            }
        } finally {
            try { send("stop") } catch (_: Throwable) {}
        }
        return best
    }

    /** Signal ponderhit: the opponent played our predicted move. Best-effort. */
    fun signalPonderHit() {
        try { send("ponderhit") } catch (_: Throwable) {}
    }

    /**
     * Abort a ponder read from another thread: stop the search, then push an
     * isready whose readyok unblocks the reader (a bare stop leaves it parked
     * on readLine with an idle engine and no output coming).
     */
    fun stopPonder() {
        try { send("stop") } catch (_: Throwable) {}
        try { send("isready") } catch (_: Throwable) {}
    }

    private fun awaitLine(prefix: String, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var found = false
        val t = Thread {
            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "<- $line")
                    if (line.startsWith(prefix)) { found = true; break }
                }
            } catch (_: Throwable) {}
            latch.countDown()
        }
        t.isDaemon = true
        t.start()
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return found
    }
}

data class BestMove(val uci: String, val ponder: String?, val scoreCp: Int?, val depth: Int = 0)
