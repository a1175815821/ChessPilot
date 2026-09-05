# 使用说明（中文）

面向多邻国 6.85.7 / arm64。两个包：`dist/app-release-test.apk`（伴侣 App：引擎+服务+状态页），
`dist/module-release-test.apk`（LSPosed 模块：读棋盘局面、画箭头）。

> 英文技术文档见 `README.md` / `docs/REVERSE_ENGINEERING.md`。注意 README 里早期的
> ContentProvider 轮询桥已废弃，当前是回环 TCP 桥（见下文“架构”）。

## 一、安装启用（只需做一次）

1. 手机已 root，且装好 Magisk + Zygisk + LSPosed（本机三者都有）。
2. 先装伴侣 App，打开点一次「启动引擎服务」（会弹通知权限，允许；悬浮窗按需授权，只影响备用横幅）。
3. 再装 Hook 模块 → LSPosed 管理器里启用它 → 作用域只勾 `com.duolingo` → **重启手机**
   （或至少杀掉多邻国重开；模块更新后也一样，必须让多邻国进程重建）。
4. 打开多邻国下棋。App 状态页的 `Hook 连接` 应变成“已连接”，走一步出一条 `published:`，
   1 秒左右棋盘上出现黄色原生箭头。

我们的两个 App **都不需要 root 授权**，LSPosed 弹的 root 请求是框架自己的，允许即可。

## 二、状态页怎么看（App 主界面，每秒刷新）

| 行 | 含义 |
|---|---|
| 引擎状态 | 运行中 / 启动中… / 已停止 / 启动失败 |
| Hook 连接 | 已连接（socket 在线）= 对局端连上桥；“x 秒前收到局面”= 正常走棋中；**未连接** = LSPosed 那半边没上报（见排查表） |
| NNUE | 已加载的神经网络（应为 `embedded nn-b1a57edbea57.nnue`） |
| 当前 FEN | 最后一个收到的局面 |
| 最佳招法 | 引擎给上一个局面的招（带我方胜率，杀棋会标 Mx） |
| 思考设置 | 当前生效的思考时间/固定深度 |
| 自动走棋 | 开（450ms）/ 关（与开关和间隔同步） |
| 最近错误 | 有则处理，无则忽略 |
| 最近事件 | 带时间的启动/连接/招法流水 |

## 二点五、三个新功能

- **胜率显示**：横幅和状态页在最佳招下多一行 `我方胜率xx%`（UCI cp 分经 logistic 换算，
  按你的颜色折算；拿不到颜色时回退显示黑/白胜率；杀棋显示 `我方M3`/`对方M2` 样式）。
- **常驻状态横幅**：深底圆角，可拖动（位置自动记住），跟着引擎状态走：
  手动己方回合 `等待走棋中…/正在计算…` → `推荐：e2e4/我方胜率xx%`；
  自动模式 `自动走棋中…/正在计算…` → `自动走棋中：e2e4/我方胜率xx%`，标题旁有转圈动画；
  对方回合 `等待对手走棋…`（第二行保留上步结论）。胜率按三档变色（≥60 绿/40~60 黄/<40 红）。
  右侧 `走棋` 按钮：手动有招可点，自动模式置灰显 `自动`；横幅本体不可点，不挡误触。
  自动工作时标题旁有圆弧转圈（自绘 View＋属性动画；试过系统 ProgressBar，其
  indeterminate 在悬浮窗里不渲染，ASCII 逐帧跳又太抖，特此记录别回退）。
  授权过悬浮窗即显示，无需其他操作。
- **思考设置**：`思考时间 100~3000ms`（默认 700）；`固定深度 0~30 层`，0 表示按时间思考，
  非 0 则忽略时间按层搜（手机上 22 层以上会很慢，实测深度 26 约 19 秒）。
  两项即时生效、自动保存，重启后保持。
- **自动走棋开关**：默认关闭（ToS 封号风险）。打开后，引擎只在“算出招的 FEN == 当前棋盘”
  时才点格子，过期招直接丢弃；升变默认点皇后。开关自动保存。
  注意：走棋靠模拟点按棋盘 Rive 视图，要求棋盘可见；切后台/锁屏时不要开。
- **升变（已知弱项）**：兵到对方底线后 App 会弹选择器，皇后按钮的准确位置尚未确认，
  当前是“凭推测点一下 + 落子核对 + 最多补点 3 次”的策略。3 次不中就不再硬点，
  横幅会明示 `需要手动操作 / 升变请手动选皇后`，对局本身不受影响（不会再整局卡死）。
  若你看到选择器，请记下皇后按钮相对目标格的位置（上/下/左/右第几格），告诉我们即可精确定位。
