# ReShizukuX 设计方案 —— Portable 完整功能版（免 Root 模块系统为核心）

> 版本：v1.0（设计稿）
> 日期：2026-09-26
> 基线：ShizukuX Portable HEAD=561d894（Android 17 适配，compileSdk/targetSdk 37，v13.7.0.k1001）
> 性质：纯方案设计，不含代码实现
> 前置调研：`analysis-noroot-module-frameworks.md`（Shevery/AxManagerD/LSPatch/MMRL 四方对比）、四份逐行深读报告

---

## 1. 定位与范围

### 1.1 ReShizukuX 是什么

**ReShizukuX = ShizukuX Portable + 免 Root 模块系统**。

Portable 已经解决了"轻量、强保活、单开关自动化"的核心体验问题（五 Tab、三层保活、11 步启动链路、SCENE 全自动激活、终端 Tab）。ReShizukuX 在 Portable 基础上，以**免 Root 模块系统**为核心增量，让用户可以安装 ZIP 格式的模块包，通过 Shizuku 特权（shell uid=2000 或 root uid=0）执行脚本、提供 WebUI 管理界面、从仓库发现和更新模块。

> **设计取向变更（2026-09）**：本方案从"省电优先"调整为**"功能优先"**——后台保活、常驻服务等不再为压低电量而牺牲模块后台脚本的存活可靠性。代价是接受一个额外的前台服务通知，换取模块 service.sh 在主进程被冻结时仍能被拉起。安全决策（见下）不受此取向变更影响。

"完整功能版"的"完整"指的是**模块生态完整**，而非把 Portable 删掉的所有功能都加回来。

### 1.2 被删功能的回归决策

| 功能 | Portable 状态 | ReShizukuX 决策 | 理由 |
|------|-------------|----------------|------|
| Sentry / Lottie / LeakCanary / AboutLibraries / biometric / glance / markwon | 已删（Phase 2） | **继续剔除** | 纯体积/崩溃分析/动画库，与模块系统无关；markwon 可由模块 WebUI 自带渲染 |
| backup（备份） | 已删（Phase 3） | **不回归为内置**，作为模块示例提供 | 备份需求可由模块实现（`BackupRestoreModule`），保持核心轻量 |
| update（OTA 更新） | 已删（Phase 3） | **选择性回归**：仅保留版本检查通知，不内置下载器 | ReShizukuX 作为持续演进项目需要更新提示；下载安装走系统 PackageInstaller |
| scripting（脚本/终端） | 已删（Phase 3） | **不回归** | 终端 Tab 已覆盖命令执行需求；脚本可由模块承载 |
| onboarding（首次引导） | 已删（Phase 3） | **不回归** | 配对向导（5 步 Compose）已覆盖首次激活引导 |
| AutomationService / AICoreExtraService / ShizukuLiveService | 已删（Phase 4） | **继续剔除** | 与模块系统后台脚本职责重叠；自动化由模块系统的后台脚本 + RuntimeModuleService 托管（更通用） |
| RemoteDbSyncWorker | 已删（Phase 4） | **继续剔除** | 无远程同步需求 |
| 16 个 server Plus AIDL | 已删（Phase 5） | **继续剔除** | 攻击面最小化；模块通过 Shizuku `newProcess` 执行 shell，不需要 Plus AIDL |
| 三星专属（11 类） | 已删（Phase 6） | **继续剔除** | 便携/通用定位；三星用户可通过模块实现 OneUI 特定功能 |
| 主题个性化 | 已删（Phase 6） | **继续剔除** | 方案 7.4 明确不引入主题系统；模块 WebUI 自带样式 |

**结论**：ReShizukuX 不回归任何已删的重型依赖/服务/AIDL。"完整"通过模块生态实现，而非内置功能膨胀。唯一选择性回归是 OTA 版本检查（仅通知，不下载）。

### 1.3 与功能优先定位的关系

模块系统引入了后台脚本执行能力。**设计取向已从"省电优先"调整为"功能优先"**：后台脚本的存活可靠性优先于前台服务数量最小化。设计原则：

1. **用户显式启用才常驻**：模块 `service.sh` 需用户在模块详情页手动启用（默认 DISABLED），不自动跑
2. **独立前台服务托管**：所有后台脚本由独立的 `RuntimeModuleService`（manager 主进程，`START_STICKY`）集中轮询保活，**不附加**在 `ShizukuDaemonService` 的 server 循环里；后台服务不做数量限制
3. **资源熔断而非数量上限**：单模块异常重启仍走指数退避 + 熔断（60s 内 ≥5 次重启标记 ERROR），防止单个坏模块烧 CPU；不对后台模块总数设上限
4. **SAFE 默认全禁**：新安装模块默认 SAFE 模式（参考 Shevery `ModuleSettings.kt:37-63`），不授权不执行

> **安全决策不因功能优先而改变**（重要）：
> - webroot WebView **禁网**（`canWebNetwork()` 恒为 false，FULL 档也禁）——防恶意模块借 WebView 外泄数据；
> - SAFE/CUSTOM/FULL **三档权限模型**不变；
> - HIGH 命令过滤（CommandFilter）不变；
> - Ed25519 签名校验 + 哈希树篡改检测不变。
>
> 这些是安全边界，不是省电妥协，功能优先只放开"后台服务保活方式"，不放开"模块能做什么"。

---

## 2. 包名与共存策略

### 2.1 推荐方案：同包名升级

| 项目 | 值 |
|------|-----|
| applicationId | `moe.shizuku.privileged.api`（与 Portable 相同） |
| versionName | `14.0.0.k1`（大版本跳 14，标识模块系统里程碑） |
| versionCode | 1100（从 Portable vc1001 递增，预留空间） |
| authority | 复用现有 `moe.shizuku.privileged.api.shizuku`（Shizuku provider） |
| 模块数据目录 | `filesDir/modules/`（app 私有，不与 Shizuku server 共享） |

