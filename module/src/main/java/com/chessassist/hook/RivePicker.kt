package com.chessassist.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Rive-picker drive (the stable promotion path — no coordinates anywhere).
 *
 * Settled facts (2026-09-05, live evidence):
 * - The picker adds no Views, opens no window, and has empty Compose roots:
 *   it is drawn by the board Rive file (`res/raw/chess_board_v67.riv`).
 * - That file carries the picker protocol as named items: the `PAWN PROMOTION`
 *   state machine, picker buttons as listener hitboxes (`hitbox_q/r/n/b`),
 *   and inputs (`hide_promotion_box`, `board_promotion_mode_reset`,
 *   `on_promoted_event_trigger`, `new_selection_trigger`, ...).
 * - Our Rive-dispatched taps DO reach it (d8 queen hit once); the failure mode
 *   was purely positional (panel clamps per file), which is why fixed
 *   coordinates were deleted.
 *
 * This object does two read-only things until the protocol is pinned:
 *  1. [probeRive]: enumerate the live controller (artboards, state machines,
 *     input names/types/values, listeners) — called from `promoscan` and at
 *     every promotion arm. Pure reflection, capped logging.
 *  2. Input/listener call trace: hooks on SMI setters + game-registered Rive
 *     listeners (discovered per arm) log name+value of every Rive↔game signal.
 *     The next manual queen tap reveals the exact confirm signal; the build
 *     after that replays it directly (setValue/fire/listener callback).
 */
object RivePicker {

