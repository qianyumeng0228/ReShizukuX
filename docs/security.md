# ReShizukuX 安全模型

> 本文档描述模块系统的安全设计：ZIP 校验、资源上限、哈希树篡改检测、Ed25519 签名、
> 权限三档与 HIGH 命令过滤。实现见 `modules/.../core/ModuleSecurity.kt`、
> `permission/ModulePermission.kt`、`execution/CommandFilter.kt`。

## 1. 模块生命周期状态

| 状态 | 磁盘标记 | 含义 |
|------|---------|------|
| `NOT_INSTALLED` | 目录不存在 | 未安装 |
| `DISABLED` | `disable` 文件存在 | 已安装但停用，`service.sh` 不运行 |
| `ENABLED` | 无 `disable` 文件 | 已启用，`service.sh` 可运行 |
| `UPDATING` | `update` 文件存在 | 正在更新（瞬时） |
| `ERROR` | `error.log` 存在 | 上次执行失败，用户可查看日志 |
| `CORRUPTED` | 哈希树校验失败 | 文件被篡改，拒绝执行，提示重装 |

## 2. ZIP 路径穿越双校验

安装解包时做两道校验（`ModuleSecurity.validateZipEntries` + `isCanonicalUnder`）：

1. **字符串级**：逐个 ZIP entry 检查：
   - 名称含 `../` 或 `..\` → 拒绝；
   - 以 `/` 或 `\` 开头（绝对路径）→ 拒绝；
   - 含 `:`（盘符 / 冒号）→ 拒绝。
2. **canonical 级**：解包后每个文件的 `canonicalPath` 必须以目标模块目录的 `canonicalPath` 为前缀，否则删除并拒绝。

## 3. 资源上限

| 限制 | 值 |
|------|-----|
| ZIP entry 数 | ≤ 2048 |
| 解压后总体积 | ≤ 100 MB |
| 单个 `.sh` 脚本文件 | ≤ 256 KB |
| WebUI `download()` 单文件 | ≤ 20 MB |
| 仓库模块 ZIP 下载 | ≤ 100 MB |

超出即拒绝安装 / 下载。

## 4. 原子安装

解包到 staging 临时目录 → 全部校验通过 → `renameTo` 正式模块目录；任一阶段失败则删除
staging，避免半安装状态。

## 5. SHA-256 哈希树篡改检测

- 安装时计算模块目录的哈希树并记录基线（写入 `.install_hash`）。
- 哈希树算法：遍历目录所有文件，按相对路径排序，逐行拼接
  `<相对路径> <文件SHA-256>\n`，再对整段文本取 SHA-256。
- **运行时排除**以下可变项，避免误判：`.install_hash`、`disable`、`update`、
  `error.log`，以及顶层 `logs/` 目录（脚本日志）。
- 启用 / 运行前重新计算并与基线比对；不一致 → 标记 `CORRUPTED`，拒绝执行，提示用户重装。
- 这防止模块文件被其他应用或恶意模块静默篡改。

## 6. Ed25519 签名验证（API 33+）

- 签名内容 = **ZIP 文件 SHA-256（hex 字符串）的 UTF-8 字节**；公钥 / 签名均为 base64。
- 公钥为 32 字节原始 Ed25519 公钥；客户端自动拼接 X.509 SPKI DER 前缀后用 JCE
  `KeyFactory.getInstance("Ed25519")` 验证。
- 行为：
  - 无签名 / 无公钥 → 放行，UI 标记「未验证」；
  - **API ≥ 33**：真实验签，失败回滚安装；
  - **API < 33**：Ed25519 JCE 不可用 → 降级为仅哈希树校验，不阻塞安装；
  - 格式非法 / 验签失败 → 拒绝。
- **公钥 pinning**：首次安装记录作者公钥，后续更新必须同一公钥签名。

## 7. 权限三档

新安装模块默认 **SAFE**（不授权不执行），用户必须主动提权。无 `trusted` 一键全提权旁路。

| 档位 | action | service | WebUI Bridge | WebUI 网络 | 下载 |
|------|:--:|:--:|:--:|:--:|:--:|
| **SAFE**（默认） | ❌ | ❌ | ❌ | ❌ | ❌ |
| **CUSTOM** | 逐开关 | 逐开关 | 逐开关 | 逐开关 | 逐开关 |
| **FULL** | ✅ | ✅ | ✅ | ❌（仍禁） | ✅ |

CUSTOM 档逐开关以位掩码存储（`InstalledModule.customPermissions`）：

| 位 | 值 | 含义 |
|----|----|------|
| `ACTION` | 1 | 允许运行 action.sh |
| `SERVICE` | 2 | 允许后台 service.sh |
| `WEB_BRIDGE` | 4 | 允许 WebUI 调用 Shizuku.exec |
| `WEB_NETWORK` | 8 | 允许 WebView 联网（FULL 档也不给） |
| `DOWNLOAD` | 16 | 允许 Shizuku.download |

权限存储于 `module_prefs.xml`，key 为 `permission_<module_id>`。

## 8. HIGH 风险命令过滤

`action.sh` / `service.sh` / WebUI `exec` 执行前，对脚本内容做静态扫描：先剥离注释行
（首字符为 `#`），再逐行匹配 8 条正则（忽略大小写、容忍空白变形）。命中即拦截：

| # | 规则 | 对应正则片段 | 风险 |
|---|------|-------------|------|
| 1 | `rm -rf /` | `rm\s+-…r…f…\s+/` | 删根文件系统 |
| 2 | `dd if=` | `dd\s+if=` | 裸写块设备 |
| 3 | `setenforce 0` | `setenforce\s+0` | 关闭 SELinux |
| 4 | `mkfs` | `mkfs(\.|\s)` | 格式化文件系统 |
| 5 | `/dev/block/by-name` | `/dev/block/by-name` | 直接写分区 |
| 6 | `mount -o remount,rw /system` | `mount\s+-o\s+remount,rw\s+/system` | 重挂系统分区 |
| 7 | `base64 … eval` | `base64\b.*\beval\b` | 解码并执行隐藏 payload |
| 8 | `curl … \| sh` | `curl\b.*\|.*\bsh\b` | 管道执行远程脚本 |

- 在 action / service 路径：命中 HIGH → 弹确认对话框展示命中行，用户确认后执行。
- 在 WebUI JS Bridge：命中 HIGH → **直接拒绝**并返回错误字符串（不在原生层弹确认）。

## 9. WebUI 安全约束

- WebView：`allowContentAccess=false`、`allowFileAccess=false`（仅 file 到 file URL）、
  `mixedContentMode=MIXED_NEVER`、关闭第三方 Cookie。
- origin 校验：仅 `file://` 且 canonical 路径在模块 `webRoot/` 内的页面可拿到 JS Bridge。
- WebUI **禁止联网**（即使 FULL 档）；下载统一走 `Shizuku.download()`（HTTPS only、
  ≤20MB、≤5 次重定向、不可覆盖 index.html）。

## 10. 用户侧安全建议

- 默认保持 SAFE，只对信任的模块逐步授权。
- 仅从官方仓库或可信源安装模块；优先选择带签名（`signature` + `publicKey`）的模块。
- 看到 `CORRUPTED` 状态立即卸载重装，不要尝试绕过。