**理由**：
- ReShizukuX 是 Portable 的超集，用户应无缝升级（保留设置、授权、保活配置）
- 同包名确保 Shizuku 授权白名单、Dhizuku 设备所有者绑定、系统自启白名单不失效
- 模块系统不需要新的 ContentProvider authority——模块数据存储在 app 私有目录，执行通过 Shizuku binder `newProcess`，无需跨进程 provider

### 2.2 与 Shevery 的共存

Shevery 包名也是 `moe.shizuku.privileged.api`（它是 Shizuku 的 fork）。因此 ReShizukuX 与 Shevery **不能共存**——这是预期行为，两者都是 Shizuku 服务端管理器，同一时刻只能有一个管理 Shizuku server。

用户从 Shevery 迁移到 ReShizukuX：卸载 Shevery → 安装 ReShizukuX → Shizuku server 需重新激活（同包名但签名不同，Shizuku server 绑定的是 manager 签名）。

### 2.3 模块 SDK 包名

为第三方模块开发者提供的 API 库（可选，远期）：
- Maven artifact：`io.github.reshizukux:module-api:1.0.0`
- 包名：`io.reshizukux.module.api`（不与 manager 包名冲突）

---

## 3. 模块系统核心设计（重中之重）

### 3.1 模块包规范

**采用 Magisk 式规范 + Shevery webroot 扩展**，与 AxManagerD/MMRL 模块格式最大兼容。

```
module.zip
├── module.prop              # 必需：元数据
├── customize.sh             # 可选：安装时执行（Magisk 兼容）
├── service.sh               # 可选：后台脚本（启用后运行）
├── action.sh                # 可选：手动动作脚本（用户点击"运行"）
├── uninstall.sh             # 可选：卸载时执行（清理钩子）
├── system.prop              # 可选：系统属性（resetprop 注入，root 模式才生效）
├── webroot/                 # 可选：WebUI 目录
│   ├── index.html           #   WebUI 入口
│   └── ...                  #   静态资源
├── module.json              # 可选：ReShizukuX 扩展元数据（权限声明、哈希等）
└── META-INF/                # 忽略（与 Magisk 一致，functions.sh 排除）
```

#### module.prop 字段（Magisk 兼容 + 扩展）

| 字段 | 必需 | 说明 | 兼容来源 |
|------|------|------|---------|
| `id` | ✅ | 模块唯一 ID，正则 `[a-zA-Z][a-zA-Z0-9._-]{1,63}` | Shevery `AdbModuleManager.kt:74-80` + Magisk |
| `name` | ✅ | 显示名 | Magisk |
| `version` | ✅ | 版本号（语义化） | Magisk |
| `versionCode` | ✅ | 整数版本码 | Magisk |
| `author` | ✅ | 作者 | Magisk |
| `description` | ✅ | 描述 | Magisk |
| `updateJson` | 可选 | 更新检查 JSON URL | Magisk |
| `minSdk` | 可选 | 最低 Android SDK | ReShizukuX 扩展 |
| `requiresRoot` | 可选 | `true`/`false`，是否需要 root 模式 | ReShizukuX 扩展 |
| `usesWebUI` | 可选 | `true`/`false`，是否有 WebUI | Shevery 扩展 |
| `usesShellBridge` | 可选 | `true`/`false`，WebUI 是否需要 JS Bridge | Shevery `ModuleJsBridge.kt` |

**设计决策**：不采用 AxManagerD 的 `axeronPlugin` 版本门控字段（`functions.sh:162`），改用 `minSdk` 更通用。不采用 Shevery 的 `banner` 图（增加 ZIP 体积，UI 用文字+图标即可）。

### 3.2 模块生命周期状态机

```
                    ┌──────────┐
                    │  NOT_INSTALLED │
                    └─────┬────┘
                          │ install (ZIP 校验+解包+customize.sh)
                          ▼
                    ┌──────────┐
              ┌────►│ DISABLED │◄────┐
              │     └─────┬────┘     │
              │           │ enable   │ disable
              │           ▼          │
              │     ┌──────────┐     │
              │     │ ENABLED  │     │
              │     └─────┬────┘     │
              │           │          │
              │   ┌───────┴───────┐  │
              │   ▼               ▼  │
              │ ┌──────┐     ┌────────┐ │
              │ │ACTION│     │SERVICE │ │
              │ │(手动) │     │(后台)  │ │
              │ └──────┘     └────────┘ │
              │                         │
              └─────── update ──────────┘
                          │
                    uninstall (uninstall.sh + 删除)
                          ▼
                    ┌──────────┐
                    │  REMOVED  │ (瞬时，回到 NOT_INSTALLED)
                    └──────────┘
```

**状态定义**（参考 MMRL `BaseModuleManager.kt:79-94` 的 remove/disable/update 三标记 + Shevery 的 enable 逻辑）：

| 状态 | 磁盘标记 | 含义 |
|------|---------|------|
| NOT_INSTALLED | 目录不存在 | 未安装 |
| DISABLED | `disable` 文件存在 | 已安装但停用，service.sh 不运行 |
| ENABLED | 无 disable 文件 | 已启用，service.sh 可运行 |
| UPDATING | `update` 文件存在 | 正在更新（瞬时） |
| ERROR | `error.log` 存在 | 上次执行失败，用户可查看日志 |

**与 Shevery/AxManagerD 的关键差异**：
- Shevery 无 `uninstall.sh`（`AdbModuleManager.kt:134-136` 直接 deleteRecursively）→ ReShizukuX **必须有**，模块改了系统设置需要回滚
- AxManagerD 的启用/停用需要"重新 ignite"才生效（`AxeronPluginService.kt:461-493` 写 update_enable 标记）→ ReShizukuX **即时生效**（启用即启动 service.sh，停用即 kill），不需要重启
- MMRL 的状态变更需要重启（root 守护进程开机扫描）→ ReShizukuX 是用户态管理，即时生效

