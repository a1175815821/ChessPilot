package com.chessassist.engine

import android.util.Log
import com.chessassist.BridgeSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * PC remote engine over LAN TCP (see I:\chessai-pc\pc_engine.py).
 *
 * Mirrors [StockfishClient]'s stream discipline EXACTLY, because EngineLoop
 * depends on it: at most one reader at a time (analyse XOR ponder),
 * signal/stop calls only ever WRITE, and a hit-path result flows back through
 * the ponder reader thread (signalPonderHit itself reads nothing — two
 * concurrent readers on one socket was the original ponder saga; never again).
 *
 * Think shape (movetime/depth) rides in each request and the server honors
 * it — the phone's REMOTE sliders drive the PC engine; the script's
 * --movetime/--depth are only defaults for clients that omit them. Threads/
 * hash stay server-side (they need a process restart). Timeouts size off the
 * largest of request/server/depth-lane budgets.
 *
 * No auto-reconnect here by design: fail fast (dead flag), EngineLoop's auto
 * backend owns the fallback-to-local + retry strategy.
 */
class RemoteEngineClient : EngineClient {

    companion object {
        private const val TAG = "RemoteEngine"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val ANALYSE_GRACE_MS = 12_000L
        private const val MAX_CONSEC_TIMEOUTS = 2
        private const val PONDER_MAX_MS = 180_000L
        private const val PONDER_POLL_MS = 200
    }

    @Volatile private var sock: Socket? = null
    @Volatile private var reader: BufferedReader? = null
    private val writeLock = Any()
    private val dead = AtomicBoolean(false)
    private var reqId = 0
    private var consecTimeouts = 0

    @Volatile override var loadedNnue: String? = null
        private set
    @Volatile override var lastThreads: Int = 0
        private set
    @Volatile override var lastHashMb: Int = 0
        private set
    @Volatile override var lastBerserk: Boolean = false
        private set

    /** Server movetime (authoritative) + last measured RTT, for status/UI. */
    @Volatile var serverMovetimeMs: Long = 3000L
        private set
    @Volatile var lastRttMs: Long = -1L
        private set

    override fun isRunning(): Boolean =
        !dead.get() && sock?.let { it.isConnected && !it.isClosed } == true

    private fun markDead() {
        if (dead.compareAndSet(false, true)) {
            Log.w(TAG, "connection marked dead")
        }
        try { sock?.close() } catch (_: Throwable) {}
    }

