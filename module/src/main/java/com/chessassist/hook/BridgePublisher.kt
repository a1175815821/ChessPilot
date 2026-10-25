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

    /** Immutable command triples: one @Volatile reference swap — no torn reads.
     * The old three independent volatiles let the reader thread (CommandLoop)
     * interleave with the main thread (ArrowPainter) into "old move + new fen",
     * painting a stale arrow onto a fresh board. */
    data class BestCmd(val uci: String?, val fen: String?, val at: Long)

    /**
     * Companion's predicted enemy reply (red arrow). Strictly display-only:
     * never feeds autoplay freshness, staleness gates, or the (fen,move) best
     * path. Repainted on renders while it matches the analysed FEN, cleared by
     * any bestmove draw (draw path resets arrows first) or "clearponder".
     */
    data class PonderCmd(val uci: String?, val fen: String?, val at: Long)

    @Volatile var bestCmd = BestCmd(null, null, 0)
        private set
    @Volatile var ponderCmd = PonderCmd(null, null, 0)
        private set

    /** Tearing-free granular accessors — existing readers keep their API. */
    val lastBestMove: String? get() = bestCmd.uci
    val lastBestMoveFen: String? get() = bestCmd.fen
    val lastBestMoveAt: Long get() = bestCmd.at
    val lastPonderMove: String? get() = ponderCmd.uci
    val lastPonderFen: String? get() = ponderCmd.fen
    val lastPonderAt: Long get() = ponderCmd.at

    /** Writer entries — called from the socket reader thread (CommandLoop). */
    fun publishBest(uci: String, fen: String) {
        bestCmd = BestCmd(uci, fen, System.currentTimeMillis())
    }

    fun publishPonder(uci: String, fen: String) {
        ponderCmd = PonderCmd(uci, fen, System.currentTimeMillis())
    }

    fun clearPonder() {
        ponderCmd = PonderCmd(null, null, 0)
    }
    /**
     * Prediction paint confirmation (self-healing repaint model): the one-shot
     * needDraw gate used to be consumed on command arrival, so an arrow wiped
     * by a board transition (or lost to a bridge drop) never came back while
     * the overlay text ("预测xxxx") sat there claiming otherwise. Now a render
     * repaints the pending prediction until a paint CONFIRMS (or the pair is
     * superseded/cleared/expired) — throttled, so no invoke storms.
     */
    @Volatile var ponderConfirmedFen: String? = null
    @Volatile var ponderConfirmedMove: String? = null
    @Volatile var lastPonderAttemptAt: Long = 0
    /**
     * Best-arrow paint confirmation (same self-healing model as the prediction:
     * renders repaint the pending bestmove until a paint CONFIRMS, throttled).
     */
    @Volatile var bestConfirmedFen: String? = null
    @Volatile var bestConfirmedMove: String? = null
    @Volatile var lastBestAttemptAt: Long = 0
    @Volatile var lastAnalyzedFen: String? = null

    /**
     * New game/lesson: drop the previous game's sticky state. Without this, the old
     * userColor inverts the new game (white opening skipped as "opponent's turn"),
     * and the identical startpos FEN is change-deduped into silence.
     */
    @Synchronized
    fun resetForNewGame() {
        lastAnalyzedFen = null
        lastPublishKey = null
        bestCmd = BestCmd(null, null, 0)
        ponderCmd = PonderCmd(null, null, 0)
        ponderConfirmedFen = null
        ponderConfirmedMove = null
        lastPonderAttemptAt = 0
        bestConfirmedFen = null
        bestConfirmedMove = null
        lastBestAttemptAt = 0
        MainHook.log("new game: sticky state reset")
    }

    /**
     * Partial reset for a rebuilt board controller: force the CURRENT position to
     * republish (lesson retries reconstruct identical setups that change-detection
     * would otherwise swallow), but KEEP the last bestmove so the painter can
     * immediately redraw it on the fresh board without waiting for re-analysis.
     * Full resetForNewGame is reserved for the exact-startpos new-game signal.
     */
    @Synchronized
    fun resetPublishState() {
        lastAnalyzedFen = null
        lastPublishKey = null
        bestConfirmedFen = null
        bestConfirmedMove = null
        lastBestAttemptAt = 0
        MainHook.log("board rebuilt: republish armed")
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

    // lastAnalyzedFen stays FEN-only: ArrowPainter's staleness gate and autoplay
    // freshness compare against move FENs, so this must never become a composite.
    // Turn/user corrections travel via a SEPARATE publish key below.
    @Volatile private var lastPublishKey: String? = null

    fun publishSnapshot(fen: String, userColor: String?, turnColor: String?, moves: List<String>) {
        // Change detection on (fen, turn, user): hooks fire at frame rate (render
        // polling, ctor copies) with the SAME state — same triple ⇒ silence. But a
        // turn/user correction for an unchanged FEN (lagging fields catching up)
        // MUST be republished, or the companion's skip decision on the old triple
        // sticks forever with no new trigger to revisit it.
        // (Companion restarts are covered by BridgeClient's reconnect flush.)
        val key = "$fen|${turnColor ?: "?"}|${userColor ?: "?"}"
        if (key == lastPublishKey) {
            pubDup++
            return
        }
        lastPublishKey = key
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
