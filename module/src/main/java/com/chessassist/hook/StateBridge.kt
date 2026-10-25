package com.chessassist.hook

import de.robv.android.xposed.XposedHelpers

/**
 * Reads the live chess position out of (obfuscated) model instances and publishes it as
 * a normalized [PositionSnapshot] to the companion app via the ContentProvider bridge.
 *
 * All field access uses XposedHelpers.getObjectField against the R8-minified names from
 * docs/REVERSE_ENGINEERING.md. Every accessor validates defensively — if Duolingo changes
 * a layout, we degrade to the FEN-only secondary hook instead of crashing the host app.
 */
class StateBridge {

    @Volatile var lastFen: String? = null
        private set

    @Volatile var lastUserColor: String? = null   // "w" | "b"
        private set

    @Volatile var lastTurnColor: String? = null
        private set

    @Volatile var lastMoves: List<String> = emptyList()   // uci history, e.g. ["e2e4","e7e5"]
        private set

    @Volatile var lastUpdateAt: Long = 0L
        private set

    private val lock = Any()

    companion object {
        /** Exact initial position: a new game/lesson always (re)starts here. */
        private const val STARTPOS =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }

    /** Called from the ChessGameState constructor hook. */
    fun onGameState(state: Any, src: String = "ctor") {
        BridgePublisher.noteTrigger(src)
        try {
            val fen = extractFen(state) ?: return
            val user = extractColor(state, "d")   // d = userColor
            val turn = extractColor(state, "e")   // e = turnColor
            val moves = extractMoves(state)       // f = List<Move>
            resetIfNewGame(fen)
            synchronized(lock) {
                lastFen = fen
                lastUserColor = user ?: lastUserColor
                lastTurnColor = turn
                lastMoves = moves
                lastUpdateAt = System.currentTimeMillis()
            }
            if (user != null) BridgeAccess.lastUserColor = user
            BridgePublisher.publishSnapshot(fen, user, turn, moves)
        } catch (t: Throwable) {
            MainHook.log("onGameState failed: $t")
        }
    }

    /** Called from the ChessFen constructor hook (secondary path). */
    fun onFen(fenObj: Any, loader: ClassLoader) {
        BridgePublisher.noteTrigger("fen")
        try {
            val fen = extractFenFrom(fenObj) ?: return
            resetIfNewGame(fen)
            val turn = fen.split(" ").getOrNull(1)?.lowercase()
            synchronized(lock) {
                lastFen = fen
                lastTurnColor = turn
                lastUpdateAt = System.currentTimeMillis()
            }
            BridgePublisher.publishSnapshot(fen, lastUserColor, lastTurnColor, lastMoves)
        } catch (t: Throwable) {
            MainHook.log("onFen failed: $t")
        }
    }

    private fun extractFenFrom(fenObj: Any): String? {
        // Preferred: the app's own full-FEN getter qh4.b().
        try {
            val full = XposedHelpers.callMethod(fenObj, "b") as? String
            if (full != null && full.count { it == ' ' } >= 5) return full
        } catch (_: Throwable) { }
        val placement = XposedHelpers.getObjectField(fenObj, "a") as? String ?: return null
        if (!placement.contains('/')) return null
        val turn = colorSymbol(XposedHelpers.getObjectField(fenObj, "b") ?: return null)
        val castling = buildString {
            if (bool(fenObj, "c")) append('K')
            if (bool(fenObj, "d")) append('Q')
            if (bool(fenObj, "e")) append('k')
            if (bool(fenObj, "f")) append('q')
            if (isEmpty()) append('-')
        }
        val ep = (XposedHelpers.getObjectField(fenObj, "g") as? String).orEmpty().ifEmpty { "-" }
        val half = XposedHelpers.getIntField(fenObj, "h")
        val fullmove = XposedHelpers.getIntField(fenObj, "i")
        return "$placement $turn $castling $ep $half $fullmove"
    }

    @Volatile private var lastRenderProcessAt: Long = 0

    /** Called when the Rive controller has rendered the state (g() hook). */
    fun onRenderedState(state: Any) {
        BridgeAccess.lastState = state
        // Render polling can fire dozens of times per second on the UI thread, each
        // doing full reflection extraction. Throttle the heavy part: states change
        // far slower than frames (ctor path is unthrottled and covers new states).
        val now = System.currentTimeMillis()
        if (now - lastRenderProcessAt < 200) return
        lastRenderProcessAt = now
        onGameState(state, "render") // re-publish: rendered state is authoritative for the UI
    }

    /** Called by ViewModelFallback when a FEN-shaped string is mined from kept-name classes. */
    fun acceptExternalFen(fen: String) {
        BridgePublisher.noteTrigger("fb")
        resetIfNewGame(fen)
        synchronized(lock) {
            if (fen == lastFen) return
            lastFen = fen
            lastTurnColor = fen.split(" ").getOrNull(1)?.lowercase()
            lastUpdateAt = System.currentTimeMillis()
        }
        BridgePublisher.publishSnapshot(fen, lastUserColor, lastTurnColor, lastMoves)
        MainHook.log("fen(fallback): $fen")
    }

    /**
     * Fresh board: forget the previous game (its userColor would invert this
     * game, and its identical startpos FEN would be change-deduped away).
     * Runs on ALL entry paths — the primary onGameState hook as well as the
     * onFen/acceptExternalFen fallbacks, which fire alone when the state
     * class gets renamed by an app update.
     */
    private fun resetIfNewGame(fen: String) {
        if (fen == STARTPOS) {
            BridgePublisher.resetForNewGame()
            synchronized(lock) { lastUserColor = null }
            BridgeAccess.lastUserColor = null
        }
    }

    // ------------------------------------------------------------ extractors

    /** ChessGameState.a (fen: ChessFen) → full FEN string. Same extraction as [extractFenFrom]. */
    private fun extractFen(state: Any): String? {
        val fenObj = XposedHelpers.getObjectField(state, "a") ?: return null
        return extractFenFrom(fenObj)
    }

    private fun extractColor(state: Any, field: String): String? {
        return try {
            colorSymbol(XposedHelpers.getObjectField(state, field) ?: return null)
        } catch (_: Throwable) { null }
    }

    // Live 6.85.7 returns UPPERCASE "B"/"W" here (docs said lowercase — wrong),
    // while FEN-derived turns are lowercase. Normalize everything to lowercase at
    // the boundary so engine-side "b"=="B" style comparisons can never mis-skip.
    private fun colorSymbol(colorObj: Any): String? = try {
        (XposedHelpers.callMethod(colorObj, "getSymbol") as? String)?.lowercase()
    } catch (_: Throwable) { null }

    /** ChessGameState.f = List<Move>; each Move.b() = uci "e2e4". */
    private fun extractMoves(state: Any): List<String> {
        return try {
            val list = XposedHelpers.getObjectField(state, "f") as? List<*> ?: return emptyList()
            list.mapNotNull { mv ->
                try { XposedHelpers.callMethod(mv, "b") as? String } catch (_: Throwable) { null }
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun bool(obj: Any, field: String): Boolean = try {
        XposedHelpers.getBooleanField(obj, field)
    } catch (_: Throwable) { false }
}