    /** Connect + info/ping handshake; throws on failure (caller falls back). */
    override fun start() {
        val host = BridgeSettings.pcHost.trim()
        require(host.isNotEmpty()) { "未设置电脑IP（高级设置→算力后端）" }
        val port = BridgeSettings.pcPort
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = PONDER_POLL_MS
            sock = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        } catch (t: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            throw IllegalStateException("连接 $host:$port 失败（同WiFi？IP变了？脚本没跑？）: ${t.message?.take(80)}")
        }
        try {
            val info = transact(JSONObject().apply { put("t", "info") }, 5000L)
                ?: throw IllegalStateException("info 无应答")
            loadedNnue = "remote " + info.optString("name", "?")
            lastThreads = info.optInt("threads", 0)
            lastHashMb = info.optInt("hash", 0)
            lastBerserk = false
            serverMovetimeMs = info.optLong("movetime", 3000L).coerceIn(500L, 60_000L)
            val t0 = System.currentTimeMillis()
            transact(JSONObject().apply { put("t", "ping") }, 5000L)
                ?: throw IllegalStateException("ping 无应答")
            lastRttMs = System.currentTimeMillis() - t0
        } catch (t: Throwable) {
            markDead()
            if (t is IllegalStateException) throw t
            throw IllegalStateException("握手失败: ${t.message?.take(80)}")
        }
        Log.i(TAG, "remote up: $loadedNnue (${lastThreads}线 ${lastHashMb}MB, rtt=${lastRttMs}ms)")
    }

    override fun stop() {
        dead.set(true)
        try { sock?.close() } catch (_: Throwable) {}
        sock = null
        reader = null
    }

    // ------------------------------------------------------------ protocol

    private fun send(obj: JSONObject) {
        val s = sock ?: throw IllegalStateException("not connected")
        val bytes = (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            s.getOutputStream().write(bytes)
            s.getOutputStream().flush()
        }
    }

    /**
     * One request + one reply (start/test only — never on the shared reader
     * paths). Throws on EOF/IO (marks dead); null only on clean timeout.
     */
    private fun transact(obj: JSONObject, timeoutMs: Long): JSONObject? {
        send(obj)
        val r = reader ?: throw IllegalStateException("not connected")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val line = try {
                r.readLine()
            } catch (e: SocketTimeoutException) {
                continue
            } ?: throw IllegalStateException("连接已断开")
            val o = try {
                JSONObject(line)
            } catch (_: Throwable) {
                continue
            }
            return o
        }
        return null
    }

    /**
     * Blocking line read that throws on EOF/IO (marks dead) and returns null
     * on each poll tick so the caller can check flags/deadlines.
     */
    private fun nextLine(): String? {
        val r = reader ?: throw IllegalStateException("not connected")
        return try {
            r.readLine() ?: throw IllegalStateException("连接已断开")
        } catch (e: SocketTimeoutException) {
            null
        } catch (t: Throwable) {
            markDead()
            throw t
        }
    }

    private fun resultOf(o: JSONObject): BestMove? {
        if (o.optString("t") != "result") return null
        val uci = o.optString("uci").ifEmpty { null } ?: return null
        return BestMove(
            uci = uci,
            ponder = o.optString("ponder").ifEmpty { null },
            scoreCp = if (o.isNull("scoreCp")) null else o.optInt("scoreCp"),
            depth = o.optInt("depth", 0),
            isMate = o.optBoolean("isMate", false),
        )
    }

    // ------------------------------------------------------- EngineClient

    override fun analyse(fen: String, movetimeMs: Long, depth: Int): BestMove? {
        if (!isRunning()) return null
        // Light shape check (server re-checks strictly): never waste a
        // round-trip + server movetime on junk.
        if (fen.trim().split(Regex("\\s+")).size != 6) {
            Log.w(TAG, "refusing malformed FEN: ${fen.take(80)}")
            return null
        }
        val id = ++reqId
        try {
            send(JSONObject().apply {
                put("t", "analyse")
                put("id", id)
                put("fen", fen)
                put("movetimeMs", movetimeMs)
                put("depth", depth)
            })
        } catch (_: Throwable) {
            markDead()
            return null
        }
        // Timeout covers whatever the server actually thinks: the phone's
        // remote sliders ride along in the request and the server honors
        // them (its --movetime/--depth are only defaults now). Fixed-depth
        // searches are unbounded in time — give them a wide but finite lane.
        val effective = maxOf(movetimeMs, serverMovetimeMs, if (depth > 0) 45_000L else 0L)
        val budget = effective + ANALYSE_GRACE_MS
        val deadline = System.currentTimeMillis() + budget
        try {
            while (System.currentTimeMillis() < deadline) {
                val line = nextLine() ?: continue
                val o = try {
                    JSONObject(line)
                } catch (_: Throwable) {
                    continue
                }
                // Stale lines (stopped-ponder acks, late answers to older ids
                // after a timeout retry) are skipped, never trusted.
                if (o.optString("t") == "error") {
                    Log.w(TAG, "analyse #${id}: server error: ${o.optString("message").take(100)}")
                    return null
                }
                if (o.optString("t") != "result") continue
                if (o.has("id") && o.optInt("id", id) != id) continue
                consecTimeouts = 0
                return resultOf(o)
            }
        } catch (_: Throwable) {
            return null // nextLine already marked dead
        }
        // Timeout (not IO death): the server may just be slow, but two in a
        // row means something is wrong — go dead so the loop fails over to
        // local instead of stalling the game in timeout cycles.
        if (++consecTimeouts >= MAX_CONSEC_TIMEOUTS) {
            Log.w(TAG, "analyse #${id}: $MAX_CONSEC_TIMEOUTS consecutive timeouts, failing over")
            markDead()
        } else {
            Log.w(TAG, "analyse #${id}: timeout (${budget}ms), retrying once")
        }
        return null
    }

    override fun ponder(
        fen: String, ponderMove: String, movetimeMs: Long, depth: Int,
        stopFlag: AtomicBoolean,
    ): BestMove? {
        if (!isRunning()) return null
        val id = ++reqId
        try {
            send(JSONObject().apply {
                put("t", "ponder")
                put("id", id)
                put("fen", fen)
                put("ponderMove", ponderMove)
                // Think shape rides along like analyse (server honors it);
                // fixed-depth ponder without this ran on server defaults.
                put("movetimeMs", movetimeMs)
                put("depth", depth)
            })
        } catch (_: Throwable) {
            markDead()
            return null
        }
        val deadline = System.currentTimeMillis() + PONDER_MAX_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                // Miss path: the owner sets the flag AND sends stop (via
                // stopPonder); just exit — the result, if any, is discarded.
                if (stopFlag.get()) {
                    try { send(JSONObject().apply { put("t", "stop"); put("id", id) }) } catch (_: Throwable) {}
                    return null
                }
                val line = nextLine() ?: continue
                val o = try {
                    JSONObject(line)
                } catch (_: Throwable) {
                    continue
                }
                // Hit path: the worker's signalPonderHit made the server
                // answer; the reply flows back through THIS reader (same as
                // the local client) so the ponder thread exits WITH it.
                // Stale lines (late answers to older ids) are skipped like analyse.
                if (o.optString("t") == "result") {
                    if (o.has("id") && o.optInt("id", id) != id) continue
                    return resultOf(o)
                }
                if (o.optString("t") == "error") {
                    Log.w(TAG, "ponder #${id}: server error: ${o.optString("message").take(100)}")
                    return null
                }
            }
        } catch (_: Throwable) {
            return null // nextLine already marked dead
        }
        try { send(JSONObject().apply { put("t", "stop"); put("id", id) }) } catch (_: Throwable) {}
        return null
    }

    /**
     * Write-only (the reply flows to the ponder reader thread — see class
     * doc). Never blocks the worker.
     */
    override fun signalPonderHit() {
        try { send(JSONObject().apply { put("t", "ponderhit"); put("id", ++reqId) }) } catch (_: Throwable) {}
    }

    override fun stopPonder() {
        try { send(JSONObject().apply { put("t", "stop"); put("id", ++reqId) }) } catch (_: Throwable) {}
    }
}
