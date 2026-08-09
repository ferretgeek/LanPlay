# 安全政策 / Security policy

请不要在公开 Issue、日志或截图中提交真实 SMB 地址、共享名、用户名、密码、媒体文件名或签名材料。安全问题请使用 GitHub 仓库的 **Security → Report a vulnerability** 私密报告入口。

Do not include real SMB endpoints, share names, usernames, passwords, media filenames, or signing material in public issues, logs, or screenshots. Please report vulnerabilities privately through **Security → Report a vulnerability** in this repository.

当前维护版本为 `1.0.x`。报告请附带最小复现、受影响版本和经过脱敏的日志；不要附带真实凭据或私人媒体。

The currently maintained line is `1.0.x`. Include a minimal reproduction, the affected version, and redacted logs—never real credentials or private media.

设置导出的备份不会包含 SMB 密码；恢复后必须重新输入凭据。请把备份文件视为私人媒体资料的一部分，不要上传到 Issue、CI 制品或公共网盘。只安装由你自行构建或能独立验证来源与签名的 APK；仓库中的源码许可不等于任意第三方 APK 都可信。

Settings backups exclude SMB passwords, so credentials must be entered again after restore. Treat backup files as private library data and never upload them to issues, CI artifacts, or public storage. Install only APKs you built yourself or whose origin and signature you can independently verify; source availability does not make arbitrary third-party APKs trustworthy.
