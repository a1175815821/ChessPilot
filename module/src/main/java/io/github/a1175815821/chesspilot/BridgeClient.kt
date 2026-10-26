package io.github.a1175815821.chesspilot

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback TCP client to the companion app (com.chessassist).
 *
 * Why not the ContentProvider: on Android 11+ Duolingo cannot resolve our provider
 * authority (`Unknown authority com.chessassist.bridge`) because its manifest has no
 * `<queries>` visibility into our package — confirmed live in the LSPosed log.
 * 127.0.0.1 needs no package visibility; Duolingo holds INTERNET.
 *
 * Behavior: connect-retry loop with backoff; latest position is flushed on every
 * (re)connect so the engine catches up immediately. All I/O is wrapped — a dead
 * bridge never crashes the host game.
 *
 * THREADING: callers (including the HOST MAIN THREAD — StateBridge's render hook and
 * ArrowPainter's ack) only build a small JSONObject and enqueue. Every byte of socket
 * I/O happens on the dedicated [sendLoop] thread. The old design wrote on the caller
 * thread: if the companion ever froze while holding the socket, the loopback buffer
 * filled and `write` parked Duolingo's UI thread (ANR). Not connected ⇒ everything
 * except [lastSnapshot] is dropped — positions go stale in seconds anyway, and the
 * reconnect flush republishes the fresh one.
 */
object BridgeClient {

    const val PORT = 41717

    /** Queue cap: a stuck writer must not grow this without bound — oldest drops first. */
    private const val MAX_QUEUE = 20

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var sender: Thread? = null

    /**
     * The whole write holds [sendLock], and every close path takes the same lock first:
     * closing a socket mid-write (or racing two writers on fd teardown) segfaults ART's
     * socketWrite0 instead of throwing IOException — an uncatchable host-process kill.
     * Only the sender thread and the connect loop touch this — never a host caller.
     */
    private val sendLock = Any()
    @Volatile private var out: OutputStream? = null

    /** Socket writes are serialized here; offer() from any thread never blocks. */
    private val queue = LinkedBlockingQueue<JSONObject>(MAX_QUEUE)

    @Volatile private var lastSnapshot: JSONObject? = null

    fun ensureStarted() {
        if (!running.compareAndSet(false, true)) return
        sender = Thread(::sendLoop, "duo-chess-bridge-send").apply {
            isDaemon = true
            start()
        }
        worker = Thread(::connectLoop, "duo-chess-bridge").apply {
            isDaemon = true
            start()
        }
    }

    fun sendPosition(fen: String, userColor: String?, turnColor: String?, moves: List<String>) {
        val obj = JSONObject().apply {
            put("t", "position")
            put("fen", fen)
            put("user", userColor ?: "")
            put("turn", turnColor ?: "")
            put("moves", moves.joinToString(" "))
        }
        lastSnapshot = obj
        enqueue(obj)
    }

    fun sendAck(uci: String) {
        enqueue(JSONObject().apply {
            put("t", "ack")
            put("drawn", uci)
        })
    }

    /**
     * Ask the companion to guide the user manually (banner + event). Codes:
     * "promotion" (picker geometry missed — ask user to pick the queen),
     * "stuck" (taps didn't land — ask user to play the move).
     */
    fun sendHelp(code: String, uci: String) {
        enqueue(JSONObject().apply {
            put("t", "help")
            put("code", code)
            put("move", uci)
        })
        MainHook.log("asked companion for manual help: $code $uci")
    }

    /** Enqueue only; never touches the socket, never blocks the caller. */
    private fun enqueue(obj: JSONObject) {
        if (out == null) return // not connected: caller state (lastSnapshot) survives
        while (!queue.offer(obj)) {
            queue.poll()
            if (out == null) return
        }
    }

    /** The ONLY thread that performs socket writes. */
    private fun sendLoop() {
        while (running.get()) {
            val obj = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            } ?: continue
            val bytes = try {
                (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) {
                continue
            }
            var dead: OutputStream? = null
            synchronized(sendLock) {
                val o = out ?: return@synchronized
                try {
                    o.write(bytes)
                    o.flush()
                } catch (_: Throwable) {
                    if (out === o) {
                        out = null
                        dead = o
                    }
                }
            }
            // Close outside the lock (close itself never races a write now).
            if (dead != null) {
                try { dead.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun connectLoop() {
        var backoff = 1000L
        while (running.get()) {
            var sock: Socket? = null
            try {
                sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
                MainHook.log("bridge connected to companion")
                val o = sock.getOutputStream()
                synchronized(sendLock) { out = o }
                // Flush the latest position so analysis resumes immediately.
                lastSnapshot?.let { enqueue(it) }
                backoff = 1000L
                readLoop(sock)
            } catch (t: Throwable) {
                // Companion not started yet (or restarting) — normal before the user
                // taps "启动引擎服务". Throttled to one line per backoff step.
                MainHook.log("bridge waiting for companion (${t.message?.take(60)})")
            } finally {
                // Take sendLock so no sender write() is inside the fd while we tear down.
                val dead: OutputStream?
                synchronized(sendLock) {
                    dead = out
                    out = null
                }
                try { sock?.close() } catch (_: Throwable) {}
                if (dead != null) {
                    try { dead.close() } catch (_: Throwable) {}
                }
                queue.clear()
            }
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return
            }
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    private fun readLoop(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        while (running.get()) {
            val line = try {
                reader.readLine()
            } catch (_: Throwable) {
                break
            } ?: break // EOF: companion restarted; reconnect.
            try {
                val cmd = cmdFromJson(line) ?: continue
                CommandLoop.handleCommand(cmd)
            } catch (t: Throwable) {
                MainHook.log("command handling: $t")
            }
        }
    }

    private fun cmdFromJson(line: String): Map<String, String>? {
        val o = try {
            JSONObject(line)
        } catch (_: Throwable) {
            return null
        }
        val out = mutableMapOf<String, String>()
        for (key in o.keys()) {
            val v = o.opt(key) ?: continue
            out[key] = v.toString()
        }
        if (out.isEmpty()) return null
        // Normalize to the legacy command shape: {"cmd":...,"move":...,"fen":...,"autoplay":...}
        // Any "t" doubles as the command name when "cmd" is absent (covers "play").
        val t = out["t"]
        if (t != null && !out.containsKey("cmd")) out["cmd"] = t
        return out
    }
}
