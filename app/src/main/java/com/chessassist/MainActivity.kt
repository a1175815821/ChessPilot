package com.chessassist

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chessassist.engine.ChessMath
import com.chessassist.engine.EngineLoop
import com.chessassist.ui.FenBoardView
import com.chessassist.ui.Ui

/**
 * 控制页（设计系统 v1.0，见 docs/design/DESIGN_SYSTEM.md）。
 *
 * 信息层级：对局雷达卡（状态/最佳招法/胜率/FEN 小棋盘）→ 连接与引擎状态（KV）
 * → 快捷操作 → 功能设置（三开关，默认展开）→ 高级设置（折叠）→ 最近事件（折叠）。
 * 纯 framework 控件，无新增依赖。状态每秒刷新，无需 adb 即可看清运行状态。
 *
 * 业务行为与旧版一致：四项滑杆映射、开关持久化、后端切换重启引擎、
 * 通知权限请求、onResume 全量同步、OverlayManager.init 仅在服务运行时触发。
 */
class MainActivity : Activity() {

    // 对局雷达卡
    private lateinit var dotTv: TextView
    private lateinit var stateLabel: TextView
    private lateinit var modeBadge: TextView
    private lateinit var moveCap: TextView
    private lateinit var bestMoveTv: TextView
    private lateinit var winRateTv: TextView
    private lateinit var winBar: Ui.WinBar
    private lateinit var bestSub: TextView
    private lateinit var board: FenBoardView
    private var pulseAnim: ObjectAnimator? = null

    // KV 状态卡
    private lateinit var kvHook: TextView
    private lateinit var kvNnue: TextView
    private lateinit var kvEngine: TextView
    private lateinit var kvBackend: TextView
    private lateinit var kvFen: TextView
    private lateinit var kvThink: TextView
    private lateinit var kvAuto: TextView
    private lateinit var kvPonder: TextView
    private lateinit var kvHeartbeat: TextView
    private lateinit var errorRow: LinearLayout
    private lateinit var errorValue: TextView

    // 操作与设置
    private lateinit var toggleBtn: Button
    private lateinit var funcSection: Ui.Section
    private lateinit var autoRow: Ui.SwitchRow
    private lateinit var berserkRow: Ui.SwitchRow
    private lateinit var ponderRow: Ui.SwitchRow
    private lateinit var advSection: Ui.Section
    private lateinit var timeRow: Ui.SliderRow
    private lateinit var depthRow: Ui.SliderRow
    private lateinit var speedRow: Ui.SliderRow
    private lateinit var backendSeg: Ui.Segmented
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var remoteTimeRow: Ui.SliderRow
    private lateinit var remoteDepthRow: Ui.SliderRow
    private lateinit var testBtn: Button
    private lateinit var eventsSection: Ui.Section
    private lateinit var eventsText: TextView
    private lateinit var eventsScroll: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            try {
                renderStatus(EngineLoop.snapshot())
                val ev = EngineLoop.recentEvents()
                eventsSection.preview.text = ev.lastOrNull() ?: "暂无事件"
                if (eventsSection.open) {
                    eventsText.text = if (ev.isEmpty()) "暂无事件" else ev.joinToString("\n")
                    eventsScroll.post { eventsScroll.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (_: Throwable) {
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.ensureChannel(this)
        BridgeSettings.load(this)

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        fun dp(v: Int): Int = Ui.dp(this, v.toFloat())
        val pad = dp(16)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            // 分组展开/折叠 200ms 高度动画（设计规范 §5）
            layoutTransition = LayoutTransition().apply { setDuration(200) }
        }

        // ================= Header =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "♞"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.ON_GOLD)
            background = GradientDrawable().apply {
                setColor(Ui.GOLD)
                cornerRadius = dp(12).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "ChessPilot"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.T1)
        })
        titleCol.addView(Ui.caption(this, "多邻国国际象棋助手"))
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        try {
            val vn = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            if (vn.isNotEmpty()) {
                header.addView(TextView(this).apply {
                    text = "v$vn"
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Ui.GOLD)
                    background = GradientDrawable().apply {
                        setColor(Ui.GOLD_DIM)
                        cornerRadius = dp(8).toFloat()
                    }
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                })
            }
        } catch (_: Throwable) {
        }
        content.addView(header)