### 3.3 执行引擎

#### 3.3.1 进程模型

**复用 Shizuku `newProcess`**，与 Portable 终端 Tab 和 WifiDebugReassert 同一执行通道：

```kotlin
// 参考 Portable 现有用法：utils/WifiDebugReassert.kt:92-103
// 参考 Shevery：module/AdbModuleManager.kt:198-218
Shizuku.newProcess(
    arrayOf("sh", "-c", scriptContent),
    env,           // 模块环境变量（MODULE_DIR, MODULE_ID, ANDROID_SDK 等）
    "/data/local/tmp"  // 工作目录
)
```

**特权级别**：继承 Shizuku server 的 uid——ADB 模式 = shell uid=2000，Root 模式 = uid=0。模块 `requiresRoot=true` 时在 ADB 模式下提示用户"此模块需要 Root"。

#### 3.3.2 action.sh（手动动作）

- 用户在模块详情页点击"运行"→ 同步执行 `action.sh`
- 超时：**60s**（Shevery 是 120s，`AdbModuleManager.kt:214-218`；ReShizukuX 缩短到 60s，手动动作不应长时间阻塞 UI）
- 输出：实时流式显示在 Compose 对话框（参考 Portable 终端 Tab 的输出区实现），同时写入 `modules/<id>/logs/action-last.log`
- 超时返回码 124，`destroy()` 强杀

#### 3.3.3 service.sh（后台脚本）—— 保活设计

这是模块系统最复杂的部分，**学 AxManagerD runtime 模块的独立前台 watchdog 设计**（`RuntimeModuleService.kt:595-663`），在 ReShizukuX 中以独立前台服务 `RuntimeModuleService` 托管。

**架构（功能优先，2026-09 修订）**：独立前台服务运行在 manager **主进程**（持有 Shizuku binder），自带 5s 轮询协程，不再附加在 `ShizukuDaemonService` 的 server 保活循环里：

```
RuntimeModuleService (manager 主进程，独立前台服务，START_STICKY)
  ├── onCreate：建通知渠道 module_runtime(LOW) + startForeground(specialUse)
  ├── 协程循环 (Dispatchers.IO + SupervisorJob)，每 5s：
  │     ├── ModuleWatchdog.checkAndRevive(context)：
  │     │     ├── 扫描 ENABLED 模块列表（ROOM DB，带 service.sh + canService）
  │     │     ├── 对每个后台模块：pgrep -f '<module_id>_service' 检测存活
  │     │     ├── 死亡 → 指数退避重启 [1s,2s,4s,8s,15s,30s,60s]（RuntimeModuleService.kt:84-85）
  │     │     └── 60s 内 ≥5 次重启 → 熔断，标记 ERROR，停止重启
  │     └── 刷新常驻通知上的后台模块计数
  ├── onStartCommand → START_STICKY（被系统杀死后由系统重建）
  └── onDestroy：取消协程

ShizukuDaemonService (:daemon 进程) —— 纯 server 双进程互守，不再管模块
```

**为什么是独立前台服务（功能优先）而不是附加在 daemon**：
- 主进程持有 Shizuku binder，`ServiceRunner` 默认走 `Shizuku.newProcess` 即可，无需像 :daemon 那样注入 libsu root shell（:daemon 自己就是拉起 server 的一方，无 binder）
- `START_STICKY` 语义：主进程被系统杀死后，系统会重建本服务继续保活；不必等 15min Alarm 兜底
- 主进程常驻一个 LOW 通知是可接受的代价（前台服务强制要求通知），换来模块 service.sh 不被 OEM 冻结掐死
- 后台模块数量不设上限；无后台模块时 `ModuleWatchdog.checkAndRevive` 快速返回空转，服务保持运行不做自停

> 历史：P3 曾把模块 watchdog「附加」在 `ShizukuDaemonService` 5s 循环末尾（省电优先，不新增前台服务）。功能优先变更后拆出独立前台服务，**指数退避 + 熔断逻辑原样保留**，仅更换调用方与进程宿主。

**service.sh 启动方式**：
```sh
# 模块脚本包装：设置进程名便于 pgrep 检测
exec -a "${MODULE_ID}_service" sh service.sh
```
参考 AxManagerD `Igniter.kt:169-189` 的 setsid 后台方式，但 ReShizukuX 用 `Shizuku.newProcess` 替代 setsid（Shizuku 执行的进程天然脱离 app 生命周期）。

#### 3.3.4 customize.sh（安装脚本）

- 安装时同步执行，超时 **120s**（安装可能涉及下载/解压，比 action 长）
- 工作目录 = 模块解包目录
- 失败 → 回滚安装（删除模块目录），显示错误日志
- 参考 AxManagerD `functions.sh:197,215`（source customize.sh）和 Shevery 的无 customize.sh 设计——ReShizukuX 选择**有 customize.sh**（Magisk 兼容，模块需要安装时初始化）

#### 3.3.5 uninstall.sh（卸载钩子）

- 卸载前同步执行，超时 **60s**
- 用于清理模块安装时改的系统设置/装的应用
- 失败不阻塞卸载（记录日志后继续删除目录）
- 这是对 Shevery 的关键改进（Shevery 无卸载钩子，`AdbModuleManager.kt:134-136`）

### 3.4 权限模型

**采用 Shevery 三档 + AxManagerD 命令过滤的混合模型**：

#### 3.4.1 三档权限（参考 Shevery `ModuleSettings.kt:37-63,112-174`）

| 档位 | action | service | WebUI Bridge | WebUI 网络 | 下载 |
|------|--------|---------|-------------|-----------|------|
| **SAFE**（默认） | ❌ | ❌ | ❌ | ❌ | ❌ |
| **CUSTOM** | 逐开关 | 逐开关 | 逐开关 | 逐开关 | 逐开关 |
| **FULL** | ✅ | ✅ | ✅ | ❌（仍禁） | ✅ |

