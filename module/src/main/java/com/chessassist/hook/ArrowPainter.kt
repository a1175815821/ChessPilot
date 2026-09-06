package com.chessassist.hook

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers

/**
 * Draws the Stockfish best-move arrow **natively inside Duolingo's own Rive board** by
 * calling the same method the app's CheckmateHelper hint system uses:
 *
 *     cl5.s(from: Square, to: Square, animate: Boolean, type: ArrowType, mode: Int, color: ArrowColor)
 *
 * This is resolved dynamically: we look for a 6-arg method on the controller whose 3rd
 * arg is boolean and whose 4th/6th args are the ArrowType/ArrowColor enums. Zero overlay
 * windows, zero SystemAlertWindow permission, perfect layering under the board artboard.
 *
 * THREADING: every board mutation runs on the host main thread. Commands arrive on the
 * socket reader thread, and Rive's native renderer is not thread-safe — invoking `s()`
 * off-main native-crashes Duolingo (instant black flash + death, no Java stack).
 */
class ArrowPainter(private val remap: Remap) {

    companion object {
        /** Prediction arrow color — one-line knob for color visibility tests. */
        const val PREDICTION_COLOR = "GRAY"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var sMethod: java.lang.reflect.Method? = null

    /** New state arrived (constructor hook): draw if we already have an analysis for it. */
    fun onGameState(state: Any) {
        val fen = BridgePublisher.lastAnalyzedFen ?: return
        val best = BridgePublisher.lastBestMove
        // staleness guard: only draw a move computed for THIS position
        if (best != null && BridgePublisher.lastBestMoveFen == fen) {
            draw(best, fen)
            return
        }
        // No best for this position (opponent's turn): repaint the live red
        // prediction instead, if it belongs to this exact position.
        val ponder = BridgePublisher.lastPonderMove
        if (ponder != null && BridgePublisher.lastPonderFen == fen) {
            drawPrediction(ponder, fen)
        }
    }

    /** Board controller rendered a state: also the moment we can (re)draw. */
    fun onRenderedState(state: Any) {
        // Staleness guard: never repaint an arrow computed for an older
        // position onto the freshly rendered board. Best (yellow) wins over
        // prediction (red) — the two can never share a fen (best is computed
        // for our turn, prediction for the opponent's), so this is exclusive.
        val analysedFen = BridgePublisher.lastAnalyzedFen ?: return
        val best = BridgePublisher.lastBestMove
        if (best != null && BridgePublisher.lastBestMoveFen == analysedFen) {
            draw(best, analysedFen)
            return
        }
        val ponder = BridgePublisher.lastPonderMove
        if (ponder != null && BridgePublisher.lastPonderFen == analysedFen) {
            drawPrediction(ponder, analysedFen)
        }
    }

    /** Entry point invoked by the command dispatcher: paint `uciMove` (e.g. "e2e4"). */
    fun draw(uciMove: String, fen: String) {
        val (from, to) = splitUci(uciMove) ?: return
        // Shared (fen,move) gate: one draw per pair, across all painter instances,
        // regardless of age. Per-frame retriggers die here for free.
        if (!BridgePublisher.needDraw(fen, uciMove)) return
        // Hop to main thread (Rive is main-only; socket/hook threads must not touch it).
        mainHandler.post { drawOnMain(uciMove, from, to) }
    }

    /**
     * Predicted enemy reply: same gate/mechanics as [draw], but RED and with NO
     * ack back to the companion (an ack would read as "bestmove confirmed" in
     * the event log) and NEVER routed to autoplay (dispatcher only draws).
     */
    fun drawPrediction(uciMove: String, fen: String) {
        drawPredictionColored(uciMove, fen, PREDICTION_COLOR)
    }

    /**
     * GRAY is the current prediction color (was RED). RED resolves but never
     * shows on the live board (renders invisible — likely missing asset in the
     * live artboard; the enum exists game-wide, probably for post-game review).
     * Single constant so the next color test is a one-line change.
     */
    fun drawPredictionColored(uciMove: String, fen: String, colorName: String) {
        val (from, to) = splitUci(uciMove) ?: return
        if (!BridgePublisher.needDraw(fen, uciMove)) return
        mainHandler.post { drawOnMain(uciMove, from, to, colorName = colorName) }
    }

    // Renderer pokes (clear + s()) landing mid-animation are the prime suspect for
    // wedging Duolingo's board state machine (manual AND auto stuck; auto more, since
    // it also taps). So draws wait for a quiet moment: no hook trigger for a short
    // window, with a hard cap so a constantly-ticking board still gets its arrow.
    private val QUIET_MS = 250L
    private val QUIET_CAP_MS = 1200L

    private fun drawOnMain(
        uciMove: String, from: String, to: String,
        waitedMs: Long = 0, colorName: String = "YELLOW",
    ) {
        val sinceTrigger = System.currentTimeMillis() - BridgePublisher.lastTriggerAt
        if (sinceTrigger < QUIET_MS && waitedMs < QUIET_CAP_MS) {
            mainHandler.postDelayed({ drawOnMain(uciMove, from, to, waitedMs + 300, colorName) }, 300)
            return
        }
        val controller = BridgeAccess.boardController ?: run {
            OverlayFallback.ensure()
            return
        }
        try {
            val m = resolveDrawMethod(controller) ?: run {
                MainHook.log("!! controller.s not resolved; overlay fallback")
                OverlayFallback.ensure()
                OverlayFallback.drawArrow(from, to, fallbackColor(colorName))
                return
            }
            val loader = controller.javaClass.classLoader
            // resolveClass failure used to be a silent `?: return` (stale
            // remap.json after an app update) — log it loudly and degrade to
            // the overlay instead of arrows just never appearing.
            val squareClz = remap.resolveClass(loader, "square") ?: run {
                MainHook.log("!! square class not resolved (remap.json stale? regenerate with tools/remap.py)")
                OverlayFallback.ensure()
                OverlayFallback.drawArrow(from, to, fallbackColor(colorName))
                return
            }
            val fromSq = makeSquare(squareClz, from) ?: return
            val toSq = makeSquare(squareClz, to) ?: return
            val solid = enumConstant(loader, "arrowType", "SOLID")
            val color = resolveArrowColor(loader, colorName)
            if (solid == null || color == null) {
                MainHook.log("!! enum constants missing")
                return
            }
            // Mirror Duolingo's own game-review flow (hq4.n): reset arrows first, then draw.
            clearNow(controller)
            // s(from, to, animate=false, SOLID, mode=0, color) — CONFIRMED against hq4.j
            // and against the live 6.85.7 signature seen in the LSPosed log.
            m.invoke(controller, fromSq, toSq, false, solid, 0, color)
            // Ack only real bestmoves: a prediction ack would masquerade as a
            // confirmed recommendation in the companion's event log.
            if (colorName == "YELLOW") BridgePublisher.markArrowDrawn(uciMove)
            MainHook.log("arrow drawn: $uciMove ($colorName, waited=${waitedMs}ms)")
        } catch (t: Throwable) {
            MainHook.log("arrow draw failed: $t")
            OverlayFallback.ensure()
            OverlayFallback.drawArrow(from, to, fallbackColor(colorName))
        }
    }

    /** Reset arrows — mirrors the app's own `cl5.r()` call before drawing (see hq4.n). */
    fun clear() {
        BridgePublisher.clearDrawn()
        val controller = BridgeAccess.boardController ?: return
        // Same reason as draw(): the controller owns Rive state — main thread only.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearNow(controller)
        } else {
            mainHandler.post { clearNow(BridgeAccess.boardController ?: return@post) }
        }
    }

