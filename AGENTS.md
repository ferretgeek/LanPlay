# LanPlay 项目约束

- 当前实现位于 `LanPlay/`，PC 刮削器位于 `lanplay-scraper/`；当前工作区不保留历史源码副本，审查和修改只针对这两个目录。
- 真实 SMB 主机、共享名、用户名、密码、本机盘符路径和私人媒体文件名不得写入源码、脚本默认值、配置模板、测试夹具、文档、日志或审查报告。
- 公开截图只允许使用项目内置匿名画廊或明确的合成占位数据；不得展示真实服务器、媒体库、通知栏身份或设备标识。
- Android 验收参数只通过命令行、`LANPLAY_*` 环境变量或已忽略的 `LanPlay/tools/verify.local.psd1` 提供。
- 刮削器真实目录只写入已忽略的 `lanplay-scraper/config.toml`；仓内只保留 `config.example.toml`。
- 修改后至少运行 Python 单测、PowerShell AST 检查、Android unit test/Kotlin 编译；交付 APK 时还需 lint 与 release 构建。
- Windows 本机构建默认采用低资源模式：最多 2 个 Gradle worker、禁用并行和常驻 daemon，不得为缩短耗时长期占满 CPU 或内存。
- 小米 13 可作为通用功能、性能和布局复测机；其 PASS 可覆盖同类硬件路径，但不能替代 MagicOS 专属后台策略、VLC 字幕 Surface 或荣耀 2K+ 物理屏证据。
- 没有目标机、批准样本或真实 SMB 证据时，MagicOS 字幕、断网恢复、长跑、视觉/TalkBack 等门禁必须写“未验证”。
