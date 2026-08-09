# 参与贡献 / Contributing

感谢帮助改进 LanPlay。提交改动前，请先阅读 [`AGENTS.md`](./AGENTS.md)、[`README.md`](./README.md) 和 [`SECURITY.md`](./SECURITY.md)。安全问题请使用 GitHub 私密漏洞报告入口，不要创建公开 Issue。

Thank you for improving LanPlay. Before changing the project, read [`AGENTS.md`](./AGENTS.md), [`README.md`](./README.md), and [`SECURITY.md`](./SECURITY.md). Report security issues privately through GitHub rather than opening a public issue.

## 开发约定 / Development contract

- 不得提交真实 SMB 地址、共享名、凭据、私人媒体名、签名材料、设备标识、日志或本机绝对路径；测试和截图只能使用从零生成的合成数据。
- Android 工程使用 JDK 21、Android SDK 37；Windows 命令必须兼容 PowerShell 和包含空格的路径。
- 保持 Media3 主内核与 VLC 回退、加密凭据、日志脱敏和本地回环媒体代理的安全边界；不要用吞错或降低校验换取测试通过。
- UI 改动需覆盖主要浅色主题与深灰主题，并检查普通手机和窄屏、键盘/遥控焦点、长文本及无障碍语义。
- 刮削器的网络改动必须保留逐跳 URL/DNS/目标地址校验、响应上限、原子写入和无账号默认值。

- Never commit real SMB endpoints, share names, credentials, private media names, signing material, device identifiers, logs, or machine-specific absolute paths. Tests and screenshots must use synthetic data created from scratch.
- The Android project targets JDK 21 and Android SDK 37. Windows commands must work in PowerShell and with paths containing spaces.
- Preserve the Media3-primary/VLC-fallback design, encrypted credentials, redacted logging, and loopback-only media proxy. Do not suppress failures or weaken validation to pass tests.
- UI changes must cover the principal light themes and the deep-gray theme, normal and narrow phone layouts, keyboard/remote focus, long text, and accessibility semantics.
- Scraper networking changes must preserve per-hop URL, DNS, and destination validation, response limits, atomic writes, and account-free defaults.

## 验证 / Verification

```powershell
cd LanPlay
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=2

cd ..\lanplay-scraper
.\.venv\Scripts\python.exe -m unittest -v
.\.venv\Scripts\python.exe -m pip check
```

涉及设备播放、备份恢复、升级或厂商兼容性的改动，还应在真实目标设备上执行 `LanPlay/tools/verify.ps1` 对应门禁并如实记录未覆盖项。Pull Request 请说明用户影响、验证环境、命令与结果；不要粘贴未脱敏日志。

Changes to device playback, backup/restore, upgrades, or vendor compatibility also require the relevant `LanPlay/tools/verify.ps1` gates on a real target device. State uncovered areas honestly. Pull requests should describe user impact, environment, commands, and results without pasting unredacted logs.