- 新安装模块默认 **SAFE**（用户必须主动授权才能运行）
- 不设 Shevery 的 `trusted` 一键全提权旁路（`ModuleSettings.kt:120-122` 是安全隐患，ReShizukuX 去掉）
- FULL 档也禁止 WebUI 联网（与 Shevery 一致，`ModuleSettings.kt` 中 webNetwork 在 FULL 也拒绝），防止恶意模块通过 WebView 窃取数据

#### 3.4.2 命令黑名单/白名单（参考 AxManagerD `RuleEngine.kt:18-131`）

在 action.sh / service.sh 执行前，对脚本内容做静态扫描：

**HIGH 风险（默认拦截，需用户手动确认每次执行）**：
```
rm -rf /          dd if=          setenforce 0
mkfs              /dev/block/     mount -o remount,rw /system
fork 炸弹         base64 | eval   curl | sh
```
（参考 AxManagerD `RuleEngine.kt:18-70` 的 HIGH 列表，ReShizukuX 精简为 8 条核心规则）

**实现方式**：不做 shell AST 解析（太重），做正则匹配 + 行级注释剥离。命中 HIGH → 弹确认对话框展示命中行，用户确认后执行。这比 Shevery 的 ReCommand（每次 exec 都确认，`ModuleJsBridge.kt:144-155`）更智能——只对危险命令确认，普通命令直接执行。

#### 3.4.3 模块级权限存储

- SharedPreferences：`module_prefs.xml`，key = `permission_<module_id>`，value = 档位枚举 + CUSTOM 逐开关位图
- 参考 Shevery `ModuleSettings.kt` 的实现，但去掉 trusted 旁路

### 3.5 安全设计

这是 ReShizukuX 的**差异化卖点**——四家框架（Shevery/AxManagerD/LSPatch/MMRL）都没有模块包签名/哈希校验。

#### 3.5.1 ZIP 路径穿越校验（学 Shevery）

双重校验（参考 Shevery `AdbModuleManager.kt:360-375`）：
1. 字符串级：ZIP entry 名含 `../` → 拒绝
2. canonical 级：解包后 `file.canonicalPath` 必须以目标目录前缀开头 → 否则删除并拒绝

#### 3.5.2 资源上限（学 Shevery）

| 限制 | 值 | 来源 |
|------|-----|------|
| ZIP entry 数 | ≤ 2048 | Shevery `AdbModuleManager.kt` |
| 解压后总体积 | ≤ 100MB | Shevery 是 200MB，ReShizukuX 收紧 |
| 单个脚本文件 | ≤ 256KB | Shevery `AdbModuleManager.kt:214-218` |
| 单模块日志 | ≤ 1MB（环形覆盖） | ReShizukuX 新增 |

#### 3.5.3 模块 id 白名单（学 Shevery，补 AxManagerD 的漏洞）

- 正则：`[a-zA-Z][a-zA-Z0-9._-]{1,63}`（Shevery `AdbModuleManager.kt:74-80`）
- AxManagerD 无此校验（`functions.sh:159,188` 直接取 id 作目录名，可写 `../../` 路径穿越）→ ReShizukuX 必须有

#### 3.5.4 模块哈希校验（差异化，四家都缺）

**两级校验**：

**Level 1：ZIP 完整性（强制）**
- 安装时计算 ZIP 的 SHA-256，存入模块数据库
- 每次启用/运行前重新计算当前模块目录的文件哈希树（Merkle tree），与安装时记录比对
- 不一致 → 标记 `CORRUPTED` 状态，拒绝执行，提示用户重装
- 防止模块文件被其他应用/恶意模块篡改

**Level 2：作者签名（可选，推荐仓库模块强制）**
- 模块 ZIP 内可附带 `module.sig`（作者 Ed25519 签名 + 公钥）
- 签名内容 = SHA-256(ZIP)
- 首次安装时记录作者公钥（pinning），后续更新必须同一公钥签名
- 无签名的模块标记"未验证"，UI 显示警告但不阻止安装（兼容现有 Magisk/Shevery 模块）
- 仓库（store）分发的模块**强制要求签名**

**为什么不用 GPG**：Ed25519 签名验证轻量（BouncyCastle 已在 Android 系统可用），密钥短（32 字节），适合移动端。GPG 太重。

#### 3.5.5 安装时原子性

- 参考 Shevery 的 staging 目录 + 原子 rename（`AdbModuleManager.kt:64-123`）
- 解包到 `modules/.staging/<id>/` → 校验全部通过 → `renameTo(modules/<id>/)` → 失败则删除 staging
- 避免半安装状态

### 3.6 WebUI

**学 Shevery 的完整实现**（四家唯一可用的 WebUI，AxManagerD 仅预留 `Service.kt:411`，MMRL 仅 KSU WebUI）：

#### 3.6.1 实现方式

- 无本地 HTTP server（Shevery 的设计，`ModuleWebViewActivity.kt:83` 直接 `file://` 加载）
- WebView 加载 `modules/<id>/webroot/index.html`
- 注入 `Shizuku` JS 对象（`addJavascriptInterface`），暴露：
  - `Shizuku.exec(command)` → 同步 shell，返回 `{ok, exitCode, stdout, stderr, timedOut}`
  - `Shizuku.execWithOptions(command, {timeout, stdin, cwd, env})`
  - `Shizuku.download(url, relativePath)` → HTTPS 下载到 webRoot（≤20MB，≤5 次重定向）
  - `Shizuku.getModuleInfo()` → 模块信息 + 权限快照

#### 3.6.2 安全约束（学 Shevery `ModuleJsBridge.kt:21-47`）

