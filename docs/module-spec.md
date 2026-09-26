# ReShizukuX 模块包规范

> 本文档描述 ReShizukuX 免 Root 模块的 ZIP 包结构、`module.prop` 字段、脚本钩子与环境变量。
> 解析实现见 `modules/.../core/ModuleSpec.kt`，执行实现见 `modules/.../execution/`。

模块包采用 **Magisk 式规范 + Shevery webroot 扩展**，与 AxManagerD / MMRL 模块格式最大兼容：
一个 ZIP 文件即可被安装、启用、运行脚本并可选提供 WebUI。

## 1. ZIP 包结构

```
module.zip
├── module.prop              # 必需：元数据（必须位于 ZIP 根目录）
├── customize.sh             # 可选：安装时执行（超时 120s）
├── action.sh                # 可选：手动动作脚本（用户点「运行」，超时 60s）
├── service.sh               # 可选：后台常驻脚本（启用后由 watchdog 拉起）
├── uninstall.sh             # 可选：卸载前执行（超时 60s，失败不阻塞卸载）
├── webroot/                 # 可选：WebUI 目录
│   ├── index.html           #   WebUI 入口
│   └── ...                  #   其他静态资源
└── META-INF/                # 忽略（与 Magisk 一致）
```

### ZIP 打包要求（重要）

- **`module.prop` 必须位于 ZIP 根目录**，不要嵌套在同名顶层目录里。
  - 正确：`module.prop`、`action.sh`、`webroot/index.html`
  - 错误：`hello-module/module.prop`、`hello-module/action.sh`
- ZIP 内路径使用 **正斜杠 `/`**（不要用反斜杠）。
- 脚本文件使用 **UTF-8 无 BOM、LF 行尾**（Android 的 `sh` 不识别 CRLF / BOM）。
- 不兼容需要 `system/` overlayfs 挂载的 Magisk 模块（本系统只跑纯 shell 脚本）。

打包示例（Python `zipfile`，arcname 取相对模块根的路径）：

```python
import zipfile, os
base = "hello-module"
with zipfile.ZipFile("hello-module-v1.0.0.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for dp, _, fns in os.walk(base):
        for fn in fns:
            full = os.path.join(dp, fn)
            arc = os.path.relpath(full, base).replace("\\", "/")
            z.write(full, arc)
```

## 2. module.prop 字段

`module.prop` 为 `key=value` 文本（按 `=` 切分一次，支持 `#` 注释）。

| 字段 | 必需 | 类型 | 默认值 | 说明 |
|------|------|------|--------|------|
| `id` | ✅ | 字符串 | — | 模块唯一 ID，命名规则见 §3 |
| `name` | ✅ | 字符串 | — | 显示名 |
| `version` | ✅ | 字符串 | — | 版本号（语义化，如 `1.0.0`） |
| `versionCode` | ✅ | 整数 | — | 整数版本码，仓库更新比较用 |
| `author` | ✅ | 字符串 | — | 作者 |
| `description` | ✅ | 字符串 | — | 描述 |
| `minSdk` | 可选 | 整数 | `0` | 最低 Android SDK（`0` 表示不限制） |
| `requiresRoot` | 可选 | `true`/`false` | `false` | 是否需要 Root 模式；ADB 模式下安装会提示 |
| `usesWebUI` | 可选 | `true`/`false` | `false` | 是否提供 `webroot/index.html` |
| `usesShellBridge` | 可选 | `true`/`false` | `false` | WebUI 是否需要 JS Bridge（`Shizuku.exec`） |

> 说明：设计稿中的 `updateJson` 字段当前版本**未被 `ModuleSpec` 解析**；在线更新检测由仓库协议 `modules.json` 承担（见 [repo-protocol.md](repo-protocol.md)）。

示例：

```properties
id=hello.module
name=示例模块：Hello
version=1.0.0
versionCode=100
author=ReShizukuX
description=最简示例模块，演示 action.sh 和 WebUI
minSdk=24
requiresRoot=false
usesWebUI=true
usesShellBridge=true
```

## 3. 模块 id 命名规则

正则（`ModuleSpec.ID_REGEX`）：

```
[a-zA-Z][a-zA-Z0-9._-]{1,63}
```

- 必须以字母开头；
- 后续可为字母、数字、点 `.`、下划线 `_`、短横线 `-`；
- 总长 2~64 字符。

合法：`hello.module`、`settings.cleaner`、`backup.daemon`、`my-Module_1`
非法：`1abc`（数字开头）、`ab`（太短）、`a/b`、`a..b`、空串。

