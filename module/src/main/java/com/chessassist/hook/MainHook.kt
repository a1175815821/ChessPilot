package com.chessassist.hook

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed entry point. Scope is com.duolingo only (see res/values/arrays.xml).
 *
 * Strategy (see docs/REVERSE_ENGINEERING.md):
 *  - Primary:  hook [chessGameState] constructor  → every new position surfaces here.
 *  - Secondary: hook [chessFen] constructor       → raw FEN parts (works in lesson + match modes).
 *  - UI:       hook [boardController] `g()` return → confirms the Rive board rendered `state`,
 *              and gives us the object to call `s(...)` (arrow) / `f(col,row)` (tap→square) on.
 *  - Fallback: ViewModel StateFlow tap if constructors are renamed or not hit.
 *
 * All names come from assets/remap.json so the module survives R8 renames across app updates
 * (regenerate with tools/remap.py).
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "DuoChessAssist"
        private const val TARGET_PACKAGE = "com.duolingo"
        fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
    }

    private lateinit var remap: Remap
    private lateinit var bridge: StateBridge
    private lateinit var arrowPainter: ArrowPainter
    private val initialized = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        // The companion command queue is single-slot consume-once: every process that
        // polls it can steal the foreground board's bestmove. Duolingo ships a :Unity
        // (ads) process, so only the main process may hook/poll.
        if (lpparam.processName != TARGET_PACKAGE) {
            log("skip non-main process=${lpparam.processName}")
            return
        }
        log("loaded into ${lpparam.packageName} process=${lpparam.processName}")

        // Defer everything else until Application.attach fires: we need a Context to
        // load OUR remap.json via AssetManager (assets are invisible to ClassLoader).
        hookApplicationAttach(lpparam)
    }

    // ---------------------------------------------------------------- Application

    /** Grab the app Context as early as possible (needed for overlay/display metrics later). */
    private fun hookApplicationAttach(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            android.content.Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        BridgeAccess.context = param.args[0] as android.content.Context
                        log("context captured: ${BridgeAccess.context}")
                    } catch (t: Throwable) {
                        log("context capture failed: $t")
                        return
                    }
                    if (initialized.compareAndSet(false, true)) {
                        initAfterContext(lpparam)
                    }
                }
            }
        )
    }

    /** Runs once, on the main process, right after we own a Context. */
    private fun initAfterContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // NOTE: no AssetManager/PackageManager lookups here on purpose. Assets are
            // invisible to the module ClassLoader in the target process, and
            // createPackageContext(ours) from Duolingo always throws NameNotFoundException
            // on Android 11+ (package visibility). The table is baked in at compile time.
            remap = Remap.fromBaked()
            log("remap table v${RemapTable.APP_VERSION}")
            bridge = StateBridge()
            arrowPainter = ArrowPainter(remap)

            hookChessGameState(lpparam)
            hookChessFen(lpparam)
            hookBoardController(lpparam)
            hookRiveViews(lpparam)
            ViewModelFallback.hook(lpparam, remap, bridge)
            PromoTrace.install(lpparam, remap)
            RivePicker.install(lpparam)
            CommandLoop.configure(remap, bridge)
        } catch (t: Throwable) {
            log("initAfterContext failed: $t")
        }
    }

    // ---------------------------------------------------------------- Rive views

    /**
     * Record every Rive board-view instance at construction. Kept (unobfuscated) names,
     * confirmed in 6.85.7 dex: `app.rive.runtime.kotlin.RiveAnimationView` and
     * `com.duolingo.core.rive.RiveWrapperView2$CallbackRiveAnimationView`.
     * This is the robust autoplay-tap target — the controller-field scan is only backup.
     */
    private fun hookRiveViews(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader
        val names = listOf(
            "app.rive.runtime.kotlin.RiveAnimationView",
            "com.duolingo.core.rive.RiveWrapperView2\$CallbackRiveAnimationView",
        )
        for (name in names) {
            try {
                val clz = XposedHelpers.findClass(name, loader)
                XposedBridge.hookAllConstructors(clz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            (param.thisObject as? android.view.View)?.let {
                                RiveViewFinder.noteCtorInstance("ctor", it)
                            }
                        } catch (t: Throwable) { log("rive ctor hook error: $t") }
                    }
                })
                log("watching Rive views: $name")
            } catch (t: Throwable) {
                log("rive class not found (ok if renamed): $name")
            }
        }
    }

    // ---------------------------------------------------------------- primary: ChessGameState

    private fun hookChessGameState(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader
        val clz = remap.resolveClass(loader, "chessGameState")
        if (clz == null) {
            log("!! ChessGameState not found — check remap.json")
            return
        }
        for (ctor in clz.declaredConstructors) {
            try {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = param.thisObject
                            bridge.onGameState(state)
                            // Immediately ask the painter to draw the best move for this state
                            // if the analysis loop already has one (low-latency path: the arrow
                            // is drawn the moment the board catches up to our analysis).
                            arrowPainter.onGameState(state)
                        } catch (t: Throwable) {
                            log("state-hook error: $t")
                        }
                    }
                })
            } catch (t: Throwable) {
                log("hook ctor failed $ctor: $t")
            }
        }
        log("hooked ChessGameState = ${clz.name} (${clz.declaredConstructors.size} ctors)")
    }

    // ---------------------------------------------------------------- secondary: ChessFen

    private fun hookChessFen(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader
        val clz = remap.resolveClass(loader, "chessFen") ?: return
        for (ctor in clz.declaredConstructors) {
            try {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            bridge.onFen(param.thisObject, loader)
                        } catch (t: Throwable) {
                            log("fen-hook error: $t")
                        }
                    }
                })
            } catch (t: Throwable) { log("hook fen ctor failed: $t") }
        }
        log("hooked ChessFen = ${clz.name}")
    }

    // ---------------------------------------------------------------- UI: board controller

    /**
     * `cl5` is the Rive board controller:
     *   g() → current ChessGameState (rendered)
     *   s(from, to, animate, ArrowType, Int, ArrowColor) → draw arrow
     *   f(col, row) → Square for a logical tap coordinate (auto-play mapping)
     *   j(sq, sq2, SquareHighlightType) → square highlight
     */
    private fun hookBoardController(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader
        val clz = remap.resolveClass(loader, "boardController")
        if (clz == null) {
            log("!! BoardController not found — arrows will use overlay fallback")
            OverlayFallback.ensure()
            return
        }

        // 1) observe the rendered state: hook every method returning the state class.
        // Each registration is isolated: one bad method must not abort the rest.
        val stateClz = remap.resolveClass(loader, "chessGameState")
        for (m in clz.declaredMethods) {
            if (stateClz != null && m.returnType == stateClz && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                BridgeAccess.boardController = param.thisObject
                                val state = param.result
                                if (state != null) {
                                    bridge.onRenderedState(state)
                                    arrowPainter.onRenderedState(state)
                                }
                            } catch (t: Throwable) { log("render-hook error: $t") }
                        }
                    })
                    log("hooked boardController.g() = ${m.name}")
                } catch (t: Throwable) { log("hook render method failed ${m.name}: $t") }
            }
        }

        // 2) learn the app's own arrow-draw call signature (log-only, once per signature)
        for (m in clz.declaredMethods) {
            if (m.parameterTypes.size == 6 && m.parameterTypes[2] == Boolean::class.javaPrimitiveType) {
                log("boardController.s candidate: ${m.name} ${m.parameterTypes.joinToString()}")
            }
        }

        // 3) observe square taps → these are what the auto-player replays
        //    `f(Int, Int): Square` converts a logical (col,row) tap to a Square.
        for (m in clz.declaredMethods) {
            val pt = m.parameterTypes
            if (pt.size == 2 && pt[0] == Integer.TYPE && pt[1] == Integer.TYPE &&
                m.returnType == remap.resolveClass(loader, "square")
            ) {
                BridgeAccess.squareFromTapMethod = m
                log("boardController tap→square: ${m.name}")
            }
        }

        // Expose the controller to the painter/auto-player.
        BridgeAccess.boardControllerClass = clz

        // Locate the live board View inside the controller so that OverlayFallback
        // and AutoPlayer have real view bounds to work with. Runtime field scan
        // (RiveViewFinder): matching declared types by name fails after R8 renames
        // the wrapper types, which silently disabled autoplay ("no board view yet").
        try {
            XposedBridge.hookAllConstructors(clz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // A fresh board controller means a fresh board context (new
                        // game / lesson retry): re-arm publishing so a repeated setup
                        // republishes instead of being deduped away. Partial reset
                        // on purpose — the last bestmove is kept for instant redraw.
                        BridgePublisher.resetPublishState()
                        RiveViewFinder.onControllerCreated(param.thisObject)
                    } catch (t: Throwable) {
                        log("rive view discovery failed: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            log("rive view discovery failed: $t")
        }
    }
}
