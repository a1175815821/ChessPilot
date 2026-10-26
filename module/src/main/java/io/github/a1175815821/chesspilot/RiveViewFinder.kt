package io.github.a1175815821.chesspilot

import android.view.View
import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the live board View inside the (obfuscated) Rive board controller.
 *
 * Why runtime scanning and not a field-name/type lookup: R8 renames the wrapper types
 * (e.g. the docs' `nh3` callback wrapper), so matching declared types by suffix
 * ("RiveAnimationView") finds nothing and autoplay silently no-ops with
 * "no board view yet". Instead we enumerate the controller's fields at construction
 * time, keep every View found (unwrapping one level for wrapper holders), and pick
 * the best board candidate lazily at tap time when layout bounds are known.
 *
 * Everything is logged (field shapes, candidates, choice) so the LSPosed log doubles
 * as live reverse-engineering when Duolingo renames things again.
 */
object RiveViewFinder {

    private const val MAX_CTOR_INSTANCES = 16

    /** Rive views seen via constructor hooks (kept-name classes, R8-proof). */
    private val ctorInstances = mutableListOf<Pair<String, View>>()
    private val ctorLock = Any()

    /** Called from a hooked Rive view constructor (runs on constructing thread). */
    fun noteCtorInstance(tag: String, view: View) {
        synchronized(ctorLock) {
            if (ctorInstances.none { it.second === view }) {
                ctorInstances.add(tag to view)
                // Bound the cache: Duolingo builds a fresh Rive view per
                // lesson/screen, and holding every instance (and through it its
                // Activity) leaks memory over a long session.
                while (ctorInstances.size > MAX_CTOR_INSTANCES) {
                    ctorInstances.removeAt(0)
                }
            }
        }
        MainHook.log("riveView ctor: $tag ${view.javaClass.name}")
    }

    /** Called from the board-controller constructor hook. */
    fun onControllerCreated(controller: Any) {
        try {
            val clz = controller.javaClass
            val shape = clz.declaredFields.joinToString { "${it.name}:${it.type.name}" }
            MainHook.log("board fields: $shape")
            val found = scan(controller)
            if (found.isEmpty()) {
                MainHook.log("riveView scan: no View inside ${clz.name}")
                return
            }
            BridgeAccess.riveCandidates = found
            BridgeAccess.riveView = bestRiveView()
            for ((path, v) in found) {
                MainHook.log("riveView candidate: $path ${v.javaClass.name} ${v.width}x${v.height}")
            }
            MainHook.log("riveView chosen: ${BridgeAccess.riveView?.javaClass?.name}")
        } catch (t: Throwable) {
            MainHook.log("riveView scan failed: $t")
        }
    }

    /** Lazy retry when a tap/overlay needs the view but none was captured yet. */
    fun ensure(): View? {
        bestRiveView()?.let { return it }
        val controller = BridgeAccess.boardController ?: return null
        onControllerCreated(controller)
        return bestRiveView()
    }

    /** All discovered Views with their field paths. */
    fun scan(controller: Any): List<Pair<String, View>> {
        val out = mutableListOf<Pair<String, View>>()
        val clz = controller.javaClass
        for (f in clz.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            if (f.type.isPrimitive) continue
            val v = try {
                f.isAccessible = true
                f.get(controller)
            } catch (_: Throwable) {
                continue
            } ?: continue
            collect(out, f.name, v, depth = 0, seen = mutableSetOf())
        }
        return out
    }

    private fun collect(
        out: MutableList<Pair<String, View>>,
        path: String,
        v: Any,
        depth: Int,
        seen: MutableSet<Any>,
    ) {
        if (v is View) {
            out.add(path to v)
            return
        }
        if (depth >= 1 || !seen.add(v)) return
        val c = v.javaClass
        if (c.name.startsWith("java.") || c.name.startsWith("kotlin.") ||
            c.name.startsWith("android.") || c.isEnum || c.isInterface
        ) return
        // Unwrap one level: callback wrappers hold the real view in a field.
        // Walk superclasses too — R8/obfuscated holders often inherit the field.
        var cur: Class<*>? = c
        while (cur != null && cur != Any::class.java && cur != Object::class.java) {
            for (f in cur.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                if (!View::class.java.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    val inner = f.get(v) as? View ?: continue
                    out.add("$path.${f.name}" to inner)
                } catch (_: Throwable) {
                }
            }
            cur = cur.superclass
        }
    }

    /**
     * Best board guess among candidates. Prefers Rive/board/chess-named, laid-out,
     * square views. Views are 0x0 before first layout — at tap time layout is done,
     * so size is a reliable signal then (and ignored when unknown).
     */
    /** Human-readable summary of the last pick (for tap diagnostics). */
    @Volatile var lastChoice: String = "-"
        private set

    fun bestRiveView(): View? {
        val cands = synchronized(ctorLock) { ctorInstances.toList() } + BridgeAccess.riveCandidates
        if (cands.isEmpty()) return BridgeAccess.riveView
        data class Scored(val path: String, val v: View, val s: Double)
        val ranked = cands.map { (path, v) -> Scored(path, v, score(path, v)) }
            .sortedBy { it.s }
        val top = ranked.take(4).joinToString(" | ") {
            "${it.path}:${it.v.javaClass.name.substringAfterLast('.')}:${it.v.width}x${it.v.height}" +
                ":shown=${it.v.isShown}:vis=${it.v.visibility}"
        }
        val win = ranked.firstOrNull()
        if (win != null) {
            BridgeAccess.riveView = win.v
            // Log only when the pick (or its shape) changes: the old unconditional
            // log spammed the LSPosed log on every overlay repaint / tap.
            if (top != lastChoice) {
                lastChoice = top
                MainHook.log("riveView pick: ${win.path} ${win.v.javaClass.name} " +
                    "${win.v.width}x${win.v.height} [alt: $top]")
            }
        }
        return win?.v
    }

    private fun score(path: String, v: View): Double {
        var s = 0.0
        val cls = v.javaClass.name
        val n = (path + " " + cls).lowercase()
        if ("rive" in n) s -= 100.0
        // The exact board view class (confirmed in 6.85.7 dex), not the wrapper.
        if (cls == "app.rive.runtime.kotlin.RiveAnimationView") s -= 50.0
        if ("board" in n || "chess" in n) s -= 50.0
        if ("callback" in n || "wrapper" in n) s -= 10.0
        if ("overlay" in n || "banner" in n || "toolbar" in n || "button" in n ||
            "avatar" in n || "mascot" in n || "reward" in n || "celebrat" in n
        ) s += 500.0
        if (!v.isShown) s += 20000.0 // detached or hidden: taps go nowhere visible
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) {
            // Not laid out: coordinates are degenerate. Only usable if NOTHING
            // laid out exists (this bug silently disabled autoplay: a 0x0 wrapper
            // kept winning ties against the real laid-out board).
            s += 50000.0
        } else {
            s += abs(w - h).toDouble() / max(w, h) * 20.0 // board is square
            s += max(0.0, (200 - max(w, h)).toDouble() / 200.0 * 30.0) // not tiny
            s -= max(w, h).coerceAtMost(2000).toDouble() / 2000.0 * 40.0 // board is big
        }
        return s
    }
}