- WebView 设置：`allowContentAccess=false`、`allowFileAccess=false`（仅 allowFileFromFileUrls）、`mixedContentMode=MIXED_NEVER`、第三方 Cookie 关闭
- JS Bridge 挂桥条件：模块 enabled + canExposeWebBridge + (`usesShellBridge=true` 或 CUSTOM/FULL 授权)
- origin 校验：仅 `file://` + canonical 路径在 webRoot 内
- 命令确认：exec 命中 HIGH 黑名单 → 弹 ReCommand 确认对话框（参考 Shevery `ModuleJsBridge.kt:144-155`）

#### 3.6.3 与 Portable UI 的整合

- 模块详情页有"打开 WebUI"按钮 → 启动 `ModuleWebViewActivity`（全屏 WebView + 返回键）
- 不做 Compose WebView 嵌入（WebView 与 Compose 互操作有坑，独立 Activity 更稳定）

### 3.7 商店（模块仓库）

**学 MMRL 的 modules.json 仓库协议**（`IRepoManager.kt:10`），不学 Shevery 的 GitHub topic 搜索（生态小、依赖 GitHub API 限流）。

#### 3.7.1 仓库格式

```
GET https://<repo-url>/modules.json
```

```json
{
  "name": "ReShizukuX Official",
  "version": 1,
  "modules": [
    {
      "id": "example.module",
      "name": "示例模块",
      "version": "1.0.0",
      "versionCode": 100,
      "author": "example",
      "description": "这是一个示例模块",
      "downloadUrl": "https://cdn.example.com/modules/example-v1.0.0.zip",
      "sha256": "abc123...",
      "signature": "base64-ed25519-sig",
      "publicKey": "base64-ed25519-pubkey",
      "minSdk": 24,
      "requiresRoot": false,
      "usesWebUI": true,
      "changelog": "首次发布",
      "lastUpdated": 1727300000
    }
  ]
}
```

#### 3.7.2 仓库管理

- 默认仓库：ReShizukuX 官方仓库（URL 硬编码 + 可热更新）
- 用户可添加第三方仓库（URL + 可选公钥固定）
- 仓库列表存储：Room 数据库 `repos` 表
- 刷新：手动下拉 + 每周自动检查更新
- 模块更新检测：对比本地 versionCode 与仓库 versionCode，通知栏提示

#### 3.7.3 与 Shevery 商店的对比

| 维度 | Shevery GitHub topic | ReShizukuX modules.json |
|------|---------------------|------------------------|
| 发现方式 | GitHub API 搜索 topic `shevery-modules` | 仓库服务器提供 JSON |
| 官方认证 | 作者硬编码 `HmnDev-Tech` | 签名验证 + 公钥 pinning |
| 每作者上限 | 非官方 4 模块 | 无上限（签名防伪造） |
| 限流 | GitHub API 限流（需 PAT） | 无（CDN 托管 JSON） |
| 生态 | 小（仅 Shevery 模块） | 可兼容 Magisk 模块格式 |

---

## 4. 架构与代码结构

### 4.1 Gradle 模块划分

在 Portable 现有模块（`:manager` / `:api` / `:server` / `:shared` / `:database`）基础上，**新增一个 `:modules` 模块**：

```
ShizukuX/
├── manager/          # 现有：UI + 业务逻辑（五 Tab + 保活 + 配对）
├── api/              # 现有：Shizuku API + AIDL
├── server/           # 现有：Shizuku server（精简后）
├── shared/           # 现有：共享工具
├── database/         # 现有：Room 数据库（授权等）
└── modules/          # ★ 新增：模块系统核心
    ├── build.gradle.kts
    └── src/main/java/io/reshizukux/modules/
        ├── core/
        │   ├── ModuleManager.kt          # 单例：安装/卸载/启用/停用
        │   ├── ModuleSpec.kt             # module.prop 解析 + 校验
        │   ├── ModuleExecutor.kt         # Shizuku newProcess 封装
        │   ├── ModuleState.kt            # 状态机定义
        │   └── ModuleSecurity.kt         # ZIP 校验 + 哈希 + 签名
        ├── execution/
        │   ├── ActionRunner.kt           # action.sh 执行（同步 60s）
        │   ├── ServiceRunner.kt          # service.sh 启动/停止
        │   ├── CustomizeRunner.kt        # customize.sh 执行（安装时）
        │   ├── UninstallRunner.kt        # uninstall.sh 执行
        │   └── CommandFilter.kt          # HIGH 风险命令正则过滤
        ├── watchdog/
        │   └── ModuleWatchdog.kt         # 模块存活轮询 + 退避/熔断（由 manager 主进程 RuntimeModuleService 周期调用）
        ├── permission/
        │   ├── ModulePermission.kt       # 三档枚举 + 逐开关
        │   └── PermissionController.kt   # 权限判定 + 存储
        ├── webui/
        │   ├── ModuleJsBridge.kt         # WebView JS Bridge
        │   └── ModuleWebViewActivity.kt  # WebUI 宿主 Activity
        ├── repository/
        │   ├── ModuleRepo.kt             # 仓库模型
        │   ├── RepoManager.kt            # 仓库添加/删除/刷新
        │   └── RepoUpdater.kt            # 模块更新检查
        ├── db/
        │   ├── ModuleDatabase.kt         # Room 数据库
        │   ├── ModuleEntity.kt           # 已安装模块元数据
        │   ├── RepoEntity.kt             # 仓库元数据
        │   └── ModuleDao.kt / RepoDao.kt
        └── api/                          # 远期：第三方模块 SDK
            └── ModuleApi.kt              # 模块可调用的 API 定义
```

**依赖关系**：
- `:modules` 依赖 `:api`（Shizuku `newProcess`）、`:database`（Room）、`:shared`（工具）
- `:manager` 依赖 `:modules`（UI 调用模块管理）
- `:server` **不依赖** `:modules`（模块执行通过 Shizuku binder，server 无需感知）

### 4.2 核心类设计（10 类，取 Shevery 7 类与 AxManagerD 多模块的平衡）

