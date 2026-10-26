package io.github.a1175815821.chesspilot

/**
 * Command dispatcher: the companion pushes bestmove/clear/ponder/clearponder/play
 * over the loopback socket ([BridgeClient]); this executes them. Push replaces the
 * old 250 ms provider poll, so arrows land faster and no poll thread can steal
 * another process's command (only the main Duolingo process runs hooks at all).
 */
object CommandLoop {

    @Volatile private var started = false

    private var painterInst: ArrowPainter? = null
    private var playerInst: AutoPlayer? = null

    /** Call once from MainHook after remap+bridge+painter exist; starts the bridge client.
     * The painter/player are the SINGLE shared instances (the old lazy second painter
     * re-resolved sMethod and re-probed enum colors on its own). */
    @Synchronized
    fun configure(remap: Remap, bridge: StateBridge, painter: ArrowPainter) {
        painterInst = painter
        playerInst = AutoPlayer(bridge)
        if (started) return
        started = true
        BridgeClient.ensureStarted()
    }

    /** Called on the bridge reader thread for every incoming command. */
    fun handleCommand(cmd: Map<String, String>) {
        try {
            when (cmd["cmd"]) {
                "bestmove" -> {
                    val uci = cmd["move"] ?: return
                    // Shape gate BEFORE any state is touched: engine markers like
                    // "(none)" (terminal position) used to be stored as the pending
                    // bestmove and then re-validated (and silently dropped) on every
                    // render for the next 120 s.
                    if (!AutoPlayer.isLegalUci(uci)) {
                        MainHook.log("bestmove rejected (not UCI): $uci")
                        return
                    }
                    // Fall back to the last published FEN for legacy commands that
                    // carry no "fen" field — and store the RESOLVED value: storing
                    // cmd["fen"] (null in that case) made the staleness guard in
                    // ArrowPainter reject every arrow.
                    val fen = cmd["fen"] ?: BridgePublisher.lastAnalyzedFen ?: return
                    BridgePublisher.publishBest(uci, fen)
                    painterInst?.draw(uci, fen)
                    // Single source of truth: the companion (BridgeSettings)
                    // decides autoplay and ships it as cmd["autoplay"].
                    if (cmd["autoplay"] == "1") {
                        playerInst?.performMove(
                            uci, fen,
                            cmd["autoplayDelay"]?.toLongOrNull()
                        )
                    }
                }
                "clear" -> painterInst?.clear()
                // Ponder prediction (display-only gray arrow): never autoplayed,
                // never touches bestmove state. Confirm-model repaints keep it on
                // the board; any bestmove draw resets arrows first.
                "ponder" -> {
                    val uci = cmd["move"] ?: return
                    val fen = cmd["fen"] ?: BridgePublisher.lastAnalyzedFen ?: return
                    BridgePublisher.publishPonder(uci, fen)
                    painterInst?.drawPrediction(uci, fen)
                }
                // Retire a CONFIRMED prediction without touching a board the
                // bestmove already claims (its yellow draw wiped ours anyway).
                // Always drops the pending pair so the self-healing repaints
                // stop even when there was nothing visible to clear.
                "clearponder" -> {
                    val pf = BridgePublisher.lastPonderFen
                    if (pf != null && BridgePublisher.ponderConfirmedFen == pf &&
                        BridgePublisher.lastBestMoveFen != BridgePublisher.lastAnalyzedFen
                    ) {
                        painterInst?.clear()
                    }
                    BridgePublisher.clearPonder()
                    BridgePublisher.ponderConfirmedFen = null
                    BridgePublisher.ponderConfirmedMove = null
                }
                // Semi-auto: user tapped the banner button — play the exact pair the
                // companion just pushed (freshness gate inside performMove applies).
                "play" -> {
                    val uci = cmd["move"] ?: return
                    val fen = cmd["fen"] ?: return
                    playerInst?.performMove(
                        uci, fen,
                        cmd["autoplayDelay"]?.toLongOrNull()
                    )
                }
            }
        } catch (t: Throwable) {
            MainHook.log("command handling: $t")
        }
    }
}
