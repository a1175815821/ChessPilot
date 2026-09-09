package com.chessassist.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * ChessPilot 设计系统 v1.0（见 docs/design/DESIGN_SYSTEM.md）。
 *
 * 纯 framework 实现，无新增依赖。语义色（胜率三档、狂暴红、远程绿）是既有契约，
 * 这里只引用不重新定义（见 ChessMath / OverlayManager）。
 */
object Ui {

    // ---- 中性层 ----
    val BG = Color.parseColor("#0B0F14")
    val SURFACE = Color.parseColor("#131922")
    val ELEVATED = Color.parseColor("#1B2431")
    val INSET = Color.parseColor("#0A0E13")
    val LINE = Color.parseColor("#14FFFFFF")
    val LINE_STRONG = Color.parseColor("#26FFFFFF")
    val T1 = Color.parseColor("#F2F5F9")
    val T2 = Color.parseColor("#A6B0BE")
    val T3 = Color.parseColor("#6B7686")
    val TRACK = Color.parseColor("#2A3342")

    // ---- 品牌 ----
    val GOLD = Color.parseColor("#F5B942")
    val GOLD_PRESSED = Color.parseColor("#D9A231")
    val ON_GOLD = Color.parseColor("#1A1305")
    val GOLD_DIM = Color.parseColor("#29F5B942")

    // ---- 功能 ----
    val DANGER = Color.parseColor("#FF5252")
    val DANGER_DIM = Color.parseColor("#26FF5252")

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 内容列最大宽度（宽屏居中，见规范 §3）。 */
    fun contentWidth(ctx: Context): Int =
        minOf(ctx.resources.displayMetrics.widthPixels, dp(ctx, 560f))

    fun ripple(ctx: Context, maskLight: Boolean): android.graphics.drawable.Drawable? {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    private fun round(color: Int, radiusPx: Float, strokeW: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeW > 0) setStroke(strokeW, strokeColor)
        }

    // ---- 卡片 ----