| 类 | 职责 | 参考来源 |
|----|------|---------|
| `ModuleManager` | 安装/卸载/启用/停用/更新的门面，原子操作 | Shevery `AdbModuleManager.kt`（479 行单例） |
| `ModuleSpec` | module.prop 解析、id 白名单校验、字段映射 | AxManagerD `functions.sh:28-34` grep_prop + MMRL `BaseModuleManager.kt:58-68` |
| `ModuleExecutor` | Shizuku newProcess 封装，环境变量注入，超时控制 | Shevery `AdbModuleManager.kt:198-218` + Portable `WifiDebugReassert.kt:92-103` |
| `ModuleWatchdog` | 模块存活轮询 + 指数退避重启 + 熔断（由 manager 主进程 `RuntimeModuleService` 前台服务周期调用，本身不自起循环） | AxManagerD `RuntimeModuleService.kt:595-663` |
| `PermissionController` | SAFE/CUSTOM/FULL 判定 + 存储 + 命令过滤 | Shevery `ModuleSettings.kt:112-174` + AxManagerD `RuleEngine.kt:104-131` |
| `ModuleSecurity` | ZIP 路径穿越双校验 + 资源上限 + SHA-256 哈希树 + Ed25519 签名 | Shevery `AdbModuleManager.kt:360-375` + 自研（四家都缺） |
| `ModuleJsBridge` | WebView JS Bridge（exec/download/getModuleInfo）+ origin 校验 | Shevery `ModuleJsBridge.kt:49-125` |
| `RepoManager` | modules.json 仓库协议 + 刷新 + 更新检测 | MMRL `IRepoManager.kt:10` + `InstallViewModel.kt` |
| `ModuleDatabase` | Room：已安装模块 + 仓库 + 哈希记录 | Portable 现有 database 模块模式 |
| `CommandFilter` | HIGH 风险命令正则 + 行级注释剥离 | AxManagerD `RuleEngine.kt:18-70`（精简版） |

### 4.3 与现有五 Tab UI 的整合

**新增第六个 Tab「模块」**，导航顺序：

```
状态 → 授权 → 终端 → 模块 → 自动化 → 设置
```

**底部导航处理**：
- 手机（<600dp）：Material 3 `NavigationBar` 最多 5 项体验最佳。6 项时采用**可滚动底部导航**（`NavigationBarItem` 6 个，文字始终显示，横向可滑），或把「自动化」移入「模块」Tab 内作为子页面（自动化规则本质是内置模块）
- **推荐方案**：手机端 5 Tab（状态/授权/终端/模块/设置），自动化规则在「模块」Tab 内以"内置模块"形式展示；平板（≥600dp）用 `NavigationRail` 侧边导航展示全部 6 项

**模块 Tab UI 结构**（Compose）：
```
ModulesScreen
├── 顶部：搜索框 + 仓库切换（已安装/仓库）
├── Tab 行：已安装 | 在线仓库
├── 已安装列表：
│   └── ModuleCard（图标+名称+版本+作者+状态开关+权限档位+更新标记）
├── 仓库列表：
│   └── RepoModuleCard（名称+描述+下载量+安装按钮+签名状态）
└── 模块详情页（点击卡片进入）：
    ├── 基本信息（名称/版本/作者/描述/权限）
    ├── 操作区（启用开关/运行 action/打开 WebUI/卸载/检查更新）
    ├── 权限设置（SAFE/CUSTOM/FULL + 逐开关）
    ├── 安全信息（SHA-256/签名状态/文件列表）
    └── 日志查看（action-last.log / service.log）
```

### 4.4 数据库设计

```kotlin
// Room Database（复用 Portable :database 模块的模式）
@Entity(tableName = "installed_modules")
data class InstalledModule(
    @PrimaryKey val id: String,           // 模块 id
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val state: String,                     // ENABLED/DISABLED/ERROR/CORRUPTED
    val permissionLevel: String,           // SAFE/CUSTOM/FULL
    val customPermissions: Int,            // 位掩码（action/service/bridge/network/download）
    val sha256: String,                    // 安装时 ZIP 哈希
    val publicKey: String?,                // 作者公钥（pinning）
    val installTime: Long,
    val lastUpdateTime: Long,
    val lastActionExitCode: Int?,
    val servicePid: Int?                   // 当前 service.sh 进程 pid
)

@Entity(tableName = "repos")
data class Repo(
    @PrimaryKey val url: String,
    val name: String,
    val publicKey: String?,                // 仓库公钥固定
    val enabled: Boolean,
    val lastRefresh: Long
)

@Entity(tableName = "repo_modules")      // 仓库缓存
data class RepoModule(
    @PrimaryKey val repoUrl_id: String,   // 复合主键
    val repoUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val downloadUrl: String,
    val sha256: String,
    val signature: String?,
    // ... 其他字段
)
```

---

## 5. 实施路线

### 5.1 阶段划分

