package com.chessassist.hook

import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Fallback hook layer when the R8-minified model classes change layout:
 * the ViewModel classes under com/duolingo/feature/chess and com/duolingo/chess keep
 * their names (they are referenced from Compose navigation & Hilt), so we hook them
 * and mine any String field/method result that looks like FEN.
 *
 * COST MODEL: the hooked methods fire at frame rate on the UI thread (Compose
 * recomposition reads StateFlow every frame), so the hook body itself must be
 * nearly free — it only stores the result in a volatile slot and schedules a
 * throttled scan. The reflection-heavy BFS runs on a dedicated background
 * HandlerThread, bounded by [MAX_SCAN_OBJECTS] per pass (one wide field used to
 * fan out to thousands of reflection reads — a regular once-per-second main-thread
 * stutter). [StateBridge.acceptExternalFen] is volatile/synchronized on both ends,
 * so background publication is safe.
 */
object ViewModelFallback {

    private const val MAX_SCAN_OBJECTS = 400

    @Volatile private var lastScanAt: Long = 0
    @Volatile private var scanTarget: Any? = null
    private var bridgeRef: StateBridge? = null
    private var scanHandler: Handler? = null
    private val scheduleLock = Any()
    private var scanScheduled = false

    // NOTE: no `^` anchor on purpose. Without MULTILINE, `^` matches only at input
    // position 0, which silently disabled the entire documented purpose of this
    // fallback ("FENs appear embedded in longer strings" — they do, e.g. in
    // data-class toStrings like "ChessBotMatch(fen=..."). With `^`, findAll could
    // only ever match whole-string FENs, so after an update that renames the primary
    // model classes this fallback mined nothing and the bridge stayed dark.
    private val FEN_RE = Regex(
        "([pnbrqkPNBRQK1-8]{1,8}/){7}[pnbrqkPNBRQK1-8]{1,8}\\s[wb]\\s(-|[KQkq]{1,4})(\\s(-|[a-h][36]))?(\\s\\d+)?(\\s\\d+)?"
    )

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam, remap: Remap, bridge: StateBridge) {
        val loader = lpparam.classLoader
        bridgeRef = bridge
        if (scanHandler == null) {
            val t = HandlerThread("duo-chess-vmscan").apply { isDaemon = true; start() }
            scanHandler = Handler(t.looper)
        }
        val candidates = listOf(
            "com.duolingo.feature.chess.match.ChessMatchSessionViewModel",
            "com.duolingo.feature.chess.minimatch.ChessMiniMatchSessionViewModel",
            "com.duolingo.feature.chess.minimatch.ChessMiniMatchViewModel",
            "com.duolingo.session.challenges.chess.ChessPuzzleViewModel",
            "com.duolingo.feature.chess.gamereview.ChessGameReviewViewModel"
        )
        var hooked = 0
        for (name in candidates) {
            val clz = try { XposedHelpers.findClass(name, loader) } catch (_: Throwable) { continue }
            for (m in clz.declaredMethods) {
                if (m.parameterTypes.isNotEmpty()) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // Frame-rate hot path on the UI thread: two volatile ops,
                            // nothing else. The throttle only bounds how OFTEN a scan
                            // is scheduled; the latest result is always what gets scanned.
                            val now = System.currentTimeMillis()
                            if (now - lastScanAt < 1000) return
                            lastScanAt = now
                            val r = param.result ?: return
                            scanTarget = r
                            synchronized(scheduleLock) {
                                if (scanScheduled) return
                                scanScheduled = true
                            }
                            scanHandler?.post(::runScan)
                        }
                    })
                    hooked++
                } catch (_: Throwable) { }
            }
        }
        MainHook.log("ViewModelFallback hooked $hooked methods")
    }

    /** Runs on the scan HandlerThread. */
    private fun runScan() {
        synchronized(scheduleLock) { scanScheduled = false }
        val target = scanTarget ?: return
        val bridge = bridgeRef ?: return
        try {
            scan(target, bridge, depth = 0, budget = intArrayOf(MAX_SCAN_OBJECTS))
        } catch (_: Throwable) { }
    }

    /**
     * BFS over fields of [obj] looking for anything FEN-shaped (max depth 3).
     * [budget] is a single-element counter decremented per visited node; exhausted
     * budget stops the walk so one pathological field can't monopolize the thread.
     */
    private fun scan(obj: Any?, bridge: StateBridge, depth: Int, budget: IntArray) {
        if (obj == null || depth > 3) return
        if (budget[0] <= 0) return
        budget[0]--
        when (obj) {
            is String -> {
                // FENs appear embedded in longer strings ("ChessBotMatch(fen=... w - - 0 1…")
                for (m in FEN_RE.findAll(obj)) {
                    bridge.acceptExternalFen(m.value)
                }
            }
            // Iterable covers List AND Set (a Set would otherwise fall into the
            // reflection branch, hit the java.* guard, and be skipped entirely).
            is Iterable<*> -> obj.forEach { scan(it, bridge, depth + 1, budget) }
            is Array<*> -> obj.forEach { scan(it, bridge, depth + 1, budget) }
            is Map<*, *> -> obj.forEach { (k, v) -> scan(k, bridge, depth + 1, budget); scan(v, bridge, depth + 1, budget) }
            else -> {
                val clz = obj.javaClass
                if (clz.name.startsWith("java.") || clz.name.startsWith("android.")) return
                for (f in clz.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        scan(f.get(obj), bridge, depth + 1, budget)
                    } catch (_: Throwable) { }
                }
            }
        }
    }
}
