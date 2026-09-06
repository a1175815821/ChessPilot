package com.chessassist

import android.content.Context

/**
 * Runtime settings shared by UI, service and engine loop (same process).
 * Persisted in SharedPreferences so toggles survive restarts/updates.
 */
object BridgeSettings {
    private const val PREFS = "chessassist"
    private const val K_AUTOPLAY = "autoplay"
    private const val K_MOVETIME = "movetime_ms"
    private const val K_DEPTH = "search_depth"
    private const val K_DELAY = "autoplay_delay_ms"
    private const val K_PONDER = "ponder"
    private const val K_BERSERK = "berserk"
    private const val K_BACKEND = "backend"
    private const val K_PCHOST = "pc_host"
    private const val K_PCPORT = "pc_port"

    const val BACKEND_LOCAL = "local"
    const val BACKEND_AUTO = "auto"
    const val BACKEND_REMOTE = "remote"

    /** Auto-play Stockfish's move on the real board. Default OFF (ToS/ban risk). */
    @Volatile var autoPlayEnabled: Boolean = false

    /** Think time per move when [searchDepth] == 0. Range 100..3000 ms. */
    @Volatile var movetimeMs: Long = 700L

    /**
     * Fixed search depth (plies). 0 = by time ([movetimeMs]).
     * Range 0..30; depth mode ignores movetime. Deep values are slow on phones.
     */
    @Volatile var searchDepth: Int = 0

    /**
     * Base gap between the select-tap and the destination-tap. Actual gap gets
     * ±20% random jitter per move. Range 150..1500 ms. Longer = more reliable
     * (too fast and the app drops the tap / the piece snaps back).
     */
    @Volatile var autoPlayDelayMs: Long = 450L

    /**
     * Berserk mode: engine runs flat-out (all cores + big hash). Default OFF:
     * sustained full load heats the phone, and thermal throttling then costs
     * more Elo than the extra threads gain. For key games / suspected cheaters.
     */
    @Volatile var berserk: Boolean = false

    /**
     * 极限思考 Ponder (think on the opponent's time): while waiting for the
     * opponent, search our reply to the engine's predicted move; if they play
     * it, the answer is instant via ponderhit. Default OFF: it runs the CPU
     * ~100% of the game (heat/battery) instead of ~50%. No restart needed on
     * toggle — the loop reads this flag live every iteration. While a ponder
     * job is live, the floating overlay's waiting line carries 极限思考中.
     */
    @Volatile var ponderEnabled: Boolean = false

    /**
     * Compute backend: [BACKEND_LOCAL] (phone engine only), [BACKEND_AUTO]
     * (PC preferred, instant local fallback + reconnect), [BACKEND_REMOTE]
     * (same mechanics as auto, but the user explicitly wants PC). Default
     * local: zero behavior change until the user opts in.
     */
    @Volatile var backend: String = BACKEND_LOCAL

    /** PC engine host (LAN IP) for remote/auto backends. Blank = unset. */
    @Volatile var pcHost: String = ""

    /** PC engine port (pc_engine.py --port, default 41718). */
    @Volatile var pcPort: Int = 41718

    fun load(context: Context) {
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            autoPlayEnabled = p.getBoolean(K_AUTOPLAY, false)
            movetimeMs = p.getLong(K_MOVETIME, 700L).coerceIn(100L, 3000L)
            searchDepth = p.getInt(K_DEPTH, 0).coerceIn(0, 30)
            autoPlayDelayMs = p.getLong(K_DELAY, 450L).coerceIn(150L, 1500L)
            berserk = p.getBoolean(K_BERSERK, false)
            ponderEnabled = p.getBoolean(K_PONDER, false)
            backend = p.getString(K_BACKEND, BACKEND_LOCAL)?.takeIf {
                it == BACKEND_LOCAL || it == BACKEND_AUTO || it == BACKEND_REMOTE
            } ?: BACKEND_LOCAL
            pcHost = p.getString(K_PCHOST, "")?.trim().orEmpty().take(64)
            pcPort = p.getInt(K_PCPORT, 41718).coerceIn(1, 65535)
        } catch (_: Throwable) {
        }
    }

    fun save(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_AUTOPLAY, autoPlayEnabled)
                .putLong(K_MOVETIME, movetimeMs)
                .putInt(K_DEPTH, searchDepth)
                .putLong(K_DELAY, autoPlayDelayMs)
                .putBoolean(K_BERSERK, berserk)
                .putBoolean(K_PONDER, ponderEnabled)
                .putString(K_BACKEND, backend)
                .putString(K_PCHOST, pcHost)
                .putInt(K_PCPORT, pcPort)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun describe(): String =
        (if (searchDepth > 0) "固定深度 $searchDepth 层" else "思考时间 ${movetimeMs}ms") +
            if (ponderEnabled) " · 极限思考开" else ""

    fun modeShort(): String = if (berserk) "狂暴" else "普通"

    fun backendShort(): String = when (backend) {
        BACKEND_REMOTE -> "远程"
        BACKEND_AUTO -> "自动"
        else -> "本地"
    }

    /**
     * Engine threads for [cores] logical processors. Normal keeps 2 cores free
     * for the game/UI/socket so taps never stutter; berserk takes everything
     * (if taps lag under berserk, drop this to cores-1 — one line).
     */
    fun threadsFor(berserk: Boolean, cores: Int): Int =
        if (berserk) cores.coerceIn(1, 12) else (cores - 2).coerceIn(1, 4)

    /**
     * Engine hash (MB) for [totalMemMb] device RAM. Normal is safe everywhere;
     * berserk scales with RAM but never below 128 (too small wastes the mode)
     * or above 512 (diminishing returns + OOM risk on 4GB phones).
     */
    fun hashMbFor(berserk: Boolean, totalMemMb: Long): Int =
        if (!berserk) 128
        else minOf(512, maxOf(128, (totalMemMb / 10).toInt()))
}
