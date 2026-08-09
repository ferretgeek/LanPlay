# LanPlay 运维指南 / Operations Guide

## 架构与部署边界 / Architecture and deployment boundary

LanPlay 是原生 Android 客户端，直接连接用户已有的 SMB 2/3 文件服务。应用内媒体代理只监听设备回环地址，用来把 SMB 字节流交给播放器，不是局域网管理接口。可选 `lanplay-scraper` 在 Windows PC 上按需扫描媒体目录并把合成索引、海报和封面写回 SMB；它不常驻、不接收入站请求，也不控制 Android 应用。

因此本项目的“本地 + 服务器”形态是 Android 本地应用配合标准 SMB 服务器，而不是再维护一个功能重复的 LanPlay Web 服务。服务器侧请使用你信任并自行加固的 SMB 实现；不要把 SMB 端口直接暴露到公网。远程访问应先建立可信 VPN，再按最小权限访问共享。

LanPlay is a native Android client that connects directly to an existing SMB 2/3 file service. Its in-app media proxy listens only on the device loopback interface to bridge SMB bytes into the player; it is not a LAN management endpoint. The optional Windows `lanplay-scraper` runs on demand, writes indexes and artwork back to SMB, accepts no inbound requests, and does not control the Android app.

The local/server model is therefore an Android client plus a standard SMB server—not a duplicate LanPlay web service. Use a trusted, hardened SMB implementation and never expose SMB directly to the public internet. For remote use, connect through a trusted VPN and grant the least share permissions needed.

## 安装与升级 / Install and upgrade

1. 使用 JDK 21 与 Android SDK 37 按 [`README.md`](../README.md) 构建 APK；Release 密钥只从工作区外的安全位置提供。
2. 在 Android 系统中确认 APK 来源与签名后安装。首次启动按引导添加 SMB 服务器，优先使用只读账号；只有使用回收站或文件管理功能时才授予写权限。
3. 升级前先导出应用备份。新 APK 必须与已安装版本签名一致，然后可使用系统安装界面或 `adb install -r <APK_PATH>` 原位升级。
4. 升级后检查服务器连接、目录浏览、播放/Seek、字幕、观看状态和一次备份导入。签名不一致时 Android 会拒绝原位升级；不要通过卸载绕过，除非已经导出备份并接受应用私有数据被删除。

1. Build with JDK 21 and Android SDK 37 as documented in [`README.md`](../README.md). Supply release signing material only from a secure location outside the workspace.
2. Verify the APK origin and signature before installation. Add an SMB server during onboarding, preferably with a read-only account; grant write access only for trash or file-management features.
3. Export a backup first. The new APK must use the same signature as the installed version; upgrade through Android or with `adb install -r <APK_PATH>`.
4. Recheck connection, browsing, playback/seek, subtitles, watch state, and one backup import. Never work around a signature mismatch by uninstalling unless a backup exists and private app data may be discarded.

## 备份与恢复 / Backup and restore

- 在设置中通过 Android 系统文件选择器导出备份，并把文件保存到受控位置。备份包含服务器定义和本地媒体状态，但不包含 SMB 密码；它仍可能透露共享结构与观看记录，应按私人数据保护。
- 恢复前保留当前备份。通过设置选择备份文件，完成后重新输入每个 SMB 凭据，再检查服务器连接、标签、书签、备注和历史。
- SMB 上的原始媒体与 `.lanplay_meta` 不在应用备份内，需由 SMB 服务器自己的快照或备份机制单独保护。刮削输出可重新生成，但手动覆盖文件应另行备份。

- Export through Android's system file picker and store the result in a controlled location. Backups include server definitions and local media state but exclude SMB passwords. They can still expose share structure and watch history, so treat them as private.
- Keep a current backup before restore. Select the file in Settings, re-enter every SMB credential, then verify connections, tags, bookmarks, notes, and history.
- Original media and `.lanplay_meta` on SMB are outside the app backup. Protect them with the SMB server's own snapshot or backup system. Scraped output can be regenerated, while manual overrides should be backed up separately.

