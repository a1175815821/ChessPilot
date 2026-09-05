package com.chessassist.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Promotion-picker driver.
 *
 * Case file (2026-09-05, LSPosed log): `c7c8q` missed 3/3. Sequence per attempt was
 * tap-c7 (select) → tap-c8 (destination, picker should open) → tap-c7 ("queen guess",
 * 350 ms later). Nothing ever applied. Two compounding causes:
 *   1. The guess cell (origin square c7) was never confirmed against the real picker —
 *      tapping it most likely just deselected the pawn / dismissed the picker.
 *   2. 350 ms is shorter than a typical picker open animation, so the guess tap may
 *      have landed before the picker was even tappable.
 * Static analysis (6.85.7 dex) found NO text for a picker queen button (icon-only UI),
 * so geometry cannot be derived offline — it must be discovered at runtime.
 *
 * Strategy here is adaptive with three layers (first hit wins), every layer fully
 * logged so the next real promotion teaches us the picker's true structure:
 *   L1 View tree:   scan the Activity decor for a node whose content-description or
 *                   text matches a queen token (multilingual). Tap via performClick()
 *                   (listener path, no coordinates) + a coordinate tap on its window
 *                   root (covers parent-handled touches). Dispatching on the view /
 *                   its own root also reaches Dialog/PopupWindow pickers that live in
 *                   a different window than the board — decorView dispatch would not.
 *   L2 Compose:     picker buttons inside an AndroidComposeView are NOT Views, so L1
 *                   is blind to them. Walk semantics owners via reflection (library
 *                   class names are kept, app obfuscation does not matter), match
 *                   contentDescription/testTag, invoke the OnClick semantics action
 *                   directly, coordinate-tap as fallback.
 *   L3 Board guess: caller falls back to the old board-relative tap (picker assumed
 *                   Rive-drawn inside the board view) when L1+L2 find nothing.
 * A before/after clickable-set diff (snapshot taken before the select-tap, rescanned
 * after the destination-tap) reveals what the picker added even when matching fails.
 */
object PromotionPicker {

    private val QUEEN_TOKENS = listOf(
        "queen", "皇后", "reine", "dama", "dame", "königin", "koenigin",
        "королева", "ферзь", "vezir", "regina", "kralovna", "królowa",
        "βασίλισσα", "ملكة", "מלכה", "रानी", "hậu", "クイーン"
    )

    data class Cand(
        val view: View,
        val cls: String,
        val l: Int, val t: Int, val r: Int, val b: Int,
        val desc: String?, val text: String?
    )

    fun keyOf(c: Cand) = "${c.cls}|${c.l},${c.t},${c.r},${c.b}"

