package com.chessassist.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Promotion endgame tracer: stop guessing coordinates, drive the game's own API.
 *
 * Why: the picker is not a View (diff always empty), Compose semantics are
 * R8-renamed with empty roots, and pinned coordinates die on every layout shift
 * (d8 worked, g8 picked the rook — panel clamps to the board edge). The game
 * itself must apply the promotion through SOME method taking the chosen piece;
 * this finds it dynamically, using the repeatable promotion tutorial as the lab:
 *
 *   1. Move constructors (promotion piece is Move.f): logged with args + the
 *      Duolingo-only caller chain. Piece-carrying ctors always log; plain ones
 *      are throttled (they fire for every move of every game).
 *   2. Board-controller full trace, gated by [arm] (20 s after a promotion
 *      performMove starts) + 5 s per-method throttle — catches the tap handler
 *      and any picker-selection call without spamming normal games.
 *   3. Kept ViewModels (puzzle / match / minimatch / pvp): any method taking a
 *      ChessPieceType is the promotion-selector callback shape — always logged.
 *   4. [inventoryRive]: Rive controller surface dump, called from the manual
 *      `promoscan` probe (safe volume, on demand only).
 *
 * Procedure: restart Duolingo → replay the promotion tutorial → when the picker
 * opens, tap the queen MANUALLY → the LSPosed log then shows the exact call
 * chain, and the next build invokes that method directly with QUEEN.
 */
object PromoTrace {

    @Volatile private var armedUntil = 0L
    private val lastLog = mutableMapOf<String, Long>()

    /** Open the trace window (covers 3 attempts + the user's manual tap). */
    fun arm(ms: Long = 20000L) {
        armedUntil = System.currentTimeMillis() + ms
    }

