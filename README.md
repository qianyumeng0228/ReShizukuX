# ShizukuX Portable

> 精简省电版 Shizuku Manager —— 剔除主题、机型专属与低频重功能，保留最小 Shizuku 闭环。

基于 [ShizukuX](https://github.com/qianyumeng0228/ShizukuX) 源码深度精简重设计，面向「省电、低内存、强保活」的无 Root 日常使用场景。

## 🧩 ReShizukuX 模块系统

ReShizukuX = **ShizukuX Portable + 免 Root 模块系统**。在 Portable 的省电闭环之上，新增了一套
Magisk 式 ZIP 模块体系：你可以安装第三方模块包，通过 Shizuku 特权（shell uid=2000 或 root uid=0）
执行脚本、提供 WebUI 管理界面，并从内置仓库发现和更新模块。**无需 Root**（ADB 激活 Shizuku 即可）。

- **什么是模块**：一个 ZIP 包，根目录放 `module.prop` 元数据，可选 `customize.sh`（安装）、
  `action.sh`（手动运行）、`service.sh`（后台常驻）、`uninstall.sh`（卸载回滚）和 `webroot/`（WebUI）。
- **如何安装模块**：① 从仓库浏览并一键安装；② 把模块 ZIP 拷到手机，在「模块」Tab 选择本地 ZIP 安装。
  新模块默认 **SAFE**（全禁），需在详情页主动授权 action / service / WebUI Bridge。
- **安全**：ZIP 路径穿越双校验 + 资源上限 + SHA-256 哈希树篡改检测 + Ed25519 签名（API 33+）+
  HIGH 风险命令过滤。

### 示例模块

| 模块 | 打包产物 | 演示点 |
|------|---------|--------|
| Hello | [`samples/hello-module-v1.0.0.zip`](samples/hello-module-v1.0.0.zip) | 最简 `action.sh` + WebUI JS Bridge（源码 [`samples/hello-module/`](samples/hello-module/)） |
| 系统设置清理 | [`samples/settings-cleaner-v1.0.0.zip`](samples/settings-cleaner-v1.0.0.zip) | `customize.sh` 备份 + `uninstall.sh` 回滚 |
| 定时备份守护 | [`samples/backup-module-v1.0.0.zip`](samples/backup-module-v1.0.0.zip) | `service.sh` 后台常驻（shell 权限即可运行） |

> 这些 ZIP 可直接通过「本地安装」导入测试。模块包要求 `module.prop` 位于 ZIP 根目录（上述打包产物已满足）。

### 文档索引

- [模块包规范 `docs/module-spec.md`](docs/module-spec.md) —— `module.prop` 字段、脚本钩子、环境变量、webroot、打包要求
- [JS Bridge API `docs/module-api.md`](docs/module-api.md) —— `Shizuku.exec` / `execWithOptions` / `download` / `getModuleInfo`
- [仓库协议 `docs/repo-protocol.md`](docs/repo-protocol.md) —— `modules.json` 格式、签名、更新检测
- [安全模型 `docs/security.md`](docs/security.md) —— 权限三档、哈希树、Ed25519、HIGH 命令过滤

---

## ✨ 特性

- **单开关全自动**：一个开关触发完整启动链路（检测激活方式 → 配对检查 → 锁屏门禁 → 启动 → Binder 就绪 → 记录方式 → 开机自启 → 双进程守护 → Alarm 兜底），无需分步操作
- **三层保活**：开机自启（BootCompleteReceiver，DE 存储直启）→ daemon 双进程互守（`:daemon` 独立进程 5s 轮询 + 60s/5 次熔断）→ 15min Alarm 兜底
- **无 WiFi 复活**：ADB 模式配对一次后，依靠固定端口 5555 回环探测自动复活（重启免 WiFi、免无线调试）
- **省电低内存**：前台服务从原版 4 个减至 1 个（仅 Watchdog）；删除周期网络同步、前台 App 轮询、无障碍事件监听
- **四 Tab Compose UI**：状态 / 授权 / 自动化 / 设置，全部 Compose 化
- **逐步配对向导**：5 步配对流程（前置检查 → 获取配对信息 → 输入配对码 → 配对执行 → 完成启动），支持通知栏 RemoteInput 输入（MIUI 推荐，避免切应用中断配对会话）
- **通用性**：剔除三星专属功能（FOTA UID 1000 提权、DeX、OneUI 主题/动画、Auto Blocker、Sleeping apps 等），任何机型可用
- **包名**：`moe.shizuku.privileged.api`（与原版 Shizuku 一致，可作为 drop-in 替代）

## 🗑️ 精简清单

| 类别 | 剔除内容 |
|------|---------|
| 依赖 | Sentry、Lottie、LeakCanary、AboutLibraries、biometric、glance、markwon |
| 常驻服务 | AutomationService、AICoreExtraService、ShizukuLiveService、RemoteDbSyncWorker（前台服务 4→1） |
| UI 功能包 | backup（备份）、update（更新检查）、scripting（脚本）、onboarding（引导页） |
| server Plus AIDL | 16 个 Plus 接口 + 17 个实现类（AVF/存储代理/Overlay/Continuity/WM/网络治理/AI 等） |
| 机型专属 | 三星 FOTA 提权、DeX、OneUI 8 动画、主题个性化、S-Pen hover、单手模式 |
| 模块 | :compat（Compat Hub）、:app-process（rosan app_process） |

保留核心：Shizuku server（Root / 无线 ADB / Dhizuku 三模式激活）、IShizuku 授权体系、WatchdogService、AdbProxyService、AdbPairingService、ShizukuTileService、AutomationEngine。

## 📦 安装

1. **卸载冲突应用**：安装前请卸载 Shevery 或其他声明 `moe.shizuku.privileged.api.shizuku` provider authority 的应用，否则报 `INSTALL_FAILED_CONFLICTING_PROVIDER`
2. 安装 `manager-release.apk`（Release 构建产物，见下）
3. 首次使用：开启「开发者选项 → 无线调试」→ 点应用开关 → 按 5 步配对向导完成配对（MIUI 请在通知栏输入 6 位配对码，避免切换应用中断配对会话）
4. 配对一次后：重启设备自动复活（5555 回环），无需 WiFi / 无线调试常开

## 🔧 构建方法

### 环境要求

- JDK 21（Android Studio 自带 `jbr` 或系统安装 JDK 21；项目在 `gradle.properties` 中通过 `org.gradle.java.home` 指定）
- Android SDK（在 `local.properties` 配置 `sdk.dir`）
- Gradle 9.x（项目自带 `gradlew.bat`）

### 签名配置（Release 必做）

创建 `manager/signing.properties`（已被 .gitignore 排除，不会提交）：

```properties
KEYSTORE_FILE=I:/path/to/your.keystore
KEYSTORE_PASSWORD=your_store_password
KEYSTORE_ALIAS=your_alias
KEYSTORE_ALIAS_PASSWORD=your_key_password
```

未配置时自动回退到 Android debug keystore（`signing.gradle` 内处理）。

### 构建命令

```bat
:: Debug 构建（含调试信息，包名 moe.shizuku.privileged.api.debug）
gradlew.bat :manager:assembleDebug

:: Release 构建（R8 混淆 + 资源压缩）
gradlew.bat :manager:assembleRelease
```

产物路径：

- Debug：`manager/build/outputs/apk/debug/manager-debug.apk`（约 28.5 MB）
- Release：`manager/build/outputs/apk/release/manager-release.apk`（约 11.3 MB，R8 minify）

### 版本

- versionName：`ShizukuX 13.6.0.r15`
- versionCode：`15`（git 仓库自 `67eac0c` 基线起为 1，经 12 个阶段 commit 递增）

## 📁 技术要点

| 组件 | 说明 |
|------|------|
| `PortableMainActivity` | 单 Activity，Compose 四 Tab 导航（LAUNCHER） |
| `PortableStartOrchestrator` | 11 步 ON 链路 / 5 步 OFF 链路 |
| `AdbPairingWizard` | 5 步配对向导（Compose）+ 通知栏 RemoteInput |
| `ShizukuDaemonService` | `:daemon` 独立进程，双进程互守 |
| `WatchdogService` | 前台保活，Binder 死亡监听 + 15min Alarm |
| `AdbPortProbe` | 回环 5555 端口探测（250ms，候选 [sysPort, lastPort, 5555]） |
| `AdbNetworkObserver` | WiFi 恢复 5s 防抖重触发 |
| `WifiDebugReassert` | 敌意 ROM `adb_wifi_enabled` flag 重断言（opt-in） |

## 📄 License

[Apache License 2.0](LICENSE)

上游：ShizukuX（qianyumeng0228）、Shizuku（RikkaApps）、Stellar（roro2239）、Shevery（HmnDev-Tech）
