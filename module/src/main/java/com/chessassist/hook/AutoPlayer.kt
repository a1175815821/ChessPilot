package com.chessassist.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Optional auto-play: executes Stockfish's move on the real board by dispatching
 * MotionEvent down/move/up on the Rive board view, running **inside** the Duolingo
 * process — no root-shell input injection needed.
 *
 * Coordinate mapping (CONFIRMED from bytecode, see docs/REVERSE_ENGINEERING.md):
 *   - `cl5.f(col: 1..8, row: 1..8)` maps a tap to a Square:
 *       WHITE: U(col-1, row-1)  → tap(1,1)=a1, tap(2,5)=e2   (row counted from rank 1)
 *       BLACK: U(8-col, 8-row)  → board is ROTATED 180°, not mirrored (a1 at top-right)
 *   - Therefore, screen position of square (file, rank):
 *       white: x = left + file*cell,            y = top + (7-(rank-1))*cell
 *       black: x = left + (7-file)*cell,        y = top + (rank-1)*cell
 *
 * Promotion: tap destination → app opens the promotion picker overlay → the
 * adaptive [PromotionPicker] driver taps the real queen button (View tree, then
 * Compose semantics, both fully logged), falling back to the board-relative guess
 * (one cell toward the board center from the destination square) only when the
 * picker exposes nothing scannable. The move is then verified via the state bridge.
 */
class AutoPlayer(private val remap: Remap, private val bridge: StateBridge) {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rand = java.util.Random()

    companion object {
        const val DEFAULT_GAP_MS = 450L
        /** Max tap attempts per (fen, move). Bounded: a systematically-wrong geometry
         * must degrade to asking the user, never to an endless tap storm. */
        const val MAX_ATTEMPTS = 3
    }

    private var attemptKey: String? = null
    private var attempts = 0

    /**
     * Clickable-set snapshot taken before the select-tap of a promotion move, so the
     * picker driver can diff what the destination-tap added (see PromotionPicker).
     */
    private var prePromoKeys: Set<String> = emptySet()

    /**
     * Generation of the currently-live tap chain. A new (fen,move) command supersedes
     * everything still queued from the old one: every delayed step re-checks this
     * (plus the board fen) and aborts instead of tapping stale squares into a board
     * that has already moved on — that cross-firing is what wedged games.
     */
    @Volatile private var activeKey: String? = null

    /**
     * Chain currently executing taps. A second play tap for the SAME pair while the
     * first is still in flight (double-tap on the banner button) is ignored instead
     * of interleaving a second gesture stream into the board — interleaved taps
     * select garbage and the move silently fails ("tap twice, first does nothing").
     */
    @Volatile private var inFlightKey: String? = null

    /**
     * @param cmdFen the FEN this move was computed for; if the board has moved on
     * (latest published fen differs), the tap is skipped — never play a stale move.
     * @param gapMs base gap select-tap → destination-tap; each move gets ±20% jitter.
     */
    fun performMove(uci: String, cmdFen: String?, gapMs: Long? = null) {
        val latest = BridgePublisher.lastAnalyzedFen
        if (cmdFen != null && latest != null && latest != cmdFen) {
            MainHook.log("autoplay skipped: board moved on (cmd for ${cmdFen.take(20)}…, now ${latest.take(20)}…)")
            return
        }
        if (uci.length < 4) return
        val flipped = bridge.lastUserColor == "b"
        val gap = (gapMs ?: DEFAULT_GAP_MS).coerceIn(150L, 1500L)
        val startFen = latest
        // Per-position attempt budget (resets on a new fen/move pair).
        val key = "${cmdFen ?: "?"}|$uci"
        if (attemptKey != key) {
            attemptKey = key
            attempts = 0
        }
        if (attempts >= MAX_ATTEMPTS) {
            MainHook.log("autoplay: attempt budget spent for $uci, staying quiet")
            return
        }
        // Claim the flight synchronously: a second tap for the same pair arriving
        // while the first is still resolving the view must not start a parallel
        // gesture stream (check-and-set here; benign worst case is one overlap).
        if (inFlightKey == key) {
            MainHook.log("autoplay: chain already in flight for $uci, ignoring duplicate tap")
            return
        }
        inFlightKey = key
        activeKey = key

        mainHandler.post {
            // Resolve the view on the main thread (layout bounds valid here), with a
            // lazy rescan if the constructor hook missed it.
            val view = RiveViewFinder.bestRiveView() ?: RiveViewFinder.ensure() ?: run {
                MainHook.log(
                    "autoplay: no board view yet " +
                        "(controller=${BridgeAccess.boardController?.javaClass?.name})"
                )
                finishFlight(key)
                return@post
            }
            if (view.width <= 0 || view.height <= 0) {
                MainHook.log("autoplay: board view not laid out yet (${view.width}x${view.height})")
                finishFlight(key)
                return@post
            }
            try {
                tapMove(view, uci, flipped, gap, startFen, cmdFen, key)
            } catch (t: Throwable) {
                finishFlight(key)
                MainHook.log("autoplay down failed: $t")
            }
        }
    }