| 阶段 | 内容 | 交付物 | 验证方式 | 人日 |
|------|------|--------|---------|------|
| **P1：模块核心** | ModuleSpec/ModuleManager/ModuleSecurity/ModuleDatabase/CommandFilter；安装/卸载/启用/停用；ZIP 校验+哈希；权限三档 | 可安装/卸载 Magisk 式 ZIP 模块，状态机正确 | 单元测试 + 手动安装测试 ZIP | 4 |
| **P2：执行引擎** | ModuleExecutor/ActionRunner/CustomizeRunner/UninstallRunner；action.sh 60s 同步执行+流式输出；命令过滤 | 模块 action.sh 可执行，输出实时显示，HIGH 命令弹确认 | 真机执行 `echo`/`pm list packages`/危险命令测试 | 3 |
| **P3：后台保活** | ServiceRunner/ModuleWatchdog + **独立 RuntimeModuleService 前台服务**（manager 主进程，START_STICKY，通知渠道 module_runtime）；指数退避重启+熔断；service.sh 启停；enable() 钩子拉起 + 启动时补拉 | 模块 service.sh 后台运行，崩溃自动重启，熔断生效 | 真机 kill 服务进程验证重启，验证熔断 | 3 |
| **P4：WebUI** | ModuleJsBridge/ModuleWebViewActivity；JS exec/download；origin 校验+命令确认 | 带 webroot 的模块可显示 WebUI，JS Bridge 可执行命令 | 真机测试示例模块 WebUI | 2 |
| **P5：商店** | RepoManager/RepoUpdater；modules.json 协议；仓库管理；更新检测+通知 | 可添加仓库、浏览在线模块、下载安装、更新提示 | 搭建测试仓库验证全流程 | 3 |
| **P6：UI 整合** | 模块 Tab + 模块卡片 + 详情页 + 权限设置 + 日志查看；导航调整 | 六 Tab（或五 Tab+子页）完整可用 | 真机 UI 走查 + 截图 | 3 |
| **P7：签名校验** | Ed25519 签名验证；作者公钥 pinning；CORRUPTED 检测；仓库强制签名 | 有签名模块验证通过，篡改模块拒绝执行 | 生成测试密钥签名+篡改测试 | 2 |
| **P8：测试与发布** | 集成测试；示例模块（备份/系统设置清理）；文档；release 构建 | 示例模块仓库上线；release APK；模块开发文档 | 全流程 E2E 测试 | 3 |

**总计：23 人日**（约 4-5 周单人，或 2-3 周双人并行）

### 5.2 依赖关系

```
P1 → P2 → P3 → P6
       ↘ P4 ↗
P1 → P5 → P6
P3 → P7（签名校验依赖模块安装流程）
P6 → P8
```

P4（WebUI）和 P5（商店）可并行于 P3 之后。

### 5.3 风险清单与缓解

| 风险 | 影响 | 概率 | 缓解 |
|------|------|------|------|
| Shizuku `newProcess` 在 ADB 模式下进程生命周期受限（ADB 断开后进程被杀） | service.sh 后台脚本在 ADB 模式下不稳定 | 高 | ADB 模式下 service.sh 标记为"ADB 会话期间运行"，提示用户；Root 模式下完整保活；参考 Portable 已有保活架构（daemon 双进程互守可缓解） |
| 模块恶意脚本执行高危命令 | 安全 | 中 | 三档权限默认 SAFE + HIGH 命令过滤 + 哈希校验 + 仓库签名；用户教育（仅从可信仓库安装） |
| RuntimeModuleService 常驻前台服务带来额外耗电/通知 | 耗电 | 低 | 功能优先取向接受此代价；通知 LOW  importance 不打扰；无后台模块时轮询空转快速返回；退避熔断防止坏模块烧 CPU |
| Magisk 模块不兼容（Magisk 模块依赖 root 特定路径/挂载） | 模块兼容性 | 中 | 文档明确"仅兼容纯 shell 脚本模块，不兼容需要 overlayfs 挂载的 Magisk 模块"；安装时检测 `system/` 目录提示不兼容 |
| Ed25519 签名验证性能 | 安装速度 | 低 | 签名验证仅在安装/更新时一次，SHA-256 哈希树在启用时计算（模块文件通常 <10MB，<1s） |
| WebView JS Bridge 安全漏洞 | 恶意模块通过 WebUI 越权 | 中 | origin 校验 + 命令过滤 + FULL 也禁 WebUI 联网（学 Shevery）；定期安全审计 |
| 与 Portable 现有代码冲突（ShizukuDaemonService 改造） | 保活回归 | 中 | 功能优先后 **不再改造 ShizukuDaemonService**：模块保活完全搬到独立 RuntimeModuleService，daemon 恢复为纯 server 双进程互守，原有 server 保活逻辑零改动 |

---

## 6. GitHub 仓库规划

### 6.1 仓库设置

| 项目 | 值 |
|------|-----|
| 仓库名 | `ReShizukuX` |
| 可见性 | **Private**（用户要求；开发完成后可考虑 public） |
| 描述 | "ShizukuX Portable 完整功能版 —— 免 Root 模块系统为核心的 Android 特权工具" |
| 主分支 | `main` |
| 开发分支 | `develop`（feature 分支从 develop 切出，PR 合回 develop，release 从 develop 合 main） |
| License | Apache-2.0（与 ShizukuX/Shevery 一致，便于模块开发者参考） |
| .gitignore | 标准 Android + `signing.properties` + `keystore/` + `local.properties` |

### 6.2 初始提交结构

```
ReShizukuX/
├── README.md                    # 项目介绍 + 模块系统说明 + 快速开始
├── LICENSE                      # Apache-2.0
├── docs/
│   ├── module-spec.md           # 模块包规范（module.prop 字段、脚本钩子、webroot）
│   ├── module-api.md            # JS Bridge API 文档
│   ├── repo-protocol.md         # modules.json 仓库协议
│   └── security.md              # 安全模型（权限三档、哈希校验、签名）
├── samples/
│   ├── hello-module/            # 最简示例模块（echo + WebUI）
│   ├── backup-module/           # 备份示例模块
│   └── settings-cleaner/        # 系统设置清理示例
└── [ShizukuX 源码，从 Portable fork]
```

### 6.3 版本路线

| 版本 | 内容 |
|------|------|
| v14.0.0-alpha1 | P1+P2：模块核心 + 执行引擎（可安装可运行） |
| v14.0.0-alpha2 | +P3：后台保活 |
| v14.0.0-beta1 | +P4+P5：WebUI + 商店 |
| v14.0.0-beta2 | +P6+P7：UI 整合 + 签名校验 |
| v14.0.0-release | +P8：测试 + 示例模块 + 文档 |

---

## 7. 设计决策汇总表