    fun activityOf(view: View): Activity? {
        var c: Context? = view.context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun decorOf(boardView: View): View? {
        return try {
            activityOf(boardView)?.window?.decorView
        } catch (t: Throwable) {
            MainHook.log("promotion: no decor (${t.message?.take(100)})")
            null
        }
    }

    /** Full decor scan. Must run on the UI thread. */
    fun scanViews(root: View): List<Cand> {
        val out = mutableListOf<Cand>()
        fun dfs(v: View, depth: Int) {
            if (out.size >= 3000 || depth > 40) return
            try {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val desc = try { v.contentDescription?.toString() } catch (_: Throwable) { null }
                val text = try { (v as? TextView)?.text?.toString() } catch (_: Throwable) { null }
                out.add(
                    Cand(
                        v, v.javaClass.name, loc[0], loc[1],
                        loc[0] + v.width, loc[1] + v.height, desc, text
                    )
                )
            } catch (_: Throwable) { /* keep scanning siblings */ }
            try {
                val g = v as? ViewGroup ?: return
                for (i in 0 until g.childCount) {
                    try { dfs(g.getChildAt(i), depth + 1) } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }
        try { dfs(root, 0) } catch (t: Throwable) {
            MainHook.log("promotion: view scan aborted: ${t.message?.take(100)}")
        }
        return out
    }

    /** Key snapshot of the current clickable set (for the before/after diff). */
    fun snapshotKeys(boardView: View): Set<String> {
        return try {
            allRoots(boardView).flatMap { scanViews(it) }.map { keyOf(it) }.toSet()
        } catch (t: Throwable) {
            MainHook.log("promotion: snapshot failed: ${t.message?.take(100)}")
            emptySet()
        }
    }

    private fun queenHit(vararg parts: String?): String? {
        for (p in parts) {
            if (p.isNullOrBlank()) continue
            val low = p.lowercase()
            for (tok in QUEEN_TOKENS) {
                if (low.contains(tok)) return p.take(60)
            }
        }
        return null
    }

    private fun fmt(c: Cand): String {
        val extra = listOfNotNull(
            c.desc?.take(30)?.let { "desc=$it" },
            c.text?.take(30)?.let { "text=$it" }
        ).joinToString(" ")
        return "${c.cls.substringAfterLast('.')} [${c.l},${c.t}-${c.r},${c.b}] click=${c.view.isClickable} $extra".take(200)
    }

    /**
     * Resolve + tap the queen button. Returns true when any layer tapped something
     * (caller falls back to the board-relative guess on false).
     * Must run on the UI thread. Never throws.
     */
    fun resolveAndTap(
        boardView: View,
        preKeys: Set<String>,
        tapAt: (View, Float, Float, String) -> Unit
    ): Boolean {
        try {
            val roots = allRoots(boardView)
            if (roots.isEmpty()) return false
            val decor = roots[0]
            val actName = try { activityOf(boardView)?.javaClass?.name } catch (_: Throwable) { "?" }
            val post = roots.flatMap { scanViews(it) }
            val hosts = post.count { it.cls.contains("AndroidComposeView") }
            MainHook.log(
                "promotion: scan act=$actName roots=${roots.size} " +
                    "nodes=${post.size} composeHosts=$hosts"
            )

            // Before/after diff: what did the destination tap add?
            if (preKeys.isNotEmpty()) {
                val added = post.filter { keyOf(it) !in preKeys }
                MainHook.log("promotion: picker diff added=${added.size}")
                for (c in added.take(8)) MainHook.log("promotion: + ${fmt(c)}")
            }

            // ---- L1b (LOG-ONLY): user-confirmed layout — horizontal row of 2-6
            // similar cells (Q R B N left-to-right, queen leftmost), matched on
            // the diff set. Never taps: a row heuristic can misfire on unknown
            // dialogs, and the Rive-signal drive (primary) needs no taps at all.
            if (preKeys.isNotEmpty()) {
                val added = post.filter { keyOf(it) !in preKeys }
                val rowQueen = leftmostOfRow(
                    added.filter { it.r > it.l + 20 && it.b > it.t + 20 }
                        .map { Cell(it.l, it.t, it.r, it.b) to it },
                    decor.width, decor.height
                )
                if (rowQueen != null) {
                    MainHook.log("promotion: L1b row node (log-only, ${added.size} added): ${fmt(rowQueen)}")
                } else {
                    MainHook.log("promotion: L1b no row among ${added.size} added")
                }
            }

            // ---- L1: plain-View queen button (any window: tap the view / its root).
            val l1 = post.mapNotNull { c ->
                val hit = queenHit(c.desc, c.text) ?: return@mapNotNull null
                c to hit
            }
            for ((c, hit) in l1.take(4)) {
                MainHook.log("promotion: L1 queen view hit ($hit): ${fmt(c)}")
                try {
                    val pc = c.view.performClick()
                    MainHook.log("promotion: L1 performClick -> $pc")
                } catch (t: Throwable) {
                    MainHook.log("promotion: L1 performClick threw: ${t.message?.take(100)}")
                }
                try {
                    val root = c.view.rootView ?: c.view
                    val rl = IntArray(2)
                    root.getLocationOnScreen(rl)
                    val cx = (c.l + c.r) / 2f - rl[0]
                    val cy = (c.t + c.b) / 2f - rl[1]
                    tapAt(root, cx, cy, "promo-queen")
                } catch (t: Throwable) {
                    MainHook.log("promotion: L1 coord tap threw: ${t.message?.take(100)}")
                }
            }
            if (l1.isNotEmpty()) return true

            // ---- L2: Compose semantics queen button.
            if (scanCompose(boardView, tapAt)) return true

            MainHook.log("promotion: L1+L2 no hit, caller waits for manual pick")
            return false
        } catch (t: Throwable) {
            MainHook.log("promotion: resolve failed: ${t.message?.take(140)}")
            return false
        }
    }

    /** Read-only probe: scan + log everything, tap nothing. Safe on any screen. */
    fun logOnlyScan(boardView: View) {
        try {
            val roots = allRoots(boardView)
            if (roots.isEmpty()) return
            val actName = try { activityOf(boardView)?.javaClass?.name } catch (_: Throwable) { "?" }
            val post = roots.flatMap { scanViews(it) }
            val hosts = post.filter { it.cls.contains("AndroidComposeView") }
            MainHook.log("promoscan: act=$actName nodes=${post.size} composeHosts=${hosts.size}")
            val withText = post.filter { !it.desc.isNullOrBlank() || !it.text.isNullOrBlank() }
            MainHook.log("promoscan: nodes with desc/text=${withText.size}")
            for (c in withText.take(40)) MainHook.log("promoscan: - ${fmt(c)}")
            for (h in hosts.take(4)) {
                val r = h.view.rootView
                MainHook.log(
                    "promoscan: host ${h.cls} [${h.l},${h.t}-${h.r},${h.b}] " +
                        "root=${r?.javaClass?.name?.substringAfterLast('.')}"
                )
            }
            // L2 walk is log-only here (no taps): validates the reflection chain
            // and reveals the live semantics structure.
            collectComposeClicks(boardView)
        } catch (t: Throwable) {
            MainHook.log("promoscan failed: ${t.message?.take(140)}")
        }
    }

    // ------------------------------------------------------------ L2: Compose -----

    /**
     * L2 is LOG-ONLY (stability first): Compose semantics are R8-renamed with
     * empty roots on this build, so any tap derived from them would be a guess.
     * Collection + logging stay (they map the terrain for free); tapping waits
     * for the direct-API drive. Always returns false.
     */
    private fun scanCompose(
        boardView: View,
        tapAt: (View, Float, Float, String) -> Unit
    ): Boolean {
        val clicks = collectComposeClicks(boardView)
        for (c in clicks) {
            if (queenHit(c.desc, c.tag) != null) {
                MainHook.log("promotion: L2 queen node (log-only) desc=[${c.desc}] tag=[${c.tag}]")
            }
        }
        val decor = decorOf(boardView)
        val rowQueen = leftmostOfRow(
            clicks.map { Cell(it.l, it.t, it.r, it.b) to it },
            decor?.width ?: 1080, decor?.height ?: 2400
        )
        if (rowQueen != null) {
            MainHook.log("promotion: L2b row node (log-only) bounds=[${rowQueen.l},${rowQueen.t}-${rowQueen.r},${rowQueen.b}]")
        }
        MainHook.log("promotion: L2 log-only, ${clicks.size} clickable semantics nodes")
        return false
    }

    private fun tapComposeButton(
        c: ComposeClick,
        tapAt: (View, Float, Float, String) -> Unit,
        label: String
    ) {
        if (c.invoke != null) {
            try {
                val ok = c.invoke()
                MainHook.log("promotion: L2 action invoke -> $ok")
            } catch (t: Throwable) {
                MainHook.log("promotion: L2 invoke threw: ${t.message?.take(100)}")
            }
        } else {
            MainHook.log("promotion: L2 no unambiguous action, coord tap only")
        }
        try {
            val rw = IntArray(2)
            c.root.getLocationInWindow(rw)
            tapAt(c.root, c.cxW - rw[0], c.cyW - rw[1], label)
        } catch (t: Throwable) {
            MainHook.log("promotion: L2 coord tap threw: ${t.message?.take(100)}")
        }
    }

    private fun tapViewButton(
        c: Cand,
        tapAt: (View, Float, Float, String) -> Unit,
        label: String
    ) {
        try {
            val pc = c.view.performClick()
            MainHook.log("promotion: $label performClick -> $pc")
        } catch (t: Throwable) {
            MainHook.log("promotion: $label performClick threw: ${t.message?.take(100)}")
        }
        try {
            val root = c.view.rootView ?: c.view
            val rl = IntArray(2)
            root.getLocationOnScreen(rl)
            tapAt(root, (c.l + c.r) / 2f - rl[0], (c.t + c.b) / 2f - rl[1], label)
        } catch (t: Throwable) {
            MainHook.log("promotion: $label coord tap threw: ${t.message?.take(100)}")
        }
    }

    /** Horizontal picker-row test: 2-6 similar cells in one band → leftmost (queen). */
    private data class Cell(val l: Int, val t: Int, val r: Int, val b: Int)

    private fun <T> leftmostOfRow(
        cells: List<Pair<Cell, T>>,
        refW: Int, refH: Int
    ): T? {
        if (cells.size !in 2..6) return null
        val refArea = (refW.coerceAtLeast(1) * refH.coerceAtLeast(1)).toLong()
        val sane = cells.filter { (c, _) ->
            val w = c.r - c.l
            val h = c.b - c.t
            w > 0 && h > 0 &&
                w.toLong() * h in (refArea / 1000)..(refArea / 4) &&
                maxOf(w, h) <= 3 * minOf(w, h)
        }
        if (sane.size !in 2..6) return null
        val hs = sane.map { it.first.b - it.first.t }
        if (hs.maxOrNull()!! > hs.minOrNull()!! * 2) return null
        val cys = sane.map { (it.first.t + it.first.b) / 2 }
        val tol = maxOf(refH / 20, 40)
        if (cys.maxOrNull()!! - cys.minOrNull()!! > tol) return null
        val span = sane.maxOf { it.first.r } - sane.minOf { it.first.l }
        if (span < 2 * sane.maxOf { it.first.r - it.first.l }) return null
        return sane.minByOrNull { it.first.l }?.second
    }

    data class ComposeClick(
        val host: View, val root: View,
        val cxW: Float, val cyW: Float,
        val l: Int, val t: Int, val r: Int, val b: Int,
        val desc: String, val tag: String,
        /** Direct action runner; null when ambiguous — caller coord-taps instead. */
        val invoke: (() -> Boolean)?
    )

    /** Classes whose method inventory was already logged (always used on UI thread). */
    private val inventoried = mutableSetOf<String>()

    private fun logMethodsOnce(clz: Class<*>, tag: String) {
        if (!inventoried.add(clz.name)) return
        try {
            val sigs = clz.declaredMethods
                .filter { !it.isSynthetic }
                .map { m ->
                    val ps = m.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') }
                    "${m.name}($ps):${m.returnType.name.substringAfterLast('.')}"
                }
                .sorted().joinToString(" ").take(900)
            MainHook.log("promotion: L2s $tag ${clz.name} methods=[$sigs]")
        } catch (_: Throwable) {
            MainHook.log("promotion: L2s inventory failed for ${clz.name}")
        }
    }

    /**
     * Sample probe on one node: invoke all no-arg scalar getters (boolean, numbers,
     * strings) and log name=value. Obfuscated position/state accessors show up here
     * by value (e.g. a cluster of 4 floats = bounds). Called sparingly (2/host).
     */
    private fun sampleScalars(node: Any, tag: String) {
        try {
            val parts = mutableListOf<String>()
            for (m in noArgMethods(node)) {
                if (parts.size >= 14) break
                val rt = m.returnType
                val isScalar = rt.isPrimitive || rt == String::class.java ||
                    Number::class.java.isAssignableFrom(rt) ||
                    java.lang.Boolean::class.java.isAssignableFrom(rt)
                if (!isScalar || rt == Void.TYPE) continue
                try {
                    val v = m.invoke(node)
                    parts.add("${m.name}=$v")
                } catch (_: Throwable) { }
            }
            MainHook.log("promotion: L2s sample $tag ${node.javaClass.name} scalars=[${parts.joinToString(" ").take(400)}]")
        } catch (_: Throwable) { }
    }

    /**
     * Walk every AndroidComposeView's semantics tree, logging nodes with
     * descriptions/tags/click actions, and return all clickable nodes with
     * window bounds. Pure reflection (no compose imports): library class names
     * are kept, so app obfuscation does not matter. Never throws.
     */
    private fun collectHosts(roots: List<View>): List<View> {
        val hosts = mutableListOf<View>()
        fun collect(v: View, depth: Int) {
            if (hosts.size >= 16 || depth > 40) return
            try {
                if (v.javaClass.name.contains("AndroidComposeView")) hosts.add(v)
                val g = v as? ViewGroup ?: return
                for (i in 0 until g.childCount) {
                    try { collect(g.getChildAt(i), depth + 1) } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }
        try {
            roots.forEach { collect(it, 0) }
        } catch (t: Throwable) {
            MainHook.log("promotion: compose host collect failed: ${t.message?.take(100)}")
        }
        return hosts
    }

    private fun collectComposeClicks(boardView: View): List<ComposeClick> {
        val out = mutableListOf<ComposeClick>()
        val hosts = collectHosts(allRoots(boardView))
        if (hosts.isEmpty()) {
            MainHook.log("promotion: L2 no AndroidComposeView found")
            return out
        }
        val loader = boardView.javaClass.classLoader ?: return out
        val cdKey = composeObjectProp(loader, "androidx.compose.ui.semantics.SemanticsProperties", "ContentDescription")
        val tagKey = composeObjectProp(loader, "androidx.compose.ui.semantics.SemanticsProperties", "TestTag")
        val clickKey = composeObjectProp(loader, "androidx.compose.ui.semantics.SemanticsActions", "OnClick")
        MainHook.log(
            "promotion: L2 hosts=${hosts.size} keys(desc/tag/click)=" +
                "${cdKey != null}/${tagKey != null}/${clickKey != null}"
        )
        var nodeCount = 0
        var logged = 0
        for (host in hosts) {
            try {
                val owner = host.javaClass.getMethod("getSemanticsOwner").invoke(host) ?: continue
                val rootNode = owner.javaClass.getMethod("getUnmergedRootSemanticsNode").invoke(owner)
                    ?: continue
                val root = host.rootView ?: host
                fun dfs(node: Any, depth: Int) {
                    if (nodeCount >= 600 || depth > 30) return
                    nodeCount++
                    try {
                        val cfg = configOf(node)
                        var desc = ""
                        var tag = ""
                        var hasClick = false
                        var clickFn: (() -> Boolean)? = null
                        if (cfg != null) {
                            desc = descOf(cfg, cdKey)
                            tag = tagOf(cfg, tagKey)
                            val actionObj = clickKey?.let { configGet(cfg, it) }
                            if (actionObj != null) {
                                hasClick = true
                                clickFn = { invokeSemanticsAction(actionObj) }
                            }
                        }
                        val bounds = try {
                            node.javaClass.getMethod("getBoundsInWindow").invoke(node) as? Rect
                        } catch (_: Throwable) { null }
                        if (desc.isNotEmpty() || tag.isNotEmpty() || hasClick) {
                            if (logged < 60) {
                                logged++
                                MainHook.log(
                                    "promotion: L2 node desc=[$desc] tag=[$tag] click=$hasClick " +
                                        "bounds=${bounds?.let { "[${it.left},${it.top}-${it.right},${it.bottom}]" } ?: "?"}"
                                )
                            }
                        }
                        if (bounds != null && clickFn != null) {
                            out.add(
                                ComposeClick(
                                    host, root,
                                    bounds.exactCenterX(), bounds.exactCenterY(),
                                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                                    desc, tag, clickFn
                                )
                            )
                        }
                        @Suppress("UNCHECKED_CAST")
                        val kids = try {
                            node.javaClass.getMethod("getChildren").invoke(node) as? List<Any>
                        } catch (_: Throwable) { null }
                        kids?.forEach { dfs(it, depth + 1) }
                    } catch (_: Throwable) { /* next sibling */ }
                }
                dfs(rootNode, 0)
            } catch (t: Throwable) {
                MainHook.log("promotion: L2 owner walk failed: ${t.message?.take(120)}")
            }
        }
        MainHook.log("promotion: L2 walked nodes=$nodeCount logged=$logged clickable=${out.size}")
        // Shape-based pass: R8 renames compose semantics classes/members (live: owner
        // `uzt`, no getUnmergedRootSemanticsNode), so the name-based pass above finds
        // nothing. Match by JVM shapes instead — framework return types survive R8.
        for (c in collectComposeClicksByShape(boardView)) {
            if (out.none { it.l == c.l && it.t == c.t && it.r == c.r && it.b == c.b }) out.add(c)
        }
        return out
    }

    // ------------------------------------------------- L2 shape-based pass -----

    private fun noArgMethods(o: Any): List<java.lang.reflect.Method> {
        return try {
            o.javaClass.methods.filter { it.parameterTypes.isEmpty() }
        } catch (_: Throwable) { emptyList() }
    }

    private fun kidsOfShape(node: Any): List<Any> {
        val acc = ArrayList<Any>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        for (m in noArgMethods(node)) {
            if (m.returnType != java.util.List::class.java) continue
            try {
                val v = m.invoke(node) as? List<*> ?: continue
                for (e in v) if (e != null && seen.add(e)) acc.add(e)
            } catch (_: Throwable) { }
        }
        return acc
    }

    private data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int)

    /**
     * Window-bounds accessor by shape. Accepts android.graphics.Rect AND
     * androidx.compose.ui.geometry.Rect (Compose renames do not touch the type
     * name; live failure was exactly this: the node exposes a compose Rect, not
     * the framework one, so the old `== Rect` test rejected the true root).
     */
    private fun rectOfShape(node: Any): Bounds? {
        for (m in noArgMethods(node)) {
            val rt = m.returnType
            if (rt.isPrimitive || rt == Void.TYPE) continue
            if (rt != Rect::class.java && !rt.name.endsWith("Rect")) continue
            try {
                val o = m.invoke(node) ?: continue
                val l = intOf(o, "getLeft", "left") ?: continue
                val t = intOf(o, "getTop", "top") ?: continue
                val r = intOf(o, "getRight", "right") ?: continue
                val b = intOf(o, "getBottom", "bottom") ?: continue
                if (r > l && b > t) return Bounds(l, t, r, b)
            } catch (_: Throwable) { }
        }
        return null
    }

    /** Kotlin-property getter first (compose Rect), field second (framework Rect). */
    private fun intOf(o: Any, getter: String, field: String): Int? {
        try {
            (o.javaClass.getMethod(getter).invoke(o) as? Number)?.let { return it.toInt() }
        } catch (_: Throwable) { }
        try {
            val f = o.javaClass.getDeclaredField(field)
            try { f.isAccessible = true } catch (_: Throwable) { }
            (f.get(o) as? Number)?.let { return it.toInt() }
        } catch (_: Throwable) { }
        return null
    }

    /**
     * The node's attached config object (SemanticsConfiguration shape): a nontrivial
     * object value. When several qualify, prefer one exposing a string list
     * (contentDescription shape), else one exposing any list, else the first.
     */
    private fun configOfShape(node: Any): Any? {
        val cands = mutableListOf<Any>()
        for (m in noArgMethods(node)) {
            val rt = m.returnType
            if (rt.isPrimitive || rt == Void.TYPE || rt == String::class.java ||
                Number::class.java.isAssignableFrom(rt) ||
                java.lang.Boolean::class.java.isAssignableFrom(rt) ||
                java.util.List::class.java.isAssignableFrom(rt) || rt == Rect::class.java ||
                rt == node.javaClass || rt.name == "java.lang.Class" ||
                rt.name == "java.lang.Object"
            ) continue
            try {
                val v = m.invoke(node)
                if (v != null && cands.size < 5) cands.add(v)
            } catch (_: Throwable) { }
        }
        for (c in cands) logMethodsOnce(c.javaClass, "config?")
        return cands.firstOrNull { stringListOf(it).isNotEmpty() }
            ?: cands.firstOrNull { hasListMethod(it) }
            ?: cands.firstOrNull()
    }

    private fun hasListMethod(o: Any): Boolean =
        noArgMethods(o).any { it.returnType == java.util.List::class.java }

    private fun stringListOf(cfg: Any): String {
        for (m in noArgMethods(cfg)) {
            if (m.returnType != java.util.List::class.java) continue
            try {
                val v = m.invoke(cfg) as? List<*> ?: continue
                if (v.isNotEmpty() && v.all { it is String }) {
                    return v.filterIsInstance<String>().joinToString("|").take(120)
                }
            } catch (_: Throwable) { }
        }
        return ""
    }

    /** All Function0-valued members (click-action shape). Invoke is gated by count. */
    private fun actionFnsOf(cfg: Any): List<() -> Boolean> {
        val fns = mutableListOf<() -> Boolean>()
        for (m in noArgMethods(cfg)) {
            val rt = m.returnType
            if (rt.isPrimitive || rt == Void.TYPE || rt == String::class.java ||
                Number::class.java.isAssignableFrom(rt) ||
                java.lang.Boolean::class.java.isAssignableFrom(rt) ||
                java.util.List::class.java.isAssignableFrom(rt) ||
                java.util.Map::class.java.isAssignableFrom(rt) ||
                rt.name == "java.lang.Class"
            ) continue
            try {
                val obj = m.invoke(cfg) ?: continue
                val fn = fnOf(obj) ?: continue
                fns.add { try { fn.invoke() == true } catch (_: Throwable) { false } }
            } catch (_: Throwable) { }
        }
        return fns
    }

    private fun fnOf(obj: Any): Function0<Boolean>? {
        try {
            for (m in obj.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                if (m.name == "getAction" ||
                    (m.name.startsWith("get") &&
                        Function0::class.java.isAssignableFrom(m.returnType))
                ) {
                    @Suppress("UNCHECKED_CAST")
                    val fn = try { m.invoke(obj) as? Function0<Boolean> } catch (_: Throwable) { null }
                    if (fn != null) return fn
                }
            }
            for (f in obj.javaClass.declaredFields) {
                if (!Function0::class.java.isAssignableFrom(f.type)) continue
                try { f.isAccessible = true } catch (_: Throwable) { }
                @Suppress("UNCHECKED_CAST")
                val fn = try { f.get(obj) as? Function0<Boolean> } catch (_: Throwable) { null }
                if (fn != null) return fn
            }
        } catch (_: Throwable) { }
        return null
    }

    private fun findRootNodeShape(owner: Any): Any? {
        logMethodsOnce(owner.javaClass, "owner")
        val cands = mutableListOf<Any>()
        for (m in noArgMethods(owner)) {
            val rt = m.returnType
            if (rt.isPrimitive || rt == Void.TYPE || rt == String::class.java ||
                Number::class.java.isAssignableFrom(rt) ||
                java.lang.Boolean::class.java.isAssignableFrom(rt) ||
                rt.name == "java.lang.Class"
            ) continue
            try {
                val v = m.invoke(owner)
                if (v != null) cands.add(v)
            } catch (_: Throwable) { }
        }
        // Live truth: the owner exposes exactly one object (`rzt`), which carries
        // children lists but NO *Rect method — bounds must be discovered deeper
        // (nested object or scalar cluster). Accept any list-bearing candidate as
        // the walk root; the DFS + sample probes below reveal the rest.
        for (c in cands) {
            val ms = noArgMethods(c)
            val hasBounds = ms.any {
                it.returnType == Rect::class.java || it.returnType.name.endsWith("Rect")
            }
            val hasKids = ms.any { it.returnType == java.util.List::class.java }
            logMethodsOnce(c.javaClass, "node?bounds=$hasBounds/kids=$hasKids")
            if (hasKids) return c
        }
        MainHook.log("promotion: L2s root candidates=${cands.size}")
        return null
    }

    /**
     * Every window root in this process (activity decor + dialog/popup windows).
     * The promotion picker may live in its own window: scanning only the activity
     * decor would then show `added=0` forever. WindowManagerGlobal is hidden API —
     * failure degrades to activity-decor-only, never crashes.
     */
    private fun allRoots(boardView: View): List<View> {
        val roots = mutableListOf<View>()
        try {
            (boardView.rootView ?: decorOf(boardView))?.let { roots.add(it) }
        } catch (_: Throwable) { }
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = wmg?.javaClass?.getMethod("getViews")?.invoke(wmg) as? List<*>
            views?.filterIsInstance<View>()?.forEach { v ->
                try {
                    val root = v.rootView ?: v
                    if (roots.none { it === root }) roots.add(root)
                } catch (_: Throwable) { }
            }
            MainHook.log("promotion: window roots=${roots.size}")
        } catch (t: Throwable) {
            MainHook.log("promotion: window enumerate failed (${t.message?.take(80)}), decor only")
        }
        return roots.ifEmpty { listOfNotNull(decorOf(boardView)) }
    }

    /**
     * Deep sample v3: the obfuscated tree (`rzt`) exposes children only through
     * ARG-TAKING List methods (`i(boolean,boolean)`, `p(ArrayList,boolean)`) and
     * bounds through unknown nested objects — both invisible to the no-arg shape
     * walk. So probe them directly: try all boolean combos, inventory every
     * no-arg object getter's class, scalar-sample everything. The output pins the
     * exact accessor path for the real walker. Runs per host per scan (rare).
     */
    private fun deepSampleRoot(owner: Any) {
        try {
            val root = try {
                owner.javaClass.methods.firstOrNull {
                    it.name == "a" && it.parameterTypes.isEmpty()
                }?.invoke(owner)
            } catch (_: Throwable) { null } ?: return
            logMethodsOnce(root.javaClass, "deeproot")
            sampleScalars(root, "deeproot")
            for (m in noArgMethods(root)) {
                val rt = m.returnType
                if (rt.isPrimitive || rt == Void.TYPE || rt == String::class.java ||
                    Number::class.java.isAssignableFrom(rt) ||
                    java.lang.Boolean::class.java.isAssignableFrom(rt) ||
                    java.util.List::class.java.isAssignableFrom(rt) ||
                    rt.name == "java.lang.Class"
                ) continue
                try {
                    val v = m.invoke(root) ?: continue
                    logMethodsOnce(v.javaClass, "deepobj:${m.name}")
                    sampleScalars(v, "deepobj:${m.name}")
                    for (lm in noArgMethods(v)) {
                        if (lm.returnType != java.util.List::class.java) continue
                        try {
                            val lst = lm.invoke(v) as? List<*> ?: continue
                            val el = lst.firstOrNull()
                            MainHook.log(
                                "promotion: L2s deepobj ${m.name}.${lm.name} " +
                                    "size=${lst.size} el=${el?.javaClass?.name}"
                            )
                            if (el != null) {
                                logMethodsOnce(el.javaClass, "deepel")
                                sampleScalars(el, "deepel")
                            }
                        } catch (_: Throwable) { }
                    }
                } catch (_: Throwable) { }
            }
            for (m in root.javaClass.methods) {
                if (m.returnType != java.util.List::class.java) continue
                val pt = m.parameterTypes
                fun probe(args: Array<Any?>) {
                    try {
                        val lst = m.invoke(root, *args) as? List<*> ?: return
                        val el = lst.firstOrNull()
                        MainHook.log(
                            "promotion: L2s deepcall ${m.name}(${args.joinToString(",")}) " +
                                "size=${lst.size} el=${el?.javaClass?.name}"
                        )
                        if (el != null) {
                            logMethodsOnce(el.javaClass, "deepel")
                            sampleScalars(el, "deepel")
                        }
                    } catch (t: Throwable) {
                        MainHook.log("promotion: L2s deepcall ${m.name} threw ${t.message?.take(60)}")
                    }
                }
                if (pt.size == 2 && pt[0] == java.lang.Boolean.TYPE && pt[1] == java.lang.Boolean.TYPE) {
                    for (a in listOf(false, true)) for (b in listOf(false, true)) probe(arrayOf(a, b))
                } else if (pt.size == 2 && java.util.ArrayList::class.java.isAssignableFrom(pt[0]) &&
                    pt[1] == java.lang.Boolean.TYPE
                ) {
                    for (b in listOf(false, true)) probe(arrayOf(java.util.ArrayList<Any>(), b))
                }
            }
        } catch (t: Throwable) {
            MainHook.log("promotion: L2s deep sample failed: ${t.message?.take(120)}")
        }
    }

    private fun collectComposeClicksByShape(boardView: View): List<ComposeClick> {
        val out = mutableListOf<ComposeClick>()
        val hosts = collectHosts(allRoots(boardView))
        if (hosts.isEmpty()) return out
        for (host in hosts) {
            try {
                val owner = try {
                    host.javaClass.getMethod("getSemanticsOwner").invoke(host)
                } catch (_: Throwable) { null } ?: continue
                // Deep sample first: the tree exposes children only through
                // arg-taking List methods, so the shape walk below cannot reach it
                // yet — this output pins the exact accessor path for the walker.
                deepSampleRoot(owner)
                val rootNode = findRootNodeShape(owner)
                if (rootNode == null) {
                    MainHook.log("promotion: L2s no root shape on ${owner.javaClass.name}")
                    continue
                }
                val root = host.rootView ?: host
                // One-time per host: scalar sample of the root + kid-class inventory
                // pins down the obfuscated position/state accessors by value.
                sampleScalars(rootNode, "root")
                try {
                    val firstKids = kidsOfShape(rootNode)
                    MainHook.log(
                        "promotion: L2s root kids=${firstKids.size} " +
                            "first=${firstKids.firstOrNull()?.javaClass?.name}"
                    )
                    firstKids.firstOrNull()?.let {
                        logMethodsOnce(it.javaClass, "kid")
                        sampleScalars(it, "kid0")
                    }
                } catch (t: Throwable) {
                    MainHook.log("promotion: L2s kid sample failed: ${t.message?.take(100)}")
                }
                var n = 0
                var logged = 0
                fun dfs(node: Any, depth: Int) {
                    if (n >= 800 || depth > 30 || out.size >= 200) return
                    n++
                    try {
                        val bounds = rectOfShape(node)
                        val cfg = configOfShape(node)
                        var desc = ""
                        var invoke: (() -> Boolean)? = null
                        var actions = 0
                        if (cfg != null) {
                            desc = stringListOf(cfg)
                            val fns = actionFnsOf(cfg)
                            actions = fns.size
                            // Invoke only an UNAMBIGUOUS single action: it is the
                            // node's own click (finger-equivalent). Ambiguous
                            // multi-action nodes are coord-tapped, never invoked.
                            if (fns.size == 1) invoke = fns[0]
                        }
                        if (bounds != null && (desc.isNotEmpty() || actions > 0)) {
                            if (desc.isNotEmpty() && logged < 20) {
                                logged++
                                MainHook.log(
                                    "promotion: L2s node desc=[$desc] actions=$actions " +
                                        "bounds=[${bounds.l},${bounds.t}-${bounds.r},${bounds.b}]"
                                )
                            }
                            out.add(
                                ComposeClick(
                                    host, root,
                                    (bounds.l + bounds.r) / 2f, (bounds.t + bounds.b) / 2f,
                                    bounds.l, bounds.t, bounds.r, bounds.b,
                                    desc, "", invoke
                                )
                            )
                        }
                        for (kid in kidsOfShape(node)) dfs(kid, depth + 1)
                    } catch (_: Throwable) { }
                }
                dfs(rootNode, 0)
                MainHook.log("promotion: L2s host nodes=$n clickable=${out.size}")
            } catch (t: Throwable) {
                MainHook.log("promotion: L2s host failed: ${t.message?.take(120)}")
            }
        }
        return out
    }

    private fun composeObjectProp(loader: ClassLoader, className: String, prop: String): Any? {
        return try {
            val clz = Class.forName(className, false, loader)
            val inst = clz.getField("INSTANCE").get(null)
            clz.getMethod("get$prop").invoke(inst)
        } catch (t: Throwable) {
            MainHook.log("promotion: L2 no $className.$prop (${t.message?.take(80)})")
            null
        }
    }

    private fun configOf(node: Any): Any? {
        for (name in listOf("getUnmergedConfig", "getConfig")) {
            try {
                return node.javaClass.getMethod(name).invoke(node)
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun configGet(config: Any, key: Any): Any? {
        return try {
            val ms = config.javaClass.methods
            val m = ms.firstOrNull { it.name == "getOrNull" && it.parameterTypes.size == 1 }
                ?: ms.firstOrNull { it.name == "get" && it.parameterTypes.size == 1 }
                ?: return null
            m.invoke(config, key)
        } catch (_: Throwable) { null }
    }

    private fun descOf(config: Any, cdKey: Any?): String {
        if (cdKey == null) return ""
        return try {
            @Suppress("UNCHECKED_CAST")
            val list = configGet(config, cdKey) as? List<*> ?: return ""
            list.filterIsInstance<String>().joinToString("|").take(120)
        } catch (_: Throwable) { "" }
    }

    private fun tagOf(config: Any, tagKey: Any?): String {
        if (tagKey == null) return ""
        return try {
            (configGet(config, tagKey) as? String)?.take(80) ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun invokeSemanticsAction(actionObj: Any): Boolean {
        return try {
            val fn = try {
                actionObj.javaClass.getMethod("getAction").invoke(actionObj)
            } catch (_: Throwable) {
                try { actionObj.javaClass.getField("action").get(actionObj) } catch (_: Throwable) { null }
            }
            @Suppress("UNCHECKED_CAST")
            (fn as? Function0<Boolean>)?.invoke() == true
        } catch (_: Throwable) { false }
    }
}
