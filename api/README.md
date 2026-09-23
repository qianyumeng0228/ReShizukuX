# ShizukuX-API

ShizukuX-API 是 Shizuku API 的增强、开发者友好版本。它为与 [ShizukuX](https://github.com/qianyumeng0228/ShizukuX) 交互提供了现代化的接口，同时与标准 Shizuku 和 Sui 服务器保持完全向后兼容。

## ✨ 核心特性（Extra 升级）

ShizukuX-API 消除了标准 Shizuku 开发中的样板代码：

*   **同步 Shell 执行**：无需再手动管理 `InputStream`、`ErrorStream` 和线程。一行代码即可获得干净的 `CommandResult`。
*   **高级工具类**：提供用于管理**系统设置**、**应用安装**和**系统叠加层（RRO）**的专用类。
*   **Dhizuku（设备所有者）集成**：直接访问 `DevicePolicyManager` binder，无需用户恢复出厂设置或进行复杂的 ADB 配置。
*   **通用兼容性**：自动检测服务器是 ShizukuX 还是标准 Shizuku。对 Extra 服务器使用直接 AIDL stub；在标准 Shizuku 上回退到 `Shizuku.newProcess` Shell 执行。

## 🚀 Extra API 特性

ShizukuX-API 包含用于高级系统交互的专属接口：

### 1. AVF（虚拟机）管理器
通过 Android 虚拟化框架管理隔离的 Linux 环境。
*   **能力**：创建、启动和管理 Microdroid 或基于 Debian 的虚拟机。
*   **用例**：运行硬件加速的 Linux 图形应用或安全的隔离服务。

### 2. 特权存储代理
为经过验证的高级用户工具绕过 SAF（存储访问框架）限制。
*   **能力**：获取 `/data/data/` 等受限路径的 `FileDescriptor`。
*   **安全性**：需要经由 ShizukuX 管理器进行明确的生物识别/用户确认。

### 3. 智能桥（AI Core Extra）
访问特权的系统智能与硬件加速器。
*   **能力**：高优先级 NPU 调度与特权屏幕上下文采样（EyeDropper 扩展）。
*   **用例**：高级自动化与上下文感知的无障碍工具。

### 4. 窗口管理器 Extra（桌面模式）
掌控桌面窗口体验。
*   **能力**：强制自由调整大小、管理系统“气泡栏”，并设置“始终置顶”窗口。

### 5. 系统主题桥（Overlay Manager Extra）
暴露特权叠加层管理。
*   **能力**：无需 root 即可启用/禁用系统 UI 叠加层。
*   **用例**：无 root 主题引擎（如 Hex Installer）。

### 6. 网络与 DNS 治理器
管理网络限制与路由。
*   **能力**：设置系统级私有 DNS，并通过路由/VPN 管理 iptables 规则。
*   **用例**：无 root 广告拦截器（AdAway）和防火墙（AFWall+）。

### 7. 深度进程控制（Activity Manager Extra）
高级内存与进程管理。
*   **能力**：深度终止后台应用并管理待机分组。
*   **用例**：性能优化工具（Thanox、3C Toolbox）。

### 8. 多设备连续性桥
无缝的多设备状态同步。
*   **能力**：在运行 ShizukuX 的设备之间同步应用状态与特权任务移交。

## 🚀 快速开始

### 添加依赖

将以下内容添加到您的 `build.gradle`：

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.qianyumeng0228:ShizukuX-API:13.2.0-plus'
}
```

## 🛠️ 使用示例

### 1. 统一 Shell
同步执行任意命令并获取输出（请在主线程外调用）：
```java
// 字符串简写
ShizukuXAPI.CommandResult result = ShizukuXAPI.executeShell("whoami");
if (result.isSuccess()) {
    Log.d("API", "输出: " + result.output);
}

// 显式参数数组（推荐——可避免 Shell 引号转义问题）
ShizukuXAPI.CommandResult result2 = ShizukuXAPI.executeShell(
    new String[]{"pm", "list", "packages", "-3"});
```

### 2. 系统设置
轻松读取或修改 `system`、`secure` 和 `global` 设置：
```java
ShizukuXAPI.Settings.putSecure("now_bar_enabled", "1");
String scale = ShizukuXAPI.Settings.getSystem("font_scale");
```

### 3. 高级窗口控制
即使应用清单中受限，也能强制其进入自由窗口模式：
```java
ShizukuXAPI.WindowManager.forceResizable("com.example.app", true);
```

### 4. 存储访问
访问应用私有数据目录中的文件（需要用户确认）：
```java
ParcelFileDescriptor pfd = ShizukuXAPI.StorageProxy.openFile(
    "/data/data/com.example.app/files/config.json",
    ParcelFileDescriptor.MODE_READ_ONLY);
```

## 🔄 兼容性

当检测到 Extra 服务器时，ShizukuX-API 通过 AIDL stub（`IShizukuService`）分发，无需魔法事务代码即可对所有 Extra 接口进行直接的类型化访问。在标准 Shizuku 服务器上，Shell 辅助方法回退到 `Shizuku.newProcess`；仅限 Extra 的 AIDL 功能返回 `null`/`false`。

**结果**：您的应用在任何地方都能运行，但在 ShizukuX 上可获得更丰富的能力。

## 📱 文档与原版 API
关于核心逻辑、`UserService` 文档和 AIDL 定义，请参阅原版 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 仓库。ShizukuX-API 包含原版所有 `rikka.shizuku.Shizuku` 方法。

## 📃 许可证
[MIT License](LICENSE)