- **走棋间隔**：`走棋间隔 150~1500ms`（默认 450），每步再 ±20% 随机浮动，落点也在格内随机偏移、
  按压 150~230ms——太快会被棋盘吞掉导致棋子弹回。走完会核对招法是否落子，
  没落上且棋盘没变就自动补点一次（日志 `autoplay performed ... attempt 2`）。
  间隔同样自动保存，并随每条招法下发到对局端。
- **连击保护**：同一招连点走棋按钮时，在途的链路没走完之前重复点击直接忽略
  （日志 `ignoring duplicate tap`），不会两套手势交错把棋子点飞。
- **横幅拖动**：纯偏移拖拽（不碰 gravity，无跳变），松手位置连同 gravity 一起记住，
  下次原位恢复；拖动阈值内松手不影响按钮点击。

## 三、故障排查对照表

| 现象 | 原因 / 做法 |
|---|---|
| `Hook 连接：未连接` | ① 模块没启用/没勾多邻国/没重启后重开多邻国；② 还没进棋局（不进对局就没有局面）；③ 多邻国升级导致类名变化（见“多邻国升级”） |
| 白方开局显示“等待对手走棋” | 旧版本 userColor 跨局残留 bug，现版本开局自动重置；若复现，把 LSPosed 日志里 `published:` 那行（带 `user=`）贴出来 |
| 进对局多邻国闪退 | 已修（画箭头必须在主线程）。若复现，抓 tombstone 看崩溃线程名是不是 `duo-chess-bridg` |
| 点启动 logcat 没日志 | MIUI 会吞 release 包的 INFO/DEBUG（E 可见），属系统行为；看 App 内状态页即可。非要看 logcat 请用 debug 包 |
| 点停止有条 E 日志 | 老版本把正常关机打成 error，现版本安静退出 |
| 箭头只第一步有效、之后一直转 | 老版本同 FEN 重报 + 去重过期 bug，现版本同 FEN 只报一次、画过不再画 |
| 引擎反复重启、30 秒退避 | 大概率 NNUE 文件损坏/截断（见下），或 `libstockfish.so` 缺失 |
| `bridge waiting for companion` 每 10 秒一条 | 正常：伴侣服务没启动，点「启动引擎服务」即可 |
| 对局中途卡死（横幅定住不动） | **已修复**：根因是动画中途碰 Rive（画箭头/点按不看棋盘忙不忙）把对局状态机 wedged。现版本画箭头前等棋盘安静 250ms（最多等 1.2 秒兜底），回退反射扫描节流到每秒一次。若还遇到：先别动对局，看 App 状态页 `循环心跳`+ LSPosed 日志 `hb:` 行贴出来 |
| 横幅显示 `局面停滞/x分无新局面` | 不是 bug，是诊断结论：对局 45 秒以上没产生新局面（对局结束/结算画面/切后台最常见）。确认棋盘是否已将死或出现完成界面；若棋还在下但无新局面，把 `content query` 的 `loopAgoSec/posAgoSec/engine` 三列贴出来 |
| 自动走棋点了没反应 | 看三行日志：`riveView pick`（选了哪个 View、多大）→ `tap <格> -> (x,y) ret=…`（`ret=false`=棋盘没收；坐标明显出界=映射偏了）→ `autoplay performed`（没这行就是新鲜度门拦了，看 `moved on`/`no board view` 行） |
| 卡住时点一下自己棋子就恢复 | 旧现象（已修）：对局端状态机被动画中途的绘制/点按 wedged，真实点按把它复位。现版本绘制与点按都等安静时刻；`hb:` 心跳（`pub/dup/ctor/render/fen/fb`，10 秒一行）保留作诊断 |
| 点的格子偏了一格 | `tap` 行坐标 vs 实际选中格：棋盘在视图里不是完全居中时按实测偏移调 `squareToScreen` |
| `Unknown authority com.chessassist.bridge` | 老版本 Provider 桥在 Android 11+ 上必死（包可见性），现版本已改走 socket，不应再出现 |

## 四、架构（v2，现状）

```
多邻国进程（LSPosed）                    伴侣进程
wq4/qh4 构造 + cl5.g() ──► StateBridge ──► FEN ──► BridgeClient ──Socket──► BridgeServer ──► EngineLoop ──► Stockfish
cl5.s() 画原生箭头 ◄── BridgeClient ◄──Socket── bestmove ◄── EngineLoop ◄── libstockfish.so（NNUE 内嵌）
```

- **传输**：`127.0.0.1:41717` JSON 行协议。Provider 桥已废弃（多邻国看不见我们的 authority），
  仅保留 `content query` 供 adb 诊断。
- **类名映射**：`module/src/main/assets/remap.json` 在**编译期烘进** `RemapTable`
  （`generateRemapTable` 任务）。运行时零 IO——assets 走 ClassLoader 读不到，
  `createPackageContext` 从多邻国调必抛 `NameNotFoundException`（包可见性），两条都已验证走不通。