    private fun armed() = System.currentTimeMillis() < armedUntil

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, remap: Remap) {
        hookMoveCtors(lpparam, remap)
        hookBoardController(lpparam, remap)
        hookPieceTypeMethods(lpparam)
    }

    private fun fmtArg(a: Any?): String {
        if (a == null) return "null"
        return try {
            val n = a.javaClass.name
            when {
                n.contains("ChessPieceType") || n.contains("ChessPlayerColor") -> a.toString()
                a is String -> a.take(24)
                a is Number || a is Boolean -> a.toString()
                else -> n.substringAfterLast('.').take(24) + "@" + Integer.toHexString(System.identityHashCode(a)).take(4)
            }
        } catch (_: Throwable) { "?" }
    }

    // ------------------------------------------------------- 1) Move ctors -----

    private fun hookMoveCtors(lpparam: XC_LoadPackage.LoadPackageParam, remap: Remap) {
        try {
            val moveClz = remap.resolveClass(lpparam.classLoader, "move")
            if (moveClz == null) {
                MainHook.log("promotrace: Move class not resolved")
                return
            }
            var n = 0
            for (ctor in moveClz.declaredConstructors) {
                try {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val hasPiece = param.args.any {
                                    it != null && it.javaClass.name.contains("ChessPieceType")
                                }
                                if (!hasPiece) {
                                    // Plain moves fire constantly — throttle hard.
                                    val now = System.currentTimeMillis()
                                    synchronized(lastLog) {
                                        if (now - (lastLog["move"] ?: 0) < 10_000) return
                                        lastLog["move"] = now
                                    }
                                }
                                val args = param.args.map { fmtArg(it) }.joinToString(",").take(200)
                                val via = Thread.currentThread().stackTrace
                                    .map { it.className }
                                    .filter { it.contains("duolingo") && !it.contains("chessassist") }
                                    .take(6).joinToString(" < ").take(300)
                                MainHook.log("promotrace: Move${if (hasPiece) "[PROMO]" else ""} args=[$args] via $via")
                            } catch (_: Throwable) { }
                        }
                    })
                    n++
                } catch (_: Throwable) { }
            }
            MainHook.log("promotrace: hooked $n Move ctors (${moveClz.name})")
        } catch (t: Throwable) {
            MainHook.log("promotrace move hook failed: ${t.message?.take(100)}")
        }
    }

    // ------------------------------------------ 2) boardController trace -----

    private fun hookBoardController(lpparam: XC_LoadPackage.LoadPackageParam, remap: Remap) {
        try {
            val clz = try {
                remap.resolveClass(lpparam.classLoader, "boardController")
            } catch (_: Throwable) { null } ?: BridgeAccess.boardControllerClass
            if (clz == null) {
                MainHook.log("promotrace: no boardController class yet")
                return
            }
            var n = 0
            for (m in clz.declaredMethods) {
                if (m.isSynthetic) continue
                val sig = "${m.name}(${m.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') }})"
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!armed()) return
                            val now = System.currentTimeMillis()
                            synchronized(lastLog) {
                                if (now - (lastLog[sig] ?: 0) < 5000) return
                                lastLog[sig] = now
                            }
                            try {
                                val args = param.args.map { fmtArg(it) }.joinToString(",").take(160)
                                MainHook.log("promotrace: cl5.$sig args=[$args]")
                            } catch (_: Throwable) { }
                        }
                    })
                    n++
                } catch (_: Throwable) { }
            }
            MainHook.log("promotrace: watching $n boardController methods (${clz.name})")
        } catch (t: Throwable) {
            MainHook.log("promotrace controller hook failed: ${t.message?.take(100)}")
        }
    }

    // ------------------------------------- 3) ViewModel piece callbacks -----

    private fun hookPieceTypeMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val vms = listOf(
            "com.duolingo.session.challenges.chess.ChessPuzzleViewModel",
            "com.duolingo.feature.chess.match.ChessMatchSessionViewModel",
            "com.duolingo.feature.chess.minimatch.ChessMiniMatchSessionViewModel",
            "com.duolingo.feature.chess.pvp.ChessPvpMatchViewModel"
        )
        for (vm in vms) {
            try {
                val clz = Class.forName(vm, false, lpparam.classLoader)
                var n = 0
                for (m in clz.declaredMethods) {
                    if (m.isSynthetic) continue
                    if (m.parameterTypes.none { it.name.contains("ChessPieceType") }) continue
                    val sig = "${m.name}(${m.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') }})"
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val args = param.args.map { fmtArg(it) }.joinToString(",").take(160)
                                    MainHook.log("promotrace: VM.$sig args=[$args]")
                                } catch (_: Throwable) { }
                            }
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    MainHook.log("promotrace: VM.$sig -> ${fmtArg(param.result)}")
                                } catch (_: Throwable) { }
                            }
                        })
                        n++
                    } catch (_: Throwable) { }
                }
                MainHook.log("promotrace: watching $n pieceType methods on ${clz.simpleName}")
            } catch (t: Throwable) {
                MainHook.log("promotrace: VM ${vm.substringAfterLast('.')}: ${t.message?.take(80)}")
            }
        }
    }

    // ------------------------------------------ 4) Rive inventory -----

    /** Rive controller surface dump (manual `promoscan` only). */
    fun inventoryRive() {
        try {
            val c = BridgeAccess.boardController
            if (c == null) {
                MainHook.log("promotrace: no live board controller")
                return
            }
            MainHook.log("promotrace: controller ${c.javaClass.name}")
            val ms = c.javaClass.declaredMethods
                .filter { !it.isSynthetic }
                .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.name.substringAfterLast('.') }}):${it.returnType.name.substringAfterLast('.')}" }
                .sorted().joinToString(" ").take(900)
            MainHook.log("promotrace: controller methods=[$ms]")
            val fs = c.javaClass.declaredFields
                .map { "${it.name}:${it.type.name.substringAfterLast('.')}" }
                .sorted().joinToString(" ").take(500)
            MainHook.log("promotrace: controller fields=[$fs]")
        } catch (t: Throwable) {
            MainHook.log("promotrace: rive inventory failed: ${t.message?.take(120)}")
        }
    }
}
