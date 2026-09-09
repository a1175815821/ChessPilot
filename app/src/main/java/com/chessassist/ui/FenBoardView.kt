package com.chessassist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * FEN 小棋盘（对局雷达卡右侧，112dp 见方）。
 *
 * 纯装饰性速览：把原始 FEN 字符串变成一眼可读的局面。解析失败画空棋盘，
 * 永不抛异常（FEN 来自桥，坏输入随时可能出现）。右下角圆点标示行棋方
 * （白=实心白，黑=实心黑+白描边）。
 */
class FenBoardView(context: Context) : View(context) {

    private val lightPaint = Paint().apply { color = Color.parseColor("#C7D2E8") }
    private val darkPaint = Paint().apply { color = Color.parseColor("#5A6B8C") }
    private val whitePiece = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAFAFA")
        textAlign = Paint.Align.CENTER
    }
    private val blackPiece = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161616")
        textAlign = Paint.Align.CENTER
    }
    private val stmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stmStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FAFAFA")
    }
    private val clipPath = Path()
    private val clipRect = RectF()

    /** 64 格，a8 在 index 0；' ' = empty square. */
    private val cells = CharArray(64)
    private var stmWhite = true

    init {
        // 初始为空棋盘（引擎未收到局面前的状态）。
        cells.fill(' ')
    }

    fun setFen(fen: String?) {
        try {
            cells.fill(' ')
            if (fen.isNullOrBlank()) {
                invalidate()
                return
            }
            val parts = fen.trim().split("\\s+".toRegex())
            val rows = parts[0].split("/")
            if (rows.size != 8) throw IllegalArgumentException("rows")
            for (r in 0 until 8) {
                var c = 0
                for (ch in rows[r]) {
                    when {
                        ch in '1'..'8' -> c += ch - '0'
                        ch in "prnbqkPRNBQK" -> {
                            if (c > 7) throw IllegalArgumentException("col")
                            cells[r * 8 + c] = ch
                            c++
                        }
                        else -> throw IllegalArgumentException("piece")
                    }
                }
                if (c != 8) throw IllegalArgumentException("width")
            }
            stmWhite = parts.getOrNull(1) != "b"
        } catch (_: Throwable) {
            cells.fill(' ')
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w) // 恒为正方形
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cell = width / 8f
        val corner = 10f * density

        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipRect, corner, corner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val textSize = cell * 0.66f
        whitePiece.textSize = textSize
        blackPiece.textSize = textSize
        // 字形垂直居中：baseline = 格心 - (ascent+descent)/2
        val fm = whitePiece.fontMetrics
        val dy = -(fm.ascent + fm.descent) / 2f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val left = c * cell
                val top = r * cell
                canvas.drawRect(left, top, left + cell, top + cell, if ((r + c) % 2 == 0) lightPaint else darkPaint)
                val p = cells[r * 8 + c]
                if (p != ' ') {
                    val glyph = glyphOf(p)
                    val paint = if (p.isUpperCase()) whitePiece else blackPiece
                    canvas.drawText(glyph, left + cell / 2f, top + cell / 2f + dy, paint)
                }
            }
        }

        // 行棋方指示点（右下）
        val dotR = 3f * density
        val cx = width - 5f * density
        val cy = height - 5f * density
        if (stmWhite) {
            stmPaint.color = Color.parseColor("#FAFAFA")
            canvas.drawCircle(cx, cy, dotR, stmPaint)
        } else {
            stmPaint.color = Color.parseColor("#161616")
            canvas.drawCircle(cx, cy, dotR, stmPaint)
            stmStroke.strokeWidth = density
            canvas.drawCircle(cx, cy, dotR, stmStroke)
        }
        canvas.restore()
    }

    private fun glyphOf(p: Char): String = when (p.lowercaseChar()) {
        'k' -> "♚"
        'q' -> "♛"
        'r' -> "♜"
        'b' -> "♝"
        'n' -> "♞"
        else -> "♟"
    }
}
