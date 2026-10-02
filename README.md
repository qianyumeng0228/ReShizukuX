<div align="center">

# ReShizukuX

**English** | [简体中文](./README.zh-CN.md)

**Shizuku enhanced rebuild with built-in Xposed engine**

ReShizukuX is a community-enhanced rebuild based on [ShizukuX](https://github.com/qianyumeng0228/ShizukuX), which itself traces back to [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku). It keeps the original Shizuku privileged process architecture and adds a built-in Xposed module engine plus a Shell script module system — one app, no root required.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ReShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ReShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ReShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ReShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ReShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ReShizukuX/releases/latest)

> **Heritage note**: ReShizukuX is an independent rebuild of ShizukuX (qianyumeng0228), integrating LSPatch's APK patch engine and a Magisk-style script module system. It has **no affiliation** with RikkaApps/Shizuku, LSPosed/LSPatch, or Shevery. All upstream copyright notices are preserved.

</div>

## ⬇️ Download

Grab the latest release from [GitHub Releases](https://github.com/qianyumeng0228/ReShizukuX/releases).

## ✨ Core Features

*   **Three activation modes**: unified **Root**, **Wireless ADB**, and **Dhizuku (Device Owner)** as permission sources — one toggle auto-detects the best method.
*   **Three-layer keep-alive**: boot self-start → daemon mutual watchdog (`:daemon` process, 5s polling + circuit breaker) → 15-min Alarm fallback.
*   **No-WiFi revival**: after one ADB pairing, loopback probe on port 5555 auto-revives after reboot — no WiFi, no wireless debugging needed.
*   **Step-by-step pairing wizard**: 5-step Compose wizard with notification RemoteInput (MIUI-friendly, avoids switching apps).
*   **Root/ADB/DO permission dashboard**: home page shows live status of all three permission sources.
*   **Built-in terminal**: execute shell commands via Shizuku privilege, with persistent command history, quick commands, and stop button.

## 🔧 Xposed Module Engine

ReShizukuX integrates LSPatch-compatible APK patching — no root needed to load Xposed modules into arbitrary apps:

*   **Module scanner**: auto-detects installed Xposed module APKs (modern + legacy).
*   **Integrated mode**: modules baked into the patched APK at build time.
*   **Manager mode**: modules delivered at runtime via IPC — change scope and force-stop, no repatch needed.
*   **LSPosed repository**: browse 1000+ modules from modules.lsposed.org directly in-app.
*   **Silent install**: patched APK installed via Shizuku root shell, preserving app data.
*   **GitHub topic search**: discover new Xposed modules by GitHub topic.

## 📦 Shell Script Modules

Magisk-style ZIP modules executed through Shizuku privilege:

*   **ZIP format**: `module.prop` + `customize.sh` + `action.sh` + `service.sh` + `uninstall.sh`.
*   **Permission tiers**: SAFE (deny-all) by default, opt-in per hook.
*   **Official repo + GitHub discovery**: browse and install with one tap.

## 🛠️ Architecture

*   **Package name**: `moe.shizuku.privileged.api` — drop-in replacement for original Shizuku.
*   **Compose UI**: 5 tabs (Status / Authorization / Terminal / Modules / Settings), single Activity.
*   **R8 minify**: release APK ~15 MB.

## ☑️ System Requirements

**Minimum: Android 9+ · Target: Android 16 (SDK 36)**

- **Root mode**: KernelSU / Magisk / APatch
- **Wireless ADB mode**: Android 11+
- **Dhizuku mode**: device owner setup

## 🙏 Acknowledgements

ReShizukuX is built on the shoulders of:

| Project | Author | License | Role |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps | Apache 2.0 | Foundation privileged process |
| [ShizukuX](https://github.com/qianyumeng0228/ShizukuX) | qianyumeng0228 | Apache 2.0 | Direct upstream fork |
| [LSPatch](https://github.com/LSPosed/LSPatch) | LSPosed | GPL-3.0 | APK patch engine |
| [Stellar](https://github.com/roro2239/Stellar) | roro2239 | Apache 2.0 | Keep-alive reference |
| [Shevery](https://github.com/HmnDev-Tech/shevery) | HmnDev-Tech | Apache 2.0 | Module system reference |

## 📃 License

GPL-3.0 (inherited from LSPatch) + Apache 2.0 (Shizuku upstream)
