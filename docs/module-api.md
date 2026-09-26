# ReShizukuX WebUI JS Bridge API

> 本文档描述模块 WebUI 页面可调用的全局 JS 对象 `Shizuku`。
> 实现见 `modules/.../webui/ModuleJsBridge.kt`。

模块的 `webroot/index.html` 运行在本地 `file://` WebView 中。ReShizukuX 通过
`addJavascriptInterface` 注入一个名为 **`Shizuku`** 的全局对象，页面可以用它在
Shizuku 特权 shell 里执行命令、下载文件、查询模块信息。

## 0. 前置条件

JS Bridge 仅在以下条件**全部满足**时才会暴露方法（每次调用都会复查）：

1. 模块处于 `ENABLED` 状态（非停用 / 非 CORRUPTED）；
2. 用户已授予 Web Bridge 权限（`PermissionController.canWebBridge`，即 CUSTOM 勾选或 FULL）；
3. 当前页面是 `file://` 且 canonical 路径落在模块 `webRoot/` 目录内（origin 校验）。

任一不满足，调用直接返回权限错误，不会执行任何命令。

## 1. 方法一览

| 方法 | 作用 |
|------|------|
| `Shizuku.exec(command)` | 同步执行 shell 命令，默认 30s 超时 |
| `Shizuku.execWithOptions(command, optionsJson)` | 带超时 / stdin / cwd / env 的执行 |
| `Shizuku.download(url, relativePath)` | 经 app 走 HTTPS 下载文件到 webRoot |
| `Shizuku.getModuleInfo()` | 返回模块信息 + 当前权限快照 |

所有方法都运行在 JS 桥后台线程，返回值均为 **JSON 字符串**（需 `JSON.parse`）。

## 2. Shizuku.exec(command)

```js
var raw = Shizuku.exec("id");
var r = JSON.parse(raw);
console.log(r.stdout);
```

- `command`：要执行的 shell 命令字符串（内部以 `sh -c command` 运行）。
- 默认超时 **30 秒**；超时返回 `timedOut=true`、`exitCode=124`。

返回 JSON：

```json
{
  "ok": true,
  "exitCode": 0,
  "stdout": "uid=2000(shell) gid=2000(shell) ...",
  "stderr": "",
  "timedOut": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `ok` | boolean | 仅当进程正常结束且 `exitCode == 0` 时为 `true` |
| `exitCode` | number | 退出码；超时为 `124`；权限/启动错误为 `-1` |
| `stdout` | string | 标准输出 |
| `stderr` | string | 标准错误 |
| `timedOut` | boolean | 是否因超时被强杀 |

权限不足 / origin 不匹配时返回：

```json
{ "ok": false, "exitCode": -1, "stdout": "", "stderr": "Permission denied: ...", "timedOut": false }
```

## 3. Shizuku.execWithOptions(command, optionsJson)

```js
var raw = Shizuku.execWithOptions("wc -l my.txt", JSON.stringify({
  timeoutSec: 10,
  stdin: "line1\nline2\n",
  cwd: "data",
  env: { "FOO": "bar" }
}));
```

`optionsJson` 字段：

| 字段 | 类型 | 默认 | 约束 |
|------|------|------|------|
| `timeoutSec` | number | 30 | 取值范围 **1..120** |
| `stdin` | string | 无 | 写入远端进程 stdin，**≤ 64 KB** |
| `cwd` | string | 模块目录 | 相对模块目录的子目录；禁止 `../` / 绝对路径；必须是已存在的目录 |
| `env` | object | `{}` | 额外环境变量，**≤ 32 个**；key 匹配 `[A-Za-z_][A-Za-z0-9_]*`；value ≤ 4096 字节 |

执行时注入的固定环境变量：`MODULE_DIR`、`MODULE_ID`、`WEBUI=1`（叠加在 `env` 之上）。

返回 JSON 与 `exec()` 相同。

## 4. Shizuku.download(url, relativePath)

经 app 进程的 `HttpsURLConnection` 下载文件到 webRoot 子目录（**不是 WebView 联网**，受下载权限独立控制）。

```js
var raw = Shizuku.download(
  "https://cdn.example.com/res/icon.png",
  "icons/icon.png"
);
var r = JSON.parse(raw);
```

约束：

- 仅允许 **HTTPS**（`http://` 一律拒绝）；
- 文件体积 **≤ 20 MB**；
- **≤ 5 次**重定向；连接 / 读取超时各 15 秒；
- `relativePath` 相对 webRoot，禁止 `../`，且**不能覆盖 `index.html`**；
- 需要 `canDownload` 权限（CUSTOM 勾选下载 / FULL）。

成功返回：

```json
{ "ok": true, "path": "icons/icon.png", "bytes": 12345 }
```

失败返回：

```json
{ "ok": false, "path": "icons/icon.png", "error": "Only HTTPS URLs are allowed." }
```

## 5. Shizuku.getModuleInfo()

返回模块信息与当前权限快照。origin 不合法时返回 `"{}"`。

```js
var info = JSON.parse(Shizuku.getModuleInfo());
```

返回 JSON：

```json
{
  "ok": true,
  "id": "hello.module",
  "name": "示例模块：Hello",
  "version": "1.0.0",
  "versionCode": 100,
  "author": "ReShizukuX",
  "description": "最简示例模块",
  "state": "ENABLED",
  "moduleDir": "/data/user/0/.../files/modules/hello.module",
  "webRoot": "/data/user/0/.../files/modules/hello.module/webroot",
  "permissions": {
    "action": true,
    "service": false,
    "webBridge": true,
    "webNetwork": false,
    "download": false,
    "level": "CUSTOM",
    "customFlags": 5
  }
}
```

`state` 取值见 [security.md](security.md) 的状态机；`level` 为 `SAFE` / `CUSTOM` / `FULL`。

## 6. 安全约束

- **origin 校验**：仅 `file://` 且 canonical 路径在 `webRoot/` 内的页面可调用桥方法；其他 `file://` 页面盗用桥对象会被拒绝。
- **模块必须启用**：停用（DISABLED）或被标记 CORRUPTED 的模块，桥方法不可用。
- **命令过滤**：`exec` / `execWithOptions` 执行前过 `CommandFilter`，命中 8 条 HIGH 规则之一会被**直接拒绝**（返回错误字符串，不在原生层弹确认框；JS 层应在调用前自行提示用户）。规则清单见 [security.md](security.md)。
- **WebUI 禁止联网**：WebView 配置 `mixedContentMode=MIXED_NEVER`、`allowContentAccess=false`、`allowFileAccess=false`（仅放行 file 到 file URL）、关闭第三方 Cookie。即使 FULL 档位也不允许 WebView 直接联网；需要网络资源请用 `Shizuku.download()`。
- **下载独立授权**：`download()` 由 `canDownload` 单独控制，与 bridge 权限解耦。

## 7. 最小示例

```html
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>demo</title></head>
<body>
  <button onclick="run()">执行 id</button>
  <pre id="out"></pre>
  <script>
    function run() {
      var r = JSON.parse(Shizuku.exec("id"));
      document.getElementById('out').textContent =
        r.ok ? r.stdout : ("错误: " + r.stderr);
    }
  </script>
</body>
</html>
```
