package com.chessassist.hook

/**
 * Command dispatcher: the companion pushes bestmove/clear/config over the loopback
 * socket ([BridgeClient]); this executes them. Push replaces the old 250 ms provider
 * poll, so arrows land faster and no poll thread can steal another process's command
 * (only the main Duolingo process runs hooks at all).
 */
object CommandLoop {

    @Volatile private var started = false

    private var painterInst: ArrowPainter? = null
    private var playerInst: AutoPlayer? = null
    private var remapInst: Remap? = null
    private var bridgeInst: StateBridge? = null

    /** Call once from MainHook after remap+bridge exist; starts the bridge client. */
    @Synchronized
    fun configure(remap: Remap, bridge: StateBridge) {
        remapInst = remap
        bridgeInst = bridge
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
                    // Fall back to the last published FEN for legacy commands that
                    // carry no "fen" field — and store the RESOLVED value: storing
                    // cmd["fen"] (null in that case) made the staleness guard in
                    // ArrowPainter reject every arrow.
                    val fen = cmd["fen"] ?: BridgePublisher.lastAnalyzedFen ?: return
                    BridgePublisher.lastBestMove = uci
                    BridgePublisher.lastBestMoveFen = fen
                    BridgePublisher.lastBestMoveAt = System.currentTimeMillis()
                    ensurePainter()?.draw(uci, fen)
                    // Single source of truth: the companion (BridgeSettings)
                    // decides autoplay and ships it as cmd["autoplay"].
                    if (cmd["autoplay"] == "1") {
                        ensurePlayer()?.performMove(
                            uci, fen,
                            cmd["autoplayDelay"]?.toLongOrNull()
                        )
                    }
                }
                "clear" -> ensurePainter()?.clear()
                // Ponder prediction (display-only red arrow): never autoplayed,
                // never touches bestmove state. Staleness rides the same
                // (fen,move) gate; any bestmove draw resets arrows first.
                "ponder" -> {
                    val uci = cmd["move"] ?: return
                    val fen = cmd["fen"] ?: BridgePublisher.lastAnalyzedFen ?: return
                    BridgePublisher.lastPonderMove = uci
                    BridgePublisher.lastPonderFen = fen
                    BridgePublisher.lastPonderAt = System.currentTimeMillis()
                    ensurePainter()?.drawPrediction(uci, fen)
                }
                // Drop the red prediction without touching the yellow best arrow
                // (companion sends this when ponder goes quiet with no successor).
                "clearponder" -> {
                    if (BridgePublisher.isPonderDrawn()) ensurePainter()?.clear()
                }
                // Kept for backward compat: remembered but no longer gates autoplay.
                "config" -> BridgePublisher.autoPlayEnabled = cmd["autoplay"] == "1"
                // Semi-auto: user tapped the banner button — play the exact pair the
                // companion just pushed (freshness gate inside performMove applies).
                "play" -> {
                    val uci = cmd["move"] ?: return
                    val fen = cmd["fen"] ?: return
                    ensurePlayer()?.performMove(
                        uci, fen,
                        cmd["autoplayDelay"]?.toLongOrNull()
                    )
                }
            }
        } catch (t: Throwable) {
            MainHook.log("command handling: $t")
        }
    }

    private fun ensurePainter(): ArrowPainter? {
        if (painterInst == null && remapInst != null) painterInst = ArrowPainter(remapInst!!)
        return painterInst
    }

    private fun ensurePlayer(): AutoPlayer? {
        if (playerInst == null && bridgeInst != null)
            playerInst = AutoPlayer(bridgeInst!!)
        return playerInst
    }
}