        // ================= 对局雷达卡 =================
        val hero = Ui.card(this)
        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dotTv = TextView(this).apply {
            text = "●"
            textSize = 13f
            setTextColor(Ui.T3)
        }
        stateRow.addView(dotTv)
        stateLabel = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.T1)
            setPadding(dp(8), 0, 0, 0)
        }
        stateRow.addView(stateLabel)
        stateRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        modeBadge = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            visibility = View.GONE
        }
        stateRow.addView(modeBadge)
        hero.addView(stateRow)

        val heroMid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val heroLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        moveCap = Ui.caption(this, "最佳招法")
        heroLeft.addView(moveCap)
        bestMoveTv = TextView(this).apply {
            textSize = 30f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.T1)
        }
        heroLeft.addView(bestMoveTv)
        winRateTv = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.T3)
        }
        heroLeft.addView(winRateTv)
        winBar = Ui.WinBar(this)
        heroLeft.addView(winBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
        ).apply { topMargin = dp(8) })
        bestSub = Ui.mono(this, "", 11f).apply { setTextColor(Ui.T3) }
        heroLeft.addView(bestSub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7) })
        heroMid.addView(heroLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        board = FenBoardView(this)
        heroMid.addView(board, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
            marginStart = dp(14)
        })
        hero.addView(heroMid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        content.addView(hero, bottomMargin(dp(12)))

        // ================= 连接与引擎状态卡 =================
        val kvCard = Ui.card(this).apply {
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        fun kv(label: String, mono: Boolean = false): TextView {
            val row = Ui.kvRow(this, label)
            if (mono) {
                row.value.typeface = Typeface.MONOSPACE
                row.value.setSingleLine(true)
                row.value.ellipsize = TextUtils.TruncateAt.END
            }
            if (kvCard.childCount > 0) kvCard.addView(Ui.hairline(this))
            kvCard.addView(row.root)
            return row.value
        }
        kvHook = kv("Hook 连接")
        kvNnue = kv("NNUE")
        kvEngine = kv("引擎")
        kvBackend = kv("算力后端")
        kvFen = kv("当前局面", mono = true)
        kvThink = kv("思考设置")
        kvAuto = kv("自动走棋")
        kvPonder = kv("极限思考")
        kvHeartbeat = kv("心跳")
        errorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.DANGER_DIM)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(9), dp(7), dp(9), dp(7))
            visibility = View.GONE
        }
        errorRow.addView(TextView(this).apply {
            text = "最近错误"
            textSize = 12f
            setTextColor(Ui.DANGER)
        }, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
        errorValue = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.DANGER)
        }
        errorRow.addView(errorValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kvCard.addView(errorRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        content.addView(kvCard, bottomMargin(dp(20)))

        // ================= 快捷操作 =================
        toggleBtn = Ui.primaryButton(this)
        toggleBtn.setOnClickListener { onToggleService() }
        content.addView(toggleBtn)
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openDuo = Ui.secondaryButton(this).apply {
            text = "打开多邻国"
            setOnClickListener { openDuolingo() }
        }
        val overlayBtn = Ui.secondaryButton(this).apply {
            text = "悬浮窗授权"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        quickRow.addView(openDuo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickRow.addView(overlayBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        content.addView(quickRow, topMargin(dp(10)))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))

        // ================= 功能设置（默认展开） =================
        funcSection = Ui.section(this, "功能设置", openDefault = true)
        autoRow = Ui.switchRow(this, "自动走棋", "全自动模拟触摸下棋，谨慎使用（ToS 风险）",
            BridgeSettings.autoPlayEnabled) { on ->
            BridgeSettings.autoPlayEnabled = on
            BridgeSettings.save(this@MainActivity)
            autoRow.captionTv.setTextColor(if (on) ChessMath.C_MID else Ui.T3)
            syncFuncPreview()
        }
        autoRow.captionTv.setTextColor(if (BridgeSettings.autoPlayEnabled) ChessMath.C_MID else Ui.T3)
        funcSection.body.addView(autoRow.root)
        funcSection.body.addView(Ui.hairline(this))
        berserkRow = Ui.switchRow(this, "狂暴模式", "极限资源配给强计算，功耗提高",
            BridgeSettings.berserk) { on ->
            BridgeSettings.berserk = on
            BridgeSettings.save(this@MainActivity)
            // Live-apply: the loop recreates the engine same-generation.
            EngineLoop.restartEngine(if (on) "狂暴开" else "狂暴关")
            syncFuncPreview()
            try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
        }
        funcSection.body.addView(berserkRow.root)
        funcSection.body.addView(Ui.hairline(this))
        ponderRow = Ui.switchRow(this, "极限思考", "对手走棋时同步预测，功耗提高",
            BridgeSettings.ponderEnabled) { on ->
            BridgeSettings.ponderEnabled = on
            BridgeSettings.save(this@MainActivity)
            // No engine restart: the loop reads the flag live every iteration.
            syncFuncPreview()
            try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
        }
        funcSection.body.addView(ponderRow.root)
        content.addView(funcSection.card, bottomMargin(dp(12)))

        // ================= 高级设置（折叠） =================
        advSection = Ui.section(this, "高级设置", openDefault = false)
        timeRow = Ui.sliderRow(this, "思考时间", 29,
            ((BridgeSettings.movetimeMs / 100).toInt() - 1).coerceIn(0, 29)) { refreshSettings() }
        advSection.body.addView(timeRow.root)
        depthRow = Ui.sliderRow(this, "固定深度", 30,
            BridgeSettings.searchDepth.coerceIn(0, 30)) { refreshSettings() }
        advSection.body.addView(depthRow.root)
        speedRow = Ui.sliderRow(this, "走棋间隔", 27,
            ((BridgeSettings.autoPlayDelayMs - 150) / 50).toInt().coerceIn(0, 27)) { refreshSettings() }
        advSection.body.addView(speedRow.root)
        advSection.body.addView(Ui.hairline(this), topMargin(dp(8)))
        advSection.body.addView(TextView(this).apply {
            text = "算力后端"
            textSize = 14f
            setTextColor(Ui.T1)
        }, topMargin(dp(12)))
        val backendValues = listOf(
            BridgeSettings.BACKEND_LOCAL,
            BridgeSettings.BACKEND_AUTO,
            BridgeSettings.BACKEND_REMOTE,
        )
        val backendLabels = listOf("本地", "自动", "远程电脑")
        backendSeg = Ui.segmented(this, backendLabels) { i ->
            BridgeSettings.backend = backendValues[i]
            BridgeSettings.save(this@MainActivity)
            // Re-resolve engines through the new policy (also lifts any remote
            // quarantine). 1~2 s gap, game untouched.
            EngineLoop.restartEngine("后端切换到${backendLabels[i]}")
            syncAdvPreview()
            try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
        }
        advSection.body.addView(backendSeg.root, topMargin(dp(6)))

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostInput = Ui.input(this, "电脑IP，如 192.168.1.5", numeric = false).apply {
            setText(BridgeSettings.pcHost)
            addTextChangedListener(saveOnType { s ->
                BridgeSettings.pcHost = s.trim().take(64)
            })
        }
        inputRow.addView(hostInput, LinearLayout.LayoutParams(0, dp(42), 1.6f))
        portInput = Ui.input(this, "端口 41718", numeric = true).apply {
            setText(BridgeSettings.pcPort.toString())
            addTextChangedListener(saveOnType { s ->
                BridgeSettings.pcPort = s.toIntOrNull()?.coerceIn(1, 65535) ?: 41718
            })
        }
        inputRow.addView(portInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(10)
        })
        advSection.body.addView(inputRow, topMargin(dp(12)))

        remoteTimeRow = Ui.sliderRow(this, "远程思考时间", 19,
            ((BridgeSettings.remoteMovetimeMs / 500).toInt() - 1).coerceIn(0, 19)) { refreshSettings() }
        advSection.body.addView(remoteTimeRow.root)
        remoteDepthRow = Ui.sliderRow(this, "远程固定深度", 40,
            BridgeSettings.remoteDepth.coerceIn(0, 40)) { refreshSettings() }
        advSection.body.addView(remoteDepthRow.root)

        testBtn = Ui.secondaryButton(this).apply {
            text = "测试远程连接"
            setOnClickListener {
                saveHostPort()
                isEnabled = false
                Thread {
                    val msg = EngineLoop.testRemote()
                    // Success means the new host/port works: re-resolve now so it
                    // takes effect without waiting for a new game.
                    if (msg.startsWith("远程可用")) {
                        EngineLoop.restartEngine("远程连通性确认")
                    }
                    ui.post {
                        isEnabled = true
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
                    }
                }.start()
            }
        }
        advSection.body.addView(testBtn, topMargin(dp(10)))
        advSection.body.addView(Ui.caption(
            this,
            "自动 = 远程优先，断线秒切本地，下局重连；远程两项发给电脑执行（脚本参数是默认值），上面三项只管本地引擎。"
        ), topMargin(dp(10)))
        content.addView(advSection.card, bottomMargin(dp(12)))

        // ================= 最近事件（折叠） =================
        eventsSection = Ui.section(this, "最近事件", openDefault = false)
        eventsScroll = ScrollView(this)
        eventsText = TextView(this).apply {
            text = "暂无事件"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.T2)
            setLineSpacing(0f, 1.35f)
        }
        eventsScroll.addView(eventsText)
        eventsScroll.background = GradientDrawable().apply {
            setColor(Ui.INSET)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Ui.LINE)
        }
        eventsScroll.setPadding(dp(12), dp(10), dp(12), dp(10))
        eventsSection.body.addView(eventsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)
        ))
        content.addView(eventsSection.card, bottomMargin(dp(4)))

        // ================= 脚注 =================
        content.addView(TextView(this).apply {
            text = "GPL-3.0 · 仅供研究\n修改多邻国行为违反其 ToS，请用小号折腾"
            textSize = 11f
            setTextColor(Ui.T3)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.6f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // 宽屏适配：内容列 max 560dp 居中（设计规范 §3）
        val frame = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(
            Ui.contentWidth(this), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL
        ))
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(frame)
        }
        setContentView(scroll)

        syncFuncPreview()
        refreshSettings()
    }

    private fun bottomMargin(px: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = px }

    private fun topMargin(px: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px }

    // ================= 状态渲染 =================

    private fun renderStatus(s: EngineLoop.StatusSnapshot) {
        dotTv.setTextColor(s.engineColor)
        setPulse(s.engineLabel == "启动中…")
        stateLabel.text = s.engineLabel

        // 模式角标：远程 > 狂暴（与 OverlayManager.applyModeStyle 同契约）
        val remote = try { EngineLoop.lastBackendUsed == "远程" } catch (_: Throwable) { false }
        when {
            remote -> {
                modeBadge.text = "算力"
                modeBadge.setTextColor(Color.parseColor("#04331D"))
                modeBadge.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00E676"))
                    cornerRadius = Ui.dp(this@MainActivity, 6f).toFloat()
                }
                modeBadge.visibility = View.VISIBLE
            }
            BridgeSettings.berserk -> {
                modeBadge.text = "狂暴"
                modeBadge.setTextColor(Color.WHITE)
                modeBadge.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF5722"))
                    cornerRadius = Ui.dp(this@MainActivity, 6f).toFloat()
                }
                modeBadge.visibility = View.VISIBLE
            }
            else -> modeBadge.visibility = View.GONE
        }

        if (s.bestMove != null) {
            moveCap.text = if (BridgeSettings.autoPlayEnabled) "自动走棋中" else "最佳招法"
            bestMoveTv.text = s.bestMove
            bestMoveTv.setTextColor(if (BridgeSettings.autoPlayEnabled) Ui.GOLD else Ui.T1)
            winRateTv.text = s.winText
            val wc = ChessMath.winRateColor(s.winRate01)
            winRateTv.setTextColor(wc)
            winBar.set(s.winRate01?.toFloat(), wc)
            bestSub.text = s.bestAgo
        } else {
            moveCap.text = "等待对局"
            bestMoveTv.text = "——"
            bestMoveTv.setTextColor(Ui.T3)
            winRateTv.text = "推荐招法与胜率将显示在这里"
            winRateTv.setTextColor(Ui.T3)
            winBar.set(0f, Ui.T3)
            bestSub.text = ""
        }
        board.setFen(s.fen)

        kvHook.text = s.hookLine
        kvHook.setTextColor(if (s.hookLine.startsWith("已连接")) ChessMath.C_WIN else Ui.T2)
        kvNnue.text = s.nnue
        kvEngine.text = s.engineResLine
        kvBackend.text = s.backendLine
        kvFen.text = s.fen.ifEmpty { "—" }
        kvThink.text = s.settingsLine
        kvAuto.text = s.autoLine
        kvAuto.setTextColor(if (s.autoLine.startsWith("开")) ChessMath.C_MID else Ui.T2)
        kvPonder.text = s.ponderLine
        kvHeartbeat.text = buildString {
            append(s.heartbeatAgo)
            if (s.staleMinOrNull != null) append("　停滞${s.staleMinOrNull}分")
            if (s.oursTurnWaiting) append("　轮到你走棋")
        }
        kvHeartbeat.setTextColor(if (s.oursTurnWaiting) Ui.GOLD else Ui.T2)
        if (s.error != null) {
            errorValue.text = s.error
            errorRow.visibility = View.VISIBLE
        } else {
            errorRow.visibility = View.GONE
        }

        Ui.styleToggleButton(toggleBtn, s.engineLabel != "已停止")
    }

    private fun setPulse(on: Boolean) {
        if (on) {
            if (pulseAnim == null) {
                pulseAnim = ObjectAnimator.ofFloat(dotTv, "alpha", 1f, 0.35f).apply {
                    duration = 1000
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            pulseAnim?.cancel()
            pulseAnim = null
            dotTv.alpha = 1f
        }
    }

    // ================= 行为 =================

    private fun onToggleService() {
        if (EngineLoop.engineState == EngineLoop.STATE_STOPPED) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, AssistService::class.java))
        } else {
            startService(Intent(this, AssistService::class.java).setAction(AssistService.ACTION_STOP))
        }
    }

    private fun openDuolingo() {
        val intent = packageManager.getLaunchIntentForPackage("com.duolingo")
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "没找到多邻国", Toast.LENGTH_SHORT).show()
        }
    }

    /** Persist backend host/port edits as the user types (no restart; the test
     * button or backend switch re-resolves engines when the user is done). */
    private fun saveOnType(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            onChange(s?.toString().orEmpty())
            BridgeSettings.save(this@MainActivity)
        }
    }

    private fun saveHostPort() {
        BridgeSettings.pcHost = hostInput.text.toString().trim().take(64)
        BridgeSettings.pcPort = portInput.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 41718
        BridgeSettings.save(this)
    }

    private fun syncFuncPreview() {
        funcSection.preview.text = buildString {
            append(if (BridgeSettings.autoPlayEnabled) "自动开" else "自动关")
            append(" · ")
            append(if (BridgeSettings.berserk) "狂暴开" else "狂暴关")
            append(" · ")
            append(if (BridgeSettings.ponderEnabled) "极限开" else "极限关")
        }
    }

    private fun syncAdvPreview() {
        advSection.preview.text = buildString {
            if (BridgeSettings.searchDepth > 0) append("深度${BridgeSettings.searchDepth}层")
            else append("${BridgeSettings.movetimeMs}ms")
            append(" · ").append(BridgeSettings.backendShort())
        }
    }

    private fun refreshSettings() {
        BridgeSettings.movetimeMs = ((timeRow.bar.progress + 1) * 100).toLong()
        BridgeSettings.searchDepth = depthRow.bar.progress
        BridgeSettings.autoPlayDelayMs = 150L + speedRow.bar.progress * 50L
        BridgeSettings.remoteMovetimeMs = ((remoteTimeRow.bar.progress + 1) * 500).toLong()
        BridgeSettings.remoteDepth = remoteDepthRow.bar.progress
        BridgeSettings.save(this)

        // Fixed depth makes the time slider meaningless — grey it out instead
        // of explaining it in small print.
        val byTime = BridgeSettings.searchDepth == 0
        timeRow.setEnabledRow(byTime)
        timeRow.setChip("${BridgeSettings.movetimeMs}ms")
        depthRow.setChip(if (BridgeSettings.searchDepth > 0) "${BridgeSettings.searchDepth} 层" else "关")
        speedRow.setChip("${BridgeSettings.autoPlayDelayMs}ms")
        val rByTime = BridgeSettings.remoteDepth == 0
        remoteTimeRow.setEnabledRow(rByTime)
        remoteTimeRow.setChip("${BridgeSettings.remoteMovetimeMs}ms")
        remoteDepthRow.setChip(if (BridgeSettings.remoteDepth > 0) "${BridgeSettings.remoteDepth} 层" else "关")
        syncAdvPreview()
    }

    override fun onResume() {
        super.onResume()
        // Retry banner setup ONLY while the engine service runs: the banner is
        // owned by the service lifecycle (AssistService creates/releases it).
        // Unconditional init used to float a stale "ChessPilot" banner over
        // everything after merely opening (and leaving) this screen.
        if (EngineLoop.engineState != EngineLoop.STATE_STOPPED) {
            OverlayManager.init(this)
        }
        BridgeSettings.load(this)
        autoRow.switch.isChecked = BridgeSettings.autoPlayEnabled
        autoRow.captionTv.setTextColor(if (BridgeSettings.autoPlayEnabled) ChessMath.C_MID else Ui.T3)
        berserkRow.switch.isChecked = BridgeSettings.berserk
        ponderRow.switch.isChecked = BridgeSettings.ponderEnabled
        syncFuncPreview()
        backendSeg.select(
            when (BridgeSettings.backend) {
                BridgeSettings.BACKEND_AUTO -> 1
                BridgeSettings.BACKEND_REMOTE -> 2
                else -> 0
            }
        )
        if (hostInput.text.toString() != BridgeSettings.pcHost) {
            hostInput.setText(BridgeSettings.pcHost)
        }
        val portStr = BridgeSettings.pcPort.toString()
        if (portInput.text.toString() != portStr) {
            portInput.setText(portStr)
        }
        remoteTimeRow.bar.progress = ((BridgeSettings.remoteMovetimeMs / 500).toInt() - 1).coerceIn(0, 19)
        remoteDepthRow.bar.progress = BridgeSettings.remoteDepth.coerceIn(0, 40)
        timeRow.bar.progress = ((BridgeSettings.movetimeMs / 100).toInt() - 1).coerceIn(0, 29)
        depthRow.bar.progress = BridgeSettings.searchDepth.coerceIn(0, 30)
        speedRow.bar.progress = ((BridgeSettings.autoPlayDelayMs - 150) / 50).toInt().coerceIn(0, 27)
        refreshSettings()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        setPulse(false)
        super.onPause()
    }
}
