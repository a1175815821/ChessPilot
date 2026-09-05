package com.chessassist.hook

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
 * This is intentionally cheap: FEN detection is a regex, only strings pass through.
 */
object ViewModelFallback {

    @Volatile private var lastScanAt: Long = 0

    private val FEN_RE = Regex(
        "^([pnbrqkPNBRQK1-8]{1,8}/){7}[pnbrqkPNBRQK1-8]{1,8}\\s[wb]\\s(-|[KQkq]{1,4})(\\s(-|[a-h][36]))?(\\s\\d+)?(\\s\\d+)?"
    )

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam, remap: Remap, bridge: StateBridge) {
        val loader = lpparam.classLoader
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
                            // Throttle: these hooks can fire at frame rate on the UI
                            // thread, and each scan reflects over arbitrary objects.
                            // Positions change far slower than 1/s — skip the rest.
                            val now = System.currentTimeMillis()
                            if (now - lastScanAt < 1000) return
                            lastScanAt = now
                            val r = param.result ?: return
                            scan(r, bridge, depth = 0)
                        }
                    })
                    hooked++
                } catch (_: Throwable) { }
            }
        }
        MainHook.log("ViewModelFallback hooked $hooked methods")
    }

    /** BFS over fields of [obj] looking for anything FEN-shaped (max depth 3). */
    private fun scan(obj: Any?, bridge: StateBridge, depth: Int) {
        if (obj == null || depth > 3) return
        when (obj) {
            is String -> {
                // FENs appear embedded in longer strings ("ChessBotMatch(fen=... w - - 0 1…")
                for (m in FEN_RE.findAll(obj)) {
                    bridge.acceptExternalFen(m.value)
                }
            }
            is List<*> -> obj.forEach { scan(it, bridge, depth + 1) }
            is Map<*, *> -> obj.forEach { (k, v) -> scan(k, bridge, depth + 1); scan(v, bridge, depth + 1) }
            else -> {
                val clz = obj.javaClass
                if (clz.name.startsWith("java.") || clz.name.startsWith("android.")) return
                for (f in clz.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        scan(f.get(obj), bridge, depth + 1)
                    } catch (_: Throwable) { }
                }
            }
        }
    }
}