## 健康检查 / Health checks

- Android：连接测试成功；目录可浏览；抽样视频可开始播放、Seek 和切换字幕；重启应用后续播状态仍在；备份导出并导入到测试数据后结果一致。
- SMB：仅在受信任网络或 VPN 可达；账号权限符合预期；共享空间充足；服务器时间和文件名编码正常。
- 刮削器：运行 `.\.venv\Scripts\python.exe -m unittest -v` 与 `-m pip check`；一次 `--recognize-only` 不联网识别通过；正式索引最终为 `complete: true`，失败项可解释。

- Android: connection test succeeds; folders browse; sample media starts, seeks, and switches subtitles; resume state survives restart; a test backup round trip is consistent.
- SMB: reachable only on a trusted network or VPN; account permissions match intent; storage is sufficient; time and filename encoding are correct.
- Scraper: run unit tests and `pip check`; confirm one offline `--recognize-only` pass; ensure the final index is `complete: true` and failures are understood.

## 故障排查 / Troubleshooting

- **连接或认证失败：** 从 Android 设备确认 SMB 主机可达，重新输入凭据，检查 SMB 2/3、共享名和最小权限；不要公开真实地址或日志。
- **播放/Seek 异常：** 先切换 Media3/VLC 内核并关闭不稳定的硬件解码；再检查 Wi-Fi、SMB 吞吐和文件本身。设备解码能力不同，不应关闭读取边界校验来兼容坏文件。
- **字幕乱码或未匹配：** 检查文件名和目录、手动选择字幕或字符集，并确认字幕文件可由同一 SMB 账号读取。
- **恢复后无法连接：** 这是预期行为，备份不含密码；逐个重新输入凭据。
- **厂商后台限制：** 在系统设置中允许播放服务按需要运行。已记录的小米设备通过了现有门禁；荣耀 MagicOS 等其他目标机必须单独实测，文档不把它们视为已验证。
- **刮削大量失败：** 检查站点变更、网络和 `scraper.log` 的脱敏原因；不要关闭 SSRF、重定向或响应大小限制。

- **Connection/authentication:** Confirm reachability from the Android device, re-enter credentials, and check SMB 2/3, share name, and least privilege. Never post real endpoints or logs.
- **Playback/seek:** Try the Media3/VLC fallback and disable unreliable hardware decoding, then check Wi-Fi, SMB throughput, and the file. Do not weaken read bounds to accommodate malformed media.
- **Subtitle issues:** Check naming and location, select the subtitle or charset manually, and confirm the SMB account can read it.
- **Cannot connect after restore:** Expected—passwords are excluded. Re-enter credentials for each server.
- **Vendor background limits:** Allow the playback service as needed in system settings. Existing gates passed on the documented Xiaomi device; Honor MagicOS and other targets require separate device testing.
- **Many scraper failures:** Check upstream page changes, networking, and redacted reasons in `scraper.log`. Never disable SSRF, redirect, or response-size controls.

## 卸载 / Uninstall

先导出应用备份，再从 Android 设置卸载 LanPlay。卸载会删除应用私有数据库、加密凭据、缓存和本地状态，无法从设备上恢复；不会删除 SMB 上的原始媒体。若不再使用刮削器，先保留需要的 `manual.toml` 与配置说明，再删除其目录和虚拟环境；是否删除 `.lanplay_meta` 由媒体库管理员决定，删除前确认 Android 端不再依赖它。

Export an app backup before uninstalling LanPlay through Android Settings. Uninstall removes the private database, encrypted credentials, cache, and local state and cannot recover them from the device; it does not delete original SMB media. To remove the scraper, retain any needed `manual.toml` and configuration notes before deleting its directory and virtual environment. The library owner decides whether to remove `.lanplay_meta` after confirming no client still relies on it.