- **绘制**：所有 `cl5.s()/r()` 调用都在宿主主线程（子线程碰 Rive 必 SIGSEGV，有 tombstone 为证，
  崩溃线程名即 `duo-chess-bridg`）。
- **走棋点按**：Rive 棋盘 View 靠构造直钩发现（`app.rive.runtime.kotlin.RiveAnimationView`，
  dex 实锤），按“大且方、已布局、已显示”打分多选一；`dispatchTouchEvent` 吃的是 View
  **局部坐标**（给屏幕坐标会整体下偏，返回 true 但点出界外，表现为点了没反应）；
  socket 发送与关闭拿同一把锁（错开会在 `socketWrite0` 里 native 崩溃，同样无 Java 栈）。
- **去重**：上报按 FEN 变更（同 FEN 永不重发）；画箭头按 `(fen,move)` 全局去重、不过期；
  新开局（初始 FEN）清掉上局残留的颜色/招法/绘制状态。
- **防抖与世代**：对局中钩子触发可达每秒十几次（含几秒 3 个新局面的抖动），伴侣侧新局面
  需存活 250ms 才送分析（抖掉动画/重试中间态，只慢 0.25 秒）；自动走棋每条点按链带世代号，
  局面一切换，没发出的点全作废——交叉开火是之前卡死的直接诱因。实测抖动对局约 4~5 秒一箭，
  无重复、无刷屏。
- **安静落笔**：画箭头前看棋盘安静了 250ms 没有（以钩子触发为准），动画正酣就推迟、
  最多等 1.2 秒——动画中途碰 Rive 是 wedging 头号嫌疑（手动自动都会卡、自动更多，
  因为还多点按）。ViewModel 回退反射扫描节流到每秒一次，不再给 UI 线程加压。
- **颜色**：`getSymbol()` 实测返回大写 `B/W`，FEN 里是小写，边界统一转小写再比。
- **Xposed 桩**：`tools/xapi` 的 `XposedBridge.hookMethod` 返回值必须是单个 `Unhook`
  （真机 API 如此；写成 `Set` 编译能过、真机 `NoSuchMethodError`，且会中断整个初始化）。
  桩改完要重打 `module/libs` 和 `tools/xapi` 下的两个 stub jar。
- **NNUE**：`nn-b1a57edbea57.nnue`（65,429,575 字节）+ `nn-baff1ede1f90.nnue`
  （3,480,122 字节）INCBIN 编进二进制。`tools/fetch_stockfish.sh` 带字节数校验，
  短了就重下——之前库里的 10MB 截断文件让引擎启动即崩，查了很久。

## 五、多邻国升级怎么办

1. `python tools/remap.py --apk <新包>.apk`（需 `pip install androguard==3.3.5`），重写
   `module/src/main/assets/remap.json`。
2. 重编模块（`generateRemapTable` 会自动烘进去）→ 签名 → 安装 → 杀掉多邻国重开。
3. 看 LSPosed 日志：`remap table v<新版>` + `hooked ChessGameState` 即成；
   若出现 `!! ChessGameState not found` 就是映射没对上。

## 六、已知限制

- 自动走棋默认关闭（`BridgeSettings.autoPlayEnabled`），且是 tap 模拟，仅供研究。
- Hook 触发是帧级的，正常对局只有局面变化时才有 `published:` 日志；`hb:` 心跳 10 秒一行常在；
  每对 `(fen,move)` 恰好一条 `arrow drawn`。
- 改动多邻国行为违反其 ToS，请用小号折腾。
- 构建：离线可用（`gradle-dist/gradle-8.7` + `gradle-wrapper.zip` 别删）；
  `build/`、`app/.cxx` 是纯缓存，已清，再编要从头跑 NDK（含 65MB 网嵌入，慢）。

## 七、更新记录（2026-09-05 前后，真机逐条验证）

- 回环 TCP 桥替代 Provider 桥（包可见性）、remap 编译期烘焙、Xposed 桩签名修正。
- 截断 NNUE 网换全量（65,429,575 字节），`fetch_stockfish.sh` 加字节校验。
- 中文 UI + 实时状态页 + 常驻两行横幅（手动/自动/等对手/求助四态）+ 我方视角胜率。
- 思考时间/固定深度/自动走棋开关/走棋间隔，四项持久化。
- 去重 `(fen,move)`、开局重置、颜色归一、主线程绘制、socket 收发同锁、推送异步化、
  分析看门狗、心跳与停滞标注、世代号点按、settle 滤抖、安静落笔、回退节流。
- 偶发卡死：动画中途碰 Rive 为主因，安静落笔后不再出现。

## 八、候选优化（未排期）

- 点横幅走棋（半自动确认模式）、升变选子（皇后/车/象/马）、强度预设档。
- 快捷开关磁贴（一滑即开，不用每次进 App 点启动）、横幅可拖动。
- PGN 对局存档与复盘分享；无连接时暂停分析省电。
