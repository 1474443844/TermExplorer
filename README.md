# TermExplorer

Android 终端模拟器 + 双栏文件管理器。内置 bash / coreutils / curl，通过 JNI PTY 提供完整交互式 shell。

## 功能

- **高保真终端**：ANSI/VT 解析、颜色（SGR / 256 / truecolor）、光标控制、alt-screen、scrollback
- **PTY 会话**：`posix_openpt` + fork/exec，SIGHUP → grace → SIGKILL 生命周期
- **内置工具链**：bash、coreutils 多路调用（ls/cp/mv/...）、curl
- **双栏文件管理器**：侧栏入口，可视化浏览/操作文件

## 构建变体（按 ABI 分包）

每个 ABI 单独出 APK，assets 与 native 库一并按 flavor 拆分，运行时无需再做 ABI 目录选择。

| Flavor  | ABI       | versionName    | 适用场景     |
|---------|-----------|----------------|--------------|
| `arm64` | arm64-v8a | `v1.0-arm64`   | 真机         |
| `x86_64`| x86_64    | `v1.0-x86_64`  | 模拟器       |

两个 flavor 共用同一个 `versionCode`（`26073101`）。

```
app/src/
├── main/                  # 公共代码 & 资源
│   └── assets/term/       # bashrc 等配置
├── arm64/assets/          # bash / coreutils / curl (aarch64)
└── x86_64/assets/         # bash / coreutils / curl (x86_64)
```

### 构建命令

```bash
# Debug
./gradlew assembleArm64Debug
./gradlew assembleX86_64Debug

# Release
./gradlew assembleArm64Release
./gradlew assembleX86_64Release

# 全部
./gradlew assemble
```

产物路径：

```
app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
app/build/outputs/apk/arm64/release/app-arm64-release.apk
app/build/outputs/apk/x86_64/release/app-x86_64-release.apk
```

## 环境要求

- Android Studio / AGP 兼容 JDK 11+
- NDK `28.2.13676358`（见 `app/build.gradle.kts`）
- `minSdk 24`，`targetSdk 28`
- 设备/模拟器 ABI 与所选 flavor 一致

## 本地运行

1. 用 Android Studio 打开本仓库
2. 在 Build Variants 面板选择 `arm64Debug`（真机）或 `x86_64Debug`（模拟器）
3. Run

或命令行：

```bash
./gradlew installArm64Debug      # 真机
./gradlew installX86_64Debug     # 模拟器
```

## 初始化逻辑

`TermConfig.init()` 在 Application 启动时执行：

1. 创建 `files/term/{bin,lib,tmp,home}` 目录
2. 从 APK assets 根目录安装 `bash`、`curl`（flavor 已保证 ABI 正确）
3. `CoreutilsManager` 安装 `coreutils` 并为各 applet 创建符号链接

不再按 `Build.SUPPORTED_ABIS` 在 assets 子目录中查找二进制。

## 项目结构（核心）

```
app/src/main/
├── cpp/                   # JNI PTY (terminal-pty.cpp)
└── java/cn/wty5/term/
    ├── MainActivity.kt            # 终端 UI
    ├── FileManagerActivity.kt     # 双栏文件管理器
    ├── terminal/
    │   ├── Pty.kt / PtySession.kt # PTY 会话生命周期
    │   ├── AnsiParser.kt          # ANSI/VT 解析
    │   ├── TermConfig.kt          # 目录 & asset 安装
    │   └── CoreutilsManager.kt    # coreutils 安装 & 链接
    ├── ui/views/TerminalView.kt   # 终端栅格渲染
    └── viewmodel/MainViewModel.kt
```

## License

Private / TBD