    fun card(ctx: Context): LinearLayout {
        val p = dp(ctx, 16f)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(SURFACE, dp(ctx, 16f).toFloat(), dp(ctx, 1f), LINE)
            setPadding(p, p, p, p)
        }
    }

    // ---- 按钮 ----

    private fun rippleOf(content: GradientDrawable, rippleColor: Int) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    fun primaryButton(ctx: Context): Button = Button(ctx).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ON_GOLD)
        minHeight = dp(ctx, 48f)
        background = rippleOf(round(GOLD, dp(ctx, 12f).toFloat()), 0x33000000)
    }

    fun dangerOutlineButton(ctx: Context): Button = Button(ctx).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(DANGER)
        minHeight = dp(ctx, 48f)
        background = rippleOf(
            round(Color.TRANSPARENT, dp(ctx, 12f).toFloat(), dp(ctx, 1.5f), DANGER),
            0x26FF5252.toInt()
        )
    }

    fun secondaryButton(ctx: Context): Button = Button(ctx).apply {
        textSize = 14f
        setTextColor(T1)
        minHeight = dp(ctx, 44f)
        background = rippleOf(
            round(ELEVATED, dp(ctx, 12f).toFloat(), dp(ctx, 1f), LINE_STRONG),
            0x22FFFFFF
        )
    }

    /** 主操作按钮随引擎状态换肤：停止=金底「启动」，运行=红描边「停止」。 */
    fun styleToggleButton(btn: Button, running: Boolean) {
        val ctx = btn.context
        if (running) {
            btn.text = "停止引擎服务"
            btn.setTextColor(DANGER)
            btn.background = rippleOf(
                round(Color.TRANSPARENT, dp(ctx, 12f).toFloat(), dp(ctx, 1.5f), DANGER),
                0x26FF5252.toInt()
            )
        } else {
            btn.text = "启动引擎服务"
            btn.setTextColor(ON_GOLD)
            btn.background = rippleOf(round(GOLD, dp(ctx, 12f).toFloat()), 0x33000000)
        }
    }

    // ---- 文本 ----

    fun caption(ctx: Context, text: String = ""): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(T3)
    }

    fun mono(ctx: Context, text: String = "", sizeSp: Float = 12f): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sizeSp
        typeface = Typeface.MONOSPACE
        setTextColor(T2)
    }

    // ---- KV 行（状态卡） ----

    class KvRow(val root: LinearLayout, val value: TextView)

    fun kvRow(ctx: Context, label: String): KvRow {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 7f), 0, dp(ctx, 7f))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(T3)
        }, LinearLayout.LayoutParams(dp(ctx, 88f), ViewGroup.LayoutParams.WRAP_CONTENT))
        val value = TextView(ctx).apply {
            textSize = 13f
            setTextColor(T2)
        }
        row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return KvRow(row, value)
    }

    /** KV 行间的 1px 虚线感分隔（实线 hairline）。 */
    fun hairline(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 0.5f)
        )
    }

    // ---- 开关行 ----

    class SwitchRow(val root: LinearLayout, val switch: Switch, val captionTv: TextView)

    fun switchRow(
        ctx: Context, title: String, captionText: String, checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): SwitchRow {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56f)
            foreground = ripple(ctx, true)
            isClickable = true
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(T1)
        })
        val cap = caption(ctx, captionText)
        col.addView(cap)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(ctx).apply {
            text = ""
            isChecked = checked
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#66F5B942"), TRACK)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(GOLD, T3)
            )
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(ctx, 12f) })
        row.setOnClickListener { sw.toggle() }
        return SwitchRow(row, sw, cap)
    }

    // ---- 滑杆行 ----

    class SliderRow(val root: LinearLayout, val labelTv: TextView, val chip: TextView, val bar: SeekBar) {
        fun setChip(text: String) {
            chip.text = text
        }

        fun setEnabledRow(enabled: Boolean) {
            bar.isEnabled = enabled
            root.alpha = if (enabled) 1f else 0.4f
        }
    }

    fun sliderRow(
        ctx: Context, label: String, max: Int, progress: Int,
        onUserChange: () -> Unit,
    ): SliderRow {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 10f), 0, dp(ctx, 6f))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelTv = TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(T1)
        }
        head.addView(labelTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(GOLD)
            background = round(GOLD_DIM, dp(ctx, 8f).toFloat())
            setPadding(dp(ctx, 9f), dp(ctx, 3f), dp(ctx, 9f), dp(ctx, 3f))
        }
        head.addView(chip)
        row.addView(head)
        val bar = SeekBar(ctx).apply {
            this.max = max
            this.progress = progress
            progressTintList = ColorStateList.valueOf(GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            thumbTintList = ColorStateList.valueOf(GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onUserChange()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 6f) })
        return SliderRow(row, labelTv, chip, bar)
    }

    // ---- 分段控件 ----

    class Segmented(val root: LinearLayout, private val cells: List<TextView>) {
        var selected: Int = -1
            private set

        fun select(i: Int) {
            selected = i
            cells.forEachIndexed { idx, tv ->
                val ctx = tv.context
                if (idx == i) {
                    tv.setTextColor(ON_GOLD)
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.background = round(GOLD, dp(ctx, 9f).toFloat())
                } else {
                    tv.setTextColor(T2)
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.background = null
                }
            }
        }
    }

    fun segmented(ctx: Context, items: List<String>, onSelect: (Int) -> Unit): Segmented {
        val pad = dp(ctx, 3f)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(INSET, dp(ctx, 12f).toFloat(), dp(ctx, 1f), LINE)
            setPadding(pad, pad, pad, pad)
        }
        val holder = arrayOfNulls<Segmented>(1)
        val cells = items.mapIndexed { i, label ->
            TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setOnClickListener {
                    val seg = holder[0]
                    if (seg != null && seg.selected != i) {
                        seg.select(i)
                        onSelect(i)
                    }
                }
            }.also {
                root.addView(it, LinearLayout.LayoutParams(0, dp(ctx, 36f), 1f))
            }
        }
        val seg = Segmented(root, cells)
        holder[0] = seg
        return seg
    }

    // ---- 角标 ----

    fun badge(ctx: Context, text: String, color: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = round(color, dp(ctx, 6f).toFloat())
        setPadding(dp(ctx, 7f), dp(ctx, 2f), dp(ctx, 7f), dp(ctx, 2f))
    }

    // ---- 可折叠分组卡 ----

    class Section(
        val card: LinearLayout,
        val head: LinearLayout,
        val chevron: TextView,
        val preview: TextView,
        val body: LinearLayout,
    ) {
        var open = true
            private set

        fun setOpen(v: Boolean, animate: Boolean = true) {
            open = v
            body.visibility = if (v) View.VISIBLE else View.GONE
            val target = if (v) 0f else -90f
            if (animate) chevron.animate().rotation(target).setDuration(200).start()
            else chevron.rotation = target
        }

        fun toggle() = setOpen(!open)
    }

    fun section(ctx: Context, title: String, openDefault: Boolean): Section {
        val card = card(ctx).apply {
            // 卡片内边距已由 card() 给出；分组头不额外留白
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            foreground = ripple(ctx, true)
            isClickable = true
        }
        head.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(T1)
        })
        val preview = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(T3)
            gravity = Gravity.END
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(preview, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = dp(ctx, 12f) })
        val chevron = TextView(ctx).apply {
            text = "▾"
            textSize = 12f
            setTextColor(T3)
        }
        head.addView(chevron, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(ctx, 8f) })
        card.addView(head)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8f), 0, 0)
        }
        card.addView(body)
        val section = Section(card, head, chevron, preview, body)
        head.setOnClickListener { section.toggle() }
        section.setOpen(openDefault, animate = false)
        return section
    }

    // ---- 输入框 ----

    fun input(ctx: Context, hint: String, numeric: Boolean): EditText = EditText(ctx).apply {
        this.hint = hint
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(T1)
        setHintTextColor(T3)
        setSingleLine(true)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = round(INSET, dp(ctx, 12f).toFloat(), dp(ctx, 1f), LINE_STRONG)
        setPadding(dp(ctx, 12f), 0, dp(ctx, 12f), 0)
    }

    // ---- 胜率条（对局雷达卡） ----

    class WinBar(ctx: Context) : View(ctx) {
        private var fraction = 0f
        private var barColor = T3
        private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = TRACK
        }
        private val barPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()

        fun set(f: Float?, color: Int) {
            fraction = (f ?: 0f).coerceIn(0f, 1f)
            barColor = color
            barPaint.color = color
            invalidate()
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(rect, r, r, trackPaint)
            if (fraction > 0f) {
                rect.set(0f, 0f, maxOf(width * fraction, height.toFloat()), height.toFloat())
                c.drawRoundRect(rect, r, r, barPaint)
            }
        }
    }
}