    private val inventoried = mutableSetOf<String>()
    private val hookedListeners = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Any, Boolean>()
    )
    private val throttle = mutableMapOf<String, Long>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookSmiInputs(lpparam.classLoader)
        } catch (t: Throwable) {
            MainHook.log("rivepicker: smi hook install failed: ${t.message?.take(100)}")
        }
    }

    // ------------------------------------------------------------ trace -----

    private fun throttled(key: String, ms: Long = 5000): Boolean {
        val now = System.currentTimeMillis()
        synchronized(throttle) {
            if (now - (throttle[key] ?: 0) < ms) return true
            throttle[key] = now
            return false
        }
    }

    private fun logMethodsOnce(clz: Class<*>, tag: String) {
        if (!inventoried.add(clz.name)) return
        try {
            val sigs = clz.declaredMethods
                .filter { !it.isSynthetic }
                .map { m ->
                    val ps = m.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') }
                    "${m.name}($ps):${m.returnType.name.substringAfterLast('.')}"
                }
                .sorted().joinToString(" ").take(800)
            MainHook.log("rivepicker: $tag ${clz.name} methods=[$sigs]")
        } catch (_: Throwable) { }
    }

    /**
     * Hook SMI input WRITES only (set/fire entry points). Never getters — and
     * the hook body never invokes ANY method on the hooked object.
     *
     * Lesson learned the hard way (ANR, 2026-09-05): hooking `get*` while the
     * hook body itself calls `getName()` is direct infinite recursion
     * (hook → getName → hook → … → StackOverflowError caught per level). Every
     * Rive input read then costs ~10k stack frames, and Rive-heavy screens
     * freeze solid. This version cannot recurse by construction: it hooks only
     * write entry points, and the body touches nothing but static metadata
     * (method name, arg class names — never a call into the target).
     */
    private fun hookSmiInputs(loader: ClassLoader) {
        val targets = listOf(
            "app.rive.runtime.kotlin.core.SMIBoolean",
            "app.rive.runtime.kotlin.core.SMINumber",
            "app.rive.runtime.kotlin.core.SMITrigger",
            "app.rive.runtime.kotlin.core.SMIInput",
            "app.rive.runtime.kotlin.core.StateMachineInstance"
        )
        for (name in targets) {
            try {
                val clz = Class.forName(name, false, loader)
                logMethodsOnce(clz, "smi")
                for (m in clz.declaredMethods) {
                    if (m.isSynthetic) continue
                    val n = m.name
                    if (!(n.startsWith("set") || n.startsWith("fire"))) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    // Static metadata only — no calls into param.thisObject,
                                    // or this hook recurses into itself.
                                    val selfCls = try {
                                        param.thisObject?.javaClass?.name?.substringAfterLast('.')
                                    } catch (_: Throwable) { "?" }
                                    val args = param.args.map {
                                        when (it) {
                                            null -> "null"
                                            is String -> it.take(40)
                                            is Number, is Boolean -> it.toString()
                                            else -> try {
                                                it.javaClass.name.substringAfterLast('.').take(20)
                                            } catch (_: Throwable) { "?" }
                                        }
                                    }.joinToString(",").take(120)
                                    if (throttled("smi:$n:$args")) return
                                    MainHook.log("rivepicker: smi.$n[$selfCls] args=[$args]")
                                } catch (_: Throwable) { }
                            }
                        })
                    } catch (_: Throwable) { }
                }
                MainHook.log("rivepicker: watching ${clz.simpleName}")
            } catch (t: Throwable) {
                MainHook.log("rivepicker: no $name (${t.message?.take(60)})")
            }
        }
    }

    // ------------------------------------------------------------ probe -----

    /** No-arg reflective call, null on any failure. */
    private fun Any.call0(vararg names: String): Any? {
        for (n in names) {
            try {
                return this.javaClass.getMethod(n).invoke(this)
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun Any.field0(vararg names: String): Any? {
        for (n in names) {
            try {
                val f = this.javaClass.getDeclaredField(n)
                try { f.isAccessible = true } catch (_: Throwable) { }
                return f.get(this)
            } catch (_: Throwable) { }
        }
        return null
    }

    /**
     * Full live-controller census. Called from `promoscan` and on every
     * promotion arm (cheap: a few dozen reflective calls, ~20 log lines).
     */
    fun probeRive(tag: String) {
        try {
            val view = try {
                RiveViewFinder.bestRiveView() ?: RiveViewFinder.ensure()
            } catch (_: Throwable) { null } ?: run {
                MainHook.log("rivepicker: $tag no board view")
                return
            }
            logMethodsOnce(view.javaClass, "view")
            val controller = view.call0("getController", "getRiveController")
                ?: view.field0("controller", "riveController")
                ?: run {
                    MainHook.log("rivepicker: $tag no controller accessor")
                    return
                }
            logMethodsOnce(controller.javaClass, "controller")
            MainHook.log("rivepicker: $tag controller=${controller.javaClass.name}")

            // Artboards + state machines by shape (names kept or not, types don't lie).
            val artboards = mutableListOf<Any>()
            for (m in controller.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val rt = m.returnType.name
                if (!rt.contains("Artboard")) continue
                try {
                    when (val v = m.invoke(controller)) {
                        is List<*> -> artboards.addAll(v.filterNotNull())
                        null -> {}
                        else -> artboards.add(v)
                    }
                } catch (_: Throwable) { }
            }
            // Common explicit accessors as backup.
            if (artboards.isEmpty()) {
                val ab = controller.call0("getActiveArtboard", "getArtboard", "activeArtboard")
                if (ab != null) artboards += ab
            }
            MainHook.log("rivepicker: $tag artboards=${artboards.size}")
            for (ab in artboards.take(4)) {
                try {
                    logMethodsOnce(ab.javaClass, "artboard")
                    val abName = ab.call0("getName") as? String ?: "?"
                    // State machine instances + names.
                    val sms = mutableListOf<Any>()
                    for (m in ab.javaClass.methods) {
                        if (m.parameterTypes.isNotEmpty()) continue
                        if (!m.returnType.name.contains("StateMachine")) continue
                        try {
                            when (val v = m.invoke(ab)) {
                                is List<*> -> sms.addAll(v.filterNotNull())
                                null -> {}
                                else -> sms.add(v)
                            }
                        } catch (_: Throwable) { }
                    }
                    val smNames = ab.call0("getStateMachineNames", "stateMachineNames")
                    MainHook.log("rivepicker: $tag artboard=[$abName] sms=${sms.size} names=$smNames")
                    for (sm in sms.take(6)) dumpStateMachine(sm, tag)
                    // Listeners registered on controller (game side).
                    hookListenersOf(controller)
                } catch (t: Throwable) {
                    MainHook.log("rivepicker: $tag artboard walk failed: ${t.message?.take(100)}")
                }
            }
            if (artboards.isEmpty()) hookListenersOf(controller)
        } catch (t: Throwable) {
            MainHook.log("rivepicker: $tag probe failed: ${t.message?.take(120)}")
        }
    }

    private fun dumpStateMachine(sm: Any, tag: String) {
        try {
            logMethodsOnce(sm.javaClass, "sm")
            val smName = sm.call0("getName") as? String ?: "?"
            // Input names (String list, no-arg).
            var inputNames: List<*>? = null
            for (m in sm.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                if (m.returnType != java.util.List::class.java) continue
                try {
                    val v = m.invoke(sm) as? List<*> ?: continue
                    if (v.isNotEmpty() && v.all { it is String }) {
                        inputNames = v
                        break
                    }
                } catch (_: Throwable) { }
            }
            MainHook.log("rivepicker: $tag sm=[$smName] inputs=$inputNames")
            // Current value per input (bool/number/trigger/null).
            for (nm in (inputNames ?: emptyList<Any?>()).filterIsInstance<String>().take(40)) {
                try {
                    val cur = readInput(sm, nm)
                    MainHook.log("rivepicker: $tag sm=[$smName] in=[$nm] $cur")
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            MainHook.log("rivepicker: $tag sm dump failed: ${t.message?.take(100)}")
        }
    }

    private fun readInput(sm: Any, name: String): String {
        for (m in sm.javaClass.methods) {
            val ps = m.parameterTypes
            if (ps.size != 1 || ps[0] != String::class.java) continue
            if (!(m.name.startsWith("get") || m.name.startsWith("is"))) continue
            try {
                val v = m.invoke(sm, name) ?: continue
                val vc = v.javaClass
                val value = try {
                    vc.methods.firstOrNull {
                        it.parameterTypes.isEmpty() &&
                            (it.name == "getValue" || it.name == "isValue" || it.name == "get fire" || it.name == "getTriggered")
                    }?.invoke(v)
                } catch (_: Throwable) { "?" }
                return "${vc.name.substringAfterLast('.')}=$value"
            } catch (_: Throwable) { }
        }
        return "?";
    }

    /** Hook the game's Rive listeners (log-only) so the queen-pick event is captured. */
    private fun hookListenersOf(controller: Any) {
        try {
            val found = mutableListOf<Any>()
            for (m in controller.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val rt = m.returnType
                val looksListeners =
                    (rt.name.contains("Listener") || rt == java.util.List::class.java || rt == java.util.Set::class.java)
                if (!looksListeners && !m.name.contains("isten", true)) continue
                try {
                    when (val v = m.invoke(controller)) {
                        is Collection<*> -> found.addAll(v.filterNotNull())
                        null -> {}
                        else -> if (!v.javaClass.name.startsWith("java.")) found.add(v)
                    }
                } catch (_: Throwable) { }
            }
            for (f in controller.javaClass.declaredFields) {
                try {
                    if (!f.type.name.contains("Listener") && !java.util.Collection::class.java.isAssignableFrom(f.type)) continue
                    try { f.isAccessible = true } catch (_: Throwable) { }
                    when (val v = f.get(controller)) {
                        is Collection<*> -> found.addAll(v.filterNotNull())
                        null -> {}
                        else -> found.add(v)
                    }
                } catch (_: Throwable) { }
            }
            var n = 0
            for (lis in found.distinct()) {
                if (!hookedListeners.add(lis)) continue
                try {
                    logMethodsOnce(lis.javaClass, "listener")
                    for (m in lis.javaClass.declaredMethods) {
                        if (m.isSynthetic) continue
                        try {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val args = param.args.map {
                                            when (it) {
                                                null -> "null"
                                                is String -> it.take(60)
                                                is Number, is Boolean -> it.toString()
                                                else -> describeEvent(it)
                                            }
                                        }.joinToString(",").take(300)
                                        MainHook.log("rivepicker: evt.${m.name}($args)")
                                    } catch (_: Throwable) { }
                                }
                            })
                            n++
                        } catch (_: Throwable) { }
                    }
                } catch (_: Throwable) { }
            }
            if (n > 0) MainHook.log("rivepicker: hooked $n listener methods")
        } catch (t: Throwable) {
            MainHook.log("rivepicker: listener hook failed: ${t.message?.take(100)}")
        }
    }

    private fun describeEvent(o: Any): String {
        return try {
            val cn = o.javaClass.name.substringAfterLast('.')
            val parts = mutableListOf<String>()
            for (m in o.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val n = m.name
                if (!(n.startsWith("get") || n.startsWith("is")) || n == "getClass") continue
                try {
                    val v = m.invoke(o) ?: continue
                    if (v is String || v is Number || v is Boolean) {
                        parts.add("$n=$v")
                        if (parts.size >= 6) break
                    } else if (v is Map<*, *>) {
                        parts.add("$n=${v.toString().take(120)}")
                        if (parts.size >= 6) break
                    }
                } catch (_: Throwable) { }
            }
            "$cn{${parts.joinToString(" ").take(200)}}"
        } catch (_: Throwable) { o.javaClass.name.substringAfterLast('.') }
    }
}
