package com.chessassist

import android.util.Log
import com.chessassist.engine.EngineLoop
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback TCP bridge to the LSPosed hook (which runs inside com.duolingo).
 *
 * Why sockets and not the ContentProvider: on Android 11+ the *caller* needs package
 * visibility into our package to resolve ANY of our components. Duolingo's manifest
 * has no `<queries>` for us (and we cannot change it), so from Duolingo
 * `ContentResolver.call(content://com.chessassist.bridge)` throws
 * `IllegalArgumentException: Unknown authority`, and `createPackageContext(ours)`
 * throws `NameNotFoundException` (both confirmed live in the LSPosed log).
 * Loopback TCP needs no visibility from either side; both apps hold INTERNET.
 *
 * Protocol: UTF-8 JSON, one object per line.
 *   hook -> app : {"t":"position","fen":"...","user":"w","turn":"w","moves":"e2e4 .."}
 *   hook -> app : {"t":"ack","drawn":"e2e4"}
 *   hook -> app : {"t":"help","code":"promotion|stuck","move":"e7e8q"}
 *   app  -> hook: {"t":"bestmove","move":"e2e4","fen":"...","autoplay":"0","autoplayDelay":"450"}
 *   app  -> hook: {"t":"clear"} | {"t":"config","autoplay":"0"}
 */
object BridgeServer {

    private const val TAG = "BridgeServer"
    const val PORT = 41717

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var senderThread: Thread? = null
    private val hookOutputs = CopyOnWriteArrayList<OutputStream>()
    private val sendLock = Any()
    private var pendingPush: JSONObject? = null

    // Accepted client sockets. stop() closes them so the serve() reader threads
    // unblock (readLine returns/throws) and exit — otherwise they survive as
    // zombies: their OutputStream was dropped from hookOutputs by stop(), so a
    // restart accepts the hook's reconnect BUT bestmove pushes silently vanish
    // into the dead registration (arrows stop working until Duolingo is killed).
    private val clients = CopyOnWriteArrayList<Socket>()

    @Volatile var lastHookSeenAt: Long = 0
        private set

    fun hookConnected(): Boolean = hookOutputs.isNotEmpty()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            server = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "listening on 127.0.0.1:$PORT")
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "bind failed on port $PORT (occupied?): $t")
            EngineLoop.onBridgeError("桥端口 $PORT 被占用：$t")
            return
        }
        acceptThread = Thread(::acceptLoop, "bridge-accept").apply {
            isDaemon = true
            start()
        }
        senderThread = Thread(::senderLoop, "bridge-send").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        synchronized(sendLock) { (sendLock as java.lang.Object).notifyAll() }
        try { server?.close() } catch (_: Throwable) {}
        server = null
        hookOutputs.clear()
        // Close accepted sockets too: this unblocks every serve() reader
        // (zombie prevention, see `clients`) and makes the hook side see EOF
        // so its reconnect loop attaches to the new server instance.
        for (s in clients) { try { s.close() } catch (_: Throwable) {} }
        clients.clear()
        try { acceptThread?.interrupt() } catch (_: Throwable) {}
        acceptThread = null
        try { senderThread?.interrupt() } catch (_: Throwable) {}
        senderThread = null
        synchronized(sendLock) { pendingPush = null }
    }

    /**
     * Enqueue a command for the hook. Latest-only + async: the engine thread never
     * blocks on socket I/O here. A write to a full/dead peer buffer would otherwise
     * stall the whole analysis loop with a frozen banner and no timeout to save it.
     */
    fun push(obj: JSONObject) {
        synchronized(sendLock) {
            pendingPush = obj
            (sendLock as java.lang.Object).notify()
        }
    }

    private fun senderLoop() {
        while (running.get()) {
            val obj: JSONObject? = synchronized(sendLock) {
                while (running.get() && pendingPush == null) {
                    try {
                        (sendLock as java.lang.Object).wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                val o = pendingPush
                pendingPush = null
                o
            }
            if (obj == null) continue
            val bytes = try {
                (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) {
                continue
            }
            for (out in hookOutputs) {
                try {
                    synchronized(out) {
                        out.write(bytes)
                        out.flush()
                    }
                } catch (_: Throwable) {
                    hookOutputs.remove(out)
                }
            }
        }
    }

    fun pushBestmove(move: String, fen: String, autoplay: Boolean, autoplayDelayMs: Long) {
        push(JSONObject().apply {
            put("t", "bestmove")
            put("move", move)
            put("fen", fen)
            put("autoplay", if (autoplay) "1" else "0")
            put("autoplayDelay", autoplayDelayMs.toString())
        })
    }

    fun pushClear() {
        push(JSONObject().apply { put("t", "clear") })
    }

    // ------------------------------------------------------------------ server

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val sock = server?.accept() ?: break
                lastHookSeenAt = System.currentTimeMillis()
                Log.i(TAG, "hook connected: ${sock.remoteSocketAddress}")
                EngineLoop.onHookConnected()
                val t = Thread({ serve(sock) }, "bridge-conn").apply {
                    isDaemon = true
                    start()
                }
                Unit
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "accept: $t")
            }
        }
    }

    private fun serve(sock: Socket) {
        var out: OutputStream? = null
        clients.add(sock)
        try {
            out = sock.getOutputStream()
            hookOutputs.add(out)
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            while (running.get()) {
                val line = try {
                    reader.readLine()
                } catch (_: Throwable) {
                    break
                } ?: break
                lastHookSeenAt = System.currentTimeMillis()
                handleLine(line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "conn: $t")
        } finally {
            clients.remove(sock)
            if (out != null) hookOutputs.remove(out)
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun handleLine(line: String) {
        val o = try {
            JSONObject(line)
        } catch (_: Throwable) {
            return
        }
        when (o.optString("t")) {
            "position" -> {
                val fen = o.optString("fen").ifEmpty { null } ?: return
                val moves = o.optString("moves").split(' ').filter { it.length >= 4 }
                EngineLoop.onPosition(
                    fen = fen,
                    userColor = o.optString("user").ifEmpty { null },
                    turnColor = o.optString("turn").ifEmpty { null },
                    moves = moves,
                )
            }
            "ack" -> EngineLoop.onArrowAck(o.optString("drawn"))
            "help" -> EngineLoop.onNeedHelp(o.optString("code"), o.optString("move"))
        }
    }
}
