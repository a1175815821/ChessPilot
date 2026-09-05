# ChessAI · 多邻国国际象棋助手

LSPosed Hook + Stockfish 伴侣 App：在多邻国国际象棋里实时分析局面、画箭头、自动走棋。

> ⚠️ 修改多邻国行为违反其 ToS，有封号风险。请用小号折腾。

## 架构（三块）

- **module**（LSPosed 模块，作用域 `com.duolingo`）：Hook 棋盘状态构造器拿 FEN/回合/颜色；Rive 棋盘控制器画箭头、模拟点按走子；回环 TCP 上报局面、接收招法。
- **app**（伴侣 App `com.chessassist`）：跑 Stockfish 引擎（子进程，`libstockfish.so`，NNUE 烘进二进制）、回环 TCP 桥、悬浮窗（推荐/胜率/走棋按钮）、控制页。
- **桥**：`127.0.0.1:41717` JSON 行协议（`position` / `bestmove` / `play` / `clear` / `help`）。ContentProvider 仅做 adb 诊断（Android 11+ 包可见性）。

## 构建（离线可用）

```powershell
# 1. 取引擎源码+网（SF19，网名即哈希验货）
tools/fetch_stockfish.sh        # 需要 git + curl（Windows 下等价手动执行）
# 2. 构建（自带 gradle-dist/gradle-8.7，offline）
.gradle-dist\gradle-8.7\bin\gradle.bat -p . :app:assembleRelease :module:assembleRelease --offline
# 3. 签名（本地 debug.keystore）→ dist/
```

详见 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md)（构建、排障、更新记录）与
[`docs/REVERSE_ENGINEERING.md`](docs/REVERSE_ENGINEERING.md)（逆向笔记：坐标映射、
`remap.json` 再生流程）。

## 使用

1. LSPosed 启用本模块，作用域勾多邻国，重启手机。
2. 伴侣 App 点启动引擎服务（悬浮窗授权按需给）。
3. 控制页调思考时间/深度/狂暴模式；升变一律手动（求助直显悬浮窗标题）。

## 仓库卫生

`work/`、 dex/apks、`.cxx`、`build/`、`dist/`、`stockfish/` 源码网文件均不进库
（见 `.gitignore`），全部可再生：APK 解包 → `remap.py` → 引擎 `fetch` → 构建签名。

## 许可证

GPL-3.0（见 [`LICENSE`](LICENSE)）。注意：Stockfish 本体同样 GPL-3.0，
分发二进制必须附带源码获取方式——本仓库即源码（引擎取自官方 tag + 构建脚本全开源）。