    private fun tapMove(
        view: View, uci: String, flipped: Boolean, gap: Long,
        startFen: String?, cmdFen: String?, key: String
    ) {
        val from = uci.substring(0, 2)
        val to = uci.substring(2, 4)
        val promo = uci.getOrNull(4)?.lowercaseChar()

        attempts++
        // Human-like gap with ±20% jitter; Duolingo highlights the selected square here.
        val jittered = (gap * (0.8 + rand.nextDouble() * 0.4)).toLong()
        // Promotion only: snapshot the clickable set BEFORE the select-tap so the
        // picker driver can diff what the destination-tap opens (runs on UI thread),
        // and arm the PromoTrace window (covers 3 attempts + the user's manual tap).
        prePromoKeys = if (promo != null) {
            try { PromoTrace.arm() } catch (_: Throwable) { }
            try { PromotionPicker.snapshotKeys(view) } catch (_: Throwable) { emptySet() }
        } else emptySet()
        tapSquare(view, from, flipped)
        mainHandler.postDelayed({
            try {
                if (isSuperseded(key, startFen, uci)) return@postDelayed
                tapSquare(view, to, flipped)
                if (promo != null) {
                    handlePromotion(view, to, flipped, startFen, key)
                }
                scheduleAppliedCheck(view, uci, flipped, gap, startFen, cmdFen, promo != null, key)
            } catch (t: Throwable) { MainHook.log("autoplay release failed: $t") }
        }, jittered)
        MainHook.log("autoplay performed: $uci (attempt $attempts/$MAX_ATTEMPTS, gap ${jittered}ms)")
    }

    /** True when this chain is no longer current: superseded, or board moved on. */
    private fun isSuperseded(key: String, startFen: String?, uci: String): Boolean {
        if (activeKey != key) {
            MainHook.log("autoplay: superseded, dropping stale tap for $uci")
            finishFlight(key)
            return true
        }
        val cur = BridgePublisher.lastAnalyzedFen
        if (startFen != null && cur != null && cur != startFen) {
            MainHook.log("autoplay: board moved on, dropping stale tap for $uci")
            finishFlight(key)
            return true
        }
        return false
    }

    private fun finishFlight(key: String) {
        if (inFlightKey == key) inFlightKey = null
    }

    /**
     * Did our move land on the board? Retry while the board is unchanged (bounded);
     * give up to explicit manual guidance instead of dying silent (which used to
     * deadlock the whole game: same position, no retrigger, no next move ever).
     */
    private fun scheduleAppliedCheck(
        view: View, uci: String, flipped: Boolean, gap: Long,
        startFen: String?, cmdFen: String?, isPromo: Boolean, key: String
    ) {
        mainHandler.postDelayed({
            try {
                if (isSuperseded(key, startFen, uci)) return@postDelayed
                val uci4 = uci.take(4)
                val applied = bridge.lastMoves.takeLast(3).any { it.take(4) == uci4 }
                if (applied) {
                    MainHook.log("autoplay applied: $uci")
                    finishFlight(key)
                    return@postDelayed
                }
                if (attempts < MAX_ATTEMPTS) {
                    MainHook.log("autoplay: move not detected, retrying ($attempts/$MAX_ATTEMPTS): $uci")
                    tapMove(view, uci, flipped, (gap * 1.2).toLong(), startFen, cmdFen, key)
                    return@postDelayed
                }
                MainHook.log("autoplay: giving up after $attempts attempts: $uci")
                finishFlight(key)
                BridgeClient.sendHelp(if (isPromo) "promotion" else "stuck", uci)
            } catch (t: Throwable) {
                MainHook.log("autoplay verify failed: $t")
            }
        }, gap + 1500)
    }