    private fun clearNow(controller: Any) {
        try {
            controller.javaClass.methods
                .firstOrNull { it.name == "r" && it.parameterCount == 0 }
                ?.invoke(controller)
        } catch (t: Throwable) {
            MainHook.log("clear failed: $t")
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun resolveDrawMethod(controller: Any): java.lang.reflect.Method? {
        sMethod?.let { return it }
        val typeClz = remap.resolveClass(controller.javaClass.classLoader, "arrowType") ?: return null
        val colorClz = remap.resolveClass(controller.javaClass.classLoader, "arrowColor") ?: return null
        val sqClz = remap.resolveClass(controller.javaClass.classLoader, "square") ?: return null
        val m = controller.javaClass.declaredMethods.firstOrNull {
            it.parameterTypes.size == 6 &&
                it.parameterTypes[0] == sqClz &&
                it.parameterTypes[1] == sqClz &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[3] == typeClz &&
                it.parameterTypes[4] == Integer.TYPE &&
                it.parameterTypes[5] == colorClz
        } ?: return null
        m.isAccessible = true
        sMethod = m
        return m
    }

    /**
     * ArrowColor probe: RED is assumed but NOT confirmed in 6.85.7 (only YELLOW
     * is). Resolved live once; on miss the available values are logged to the
     * LSPosed log and we fall back to YELLOW — the arrow always appears, worst
     * case in the classic color.
     */
    @Volatile private var probedColors: String? = null

    private fun resolveArrowColor(loader: ClassLoader, name: String): Any? {
        // Always report the full enum once (diagnosing "RED resolves but renders
        // invisible/yellow": the constant existing says nothing about rendering).
        if (probedColors == null) {
            probedColors = try {
                remap.resolveClass(loader, "arrowColor")
                    ?.enumConstants?.map { (it as java.lang.Enum<*>).name() }?.toString()
            } catch (_: Throwable) {
                "?"
            }
            MainHook.log("arrowColor values=$probedColors (want $name)")
        }
        enumConstant(loader, "arrowColor", name)?.let { return it }
        MainHook.log("arrowColor has no $name; available=$probedColors (falling back to YELLOW)")
        return enumConstant(loader, "arrowColor", "YELLOW")
    }

    /** Best-effort overlay mirror of the native arrow color (fallback path only). */
    private fun fallbackColor(colorName: String): Int = when (colorName) {
        "RED" -> Color.argb(190, 255, 60, 40)
        "GRAY" -> Color.argb(190, 160, 160, 160)
        else -> Color.argb(190, 255, 210, 0)
    }

    /** ge4 ctor is CONFIRMED (row: Int from rank 1, col: Int) — see docs §Board & squares. */
    private fun makeSquare(squareClz: Class<*>, coord: String): Any? {
        val col = coord[0] - 'a'
        val row = coord[1] - '1'
        return try {
            XposedHelpers.newInstance(squareClz, row, col)
        } catch (t: Throwable) {
            MainHook.log("makeSquare failed: $t")
            null
        }
    }

    private fun enumConstant(loader: ClassLoader, key: String, name: String): Any? = try {
        val clz = remap.resolveClass(loader, key) ?: return null
        XposedHelpers.getStaticObjectField(clz, name)
    } catch (t: Throwable) {
        MainHook.log("enum $key.$name failed: $t")
        null
    }

    private fun splitUci(uci: String): Pair<String, String>? {
        if (uci.length < 4) return null
        val f = uci.substring(0, 2)
        val t = uci.substring(2, 4)
        return f to t
    }
}
