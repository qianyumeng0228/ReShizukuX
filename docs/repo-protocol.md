# ReShizukuX 仓库协议（modules.json）

> 本文档描述模块仓库的 `modules.json` 格式、签名要求与更新检测机制。
> 实现见 `modules/.../repository/RepoManager.kt`、`RepoManifest.kt`、`RepoModuleInfo.kt`。

模块商店采用 **MMRL 式 `modules.json` 仓库协议**：仓库服务器托管一个 JSON 文件，客户端
`GET <repo-url>/modules.json` 拉取模块列表，下载对应的模块 ZIP 并安装。

## 1. 默认仓库

官方默认仓库（`RepoManager.DEFAULT_REPO_URL`，首次启动自动注入为 enabled）：

```
https://raw.githubusercontent.com/qianyumeng0228/reshizukux-modules/main/modules.json
```

仓库 URL 会被自动规范化：去掉末尾斜杠，若不以 `/modules.json` 结尾则自动补全。
用户可添加第三方仓库（`http(s)://` 开头）。

## 2. 仓库 JSON 格式

```
GET https://<repo-host>/<path>/modules.json
Accept: application/json
```

根对象：

```json
{
  "name": "ReShizukuX Official",
  "version": 1,
  "modules": [ ... ]
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|:--:|------|
| `name` | string | ✅ | 仓库显示名（不可为空） |
| `version` | int | ✅ | 仓库协议版本号（当前为 `1`） |
| `modules` | array | 可选 | 模块条目列表，缺省视为空数组 |

解析采用宽松模式：忽略未知字段（向前兼容）。

## 3. 模块条目字段

```json
{
  "id": "hello.module",
  "name": "示例模块：Hello",
  "version": "1.0.0",
  "versionCode": 100,
  "author": "ReShizukuX",
  "description": "最简示例模块，演示 action.sh 和 WebUI",
  "downloadUrl": "https://cdn.example.com/modules/hello-module-v1.0.0.zip",
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "signature": "base64-ed25519-signature",
  "publicKey": "base64-ed25519-32-byte-public-key",
  "minSdk": 24,
  "requiresRoot": false,
  "usesWebUI": true,
  "changelog": "首次发布",
  "lastUpdated": 1727300000
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|:--:|------|
| `id` | string | ✅ | 模块 id，须符合 `[a-zA-Z][a-zA-Z0-9._-]{1,63}` |
| `name` | string | ✅ | 模块显示名（不可为空） |
| `version` | string | ✅ | 语义化版本号（如 `1.0.0`） |
| `versionCode` | int | ✅ | 整数版本码，更新检测依据 |
| `author` | string | ✅ | 作者 |
| `description` | string | ✅ | 描述 |
| `downloadUrl` | string | ✅ | 模块 ZIP 下载地址（不可为空） |
| `lastUpdated` | long | ✅ | 条目最后更新时间（Unix epoch 秒） |
| `sha256` | string | 推荐 | ZIP 完整文件的 SHA-256（hex，大小写不敏感） |
| `signature` | string | 仓库推荐 | 对 `sha256` 的 Ed25519 签名（base64） |
| `publicKey` | string | 仓库推荐 | Ed25519 公钥（base64，32 字节原始公钥） |
| `minSdk` | int | 可选 | 最低 Android SDK |
| `requiresRoot` | bool | 可选 | 是否需要 Root |
| `usesWebUI` | bool | 可选 | 是否带 WebUI |
| `changelog` | string | 可选 | 本次更新说明 |

## 4. 刷新流程

1. `GET <repo>/modules.json`（连接超时 15s / 读取超时 30s，UA `ReShizukuX-ModuleStore/1.0`）。
2. 校验根 `name` 非空；逐条校验 `id` / `name` / `downloadUrl` 非空，否则整体拒绝。
3. 事务内：更新 `repos.lastRefresh` → 删除该仓库旧 `repo_modules` → 批量插入新条目。
4. 失败返回错误描述，不影响其他仓库与已安装模块。

刷新只更新本地缓存；浏览在线模块、下载安装均基于缓存。

## 5. 下载与校验

点击安装时，从仓库缓存下载 ZIP（文件名 `<id>-<version>.zip`，读取超时 60s，**体积上限 100MB**）：

1. 若条目提供 `sha256`：计算下载文件的 SHA-256 与之比对，不匹配则删除文件并报错。
2. 若同时提供 `signature` + `publicKey`：调用 `ModuleSecurity.verifyEd25519Signature` 验证，失败则删除并报错。
3. 之后进入正常安装流程（ZIP 路径穿越校验、解包、`customize.sh`）。

## 6. 签名要求

- 签名算法：**Ed25519（RFC 8032）**，仅在 **API 33+** 真实验证；旧版本降级为仅 SHA-256 校验、不阻塞安装。
- 被签名内容：**ZIP 文件 SHA-256 的 hex 字符串的 UTF-8 字节**（与 `sha256` 字段同值）。
- `publicKey` / `signature` 均为 base64；公钥为 32 字节原始 Ed25519 公钥。
- **公钥 pinning**：首次安装某模块时记录其公钥，后续更新必须是同一公钥签名，防止作者账号被盗后换钥投毒。
- 无 `signature` / `publicKey` 的模块标记「未验证」，UI 显示警告但不阻止安装（兼容普通 Magisk 风格模块）；**仓库分发模块强烈建议提供签名**。

生成签名示例（Python `cryptography`）：

```python
import base64, hashlib
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

priv = Ed25519PrivateKey.generate()
pub  = priv.public_key()
zip_sha = hashlib.sha256(open("hello-module-v1.0.0.zip","rb").read()).hexdigest()
sig = priv.sign(zip_sha.encode("utf-8"))
print("publicKey:", base64.b64encode(pub.public_bytes_raw()).decode())
print("signature:", base64.b64encode(sig).decode())
print("sha256   :", zip_sha)
```

## 7. 更新检测

`RepoManager.checkUpdates()` 基于本地缓存（不触发网络刷新）：

- 遍历所有**已启用仓库**的缓存模块；
- 若同名模块已安装，且仓库 `versionCode` **大于**已安装 `versionCode` → 产出一条 `UpdateInfo`；
- UI 在模块卡片上提示可更新；用户确认后下载新版 ZIP 覆盖安装。

建议流程：先手动下拉刷新各仓库，再检查更新；客户端也会周期性自动检查。

## 8. 自建仓库最小示例

把下面文件托管到任意静态 HTTPS 空间（GitHub Raw / CDN 均可）：

```json
{
  "name": "My Private Repo",
  "version": 1,
  "modules": [
    {
      "id": "hello.module",
      "name": "示例模块：Hello",
      "version": "1.0.0",
      "versionCode": 100,
      "author": "ReShizukuX",
      "description": "最简示例模块",
      "downloadUrl": "https://cdn.example.com/modules/hello-module-v1.0.0.zip",
      "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "lastUpdated": 1727300000,
      "usesWebUI": true
    }
  ]
}
```

然后在应用内「仓库」页添加该 URL（无需带 `/modules.json`，客户端自动补全）。
