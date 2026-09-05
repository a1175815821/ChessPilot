package com.chessassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import com.chessassist.engine.EngineLoop
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 控制页：引擎启停、自动走棋开关、思考设置，实时显示引擎状态、
 * Hook 连接状态和最近事件。不用 adb 也能看清运行状态。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var events: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var timeLabel: TextView
    private lateinit var timeBar: SeekBar
    private lateinit var depthLabel: TextView
    private lateinit var depthBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            try {
                status.text = EngineLoop.statusText()
                val ev = EngineLoop.recentEvents()
                events.text = if (ev.isEmpty()) "暂无事件" else ev.joinToString("\n")
            } catch (_: Throwable) {
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.ensureChannel(this)
        BridgeSettings.load(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "多邻国国际象棋助手"
            textSize = 20f
        })
        status = TextView(this).apply {
            text = "…"
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        autoSwitch = Switch(this).apply {
            text = "自动走棋（在棋盘上自动走出最佳招）"
            isChecked = BridgeSettings.autoPlayEnabled
            setOnCheckedChangeListener { _, on ->
                BridgeSettings.autoPlayEnabled = on
                BridgeSettings.save(this@MainActivity)
            }
        }
        root.addView(autoSwitch)
        root.addView(TextView(this).apply {
            text = "提醒：自动走棋违反多邻国 ToS，有封号风险，默认关闭。"
            textSize = 12f
        })

        timeLabel = TextView(this)
        root.addView(timeLabel)
        timeBar = SeekBar(this).apply {
            max = 29 // 0..29 → 100..3000ms
            progress = ((BridgeSettings.movetimeMs / 100).toInt() - 1).coerceIn(0, 29)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        root.addView(timeBar)

        depthLabel = TextView(this)
        root.addView(depthLabel)
        depthBar = SeekBar(this).apply {
            max = 30 // 0 = 按时间思考，1..30 = 固定深度
            progress = BridgeSettings.searchDepth.coerceIn(0, 30)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        root.addView(depthBar)

        speedLabel = TextView(this)
        root.addView(speedLabel)
        speedBar = SeekBar(this).apply {
            max = 27 // 0..27 → 150..1500ms
            progress = ((BridgeSettings.autoPlayDelayMs - 150) / 50).toInt().coerceIn(0, 27)
            setOnSeekBarChangeListener(simpleSeek { refreshSettings() })
        }
        root.addView(speedBar)
        refreshSettings()

        root.addView(Button(this).apply {
            text = "启动引擎服务"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                startForegroundService(Intent(this@MainActivity, AssistService::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "停止引擎服务"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, AssistService::class.java)
                        .setAction(AssistService.ACTION_STOP)
                )
            }
        })
        root.addView(Button(this).apply {
            text = "授权悬浮窗（备用横幅）"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        })
        root.addView(TextView(this).apply {
            text = "对局无反应的话：去 LSPosed 管理器启用本模块，" +
                "作用域勾选多邻国（com.duolingo），然后重启手机。"
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "最近事件："
            setPadding(0, pad / 2, 0, 0)
        })
        val scroll = ScrollView(this)
        events = TextView(this).apply { text = "暂无事件" }
        scroll.addView(events)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
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
        BridgeSettings.save(this)
        timeLabel.text = "思考时间：${BridgeSettings.movetimeMs}ms（固定深度开启时忽略）"
        depthLabel.text = if (BridgeSettings.searchDepth > 0) {
            "固定深度：${BridgeSettings.searchDepth} 层（手机上超过 22 层会很慢）"
        } else {
            "固定深度：按时间思考"
        }
        speedLabel.text =
            "走棋间隔：${BridgeSettings.autoPlayDelayMs}ms（每步±20%随机浮动；太小容易点不上弹回）"
    }

    override fun onResume() {
        super.onResume()
        // Retry banner setup ONLY while the engine service runs: the banner is
        // owned by the service lifecycle (AssistService creates/releases it).
        // Unconditional init used to float a stale "象棋助手就绪" banner over
        // everything after merely opening (and leaving) this screen.
        if (EngineLoop.engineState != EngineLoop.STATE_STOPPED) {
            OverlayManager.init(this)
        }
        BridgeSettings.load(this)
        autoSwitch.isChecked = BridgeSettings.autoPlayEnabled
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
