package com.chessassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.chessassist.engine.ChessMath
import com.chessassist.engine.EngineLoop

/**
 * 控制页（深色）：状态卡、一键操作、自动走棋、高级设置（折叠）、事件日志（折叠）。
 * 纯 framework 控件，无新增依赖。状态每秒刷新，无需 adb 即可看清运行状态。
 */
class MainActivity : Activity() {

    private lateinit var stateDot: TextView
    private lateinit var stateLabel: TextView
    private lateinit var bestMove: TextView
    private lateinit var winRate: TextView
    private lateinit var statusRows: TextView
    private lateinit var toggleBtn: Button
    private lateinit var autoSwitch: Switch
    private lateinit var timeLabel: TextView
    private lateinit var timeBar: SeekBar
    private lateinit var depthLabel: TextView
    private lateinit var depthBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var berserkSwitch: Switch
    private lateinit var ponderSwitch: Switch
    private lateinit var funcHeader: TextView
    private lateinit var funcBox: LinearLayout
    private lateinit var backendLabel: TextView
    private lateinit var backendRow: LinearLayout
    private var backendButtons: List<Button> = emptyList()
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var testBtn: Button
    private lateinit var remoteTimeLabel: TextView
    private lateinit var remoteTimeBar: SeekBar
    private lateinit var remoteDepthLabel: TextView
    private lateinit var remoteDepthBar: SeekBar
    private lateinit var advancedBox: LinearLayout
    private lateinit var advancedHeader: TextView
    private lateinit var eventsHeader: TextView
    private lateinit var eventsScroll: ScrollView
    private lateinit var events: TextView
    private var eventsOpen = false
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            try {
                renderStatus(EngineLoop.snapshot())
                val ev = EngineLoop.recentEvents()
                eventsHeader.text = "最近事件 " + if (eventsOpen) "▼" else "▶ " +
                    (ev.lastOrNull() ?: "暂无事件")
                if (eventsOpen) {
                    events.text = if (ev.isEmpty()) "暂无事件" else ev.joinToString("\n")
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

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "ChessPilot"
            textSize = 22f
            paint.isFakeBoldText = true
        })
        try {
            val vn = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            if (vn.isNotEmpty()) {
                titleRow.addView(TextView(this).apply {
                    text = "v$vn"
                    textSize = 12f
                    setTextColor(Color.parseColor("#9E9E9E"))
                    setPadding(dp(8), dp(6), 0, 0)
                })
            }
        } catch (_: Throwable) {
        }
        root.addView(titleRow)

