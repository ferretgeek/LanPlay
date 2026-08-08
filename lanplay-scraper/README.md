# LanPlay 资料刮削工具

> 文档版本：v3.0 · 2026-07-30  
> 运行环境：Windows 11、Python 3.11 x64

本工具在 PC 上递归扫描视频目录，从文件名识别番号，优先访问无需账号的 R18.dev 结构化资料，再由 JavDB/JavBus 补齐缺失字段，并可从 Gfriends 获取高清演员头像。结果写入指定的 `.lanplay_meta` 目录，Android APP 只通过 SMB 读取这些资料，本身不访问公网。

本文描述公开使用契约，不代表实现已经通过独立安全审查；新会话应直接核对 `scraper.py`、安装器、锁定依赖和离线测试。

工具不会改名、移动或删除视频，也不会扫描 `.srt`、`.ass`、`.ssa`、`.vtt` 等字幕文件。增量模式会从索引中剔除已删除或改名的视频条目，但不会删除原视频。无法识别标准编号的视频会保留本地标题，并用 FFmpeg 从视频画面生成封面；没有 FFmpeg 时仍会写入标题资料。

## 安装

1. 安装 Python 3.11 x64，并确保 Windows Python Launcher `py` 可用。
2. 双击 `install.bat`。
3. 安装器会创建 `.venv`，并使用 `requirements.lock` 的固定版本和 SHA-256 hash 安装依赖。

项目现有 `.venv` 只用于本机开发，不应随正式源码包复制到其他设备。

## 配置

首次双击 `run.bat` 会从 `config.example.toml` 生成仅保存在本机、已被忽略的 `config.toml`，并在填写前停止。编辑生成的 `config.toml`：

```toml
[paths]
video_dir = '<VIDEO_DIR>'
output_dir = '<OUTPUT_DIR>'

[filters]
include_top_level_patterns = []
exclude_directory_names = ['电影', 'movie', 'movies']

[network]
proxy = ''
timeout = 20
delay_min = 1.0
delay_max = 3.0
retry = 3

[sources]
r18 = true
javdb = true
javbus = true
gfriends = true

[scrape]
incremental = true
local_fallback = true
download_poster = true
download_cover = true
download_actor_avatar = true

[output]
poster_max_size = 900
cover_max_size = 1600
avatar_size = 400
jpeg_quality = 88

[performance]
workers = 3
checkpoint_every = 5
cache_days = 7

[gfriends]
index_url = 'https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json'
base_url = 'https://cdn.jsdelivr.net/gh/gfriends/gfriends@master/'
```

`output_dir` 应指向 SMB 共享根目录下的 `.lanplay_meta`。`video_dir` 可以是该共享根目录本身，也可以是其中任意层级的视频子目录；工具会自动生成与 SMB 根目录一致的相对路径。

`include_top_level_patterns` 用于只扫描指定的顶层文件夹，支持 `*` 通配符；留空数组表示扫描全部顶层文件夹。`exclude_directory_names` 按目录名排除内容，大小写不敏感。默认排除“电影”、`movie` 和 `movies`；只匹配目录名，不会因为影片标题中偶然出现这些文字而误排除。

`config.toml` 已被 `.gitignore` 排除；公开源码只保留占位模板。不要在文档、日志或问题报告中公开真实媒体目录。

### 代理

需要代理时，在 `network.proxy` 填入：

- `http://127.0.0.1:端口`
- `socks5://127.0.0.1:端口`

工具不会读取系统代理环境变量，只使用这里显式配置的代理。

### 手动覆盖

个别文件识别不准确时，在 `manual.toml` 中按示例配置“相对视频路径 → 番号/元数据”覆盖。索引 key 是相对 `video_dir` 的 POSIX 路径，子目录使用 `/`。

## 使用

- `run.bat`：增量刮削。
- `.venv\Scripts\python.exe scraper.py --recognize-only`：只检查文件名识别，不联网、不写资料。
- `.venv\Scripts\python.exe scraper.py --force`：忽略已有成功记录并重新刮削。

每次运行使用进程锁，避免双实例同时写索引。默认最多同时进行 3 个轻量网络查询，并每完成 5 项保存一次检查点；检查点的 `index.json` 标记为 `complete: false`，全部完成后才写入 `complete: true`。APP 对不完整或旧格式索引只增量导入，不删除现有元数据和追剧状态。成功资料和 Gfriends 文件树会缓存 7 天。JSON、图片和失败清单使用临时文件校验后原子替换；响应字节、重定向次数和图片像素均有硬上限。

同一张来源图片只下载一次，再分别生成横版封面和竖版海报。R18.dev 的原始大图优先，图片不可用时才依次尝试后备来源。

## 网络安全边界

- 只允许 `http`/`https` URL，拒绝 URL 中的用户名和密码。
- DNS 解析得到任一非公网地址时拒绝请求。
- 实际连接固定到已经审核的公网 IP，并保留原 Host/SNI，防止 DNS rebinding。
- 每一跳重定向都会重新解析和校验。
- 拒绝 loopback、私网、链路本地、保留地址和云 metadata 地址。
- 不把代理口令、Cookie 或个人路径写入公开日志。

## 输出

典型结构：

```text
.lanplay_meta/
├─ index.json
├─ actors.json
├─ posters/
├─ covers/
├─ actors/
├─ failed.txt        # 每行“相对路径<TAB>失败原因”
└─ scraper.log
```

仅完整索引会按当前视频集合 prune；删除或改名的视频不会永久留在最终 `index.json`。中途检查点不会触发 APP 端陈旧记录清理，单个条目失败也不会中断整个任务。

## 验证

```powershell
& '.\.venv\Scripts\python.exe' -m unittest -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& '.\.venv\Scripts\python.exe' -m pip check
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

当前自动测试覆盖 URL 安全校验、固定 IP、R18.dev 结构化资料、跨来源合并、FC2 编号、单次图片下载、字幕排除、递归索引与 stale prune。站点页面结构可能变化，正式运行会保留已成功条目并继续处理其他媒体。

## 已知边界

- 当前实现的数据源是 R18.dev、JavDB、JavBus 和 Gfriends，均不要求提供账号；DMM/FANZA 官方 API 需要账号申请，因此未接入。
- 自动翻译标题和计划任务均未开启；刮削器只在用户主动运行 `run.bat` 或命令行时工作。
- 站点 DOM 可能变化。出现大量失败时先检查 `scraper.log`，不要通过关闭 SSRF 或响应上限来规避错误。
- 工具不会创建计划任务或常驻服务；需要周期运行时由用户另行明确授权。
