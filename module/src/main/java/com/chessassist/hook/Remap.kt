package com.chessassist.hook

import android.content.Context
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject

/**
 * Cross-version class resolution.
 *
 * R8 renames every internal class each release, so hard-coded names like `wq4` break on
 * update. remap.json lists candidate names per logical role; the first that resolves wins.
 * Kept (unobfuscated) names like `com.duolingo.chess.model.ChessPieceType` are stable.
 * Regenerate remap.json with tools/remap.py when Duolingo updates (then rebuild: the
 * table is baked into [RemapTable] at compile time by generateRemapTable).
 */
class Remap private constructor(private var map: JSONObject) {

    private fun candidatesFor(key: String): org.json.JSONArray? {
        // remap.json nests entries under top-level "classes" (see tools/remap.py output);
        // accept both nested and flat layouts so hand-written tables keep working.
        map.optJSONArray(key)?.let { return it }
        map.optJSONObject("classes")?.optJSONArray(key)?.let { return it }
        return null
    }

    /**
     * Resolution cache INCLUDING negative results: a stale key used to re-throw
     * ClassNotFoundException per candidate on every draw/clear call (exception
     * construction is not cheap). The baked table is immutable per process, so
     * caching (even misses) is safe.
     */
    private val classCache = HashMap<String, Class<*>?>()

    fun resolveClass(loader: ClassLoader, key: String): Class<*>? = synchronized(classCache) {
        if (classCache.containsKey(key)) return classCache[key]
        val candidates = candidatesFor(key)
        var resolved: Class<*>? = null
        if (candidates != null) {
            for (i in 0 until candidates.length()) {
                val name = candidates.optString(i)
                if (name.isNullOrEmpty()) continue
                val dotted = name.replace('/', '.')
                try {
                    resolved = XposedHelpers.findClass(dotted, loader)
                    break
                } catch (_: Throwable) { /* try next candidate */ }
            }
        }
        classCache[key] = resolved
        resolved
    }

    fun resolveClassOrNull(loader: ClassLoader, key: String): Class<*>? = resolveClass(loader, key)

    companion object {
        /**
         * Primary load path: the compile-time-baked table (always in sync with
         * module/src/main/assets/remap.json, zero runtime I/O, zero permissions).
         */
        fun fromBaked(): Remap = Remap(JSONObject(RemapTable.JSON))
    }
}

/** Shared runtime accessors for the various hook components. */
object BridgeAccess {
    @Volatile var context: Context? = null
    @Volatile var boardController: Any? = null
    /** Last known user color ("w"|"b") — mirrors StateBridge for flip-aware fallbacks. */
    @Volatile var lastUserColor: String? = null

    /** Latest rendered ChessGameState instance (type `Object` — resolved dynamically). */
    @Volatile var lastState: Any? = null

    /** The RiveAnimationView inside the board controller (set by MainHook when found). */
    @Volatile var riveView: android.view.View? = null

    /** All View candidates discovered in the controller (see RiveViewFinder). */
    @Volatile var riveCandidates: List<Pair<String, android.view.View>> = emptyList()
}
