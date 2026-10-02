# ReShizukuX

> 基于 Shizuku 的增强重制版 —— 在保留原版 Shizuku 核心能力的基础上，集成 Xposed 模块引擎与 Shell 脚本模块系统，一个 App 搞定免 Root 特权与模块生态。

## 这是什么？

ReShizukuX 是基于 [ShizukuX](https://github.com/qianyumeng0228/ShizukuX) 的增强重制版，包名与原版 Shizuku 一致（`moe.shizuku.privileged.api`），可直接替代。在保留 Shizuku server 三模式激活（Root / 无线 ADB / Dhizuku）和完整授权体系的同时，新增了两大模块系统：

1. **Xposed 模块引擎**：内置 LSPatch 兼容的 APK patch 能力，免 Root 给任意 App 加载 Xposed 模块，支持 Manager 模式动态管理作用域
2. **Shell 脚本模块**：Magisk 式 ZIP 模块，通过 Shizuku 特权执行脚本，支持后台常驻、安装/卸载钩子、WebUI

## 功能总览

| 模块 | 功能 |
|------|------|
| **状态** | 服务运行状态、激活模式（Root/ADB/Dhizuku）、Root/ADB/DO 三项权限检测、单开关一键启动、守护模式显示 |
| **授权** | 已声明 Shizuku 权限的应用列表，一键 grant/revoke，搜索 + 全部/已授权/未授权筛选 |
| **终端** | 通过 Shizuku shell 执行命令，历史命令记录（持久化）、常用命令快捷栏、停止按钮 |
| **模块** | Xposed 模块扫描 + APK patch + 作用域管理；Shell 脚本模块安装；在线仓库浏览；GitHub topic 搜索 |
| **设置** | 开机自启、守护模式、无线调试守护、语言切换、主题、版本信息 |

## 核心特性

### 三模式激活
- **Root**：KernelSU / Magisk / APatch 授权后自动启动 server
- **无线 ADB**：5 步配对向导，配对一次后重启自动复活（5555 回环探测）
- **Dhizuku / 设备所有者**：无需启动 server，直接通过 DO 权限工作

### 三层保活
- 开机自启（BootCompleteReceiver）
- daemon 双进程互守（`:daemon` 独立进程，5s 轮询 + 60s/5 次熔断）
- 15min Alarm 兜底

### Xposed 模块引擎
- 扫描本机已安装的 Xposed 模块 APK（modern + legacy）
- Integrated 模式：模块烤入 patched APK
- Manager 模式：运行时通过 IPC 动态下发模块，改作用域只需 force-stop
- 接入 LSPosed 在线仓库（1000+ 模块）
- Shizuku 静默安装 patched APK（root shell 通道）

### Shell 脚本模块
- ZIP 包格式：`module.prop` + `customize.sh` + `action.sh` + `service.sh` + `uninstall.sh`
- 权限三档：SAFE（全禁）/ 半开放 / 全开放
- 官方仓库 + GitHub topic 发现

## 安装

1. 卸载冲突应用（Shevery 等同包名应用）
2. 安装 Release APK
3. 开启「开发者选项 → 无线调试」
4. 点开关，按配对向导完成配对（Root 设备跳过配对直接用 Root 模式）
5. 配对一次后重启自动复活

## 构建

```bash
# JDK 21 + Android SDK
gradlew.bat :manager:assembleRelease
```

产物：`manager/build/outputs/apk/release/manager-release.apk`（约 15 MB，R8 minify）

## License

GPL-3.0（继承 LSPatch）+ Apache 2.0（Shizuku 上游）

上游：ShizukuX（qianyumeng0228）、Shizuku（RikkaApps）、LSPatch（LSPosed）、Stellar、Shevery