        // ---- status card ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(pad, dp(12), pad, dp(12))
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stateDot = TextView(this).apply { text = "●"; textSize = 18f }
        stateLabel = TextView(this).apply {
            textSize = 18f
            paint.isFakeBoldText = true
            setPadding(dp(6), 0, 0, 0)
        }
        row1.addView(stateDot)
        row1.addView(stateLabel)
        card.addView(row1)
        bestMove = TextView(this).apply {
            textSize = 26f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
        }
        winRate = TextView(this).apply { textSize = 16f }
        card.addView(bestMove)
        card.addView(winRate)
        statusRows = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(statusRows)
        root.addView(card.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.let {
                it.setMargins(0, dp(8), 0, 0)
            }
        })

        // ---- primary toggle + secondary row ----
        toggleBtn = Button(this).apply { textSize = 16f }
        toggleBtn.setOnClickListener { onToggleService() }
        root.addView(toggleBtn)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openDuo = Button(this).apply {
            text = "打开多邻国"
            textSize = 14f
            setOnClickListener { openDuolingo() }
        }
        val overlayBtn = Button(this).apply {
            text = "悬浮窗授权"
            textSize = 14f
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        row2.addView(openDuo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(overlayBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        // ---- function settings (expandable): the three live switches in one row ----
        funcHeader = TextView(this).apply {
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, pad / 2, 0, 0)
            setOnClickListener { toggleFunc() }
        }
        root.addView(funcHeader)
        funcBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        autoSwitch = Switch(this).apply {
            text = "自动走棋"
            textSize = 15f
            isChecked = BridgeSettings.autoPlayEnabled
            setOnCheckedChangeListener { _, on ->
                BridgeSettings.autoPlayEnabled = on
                BridgeSettings.save(this@MainActivity)
            }
        }
        funcBox.addView(autoSwitch)
        funcBox.addView(TextView(this).apply {
            text = "全自动模拟触摸下棋，谨慎使用。"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
        })
        berserkSwitch = Switch(this).apply {
            text = "狂暴模式"
            textSize = 15f
            isChecked = BridgeSettings.berserk
            setOnCheckedChangeListener { _, on ->
                BridgeSettings.berserk = on
                BridgeSettings.save(this@MainActivity)
                // Live-apply: the loop recreates the engine same-generation.
                EngineLoop.restartEngine(if (on) "狂暴开" else "狂暴关")
                try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
            }
        }
        funcBox.addView(berserkSwitch)
        funcBox.addView(TextView(this).apply {
            text = "极限资源配给强计算，功耗提高。"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
        })
        ponderSwitch = Switch(this).apply {
            text = "极限思考"
            textSize = 15f
            isChecked = BridgeSettings.ponderEnabled
            setOnCheckedChangeListener { _, on ->
                BridgeSettings.ponderEnabled = on
                BridgeSettings.save(this@MainActivity)
                // No engine restart: the loop reads the flag live every iteration.
                try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
            }
        }
        funcBox.addView(ponderSwitch)
        funcBox.addView(TextView(this).apply {
            text = "对手走棋时同步预测，功耗提高。"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
        })
        root.addView(funcBox)
        updateFuncHeader()

        // ---- advanced (collapsed) ----
        advancedHeader = TextView(this).apply {
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, pad / 2, 0, 0)
            setOnClickListener { toggleAdvanced() }
        }
        root.addView(advancedHeader)
        advancedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        timeLabel = TextView(this).apply { textSize = 14f }
        advancedBox.addView(timeLabel)
        timeBar = SeekBar(this).apply {
            max = 29 // 0..29 → 100..3000ms
            progress = ((BridgeSettings.movetimeMs / 100).toInt() - 1).coerceIn(0, 29)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        advancedBox.addView(timeBar)
        depthLabel = TextView(this).apply { textSize = 14f }
        advancedBox.addView(depthLabel)
        depthBar = SeekBar(this).apply {
            max = 30 // 0 = 按时间思考，1..30 = 固定深度
            progress = BridgeSettings.searchDepth.coerceIn(0, 30)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        advancedBox.addView(depthBar)
        speedLabel = TextView(this).apply { textSize = 14f }
        advancedBox.addView(speedLabel)
        speedBar = SeekBar(this).apply {
            max = 27 // 0..27 → 150..1500ms
            progress = ((BridgeSettings.autoPlayDelayMs - 150) / 50).toInt().coerceIn(0, 27)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        advancedBox.addView(speedBar)
        // ---- compute backend (local phone engine vs PC remote engine) ----
        backendLabel = TextView(this).apply {
            text = "算力后端"
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        }
        advancedBox.addView(backendLabel)
        backendRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val specs = listOf(
            "本地" to BridgeSettings.BACKEND_LOCAL,
            "自动" to BridgeSettings.BACKEND_AUTO,
            "远程电脑" to BridgeSettings.BACKEND_REMOTE,
        )
        backendButtons = specs.map { (label, value) ->
            Button(this).apply {
                text = label
                textSize = 13f
                setOnClickListener {
                    BridgeSettings.backend = value
                    BridgeSettings.save(this@MainActivity)
                    updateBackendButtons()
                    // Re-resolve engines through the new policy (also lifts
                    // any remote quarantine). 1~2 s gap, game untouched.
                    EngineLoop.restartEngine("后端切换到$label")
                    try { renderStatus(EngineLoop.snapshot()) } catch (_: Throwable) {}
                }
            }.also {
                backendRow.addView(it, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ))
            }
        }
        advancedBox.addView(backendRow)
        hostInput = EditText(this).apply {
            hint = "电脑IP，如 192.168.1.5"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(BridgeSettings.pcHost)
            addTextChangedListener(saveOnType { s ->
                BridgeSettings.pcHost = s.trim().take(64)
            })
        }
        advancedBox.addView(hostInput)
        portInput = EditText(this).apply {
            hint = "端口（默认 41718）"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(BridgeSettings.pcPort.toString())
            addTextChangedListener(saveOnType { s ->
                BridgeSettings.pcPort = s.toIntOrNull()?.coerceIn(1, 65535) ?: 41718
            })
        }
        advancedBox.addView(portInput)
        remoteTimeLabel = TextView(this).apply { textSize = 14f }
        advancedBox.addView(remoteTimeLabel)
        remoteTimeBar = SeekBar(this).apply {
            max = 19 // 0..19 → 500..10000ms
            progress = ((BridgeSettings.remoteMovetimeMs / 500).toInt() - 1).coerceIn(0, 19)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        advancedBox.addView(remoteTimeBar)
        remoteDepthLabel = TextView(this).apply { textSize = 14f }
        advancedBox.addView(remoteDepthLabel)
        remoteDepthBar = SeekBar(this).apply {
            max = 40 // 0 = 按时间思考，1..40 = 固定深度
            progress = BridgeSettings.remoteDepth.coerceIn(0, 40)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        advancedBox.addView(remoteDepthBar)
        testBtn = Button(this).apply {
            text = "测试远程连接"
            textSize = 14f
            setOnClickListener {
                saveHostPort()
                isEnabled = false
                Thread {
                    val msg = EngineLoop.testRemote()
                    // Success means the new host/port works: re-resolve now
                    // so it takes effect without waiting for a new game.
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
        advancedBox.addView(testBtn)
        advancedBox.addView(TextView(this).apply {
            text = "自动=远程优先，断线秒切本地，下局重连；下面两项发给电脑执行（脚本参数是默认值，可被覆盖），上面三项只管本地。"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
        })
        root.addView(advancedBox)
        updateAdvancedHeader()

        eventsHeader = TextView(this).apply {
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, pad / 2, 0, 0)
            setOnClickListener { toggleEvents() }
        }
        root.addView(eventsHeader)
        eventsScroll = ScrollView(this).apply { visibility = View.GONE }
        events = TextView(this).apply {
            text = "暂无事件"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        eventsScroll.addView(events)
        // Fixed log height: the whole page scrolls now (weight needs a bounded
        // parent, which a ScrollView is not).
        root.addView(eventsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
        ))
        setContentView(ScrollView(this).apply { addView(root) })
        refreshSettings()
    }

    private fun renderStatus(s: EngineLoop.StatusSnapshot) {
        stateDot.setTextColor(s.engineColor)
        stateLabel.text = s.engineLabel
        if (s.bestMove != null) {
            bestMove.visibility = View.VISIBLE
            winRate.visibility = View.VISIBLE
            bestMove.text = s.bestMove
            winRate.text = "${s.winText}（${s.bestAgo}）"
            winRate.setTextColor(ChessMath.winRateColor(s.winRate01))
        } else {
            bestMove.visibility = View.GONE
            winRate.visibility = View.GONE
        }
        statusRows.text = buildString {
            appendLine("Hook：${s.hookLine}")
            appendLine("NNUE：${s.nnue}")
            appendLine("引擎：${s.engineResLine}")
            appendLine("后端：${s.backendLine}")
            appendLine("局面：${s.fen.take(48)}")
            appendLine("思考：${s.settingsLine}　自动：${s.autoLine}")
            appendLine("极限思考：${s.ponderLine}")
            append("心跳：${s.heartbeatAgo}")
            if (s.staleMinOrNull != null) append("　停滞${s.staleMinOrNull}分")
            if (s.oursTurnWaiting) append("　轮到你走棋")
            if (s.error != null) append("\n错误：${s.error}")
        }
        toggleBtn.text = if (s.engineLabel == "已停止") "启动引擎服务" else "停止引擎服务"
    }

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

    private var funcOpen = true

    private fun toggleFunc() {
        funcOpen = !funcOpen
        funcBox.visibility = if (funcOpen) View.VISIBLE else View.GONE
        updateFuncHeader()
    }

    private fun updateFuncHeader() {
        funcHeader.text = "功能设置 " + if (funcOpen) "▼" else "▶"
    }

    private var advancedOpen = false

    private fun toggleAdvanced() {
        advancedOpen = !advancedOpen
        advancedBox.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        updateAdvancedHeader()
    }

    private fun updateAdvancedHeader() {
        advancedHeader.text = "高级设置 " + if (advancedOpen) "▼" else "▶"
    }

    private fun toggleEvents() {
        eventsOpen = !eventsOpen
        eventsScroll.visibility = if (eventsOpen) View.VISIBLE else View.GONE
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

    /** Selected backend button reads disabled; the other two stay tappable. */
    private fun updateBackendButtons() {
        val cur = BridgeSettings.backend
        val values = listOf(
            BridgeSettings.BACKEND_LOCAL,
            BridgeSettings.BACKEND_AUTO,
            BridgeSettings.BACKEND_REMOTE,
        )
        backendButtons.forEachIndexed { i, b ->
            b.isEnabled = values.getOrNull(i) != cur
        }
    }

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) onChange()
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun refreshSettings() {
        BridgeSettings.movetimeMs = ((timeBar.progress + 1) * 100).toLong()
        BridgeSettings.searchDepth = depthBar.progress
        BridgeSettings.autoPlayDelayMs = 150L + speedBar.progress * 50L
        BridgeSettings.remoteMovetimeMs = ((remoteTimeBar.progress + 1) * 500).toLong()
        BridgeSettings.remoteDepth = remoteDepthBar.progress
        BridgeSettings.save(this)
        // Fixed depth makes the time slider meaningless — grey it out instead
        // of explaining it in small print.
        val byTime = BridgeSettings.searchDepth == 0
        timeBar.isEnabled = byTime
        timeLabel.alpha = if (byTime) 1f else 0.4f
        timeLabel.text = "思考时间 ${BridgeSettings.movetimeMs}ms"
        depthLabel.text = if (BridgeSettings.searchDepth > 0) {
            "固定深度 ${BridgeSettings.searchDepth} 层"
        } else {
            "固定深度 关"
        }
        speedLabel.text = "走棋间隔 ${BridgeSettings.autoPlayDelayMs}ms"
        val rByTime = BridgeSettings.remoteDepth == 0
        remoteTimeBar.isEnabled = rByTime
        remoteTimeLabel.alpha = if (rByTime) 1f else 0.4f
        remoteTimeLabel.text = "远程思考时间 ${BridgeSettings.remoteMovetimeMs}ms"
        remoteDepthLabel.text = if (BridgeSettings.remoteDepth > 0) {
            "远程固定深度 ${BridgeSettings.remoteDepth} 层"
        } else {
            "远程固定深度 关"
        }
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
        autoSwitch.isChecked = BridgeSettings.autoPlayEnabled
        berserkSwitch.isChecked = BridgeSettings.berserk
        ponderSwitch.isChecked = BridgeSettings.ponderEnabled
        updateBackendButtons()
        if (hostInput.text.toString() != BridgeSettings.pcHost) {
            hostInput.setText(BridgeSettings.pcHost)
        }
        val portStr = BridgeSettings.pcPort.toString()
        if (portInput.text.toString() != portStr) {
            portInput.setText(portStr)
        }
        remoteTimeBar.progress = ((BridgeSettings.remoteMovetimeMs / 500).toInt() - 1).coerceIn(0, 19)
        remoteDepthBar.progress = BridgeSettings.remoteDepth.coerceIn(0, 40)
        timeBar.progress = ((BridgeSettings.movetimeMs / 100).toInt() - 1).coerceIn(0, 29)
        depthBar.progress = BridgeSettings.searchDepth.coerceIn(0, 30)
        speedBar.progress = ((BridgeSettings.autoPlayDelayMs - 150) / 50).toInt().coerceIn(0, 27)
        refreshSettings()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }
}
