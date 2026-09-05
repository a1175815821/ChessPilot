package com.chessassist.engine

import kotlin.math.exp

/**
 * Score (centipawns, side-to-move perspective, as reported by UCI `score cp`) to
 * White win probability. Standard logistic curve (k = 0.00368208, same family as
 * Lichess/Stockfish WDL fitting).
 *
 * Mate scores use our [BestMove.scoreCp] convention (mate * 10000, sign = side to move):
 * mating side ≈ 100%, mated side ≈ 0%.
 */
object ChessMath {

    private const val K = 0.00368208

    /** White win probability in [0,1] from a side-to-move score and mover ("w"|"b"|null). */
    fun whiteWinRate(scoreCp: Int?, sideToMove: String?): Double? {
        if (scoreCp == null) return null
        if (scoreCp >= 9000) return if (sideToMove == "w") 1.0 else 0.0
        if (scoreCp <= -9000) return if (sideToMove == "w") 0.0 else 1.0
        val p = 1.0 / (1.0 + exp(-K * scoreCp))
        return if (sideToMove == "b") 1.0 - p else p
    }

    /** Mate distance if [scoreCp] carries a mate score, else null. Sign = side to move. */
    fun mateIn(scoreCp: Int?): Int? {
        if (scoreCp == null) return null
        if (scoreCp > 9000) return scoreCp / 10000
        if (scoreCp < -9000) return scoreCp / 10000
        return null
    }

    /**
     * One-line display from OUR perspective, e.g. "我方胜率62%" / "我方胜率0%·对方M2".
     * [userColor] ("w"|"b"|null) decides the perspective; unknown falls back to
     * White (legacy behavior) so the line never goes blank.
     */
    fun formatWinRate(scoreCp: Int?, sideToMove: String?, userColor: String?): String {
        val stm = sideToMove?.lowercase()
        val user = userColor?.lowercase()
        val p = if (user == "w" || user == "b") user else stm
        if (p != "w" && p != "b") return "胜率-"
        val white = whiteWinRate(scoreCp, stm) ?: return "胜率-"
        val mine = if (p == "w") white else 1.0 - white
        val who = if (user == "w" || user == "b") "我方" else if (p == "w") "白" else "黑"
        val mate = mateIn(scoreCp)
        if (mate != null) {
            // mate>0: side to move mates; otherwise the opponent does.
            val mater = if (mate > 0) stm else if (stm == "w") "b" else "w"
            val tag = when {
                user == "w" || user == "b" ->
                    if (mater == p) "我方M${kotlin.math.abs(mate)}" else "对方M${kotlin.math.abs(mate)}"
                else -> "${if (mater == "w") "白" else "黑"}M${kotlin.math.abs(mate)}"
            }
            return "${who}胜率${(mine * 100).toInt()}%·$tag"
        }
        return "${who}胜率${(mine * 100).toInt()}%"
    }

    /** Legacy White-perspective display (kept for compat; prefer [formatWinRate]). */
    fun formatWhiteWinRate(scoreCp: Int?, sideToMove: String?): String {
        val w = whiteWinRate(scoreCp, sideToMove) ?: return "胜率-"
        return "白胜率${(w * 100).toInt()}%"
    }
}
