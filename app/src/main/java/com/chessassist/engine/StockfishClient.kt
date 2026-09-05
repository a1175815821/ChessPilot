package com.chessassist.engine

import android.content.Context
import android.util.Log
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
    }

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private val lock = Any()
    private val running = AtomicBoolean(false)

    @Volatile var loadedNnue: String? = null
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
        send("setoption name Threads value ${defaultThreads()}")
        send("setoption name Hash value 64")
        send("setoption name MultiPV value 1")
        loadedNnue = "(embedded nn-b1a57edbea57.nnue)"
        send("isready")
        awaitLine("readyok", IDLE_TIMEOUT_MS)
    }

    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)

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
        val r: BufferedReader?
        synchronized(lock) { r = reader }
        if (r == null) return null
        send("position fen $fen")
        if (depth > 0) send("go depth $depth") else send("go movetime $movetimeMs")
        var best: BestMove? = null
        var lastScoreCp: Int? = null
        val budget = if (depth > 0) 180_000L else movetimeMs + IDLE_TIMEOUT_MS
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
                    line.startsWith("info") && line.contains(" score cp ") -> {
                        val cp = line.substringAfter(" score cp ").substringBefore(' ').toIntOrNull()
                        if (cp != null && line.contains(" pv ")) lastScoreCp = cp
                    }
                    line.startsWith("info") && line.contains(" score mate ") -> {
                        val mate = line.substringAfter(" score mate ").substringBefore(' ').toIntOrNull()
                        if (mate != null) lastScoreCp = mate * 10000
                    }
                    line.startsWith("bestmove") -> {
                        val uci = line.split(' ').getOrNull(1)
                        val ponder = line.split(" ponder ").getOrNull(1)?.trim()
                        best = uci?.let { BestMove(it, ponder, lastScoreCp) }
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
        try { p?.outputStream?.write("quit\n".toByteArray()); p?.outputStream?.flush() } catch (_: Throwable) {}
        try { r?.close() } catch (_: Throwable) {}
        try { p?.destroy() } catch (_: Throwable) {}
    }

    private fun send(cmd: String) {
        val p: Process?
        synchronized(lock) { p = process }
        val proc = p ?: throw IllegalStateException("engine not running")
        proc.outputStream.write((cmd + "\n").toByteArray())
        proc.outputStream.flush()
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

data class BestMove(val uci: String, val ponder: String?, val scoreCp: Int?)
