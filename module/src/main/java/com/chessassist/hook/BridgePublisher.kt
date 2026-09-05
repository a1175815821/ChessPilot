package com.chessassist.hook

/**
 * Publishes snapshots/commands between the hook process and the companion app.
 *
 * Transport v2 is loopback TCP ([BridgeClient]/`BridgeServer`): Duolingo cannot resolve
 * our ContentProvider authority on Android 11+ (package visibility — the old
 * `Unknown authority com.chessassist.bridge` failure), so the provider path is dead.
 * This object keeps the shared command state (latest bestmove + staleness) that
 * [ArrowPainter] relies on; the actual I/O lives in [BridgeClient].
 */
object BridgePublisher {

    @Volatile var lastBestMove: String? = null
    @Volatile var lastBestMoveFen: String? = null
    @Volatile var lastBestMoveAt: Long = 0
    @Volatile var autoPlayEnabled: Boolean = false
    @Volatile var lastAnalyzedFen: String? = null

    // Shared (fen,move) draw dedupe across ALL painter instances. A draw is identified
    // purely by what it shows — never by age: an "expired" arrow must NOT be redrawn
    // (that caused a 13k-invoke storm: per-frame retriggers × lapsed 30 s window).
    // Redraw happens only when the pair changes; explicit clear() re-arms it.
    @Volatile private var drawnFen: String? = null
    @Volatile private var drawnMove: String? = null

    @Synchronized
    fun needDraw(fen: String, move: String): Boolean {
        if (drawnFen == fen && drawnMove == move) return false
        drawnFen = fen
        drawnMove = move
        return true
    }

    @Synchronized
    fun clearDrawn() {
        drawnFen = null
        drawnMove = null
    }

    /**
     * New game/lesson: drop the previous game's sticky state. Without this, the old
     * userColor inverts the new game (white opening skipped as "opponent's turn"),
     * and the identical startpos FEN is change-deduped into silence.
     */
    @Synchronized
    fun resetForNewGame() {
        lastAnalyzedFen = null
        lastBestMove = null
        lastBestMoveFen = null
        lastBestMoveAt = 0
        clearDrawn()
        MainHook.log("new game: sticky state reset")
    }

    // Trigger accounting for stuck-game diagnosis (see StateBridge call sites).
    // A stuck episode with triggers flowing (all dup-suppressed) means Duolingo is
    // alive but emitting no NEW position (lesson gating on real input?) — versus
    // zero triggers, which means the game side itself went quiet.
    @Volatile private var trigCtor = 0L
    @Volatile private var trigRender = 0L
    @Volatile private var trigFen = 0L
    @Volatile private var trigFallback = 0L
    @Volatile private var pubSent = 0L
    @Volatile private var pubDup = 0L
    @Volatile private var lastHbAt = 0L

    /**
     * Last hook-trigger time (any source). The board is considered "hot" (mid
     * animation/transition, render polling firing) right after a trigger — renderer
     * pokes (arrow draw, taps) wait for a quiet moment instead, see ArrowPainter.
     */
    @Volatile var lastTriggerAt: Long = 0
        private set

    fun noteTrigger(src: String) {
        lastTriggerAt = System.currentTimeMillis()
        when (src) {
            "ctor" -> trigCtor++
            "render" -> trigRender++
            "fen" -> trigFen++
            "fb" -> trigFallback++
        }
        val now = System.currentTimeMillis()
        if (now - lastHbAt > 10_000) {
            lastHbAt = now
            MainHook.log(
                "hb: pub=$pubSent dup=$pubDup ctor=$trigCtor render=$trigRender " +
                    "fen=$trigFen fb=$trigFallback fen=${lastAnalyzedFen?.take(24)}…"
            )
        }
    }

    fun publishSnapshot(fen: String, userColor: String?, turnColor: String?, moves: List<String>) {
        // Pure change detection: hooks fire at frame rate (render polling, ctor copies)
        // with the SAME state. Republishing it (even throttled) poisons lastAnalyzedFen,
        // forces useless re-analysis, and resurrects stale arrows. Same fen ⇒ silence.
        // (Companion restarts are covered by BridgeClient's reconnect flush.)
        if (fen == lastAnalyzedFen) {
            pubDup++
            return
        }
        lastAnalyzedFen = fen
        try {
            BridgeClient.sendPosition(fen, userColor, turnColor, moves)
            pubSent++
            MainHook.log("published: $fen  turn=$turnColor user=$userColor")
        } catch (t: Throwable) {
            MainHook.log("publish failed: $t")
        }
    }

    fun markArrowDrawn(uci: String) {
        try {
            BridgeClient.sendAck(uci)
        } catch (_: Throwable) {
        }
    }
}
