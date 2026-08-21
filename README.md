<p align="center">
  <img src="./docs/images/social-preview.png" alt="局域网影片播放器 — 直接播放 SMB 共享里的视频" width="100%" />
</p>

# 局域网影片播放器

中文 · [English](./README_EN.md)

[![CI](https://github.com/ferretgeek/android-smb-player/actions/workflows/ci.yml/badge.svg)](https://github.com/ferretgeek/android-smb-player/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-0f766e.svg)](./LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](#自己构建)

> 手机直接打开电脑或 NAS 共享文件夹里的影片，点开就播。

## 为什么会需要它

电影都在台式机或 NAS 上。想在手机、平板上躺着看，一般有两条路：先传一份过去（占空间又麻烦），或者装一整套媒体服务器（要维护、要转码、要一直开着）。

其实第三条路更简单：Windows 共享文件夹、群晖、任何开了 SMB 的设备，都可以直接连进去播。这个 App 做的就是这件事。

- 字幕自动配对，乱码时能换字符集。
- 看到哪儿自动记住，下次接着看。
- 历史、书签、标签、备注都在手机本地。
- **没有账号，没有云，没有转码服务器，没有遥测。**

它的"服务端"就是你已经有的那个共享文件夹——不需要你再装什么后台。

## 界面

下图来自项目内置的匿名画廊：不连接任何 SMB，不读取私人媒体，也不含真实账号或文件名。

<p align="center">
  <img src="./docs/images/gallery-preview.png" alt="匿名画廊预览" width="360" />
</p>

## 它能做什么

- **找得到、进得去** — 局域网扫描、共享发现、访客或账号登录、目录浏览、搜索与排序。
- **播得动** — Media3 为主内核，遇到冷门编码自动回退 libVLC；硬件解码、倍速、画面适配、帧率匹配。
- **字幕和音轨** — 自动匹配同名外挂字幕，可切字符集、内嵌音轨和外挂音轨。
- **记得住** — 续播、观看历史、书签、标签、备注、回收站，以及完整备份与恢复。
- **看得舒服** — 多套浅色配色与 `#17191d` 深灰暗色，贯穿浏览、详情和播放三个界面。
- **不留痕迹** — 没有账号系统、云同步或遥测；凭据只在设备上加密保存，日志主动脱敏。
- **想要海报的话** — 附一个可选的 PC 端刮削器，提前生成海报和结构化元数据写回共享目录，手机端只读结果。

## 自己构建

首个公开版本**只发布源码，不发通用签名 APK**——避免让开发签名被当成可信的分发身份。

准备 JDK 21、Android SDK 37 和 Android Studio：

```powershell
cd LanPlay
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=2
```

debug APK 在 `LanPlay/app/build/outputs/apk/debug/`。Release 签名只从工作区之外的环境变量或签名配置读取，仓库里不含任何签名文件。

跑测试：

```powershell
cd LanPlay
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=2

cd ..\lanplay-scraper
.\.venv\Scripts\python.exe -m unittest -v
```

## 技术上值得一提的地方

**SMB 2/3 是原生实现的。** 不经过 WebDAV 桥、不依赖第三方网关、不需要在电脑上装任何伴生服务。你在 Windows 上右键共享的那个文件夹，就是它的数据源。

**播放走双内核，而且会自己切。** Media3 覆盖绝大多数情况，遇到它啃不下来的编码自动回退 libVLC，用户不需要知道这件事发生了。硬解、倍速、画面适配和帧率匹配在两个内核下都可用。

**刮削器是一次性工具，不是常驻服务。** 它在 PC 上按需运行，把海报和元数据写回共享目录，然后就可以关掉。手机端只通过 SMB 读结果——所以整套东西没有任何需要 24 小时开着的管理进程。真实目录只写进已被忽略的 `config.toml`。

**凭据和日志都当敏感数据处理。** SMB 账号密码在设备上加密保存，日志输出前主动脱敏，不会把共享路径和用户名原样打出来。

刮削器的安装、配置和网络安全边界见 [`lanplay-scraper/README.md`](./lanplay-scraper/README.md)。

## 目录

```text
LanPlay/             Android 应用与 Gradle 工程
lanplay-scraper/     可选的 Windows / Python 元数据刮削器
docs/images/         脱敏后的真实预览与分享封面
播放器规格.md         已实现的产品与技术规格
设计系统.md           视觉、布局与交互规范
需求文档.md           完整需求与验收边界
```

## 现实边界

- 播放体验受 SMB 服务器本身、网络质量、厂商后台策略和设备解码能力影响——这些不在 App 能控制的范围里。
- 可选刮削器会访问公开的第三方页面；使用时请遵守所在地法律和相应站点条款。
- 首个公开版本不提供签名 APK，需要自己构建。

## 更多文档

[安装、升级、备份、恢复、排错](./docs/OPERATIONS.md) · [版本变更](./CHANGELOG.md) · [参与开发](./CONTRIBUTING.md) · [安全策略](./SECURITY.md)

## 许可

源码以 [MIT License](./LICENSE) 发布。包括 libVLC 在内的第三方库保留各自许可证，本仓库不对其重新授权。