| 决策点 | 选择 | 替代方案 | 理由 | 依据 |
|--------|------|---------|------|------|
| 模块规范 | Magisk 式 + webroot 扩展 | Shevery 自创式 | 与 AxManagerD/MMRL 最大兼容，模块可复用 | `analysis-noroot-module-frameworks.md` §13.4 |
| 执行引擎 | Shizuku newProcess | 自研 server / libsu | 复用 Portable 已有通道，零新增 native 依赖 | Portable `WifiDebugReassert.kt:92-103` |
| 后台保活 | 独立 RuntimeModuleService 前台服务（主进程，START_STICKY） | 附加到 ShizukuDaemonService 循环 | 功能优先：模块保活可靠性优先于前台服务数量；主进程有 binder，免 libsu 注入；daemon 恢复纯 server 互守 | 2026-09 设计取向变更 |
| 重启策略 | 指数退避 [1,2,4,8,15,30,60]s + 熔断 | 固定间隔 / 不重启 | AxManagerD 验证有效的方案 | `RuntimeModuleService.kt:84-85` |
| 权限模型 | SAFE/CUSTOM/FULL + 命令过滤 | 全权限+过滤 / 逐命令授权 | 默认安全（Shevery）+ 智能确认（AxManagerD） | `ModuleSettings.kt:37-63` + `RuleEngine.kt:18-70` |
| trusted 旁路 | **去掉** | 保留（Shevery 有） | 安全隐患，一键全提权 | `ModuleSettings.kt:120-122` 分析结论 |
| ZIP 安全 | 双校验+资源上限+id白名单 | 单校验 / 无校验 | Shevery 已验证，AxManagerD 无校验是漏洞 | `AdbModuleManager.kt:360-375` vs `functions.sh:201` |
| 哈希/签名 | SHA-256 哈希树 + Ed25519 签名 | 不做 / GPG | 四家都缺，差异化卖点；Ed25519 轻量 | 四家深读报告均确认无签名 |
| WebUI | WebView file:// + JS Bridge | 本地 HTTP server | Shevery 已验证，无端口占用风险 | `ModuleWebViewActivity.kt:83` |
| 商店 | modules.json 仓库协议 | GitHub topic 搜索 | MMRL 协议最成熟，无 GitHub 限流 | `IRepoManager.kt:10` vs `ModuleDiscoveryManager.kt:91-92` |
| 卸载钩子 | uninstall.sh 必需执行 | 不做（Shevery 式） | 模块改系统设置需回滚 | Shevery 无钩子是已知缺陷 |
| 包名 | 同 Portable（升级） | 新包名共存 | 保留 Shizuku 授权/自启白名单 | 同包名是 Shizuku manager 标准做法 |
| 自动化 Tab | 合并入模块 Tab（手机端） | 保留第六 Tab | 底部导航 5 项最佳，自动化本质是内置模块 | Material 3 NavigationBar 规范 |
| LSPatch/Xposed | **不做** | 集成（AxManagerD 有） | 工程重，与便携定位冲突 | `analysis-noroot-module-frameworks.md` §13.7 |
| AI 追踪回滚 | **不做** | 集成（AxManagerD 有） | 依赖云端，复杂度高 | `analysis-noroot-module-frameworks.md` §13.7 |

---

## 附录 A：与四家框架的设计来源映射

| ReShizukuX 设计 | 来源 | 改进点 |
|-----------------|------|--------|
| module.prop + customize.sh + service.sh + uninstall.sh | Magisk/AxManagerD/MMRL | 加 minSdk/requiresRoot/usesWebUI 扩展字段 |
| ZIP 路径穿越双校验 + 资源上限 | Shevery `AdbModuleManager.kt:360-375` | 收紧解压上限 200MB→100MB |
| id 正则白名单 | Shevery `AdbModuleManager.kt:74-80` | 补 AxManagerD 的漏洞 |
| SAFE/CUSTOM/FULL 三档 | Shevery `ModuleSettings.kt:37-63` | 去掉 trusted 旁路 |
| HIGH 命令过滤 | AxManagerD `RuleEngine.kt:18-70` | 精简为 8 条核心规则 + 仅危险命令确认 |
| watchdog 指数退避 + 熔断 | AxManagerD `RuntimeModuleService.kt:84-85,595-663` | 退避/熔断逻辑不变，调用方改为独立 RuntimeModuleService 前台服务 |
| WebView + JS Bridge + origin 校验 | Shevery `ModuleJsBridge.kt:49-125` | 命令确认改为仅 HIGH 风险 |
| modules.json 仓库协议 | MMRL `IRepoManager.kt:10` | 加签名字段 + 公钥 pinning |
| SHA-256 哈希树 + Ed25519 签名 | **自研**（四家都缺） | 差异化安全卖点 |
| uninstall.sh 卸载钩子 | Magisk/MMRL/AxManagerD | 补 Shevery 的缺失 |

## 附录 B：Portable 现有可复用组件

| Portable 组件 | 模块系统用途 |
|--------------|------------|
| `Shizuku.newProcess`（终端 Tab/WifiDebugReassert 已验证） | 模块脚本执行引擎 |
| `ShizukuDaemonService`（:daemon 进程，5s 轮询） | **仅 server 双进程互守**（功能优先后不再承担模块 watchdog） |
| `RuntimeModuleService`（manager 主进程，独立前台服务） | 模块 service.sh watchdog 宿主（START_STICKY，5s 轮询） |
| `WatchdogAlarmReceiver`（15min Alarm） | 模块后台服务兜底拉起 |
| `ShizukuStateMachine`（服务状态 Flow） | 模块 Tab 显示 Shizuku 运行状态 |
| `:database` 模块（Room） | 模块元数据存储 |
| Compose 五 Tab 骨架（PortableMainActivity） | 新增模块 Tab |
| `AdbPortProbe` / `AdbNetworkObserver` | 模块网络相关功能（远期） |
| R8 minify + 签名配置 | release 构建直接复用 |
