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
            val decor = decorOf(boardView) ?: return emptySet()
            scanViews(decor).map { keyOf(it) }.toSet()
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
            val decor = decorOf(boardView) ?: return false
            val actName = try { activityOf(boardView)?.javaClass?.name } catch (_: Throwable) { "?" }
            val post = scanViews(decor)
            val hosts = post.count { it.cls.contains("AndroidComposeView") }
            MainHook.log("promotion: scan act=$actName nodes=${post.size} composeHosts=$hosts")

            // Before/after diff: what did the destination tap add?
            if (preKeys.isNotEmpty()) {
                val added = post.filter { keyOf(it) !in preKeys }
                MainHook.log("promotion: picker diff added=${added.size}")
                for (c in added.take(8)) MainHook.log("promotion: + ${fmt(c)}")
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

            MainHook.log("promotion: L1+L2 no hit, caller falls back to board guess")
            return false
        } catch (t: Throwable) {
            MainHook.log("promotion: resolve failed: ${t.message?.take(140)}")
            return false
        }
    }

    /** Read-only probe: scan + log everything, tap nothing. Safe on any screen. */
    fun logOnlyScan(boardView: View) {
        try {
            val decor = decorOf(boardView) ?: return
            val actName = try { activityOf(boardView)?.javaClass?.name } catch (_: Throwable) { "?" }
            val post = scanViews(decor)
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
            // L2 in dry-run: log nodes, invoke nothing.
            scanComposeNodes(boardView, dryRun = true) { _, _, _, _, _ -> }
        } catch (t: Throwable) {
            MainHook.log("promoscan failed: ${t.message?.take(140)}")
        }
    }

    // ------------------------------------------------------------ L2: Compose -----

    /** Returns true when a queen semantics node was found (and clicked, unless dryRun). */
    private fun scanCompose(
        boardView: View,
        tapAt: (View, Float, Float, String) -> Unit
    ): Boolean {
        var tapped = false
        scanComposeNodes(boardView, dryRun = false) { host, root, cxW, cyW, invoke ->
            try {
                val ok = invoke()
                MainHook.log("promotion: L2 OnClick invoke -> $ok")
            } catch (t: Throwable) {
                MainHook.log("promotion: L2 invoke threw: ${t.message?.take(100)}")
            }
            try {
                val rw = IntArray(2)
                root.getLocationInWindow(rw)
                tapAt(root, cxW - rw[0], cyW - rw[1], "promo-compose")
                tapped = true
            } catch (t: Throwable) {
                MainHook.log("promotion: L2 coord tap threw: ${t.message?.take(100)}")
            }
        }
        return tapped
    }

    private fun scanComposeNodes(
        boardView: View,
        dryRun: Boolean,
        onQueen: (host: View, root: View, cxW: Float, cyW: Float, invoke: () -> Boolean) -> Unit
    ) {
        val decor = decorOf(boardView) ?: return
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
        try { collect(decor, 0) } catch (t: Throwable) {
            MainHook.log("promotion: compose host collect failed: ${t.message?.take(100)}")
            return
        }
        if (hosts.isEmpty()) {
            MainHook.log("promotion: L2 no AndroidComposeView found")
            return
        }
        val loader = boardView.javaClass.classLoader ?: return
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
                        if (!dryRun && bounds != null && clickFn != null &&
                            queenHit(desc, tag) != null
                        ) {
                            MainHook.log("promotion: L2 queen hit desc=[$desc] tag=[$tag]")
                            onQueen(host, root, bounds.exactCenterX(), bounds.exactCenterY(), clickFn)
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
        MainHook.log("promotion: L2 walked nodes=$nodeCount logged=$logged")
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