    /**
     * After tapping the destination square, the app shows the promotion picker.
     * The adaptive driver ([PromotionPicker]) taps the real queen button when it
     * can find one (View-tree text match, or a new same-row button cluster from
     * the before/after diff). Fixed coordinates were tried and REMOVED (d8 hit,
     * g8 picked the rook — the panel clamps per file, and any constant dies on
     * the next layout/resolution): when nothing is found we tap nothing and let
     * the user pick, while the verify loop + [PromoTrace] record everything for
     * the direct-API drive. 800 ms: the old 350 ms fired before the picker
     * finished opening. Bounded retry stays unified in scheduleAppliedCheck.
     */
    private fun handlePromotion(
        view: View, toSquare: String, flipped: Boolean, startFen: String?, key: String
    ) {
        mainHandler.postDelayed({
            try {
                if (isSuperseded(key, startFen, "promo@$toSquare")) return@postDelayed
                val tapped = try {
                    PromotionPicker.resolveAndTap(view, prePromoKeys, ::tapViewAt)
                } catch (t: Throwable) {
                    MainHook.log("promotion driver threw: ${t.message?.take(100)}")
                    false
                }
                if (!tapped) {
                    MainHook.log("promotion: no picker hit for $toSquare — waiting for manual pick (verify loop watches)")
                }
            } catch (t: Throwable) { MainHook.log("promotion tap failed: $t") }
        }, 800)
    }

    /**
     * Read-only probe: dump the current screen's view + Compose-semantics structure
     * to the log without tapping anything. Triggered by the hidden "promoscan"
     * socket command — safe to run on a live game screen any time.
     */
    fun debugScanCurrentBoard() {
        mainHandler.post {
            try {
                val view = RiveViewFinder.bestRiveView() ?: RiveViewFinder.ensure() ?: run {
                    MainHook.log("promoscan: no board view")
                    return@post
                }
                PromotionPicker.logOnlyScan(view)
            } catch (t: Throwable) {
                MainHook.log("promoscan failed: $t")
            }
        }
    }

    /** Send a full tap gesture (DOWN → 2 MOVEs → UP) centered on `squareName`. */
    private fun tapSquare(view: View, squareName: String, flipped: Boolean) {
        val file = squareName[0] - 'a'
        val rank = squareName[1] - '1'
        tapSquareAt(view, file, rank, flipped)
    }

    private fun tapSquareAt(view: View, file: Int, rank: Int, flipped: Boolean) {
        val (bx, by) = squareToScreen(view, file, rank, flipped)
        // Humanize: land slightly off-center (±8% of a cell) instead of pixel-perfect.
        val cell = minOf(view.width, view.height).toFloat() / 8f
        val x = bx + (rand.nextFloat() - 0.5f) * 0.16f * cell
        val y = by + (rand.nextFloat() - 0.5f) * 0.16f * cell
        tapViewAt(view, x, y, "${'a' + file}${rank + 1} flipped=$flipped")
    }

    /**
     * Send a full tap gesture (DOWN → 2 MOVEs → UP) at target-local coordinates.
     * The target can be any view in any window (board, queen button, window root):
     * dispatching directly on it bypasses window hit-routing, and the coordinates
     * are interpreted in that view's own frame.
     */
    private fun tapViewAt(target: View, x: Float, y: Float, label: String) {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        // Deliberate press (~150-230 ms): instant down/up taps sometimes don't register
        // and the piece snaps back.
        val press = 150L + rand.nextInt(80)
        val downTime = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, ex: Float, ey: Float, t: Long): MotionEvent {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = ex; this.y = ey; pressure = 1f; size = 1f
            })
            return MotionEvent.obtain(downTime, t, action, 1, props, coords, 0, 0, 1f, 1f,
                0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val downRet = target.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y, downTime))
        val moveRet = target.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, downTime + press / 3))
        target.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, downTime + 2 * press / 3))
        val upRet = target.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x, y, downTime + press))
        MainHook.log(
            "tap $label -> (${x.toInt()},${y.toInt()}) " +
                "view=${target.javaClass.name.substringAfterLast('.')} " +
                "${target.width}x${target.height}@(${loc[0]},${loc[1]}) " +
                "shown=${target.isShown} ret=$downRet/$moveRet/$upRet"
        )
    }

    /**
     * Square (file 0-7, rank 0-7 from white's side) → VIEW-LOCAL coordinates.
     * White: a1 bottom-left. Black: board rotated 180°.
     *
     * Must be local, not screen-absolute: View.dispatchTouchEvent consumes the event
     * as-is (the framework only translates coordinates when descending a hierarchy).
     * Passing screen coords to a view at (0,650) shifted every tap ~5 ranks down —
     * out of bounds, accepted (true) but ignored. That silently disabled autoplay.
     */
    private fun squareToScreen(view: View, file: Int, rank: Int, flipped: Boolean): Pair<Float, Float> {
        val boardSize = minOf(view.width, view.height).toFloat()
        val left = (view.width - boardSize) / 2f
        val top = (view.height - boardSize) / 2f
        val cell = boardSize / 8f

        val col = if (flipped) 7 - file else file
        val rowFromTop = if (flipped) rank else 7 - rank
        return (left + col * cell + cell / 2f) to (top + rowFromTop * cell + cell / 2f)
    }
}
