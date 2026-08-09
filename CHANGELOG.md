# 变更记录 / Changelog

本项目按版本记录面向用户的已验证变化。日期采用 `YYYY-MM-DD`，未验证的设备兼容性不写成完成事实。

This file records verified, user-facing changes by release. Dates use `YYYY-MM-DD`; unverified device compatibility is never presented as complete.

## Unreleased

- 补齐中英双语运维、安全、贡献与服务器边界说明。
- Added bilingual operations, security, contribution, and server-boundary documentation.

## 1.0.4 — 2026-08-08

- 修复备份 JSON 未显式写入格式版本、全新安装测试入口未完成首次引导、服务器修改误断开其他连接等问题。
- 加固 SMB 路径与文件读取、恢复流程、日志脱敏、Release 测试入口隔离及签名配置边界。
- 在已记录的小米 Android 设备上验证播放、字幕、书签/标签/备注、备份恢复和 `1.0.3 → 1.0.4` 同签名原位升级；荣耀 MagicOS 目标机兼容性仍未完成实机验证。
- Fixed explicit backup-version serialization, clean-install test onboarding, and over-broad SMB connection invalidation.
- Hardened SMB path/file handling, recovery, log redaction, release-test isolation, and signing boundaries.
- Verified playback, subtitles, library state, backup/restore, and a same-signature `1.0.3 → 1.0.4` in-place upgrade on the documented Xiaomi Android device. Honor MagicOS target-device compatibility remains unverified.