## 4. 脚本钩子

所有脚本都以 `sh -c <脚本内容>` 执行，工作目录默认为 `/data/local/tmp`。
特权身份继承 Shizuku server：ADB 模式 = shell uid=2000，Root 模式 = uid=0。

| 钩子 | 触发时机 | 超时 | 失败行为 |
|------|---------|------|---------|
| `customize.sh` | 安装时同步执行 | **120s** | 失败回滚安装（删除模块目录） |
| `action.sh` | 用户点「运行」同步执行 | **60s** | 输出实时显示，写入 `logs/action-last.log` |
| `service.sh` | 用户启用「后台运行」后由 watchdog 拉起 | 常驻（无超时） | 崩溃指数退避重启，60s 内 ≥5 次后熔断标记 ERROR |
| `uninstall.sh` | 卸载前同步执行 | **60s** | 失败不阻塞卸载（记录日志后继续删除） |

超时后进程被 `destroy()` 强杀，退出码为 `124`。

### 4.1 customize.sh（安装）

工作目录为模块解包目录。用于初始化、备份当前系统状态（见 `settings-cleaner` 示例）。

### 4.2 action.sh（手动）

适合短时操作（< 60s）。长任务请放到 `service.sh`。

### 4.3 service.sh（后台常驻）

- 默认不自动运行，需用户在模块详情页手动启用「后台运行」。
- 由 `ShizukuDaemonService` 内的 watchdog 统一管理，不新增前台服务。
- 启动方式：`exec -a "<module_id>_service" sh service.sh`（设置进程名便于存活检测）。
- 请在脚本里自行实现循环 + `sleep`；退出后 watchdog 会按 `[1,2,4,8,15,30,60]s` 指数退避重启。
- ADB 模式下 Shizuku 会话断开后进程会被杀，提示用户这是「ADB 会话期间运行」；Root 模式下保活完整。

### 4.4 uninstall.sh（卸载回滚）

用于撤销安装时对系统做的修改（恢复设置、删除装的应用等）。

## 5. 环境变量

执行脚本时注入的环境变量：

| 变量 | customize | action | service | uninstall | WebUI exec |
|------|:--:|:--:|:--:|:--:|:--:|
| `MODULE_DIR` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `MODULE_ID` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `ANDROID_SDK` | ✅ | ✅ | ✅ | ✅ | — |
| `MODULE_VERSION` | ✅ | ✅ | ✅ | — | — |
| `WEBUI` | — | — | — | — | ✅（值为 `1`） |

- `MODULE_DIR`：模块解包目录的绝对路径。
- `MODULE_ID`：模块 id。
- `ANDROID_SDK`：当前设备 `Build.VERSION.SDK_INT`（十进制）。
- `MODULE_VERSION`：`module.prop` 里的 `version`。
- `WEBUI=1`：仅在通过 WebUI JS Bridge 触发 `exec` 时设置，脚本可据此区分调用来源。

脚本内读取示例：

```sh
echo "MODULE_DIR=$MODULE_DIR"
echo "ANDROID_SDK=$ANDROID_SDK"
```

## 6. webroot 规范

- 存在 `webroot/index.html` 且 `usesWebUI=true` 的模块，详情页出现「打开 WebUI」按钮。
- WebView 以 `file://` 加载 `webroot/index.html`，无本地 HTTP server。
- 仅 `webroot/` 内的页面可获得 JS Bridge（origin 校验见 [module-api.md](module-api.md)）。
- WebUI 页面**禁止联网**（即使 FULL 档位也禁）；如需下载资源，使用 `Shizuku.download()`。

## 7. 权限三档

模块默认 **SAFE**，用户必须主动授权才能执行。详见 [security.md](security.md)。

| 档位 | action | service | WebUI Bridge | WebUI 网络 | 下载 |
|------|:--:|:--:|:--:|:--:|:--:|
| SAFE（默认） | ❌ | ❌ | ❌ | ❌ | ❌ |
| CUSTOM | 逐开关 | 逐开关 | 逐开关 | 逐开关 | 逐开关 |
| FULL | ✅ | ✅ | ✅ | ❌（仍禁） | ✅ |

## 8. 示例模块

| 模块 | 路径 | 演示点 |
|------|------|--------|
| Hello | `samples/hello-module/` | 最简 action.sh + WebUI JS Bridge |
| 系统设置清理 | `samples/settings-cleaner/` | customize.sh 备份 + uninstall.sh 回滚 |
| 定时备份守护 | `samples/backup-module/` | service.sh 后台常驻 |
