#requires -Version 7.0
<#
.SYNOPSIS
    LanPlay 第 1 阶段自动验收脚本。

.DESCRIPTION
    流程：清空设备端指标文件 → 通过 adb 广播驱动 APP → run-as 拉回 JSONL → 比对门禁 → 输出结果表。

    指标不走 logcat：测试机（荣耀 MagicOS）对 logcat 全量加密，输出形如 (HKS)…(HKE)，
    一行明文都取不到。APP 因此把指标同时落盘到私有目录，debug 构建用 run-as 读回。

    门禁来自 需求文档.md §9.2 / §13 第 1 阶段与 播放器规格.md §1.5、§3.6：
      · 4K 12.19GiB 首帧 ≤ 3.0 s，1080p ≤ 2.0 s
      · 连播卡顿 0 次，丢帧率 ≤ 0.005%
      · SMB 有效吞吐 ≥ 8 MB/s
      · 缓冲水位稳定 ≥ 30 秒
      · 解码器名匹配 c2.qti.* 或 OMX.qcom.*（出现 c2.android.* 即为掉软解，不通过）
      · seek 窗口内 < 200 ms，窗口外 ≤ 1.5 s
      · >4GB 偏移读取与 PC 端逐字节一致

.EXAMPLE
    .\verify.ps1 -Scenario all
    .\verify.ps1 -Scenario longrun -LongRunMinutes 30
    .\verify.ps1 -Scenario subtitle -Video1080 '<SYNTHETIC_VIDEO>' -Subtitle '<SYNTHETIC_SUBTITLE>'
    .\verify.ps1 -Scenario tune
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'connect', 'offset', 'firstframe', 'firstframe1080', 'subtitle', 'seek', 'reconnect', 'longrun', 'speedtest', 'tune')]
    [string]$Scenario = 'all',

    [string]$Device = '',
    [string]$SmbHost = '',
    [string]$Share = '',
    [string]$User = '',
    [string]$Password = '',
    [string]$Dir = '',

    [string]$Video4K = '',
    [string]$Video1080 = '',
    [string]$Subtitle = '',

    # PC 侧的同一份素材，用于 >4GB 偏移的逐字节比对
    [string]$LocalRoot = '',

    [int]$LongRunMinutes = 30,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
# 不开 StrictMode：指标 JSON 的字段随事件类型而异，严格模式下访问缺失字段会中断整轮验收

# 真实环境只允许来自命令行、环境变量或已忽略的 verify.local.psd1。
$localProfilePath = Join-Path $PSScriptRoot 'verify.local.psd1'
$localProfile = if (Test-Path -LiteralPath $localProfilePath) {
    Import-PowerShellDataFile -LiteralPath $localProfilePath
} else { @{} }
function Resolve-LocalSetting([string]$Value, [string]$Key, [string]$EnvironmentName) {
    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }
    if ($localProfile.ContainsKey($Key) -and
        -not [string]::IsNullOrWhiteSpace([string]$localProfile[$Key])) {
        return [string]$localProfile[$Key]
    }
    return [Environment]::GetEnvironmentVariable($EnvironmentName)
}
$SmbHost = Resolve-LocalSetting $SmbHost 'SmbHost' 'LANPLAY_SMB_HOST'
$Share = Resolve-LocalSetting $Share 'Share' 'LANPLAY_SMB_SHARE'
$User = Resolve-LocalSetting $User 'User' 'LANPLAY_SMB_USER'
$Password = Resolve-LocalSetting $Password 'Password' 'LANPLAY_SMB_PASSWORD'
$Dir = Resolve-LocalSetting $Dir 'Dir' 'LANPLAY_VIDEO_DIR'
$Video4K = Resolve-LocalSetting $Video4K 'Video4K' 'LANPLAY_SAMPLE_4K'
$Video1080 = Resolve-LocalSetting $Video1080 'Video1080' 'LANPLAY_SAMPLE_1080'
$Subtitle = Resolve-LocalSetting $Subtitle 'Subtitle' 'LANPLAY_SUBTITLE'
$LocalRoot = Resolve-LocalSetting $LocalRoot 'LocalRoot' 'LANPLAY_LOCAL_ROOT'

$required = [ordered]@{ SmbHost = $SmbHost; Share = $Share }
if ($Scenario -in @('offset', 'firstframe', 'seek', 'reconnect', 'speedtest', 'tune', 'longrun', 'all')) {
    $required['Video4K'] = $Video4K
}
if ($Scenario -in @('firstframe1080', 'subtitle', 'all')) { $required['Video1080'] = $Video1080 }
if ($Scenario -in @('subtitle', 'all')) { $required['Subtitle'] = $Subtitle }
if ($Scenario -in @('offset', 'all')) { $required['LocalRoot'] = $LocalRoot }
$missing = @($required.GetEnumerator() | Where-Object {
    [string]::IsNullOrWhiteSpace([string]$_.Value) -or [string]$_.Value -match '^<.+>$'
} | ForEach-Object Key)
if ($missing.Count -gt 0) {
    throw "缺少验收参数：$($missing -join ', ')。请复制 tools/verify.example.psd1 为 verify.local.psd1 并填写，或使用命令行/环境变量。"
}
if ($Scenario -in @('subtitle', 'all')) {
    $subtitleExtension = [IO.Path]::GetExtension($Subtitle).TrimStart('.').ToLowerInvariant()
    if ($subtitleExtension -notin @('ass', 'ssa', 'idx', 'sub', 'smi')) {
        throw 'subtitle 场景只验收必须使用 VLC 原生字幕 Surface 的 ASS/SSA/VobSub/SMI 样本。'
    }
}

$PKG = 'com.lanplay.player'
$RECEIVER = "$PKG/.debug.TestHookReceiver"
$ACTION = 'com.lanplay.player.TEST'
$SINK = 'files/metrics.jsonl'

$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'

$script:Dev = ''
$script:IsArm64 = $false
$script:Results = [System.Collections.Generic.List[object]]::new()

# ── 基础设施 ────────────────────────────────────────────────

function Write-Section($text) {
    Write-Host ''
    Write-Host "══ $text " -ForegroundColor Cyan -NoNewline
    Write-Host ('═' * [Math]::Max(0, 58 - $text.Length)) -ForegroundColor Cyan
}

function Add-Check {
    param(
        [string]$Name,
        [string]$Expected,
        [string]$Actual,
        [ValidateSet('PASS', 'FAIL', 'SKIP', 'INFO')][string]$Status
    )
    $script:Results.Add([pscustomobject]@{
            检查项 = $Name
            门禁   = $Expected
            实测   = $Actual
            结果   = $Status
        })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'DarkGray' } default { 'Gray' } }
    Write-Host ("  [{0}] {1,-26} 门禁 {2,-20} 实测 {3}" -f $Status, $Name, $Expected, $Actual) -ForegroundColor $color
}

function ConvertTo-LongOrNull($Value) {
    if ($null -eq $Value) { return $null }
    $parsed = 0L
    if ([long]::TryParse(
            [string]$Value,
            [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )) { return $parsed }
    return $null
}

function ConvertTo-DoubleOrNull($Value) {
    if ($null -eq $Value) { return $null }
    $parsed = 0.0
    if ([double]::TryParse(
            [string]$Value,
            [Globalization.NumberStyles]::Float -bor [Globalization.NumberStyles]::AllowThousands,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )) { return $parsed }
    return $null
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$AdbArgs)
    $full = @()
    if ($script:Dev) { $full += @('-s', $script:Dev) }
    $full += $AdbArgs
    $output = (& $adb @full 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "adb 命令失败（退出码 $LASTEXITCODE）：$output"
    }
    return $output
}

# exec-out 走二进制通道，配合显式 UTF-8 解码，指标里的中文文件名才不会乱码
function Invoke-AdbCaptureUtf8 {
    param([string[]]$AdbArgs)
    $full = @()
    if ($script:Dev) { $full += @('-s', $script:Dev) }
    $full += $AdbArgs

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $adb
    foreach ($a in $full) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $out = $proc.StandardOutput.ReadToEnd()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) {
        throw "adb 命令失败（退出码 $($proc.ExitCode)）：$err"
    }
    return $out
}

function Quote-DeviceShellArg([string]$Value) {
    # adb shell 会把参数重新拼成设备端 shell 命令；POSIX 单引号必须显式转义。
    $escapedQuote = "'" + '"' + "'" + '"' + "'"
    return "'" + $Value.Replace("'", $escapedQuote) + "'"
}

function Send-Cmd {
    param(
        [string]$Cmd,
        [hashtable]$StringArgs = @{},
        [hashtable]$IntArgs = @{},
        [hashtable]$LongArgs = @{},
        [hashtable]$BoolArgs = @{}
    )
    $a = @(
        'shell', 'am', 'broadcast', '-n', $RECEIVER, '-a', $ACTION,
        '--es', 'cmd', (Quote-DeviceShellArg $Cmd)
    )
    foreach ($k in $StringArgs.Keys) {
        $a += @('--es', (Quote-DeviceShellArg $k), (Quote-DeviceShellArg ([string]$StringArgs[$k])))
    }
    foreach ($k in $IntArgs.Keys) { $a += @('--ei', $k, [string]$IntArgs[$k]) }
    foreach ($k in $LongArgs.Keys) { $a += @('--el', $k, [string]$LongArgs[$k]) }
    foreach ($k in $BoolArgs.Keys) { $a += @('--ez', $k, ([string]$BoolArgs[$k]).ToLower()) }
    Invoke-Adb @a | Out-Null
}

# 路径统一由 Send-Cmd 做设备 shell 转义，调用点不得自行拼引号。
function Format-DevicePath([string]$p) { $p }
function Join-SmbRelativePath([string]$Base, [string]$Child) {
    return (@($Base.Trim('/\\'), $Child.Trim('/\\')) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join '/'
}

# 清空指标前必须先停掉上一轮播放。否则旧会话的 metrics 循环还在写入它自己的播放位置，
# 切到新文件后位置从 0 重来，看起来就成了"位置回退"，会被误判为卡顿。
function Reset-Metrics {
    param([switch]$KeepPlaying)
    if (-not $KeepPlaying) {
        Send-Cmd -Cmd 'stop'
        Start-Sleep -Seconds 2
    }
    Send-Cmd -Cmd 'clear'
    Start-Sleep -Milliseconds 800
}

function Read-Metrics {
    $raw = Invoke-AdbCaptureUtf8 @('exec-out', 'run-as', $PKG, 'cat', $SINK)
    $events = [System.Collections.Generic.List[object]]::new()
    foreach ($line in ($raw -split "`r?`n")) {
        $t = $line.Trim()
        if (-not $t.StartsWith('{')) { continue }
        try { $events.Add(($t | ConvertFrom-Json)) } catch { }
    }
    return $events
}

function Where-Event($events, [string]$name) {
    return @($events | Where-Object { $_.PSObject.Properties.Name -contains 'e' -and $_.e -eq $name })
}

function Wait-ForFirstFrame {
    param([int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $events = Read-Metrics
        if ((Where-Event $events 'first_frame').Count -gt 0) { return $true }
        # 播放器错误已经进入终态时立即结束，避免继续发 Seek 或断网指令，
        # 否则会把虚拟设备不支持素材误写成后续功能失败。
        if (@(Where-Event $events 'error' | Where-Object {
                    $_.code -in @('PLAYBACK_ERROR', 'ERROR_CODE_DECODING_FAILED')
                }).Count -gt 0) {
            return $false
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Wait-ForBufferWatermark {
    param(
        [double]$MinimumSeconds,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $io = Where-Event (Read-Metrics) 'io'
        if ($io.Count -gt 0) {
            $maxBuffer = ($io | Measure-Object -Property buf_sec -Maximum).Maximum
            if ($null -ne $maxBuffer -and [double]$maxBuffer -ge $MinimumSeconds) {
                return $true
            }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Add-PlaybackPrerequisiteFailure {
    param([string]$ScenarioName)

    $events = Read-Metrics
    $lastError = Where-Event $events 'error' | Select-Object -Last 1
    $actual = if ($lastError -and $lastError.code) {
        "首帧前终止（$($lastError.code)）"
    } else {
        '等待首帧超时'
    }
    Add-Check "$ScenarioName 前置播放" '已出现首帧' $actual 'FAIL'
}

# ── 准备 ────────────────────────────────────────────────────

function Initialize-Device {
    if (-not (Test-Path -LiteralPath $adb)) { throw "找不到 adb：$adb（检查 ANDROID_HOME）" }

    $lines = @((& $adb devices) -split "`r?`n" | Where-Object { $_ -match "`tdevice$" })
    if ($lines.Count -eq 0) { throw '没有已连接的设备。USB 请开启「开发者选项 → USB 调试」，无线请先 adb pair / adb connect。' }

    if ($Device) { $script:Dev = $Device }
    elseif ($lines.Count -eq 1) { $script:Dev = ($lines[0] -split "`t")[0] }
    else { throw "连接了多台设备，请用 -Device 指定：`n" + ($lines -join "`n") }

    $model = (Invoke-Adb 'shell' 'getprop' 'ro.product.model').Trim()
    $rel = (Invoke-Adb 'shell' 'getprop' 'ro.build.version.release').Trim()
    $abi = (Invoke-Adb 'shell' 'getprop' 'ro.product.cpu.abi').Trim()
    $soc = (Invoke-Adb 'shell' 'getprop' 'ro.soc.model').Trim()
    $script:IsArm64 = $abi -match 'arm64'
    Write-Host "设备：$script:Dev  $model  Android $rel  $abi  $soc" -ForegroundColor Yellow

    if (-not $script:IsArm64) {
        Write-Host '⚠ 非 arm64 设备：硬解、刷新率、吞吐等门禁数字不代表真机，仅能验证功能正确性。' -ForegroundColor Yellow
    }
}

function Install-Apk {
    if ($SkipInstall) { return }
    if (-not (Test-Path -LiteralPath $apk)) { throw "找不到 APK：$apk（先跑 gradlew :app:assembleDebug）" }
    Write-Host "安装 $([Math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 2)) MB ..." -NoNewline
    $out = Invoke-Adb 'install' '-r' $apk
    if ($out -notmatch 'Success') { throw "安装失败：$out" }
    Write-Host ' 完成' -ForegroundColor Green
}

function Start-App {
    # 屏幕休眠时没有可见 Activity，也就没有 Surface，首帧永远出不来。
    # 播放中有 FLAG_KEEP_SCREEN_ON 顶着，但一 stop 屏幕就会灭，后续场景全部失败。
    # 小米 Android 16 会拦截 shell 的 input keyevent，改用系统电源命令。
    Invoke-Adb 'shell' 'cmd' 'power' 'wakeup' | Out-Null
    # 小米 Android 16 会拒绝 shell 的 WRITE_SETTINGS，因此不能调用
    # `svc power stayon usb`。播放页自身使用 FLAG_KEEP_SCREEN_ON；这里只负责唤醒和解锁。
    Invoke-Adb 'shell' 'wm' 'dismiss-keyguard' | Out-Null
    Start-Sleep -Milliseconds 500

    Invoke-Adb 'shell' 'am' 'force-stop' $PKG | Out-Null
    # 始终通过当前启用的桌面入口启动；常规图标被伪装关闭时回退到工具入口。
    $launcherState = Invoke-Adb 'shell' 'cmd' 'package' 'resolve-activity' '--brief' "$PKG/.LauncherAlias"
    $launcher = if ($launcherState -match 'LauncherAlias') { "$PKG/.LauncherAlias" } else { "$PKG/.ToolLauncherAlias" }
    Invoke-Adb 'shell' 'am' 'start' '-n' $launcher | Out-Null
    Start-Sleep -Seconds 3
    # 真机自动验收全程静音，避免测试素材音轨意外外放。
    Send-Cmd -Cmd 'mute' -BoolArgs @{ on = $true }
    Start-Sleep -Milliseconds 500
}

function Set-SmbConfig {
    $s = @{ host = $SmbHost; share = $Share; user = $User; path = $Dir }
    if ($Password) { $s['pass'] = $Password }
    Send-Cmd -Cmd 'configure' -StringArgs $s
    Start-Sleep -Seconds 5
}

# ── 场景 ────────────────────────────────────────────────────

function Test-Connect {
    Write-Section '连接与列目录（C-01/C-07/C-08/C-09）'
    Reset-Metrics
    Set-SmbConfig
    Send-Cmd -Cmd 'list' -StringArgs @{ path = $Dir }
    Start-Sleep -Seconds 6

    $ev = Read-Metrics
    $configured = Where-Event $ev 'configured'
    $smb = Where-Event $ev 'smb'
    $list = Where-Event $ev 'list'
    $errors = Where-Event $ev 'error'

    Add-Check 'SMB 认证' '成功' $(if ($configured.Count -gt 0) { '已连接目标 SMB' } else { '失败' }) `
        $(if ($configured.Count -gt 0) { 'PASS' } else { 'FAIL' })

    # 方言优先从握手事件取；连接被复用时不会有握手，退回读 configured 里的当前值
    $dialect = ($smb | Where-Object { $_.dialect -ne '-' } | Select-Object -First 1).dialect
    if (-not $dialect) {
        $dialect = ($configured | Where-Object { $_.dialect -and $_.dialect -ne '-' } | Select-Object -First 1).dialect
    }
    Add-Check 'SMB 方言' 'SMB2 或 SMB3' $(if ($dialect) { $dialect } else { '未知' }) `
        $(if ($dialect -match 'SMB_(2|3)') { 'PASS' } else { 'FAIL' })

    $connectMs = ($smb | Select-Object -First 1).connect_ms
    if ($null -ne $connectMs) { Add-Check 'SMB 握手耗时' '记录用' "$connectMs ms" 'INFO' }

    # 期望值从 PC 端实际目录推算，不写死——素材增减时脚本自动跟上
    $videoExt = @('.mp4', '.mkv', '.avi', '.mov', '.ts', '.m2ts', '.mts', '.flv',
        '.wmv', '.webm', '.m4v', '.mpg', '.mpeg', '.rmvb', '.rm', '.3gp', '.vob', '.iso')
    $localDir = if ([string]::IsNullOrWhiteSpace($LocalRoot)) { $null } else { Join-Path $LocalRoot $Dir }
    $expectTotal = $null
    $expectVideos = $null
    if ($null -ne $localDir -and (Test-Path -LiteralPath $localDir)) {
        # SMB 列表会返回 Windows 隐藏/系统项；PC 侧必须加 -Force 才是同一集合。
        $localEntries = @(Get-ChildItem -LiteralPath $localDir -Force | Where-Object { -not $_.Name.StartsWith('.') })
        $expectTotal = $localEntries.Count
        $expectVideos = @($localEntries | Where-Object {
            -not $_.PSIsContainer -and $videoExt -contains $_.Extension.ToLower()
        }).Count
    }

    $last = $list | Select-Object -Last 1
    if ($last) {
        if ($null -ne $expectTotal) {
            Add-Check '列目录条目数' "$expectTotal（与 PC 端一致）" "$($last.n)" $(if ($last.n -eq $expectTotal) { 'PASS' } else { 'FAIL' })
            Add-Check '视频文件数（C-09）' "$expectVideos" "$($last.videos)" $(if ($last.videos -eq $expectVideos) { 'PASS' } else { 'FAIL' })
        } else {
            Add-Check '列目录条目数' '未提供 PC 本地镜像，仅记录' "$($last.n)" 'INFO'
        }
        Add-Check '列目录耗时' '≤ 1000 ms' "$($last.ms) ms" $(if ($last.ms -le 1000) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check '列目录' '成功' '无数据' 'FAIL'
    }

    if ($errors.Count -gt 0) {
        Add-Check '错误事件' '0 条' "$($errors.Count) 条：$($errors[0].code)" 'FAIL'
    } else {
        Add-Check '错误事件' '0 条' '0 条' 'PASS'
    }
}

function Test-Offset {
    Write-Section '>4GB 偏移正确性（风险 R-5 / 需求 P-06）'
    $localFile = Join-Path (Join-Path $LocalRoot $Dir) $Video4K
    if (-not (Test-Path -LiteralPath $localFile)) {
        Add-Check '>4GB 偏移' 'PC 端可比对' '找不到本机对应样本' 'SKIP'
        return
    }
    $size = (Get-Item -LiteralPath $localFile).Length
    $offsets = @(0, 4294967296, 4294967297, 12000000000, ($size - 64))

    $expected = @{}
    $fs = [System.IO.File]::OpenRead($localFile)
    try {
        foreach ($o in $offsets) {
            $null = $fs.Seek([long]$o, [System.IO.SeekOrigin]::Begin)
            $buf = New-Object byte[] 16
            $n = $fs.Read($buf, 0, 16)
            $expected[[string]$o] = (($buf[0..($n - 1)] | ForEach-Object { $_.ToString('x2') }) -join '')
        }
    } finally { $fs.Close() }

    Reset-Metrics
    foreach ($o in $offsets) {
        Send-Cmd -Cmd 'probe_offset' -StringArgs @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video4K)) } `
            -LongArgs @{ offset = $o } -IntArgs @{ len = 16 }
        Start-Sleep -Milliseconds 1500
    }
    Start-Sleep -Seconds 3

    $probes = Where-Event (Read-Metrics) 'probe_offset'
    foreach ($o in $offsets) {
        $p = $probes | Where-Object { [long]$_.offset -eq [long]$o } | Select-Object -First 1
        $label = "偏移 $o"
        if (-not $p) { Add-Check $label '与 PC 端一致' '无响应' 'FAIL'; continue }
        Add-Check $label $expected[[string]$o] $p.head16 $(if ($p.head16 -eq $expected[[string]$o]) { 'PASS' } else { 'FAIL' })
    }

    $sizeProbe = $probes | Select-Object -First 1
    if ($sizeProbe) {
        Add-Check '文件大小（64 位）' "$size" "$($sizeProbe.size)" $(if ([long]$sizeProbe.size -eq $size) { 'PASS' } else { 'FAIL' })
    }
}

function Test-FirstFrame {
    param(
        [string]$Video,
        [int]$LimitMs,
        [string]$Label,
        [switch]$ValidateSubtitle
    )
    Write-Section "首帧与硬解 · $Label（P-01/P-02/P-03）"
    $subtitleValidation = $ValidateSubtitle.IsPresent
    Reset-Metrics
    # startMs=1 明确绕过真实观看记录续播，保证每轮性能测试从文件头开始。
    $playArgs = @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video)) }
    if ($subtitleValidation) {
        $playArgs['subtitle'] = Format-DevicePath (Join-SmbRelativePath $Dir $Subtitle)
    }
    Send-Cmd -Cmd 'play' -StringArgs $playArgs -LongArgs @{ startMs = 1 }
    Start-Sleep -Seconds 30
    Send-Cmd -Cmd 'metrics_dump'
    Start-Sleep -Seconds 3

    $ev = Read-Metrics
    $ff = Where-Event $ev 'first_frame' | Select-Object -First 1
    $dec = Where-Event $ev 'decoder' | Where-Object { $_.video -ne '-' } | Select-Object -First 1
    $io = Where-Event $ev 'io'
    $frames = Where-Event $ev 'frames'
    $state = Where-Event $ev 'state' | Select-Object -Last 1

    $ffMs = if ($ff) { ConvertTo-LongOrNull $ff.ms } else { $null }
    if ($null -ne $ffMs) {
        Add-Check "首帧 $Label" "≤ $LimitMs ms" "$ffMs ms" $(if ($ffMs -le $LimitMs) { 'PASS' } else { 'FAIL' })
        Add-Check "分辨率 $Label" '与素材一致' "$($ff.w)×$($ff.h)" 'INFO'
    } else {
        Add-Check "首帧 $Label" "≤ $LimitMs ms" '未出画面' 'FAIL'
    }

    # 首帧的下限由容器索引大小决定：播放器必须先完整读到 moov / Cues 才能出画面。
    # 长片 1080p60 的 moov 能到 33 MB，光传输就要 3 秒，与 IO 层实现无关。
    $bypass = Where-Event $ev 'range_bypass_load' | Select-Object -First 1
    if ($bypass) {
        Add-Check "容器索引 $Label" '记录用' `
            ("{0:N1} MB / {1} ms" -f ($bypass.bytes / 1MB), $bypass.ms) 'INFO'
    }

    if ($dec) {
        $isHw = if ($subtitleValidation) {
            $dec.video -eq 'libvlc hardware' -and $dec.hw
        } else {
            $dec.video -match '^(c2\.qti\.|OMX\.qcom\.)'
        }
        Add-Check '视频解码器' $(if ($subtitleValidation) { 'libVLC hardware 请求' } else { 'c2.qti.* 或 OMX.qcom.*' }) `
            $dec.video $(if ($isHw) { 'PASS' } else { 'FAIL' })
        Add-Check $(if ($subtitleValidation) { '硬解请求标志' } else { '硬解标志' }) 'true' "$($dec.hw)" `
            $(if ($dec.hw) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check '视频解码器' 'c2.qti.* 或 OMX.qcom.*' '未初始化' 'FAIL'
    }

    # 吞吐取峰值：预读窗口填满后会主动节流到 0，平均值没有意义
    if ($io.Count -gt 0) {
        $peak = ($io | Measure-Object -Property mbps -Maximum).Maximum
        $maxBufSec = ($io | Measure-Object -Property buf_sec -Maximum).Maximum
        $throughputGate = -not $subtitleValidation
        Add-Check 'SMB 吞吐（峰值）' $(if ($throughputGate) { '≥ 8 MB/s' } else { '字幕合成样本仅记录' }) `
            ("{0:N2} MB/s" -f $peak) $(if (-not $throughputGate) { 'INFO' } elseif ($peak -ge 8) { 'PASS' } else { 'FAIL' })
        Add-Check '缓冲水位' $(if ($throughputGate) { '≥ 30 秒' } else { '字幕合成样本仅记录' }) `
            ("{0:N1} 秒" -f $maxBufSec) $(if (-not $throughputGate) { 'INFO' } elseif ($maxBufSec -ge 30) { 'PASS' } else { 'FAIL' })
        $lastIo = $io | Select-Object -Last 1
        Add-Check '重连次数' '0' "$($lastIo.reconnect)" $(if ($lastIo.reconnect -eq 0) { 'PASS' } else { 'FAIL' })
        Add-Check '缓存命中率' '记录用' ("{0:P1}" -f $lastIo.hit) 'INFO'
    } else {
        Add-Check 'SMB 吞吐（峰值）' '≥ 8 MB/s' '无 io 采样' 'FAIL'
    }

    # 播放位置必须单调前进，停滞即为卡顿。
    # 只看首帧出画面之后的采样：在那之前 pos_ms 恒为 0，连续的 0 不是卡顿。
    $moving = @($frames | Where-Object { $_.pos_ms -gt 0 })
    if ($moving.Count -ge 2) {
        $stalls = 0
        for ($i = 1; $i -lt $moving.Count; $i++) {
            if ($moving[$i].pos_ms -le $moving[$i - 1].pos_ms) { $stalls++ }
        }
        Add-Check '播放停滞' '0 次' "$stalls 次（采样 $($moving.Count) 点）" $(if ($stalls -eq 0) { 'PASS' } else { 'FAIL' })
        $frames = $moving
        $lastF = $frames | Select-Object -Last 1
        Add-Check '丢帧' '≤ 10 帧' "$($lastF.dropped) 帧 / 已渲染 $($lastF.rendered)" `
            $(if ($null -ne (ConvertTo-LongOrNull $lastF.dropped) -and $lastF.dropped -le 10) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check '播放采样' '至少 2 个有效位置' "$($moving.Count) 个" 'FAIL'
    }

    if ($subtitleValidation) {
        $request = Where-Event $ev 'play_request' | Select-Object -First 1
        Add-Check '显式字幕请求' '已传给播放器' $(if ($request -and $request.has_subtitle) { '已传入' } else { '未捕获' }) `
            $(if ($request -and $request.has_subtitle) { 'PASS' } else { 'FAIL' })
    }
    if ($state) {
        Add-Check '播放状态' 'PLAYING' $state.state $(if ($state.state -in @('PLAYING', 'READY')) { 'PASS' } else { 'FAIL' })
        Add-Check '播放进度' '> 0' "$($state.pos_ms) / $($state.dur_ms) ms" $(if ($state.pos_ms -gt 0) { 'PASS' } else { 'FAIL' })
        if ($subtitleValidation) {
            $nativeSubtitle = $Subtitle.Substring($Subtitle.LastIndexOf('.') + 1).ToLowerInvariant() -in @('ass', 'ssa', 'idx', 'sub', 'smi')
            Add-Check '外挂字幕加载' '已启用' $(if ($state.has_subtitle) { "已加载（$($state.subtitle_charset)）" } else { '未加载' }) `
                $(if ($state.has_subtitle) { 'PASS' } else { 'FAIL' })
            if ($nativeSubtitle) {
                Add-Check '图形/特效字幕内核' 'VLC 原生字幕 Surface' $state.kernel `
                    $(if ($state.kernel -eq 'VLC') { 'PASS' } else { 'FAIL' })
            }
        }
    } else {
        Add-Check $(if ($subtitleValidation) { '外挂字幕状态' } else { '播放状态' }) '可读取播放器状态' '无 state 事件' 'FAIL'
    }
    Test-Crash
}

function Test-Seek {
    Write-Section 'Seek 恢复（P-04/P-06）'
    Reset-Metrics
    Send-Cmd -Cmd 'play' -StringArgs @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video4K)) } `
        -LongArgs @{ startMs = 1 }
    $readyTimeout = if ($script:IsArm64) { 25 } else { 90 }
    if (-not (Wait-ForFirstFrame -TimeoutSeconds $readyTimeout)) {
        Add-PlaybackPrerequisiteFailure 'Seek'
        Test-Crash
        return
    }
    # 高性能实机可能约 1 秒就出首帧，此时 48MB 预读窗尚未填到 30 秒位置；
    # 直接 Seek 会把“缓存尚未建立”误报为窗口未命中。等待真实 IO 水位而不是
    # 固定睡眠，弱设备不被额外拖慢，快设备也获得可比较的窗口内样本。
    $bufferReady = Wait-ForBufferWatermark -MinimumSeconds 35 -TimeoutSeconds 15
    Add-Check 'Seek 窗口内准备' '缓冲 ≥ 35 秒' $(if ($bufferReady) { '已就绪' } else { '15 秒内未就绪' }) `
        $(if ($bufferReady) { 'PASS' } else { 'FAIL' })
    # 窗口内：向前小跳。预读是前向的，读指针之前的块已丢弃，
    # 往回跳反而落到窗口外，所以必须向前且幅度小于预读时长（48MB ÷ 10.74Mbps ≈ 36 秒）
    Send-Cmd -Cmd 'seek' -LongArgs @{ ms = 30000 }
    Start-Sleep -Seconds 10
    # 窗口外：跳到 2 小时处，对应 >4GB 文件偏移
    Send-Cmd -Cmd 'seek' -LongArgs @{ ms = 7200000 }
    Start-Sleep -Seconds 18
    Send-Cmd -Cmd 'metrics_dump'
    Start-Sleep -Seconds 3

    $ev = Read-Metrics
    $seeks = Where-Event $ev 'seek'
    # 用目标位置关联两次动作，不能只找第一个 true/false：弱性能设备上 30 秒位置
    # 可能尚未进入预读窗口，但这不能把后面的两小时 Seek 冒充成“窗口内”样本。
    $inWindow = $seeks | Where-Object {
        $target = ConvertTo-LongOrNull $_.target_ms
        $null -ne $target -and $target -ge 20000 -and $target -lt 120000
    } | Select-Object -First 1
    $outWindow = $seeks | Where-Object {
        $target = ConvertTo-LongOrNull $_.target_ms
        $null -ne $target -and $target -gt 7000000
    } | Select-Object -First 1

    $inResumeMs = if ($inWindow -and $inWindow.in_window) {
        ConvertTo-LongOrNull $inWindow.resume_ms
    } else { $null }
    if ($null -ne $inResumeMs) {
        Add-Check 'Seek 窗口内' '< 200 ms' "$inResumeMs ms" $(if ($inResumeMs -lt 200) { 'PASS' } else { 'FAIL' })
    } elseif ($inWindow) {
        Add-Check 'Seek 窗口内' '< 200 ms' '该轮未命中预读窗口' 'SKIP'
    } else {
        Add-Check 'Seek 窗口内' '< 200 ms' '未捕获到窗口内 seek' 'SKIP'
    }
    $outResumeMs = if ($outWindow) { ConvertTo-LongOrNull $outWindow.resume_ms } else { $null }
    if ($null -ne $outResumeMs) {
        Add-Check 'Seek 窗口外' '≤ 1500 ms' "$outResumeMs ms" $(if ($outResumeMs -le 1500) { 'PASS' } else { 'FAIL' })
        Add-Check 'Seek 窗口外识别' 'in_window=false' "$($outWindow.in_window)" `
            $(if (-not $outWindow.in_window) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check 'Seek 窗口外' '≤ 1500 ms' '未捕获' 'FAIL'
    }

    $state = Where-Event $ev 'state' | Select-Object -Last 1
    if ($state) {
        Add-Check 'Seek 到 >4GB 后播放' '不崩溃且继续播放' "$($state.state) @ $($state.pos_ms) ms" `
            $(if ($state.state -in @('PLAYING', 'READY', 'BUFFERING')) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check 'Seek 到 >4GB 后播放' '可读取播放器状态' '无 state 事件' 'FAIL'
    }
    Test-Crash
}

function Test-Reconnect {
    Write-Section '断网恢复与重连指标反证（R-04 / 问题 5）'
    if ($script:Dev -match ':\d+$') {
        Add-Check '断网故障注入' 'USB ADB 设备' '当前是无线 ADB，无法安全关闭 Wi-Fi' 'SKIP'
        return
    }

    Reset-Metrics
    Send-Cmd -Cmd 'play' -StringArgs @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video4K)) } `
        -LongArgs @{ startMs = 1 }
    $readyTimeout = if ($script:IsArm64) { 25 } else { 90 }
    if (-not (Wait-ForFirstFrame -TimeoutSeconds $readyTimeout)) {
        Add-PlaybackPrerequisiteFailure '断网恢复'
        Test-Crash
        return
    }

    $wifiWasEnabled = (Invoke-Adb 'shell' 'cmd' 'wifi' 'status') -match 'Wifi is enabled'
    if (-not $wifiWasEnabled) {
        Add-Check '断网故障注入' 'Wi-Fi 初始已连接' 'Wi-Fi 未启用' 'SKIP'
        return
    }

    try {
        Invoke-Adb 'shell' 'svc' 'wifi' 'disable' | Out-Null
        Start-Sleep -Seconds 3
        # 跳到预读窗口外，确保断网期间立即产生真实 SMB 读取，而不是继续消费缓存。
        Send-Cmd -Cmd 'seek' -LongArgs @{ ms = 7800000 }
        Start-Sleep -Seconds 10
    } finally {
        Invoke-Adb 'shell' 'svc' 'wifi' 'enable' | Out-Null
    }

    $wifiRecovered = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        $status = Invoke-Adb 'shell' 'cmd' 'wifi' 'status'
        if ($status -match 'Wifi is enabled' -and $status -match 'is connected') {
            $wifiRecovered = $true
            break
        }
    }
    Add-Check 'Wi-Fi 自动恢复' '重新连接' $(if ($wifiRecovered) { '已连接' } else { '60 秒内未连接' }) `
        $(if ($wifiRecovered) { 'PASS' } else { 'FAIL' })

    Start-Sleep -Seconds 35
    Send-Cmd -Cmd 'metrics_dump'
    Start-Sleep -Seconds 3
    $ev = Read-Metrics
    $reconnectEvents = Where-Event $ev 'io_reconnect'
    $io = Where-Event $ev 'io'
    $reconnects = if ($io.Count -gt 0) {
        ($io | Measure-Object -Property reconnect -Maximum).Maximum
    } else { 0 }
    Add-Check '重连指标反证' '> 0（证明门禁不再恒零）' "$reconnects 次 / $($reconnectEvents.Count) 条事件" `
        $(if ($reconnects -gt 0 -and $reconnectEvents.Count -gt 0) { 'PASS' } else { 'FAIL' })

    $state = Where-Event $ev 'state' | Select-Object -Last 1
    if ($state) {
        Add-Check '断网后继续播放' 'PLAYING 且位置 > 2 小时' "$($state.state) @ $($state.pos_ms) ms" `
            $(if ($state.state -in @('PLAYING', 'READY') -and $state.pos_ms -gt 7200000) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check '断网后继续播放' 'PLAYING 且位置 > 2 小时' '无状态事件' 'FAIL'
    }
    Test-Crash
}

function Test-SpeedTest {
    Write-Section '纯读测速（O-05）'
    Reset-Metrics
    # 必须并发测。实测同一链路：串行 6.81 MB/s，6 路并发 11.19 MB/s——
    # 串行数字会把可用带宽低估近 40%，也就测不出流水线本身的价值。
    Send-Cmd -Cmd 'speedtest' -StringArgs @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video4K)) } `
        -IntArgs @{ mb = 300; parts = 6 }
    Start-Sleep -Seconds 75

    $st = Where-Event (Read-Metrics) 'speedtest' | Select-Object -First 1
    if ($st) {
        Add-Check 'SMB 纯读吞吐' '≥ 8 MB/s' `
            ("{0:N2} MB/s（{1} MB / {2} 秒 / {3} 路）" -f $st.mbps, $st.mb, $st.sec, $st.parts) `
            $(if ($st.mbps -ge 8) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check 'SMB 纯读吞吐' '≥ 8 MB/s' '无响应' 'FAIL'
    }
}

function Invoke-Tune {
    Write-Section 'IO 参数扫描（X-02：读块 × 并发）'
    Write-Host '  用 4K 素材施加最大压力，每个组合播 25 秒取峰值吞吐与首帧。' -ForegroundColor DarkGray

    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($b in @(512, 1024, 2048)) {
        foreach ($c in @(2, 4, 6, 8)) {
            Send-Cmd -Cmd 'stop'
            Start-Sleep -Seconds 2
            Send-Cmd -Cmd 'set' -StringArgs @{ key = 'readBlockKb' } -IntArgs @{ value = $b }
            Start-Sleep -Milliseconds 800
            Send-Cmd -Cmd 'set' -StringArgs @{ key = 'concurrentReads' } -IntArgs @{ value = $c }
            Start-Sleep -Milliseconds 800

            Reset-Metrics
            Send-Cmd -Cmd 'play' -StringArgs @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video4K)) } `
                -LongArgs @{ startMs = 1 }
            Start-Sleep -Seconds 25
            Send-Cmd -Cmd 'metrics_dump'
            Start-Sleep -Seconds 2

            $ev = Read-Metrics
            $ff = Where-Event $ev 'first_frame' | Select-Object -First 1
            $io = Where-Event $ev 'io'
            $lastF = Where-Event $ev 'frames' | Select-Object -Last 1

            $peak = if ($io.Count -gt 0) { ($io | Measure-Object -Property mbps -Maximum).Maximum } else { 0 }
            $bufSec = if ($io.Count -gt 0) { ($io | Measure-Object -Property buf_sec -Maximum).Maximum } else { 0 }

            $row = [pscustomobject]@{
                读块KB   = $b
                并发     = $c
                首帧ms   = if ($ff) { $ff.ms } else { $null }
                峰值MBps = [Math]::Round($peak, 2)
                水位秒   = [Math]::Round($bufSec, 1)
                丢帧     = if ($lastF) { $lastF.dropped } else { $null }
            }
            $rows.Add($row)
            Write-Host ("  读块 {0,4}KB 并发 {1}  首帧 {2,5} ms  峰值 {3,6:N2} MB/s  水位 {4,5:N1}s  丢帧 {5}" -f `
                    $b, $c, $row.首帧ms, $row.峰值MBps, $row.水位秒, $row.丢帧) -ForegroundColor Gray
        }
    }

    Send-Cmd -Cmd 'stop'
    Write-Host ''
    Write-Host '  按峰值吞吐排序：' -ForegroundColor Cyan
    $rows | Sort-Object 峰值MBps -Descending | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

    $best = $rows | Where-Object { $null -ne $_.首帧ms } | Sort-Object 峰值MBps -Descending | Select-Object -First 1
    if ($best) {
        Add-Check '最优组合' '吞吐 ≥ 8 MB/s' `
            ("读块 {0}KB / 并发 {1} → {2:N2} MB/s，首帧 {3} ms" -f $best.读块KB, $best.并发, $best.峰值MBps, $best.首帧ms) `
            $(if ($best.峰值MBps -ge 8) { 'PASS' } else { 'FAIL' })
        Write-Host "  → 若要改默认值，改 SettingsRepository.IoSettings：readBlockKb=$($best.读块KB), concurrentReads=$($best.并发)" -ForegroundColor Yellow
    }

    # 扫描留下了非默认参数，跑完恢复默认值（读块 512 KB 见 IoSettings 的说明）
    Send-Cmd -Cmd 'set' -StringArgs @{ key = 'readBlockKb' } -IntArgs @{ value = 512 }
    Start-Sleep -Milliseconds 500
    Send-Cmd -Cmd 'set' -StringArgs @{ key = 'concurrentReads' } -IntArgs @{ value = 6 }
}

function Test-LongRun {
    Write-Section "连播 $LongRunMinutes 分钟（P-03 卡顿 0 次）"
    Reset-Metrics
    Send-Cmd -Cmd 'play' -StringArgs @{ path = (Format-DevicePath (Join-SmbRelativePath $Dir $Video4K)) } `
        -LongArgs @{ startMs = 1 }

    $readyTimeout = if ($script:IsArm64) { 25 } else { 90 }
    if (-not (Wait-ForFirstFrame -TimeoutSeconds $readyTimeout)) {
        Add-PlaybackPrerequisiteFailure '连播'
        Test-Crash
        return
    }
    $deadline = (Get-Date).AddMinutes($LongRunMinutes)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 60
        $remain = [int]($deadline - (Get-Date)).TotalMinutes
        $snap = Where-Event (Read-Metrics) 'frames' | Select-Object -Last 1
        if ($snap) {
            Write-Host ("  剩余 {0,3} 分钟 · 位置 {1:N0}s · 丢帧 {2}" -f $remain, ($snap.pos_ms / 1000), $snap.dropped) -ForegroundColor DarkGray
        }
    }
    Send-Cmd -Cmd 'metrics_dump'
    Start-Sleep -Seconds 3

    $ev = Read-Metrics
    # 只统计 first_frame 之后的播放采样。Media3 在画面尚未出现时已经处于
    # PLAYING，位置会停在 startMs（通常为 1 ms）；把这些两秒采样算成卡顿，
    # 会把“首帧慢”重复计为十几次“播放中停滞”。
    $firstFrameSeen = $false
    $frames = [System.Collections.Generic.List[object]]::new()
    foreach ($event in $ev) {
        if ($event.e -eq 'first_frame') {
            $firstFrameSeen = $true
            continue
        }
        if ($firstFrameSeen -and $event.e -eq 'frames') {
            $frames.Add($event)
        }
    }
    $io = Where-Event $ev 'io'

    if ($frames.Count -eq 0) {
        Add-Check '连播采集' '有数据' '无 frames 事件' 'FAIL'
        if ($io.Count -eq 0) { Add-Check '连播 IO 采集' '有数据' '无 io 事件' 'FAIL' }
        Test-Crash
        return
    }

    $last = $frames | Select-Object -Last 1
    $rendered = ConvertTo-LongOrNull $last.rendered
    $dropped = ConvertTo-LongOrNull $last.dropped
    $maxConsecutive = ConvertTo-LongOrNull $last.max_consec
    if ($null -eq $rendered -or $null -eq $dropped -or $null -eq $maxConsecutive) {
        Add-Check '连播帧字段' 'rendered/dropped/max_consec 完整' '字段缺失或非数值' 'FAIL'
    } else {
        $dropRate = if ($rendered -gt 0) { $dropped / [double]$rendered } else { 1.0 }
        Add-Check '累计丢帧' '≤ 10 帧' "$dropped 帧 / 已渲染 $rendered" $(if ($dropped -le 10) { 'PASS' } else { 'FAIL' })
        Add-Check '丢帧率' '≤ 0.005%' ("{0:P4}" -f $dropRate) $(if ($dropRate -le 0.00005) { 'PASS' } else { 'FAIL' })
        Add-Check '最大连续丢帧' '≤ 5' "$maxConsecutive" $(if ($maxConsecutive -le 5) { 'PASS' } else { 'FAIL' })
    }

    $positionSamples = @($frames | Where-Object { $null -ne (ConvertTo-LongOrNull $_.pos_ms) })
    if ($positionSamples.Count -ne $frames.Count) {
        Add-Check '播放停滞采样' '位置字段完整' "$($positionSamples.Count) / $($frames.Count)" 'FAIL'
    } else {
        $stalls = 0
        for ($i = 1; $i -lt $frames.Count; $i++) {
            if ($frames[$i].pos_ms -le $frames[$i - 1].pos_ms) { $stalls++ }
        }
        Add-Check '播放停滞次数' '0 次' "$stalls 次（采样 $($frames.Count) 点）" $(if ($stalls -eq 0) { 'PASS' } else { 'FAIL' })
    }

    if ($io.Count -gt 0) {
        # “稳定水位”从首次充到 30 秒门禁后起算；首帧前后的 1~29 秒属于必然的启动填充，
        # 但一旦达到门禁，后续任何跌破都必须计入最低值。
        $bufferPrimed = $false
        $steadyIo = [System.Collections.Generic.List[object]]::new()
        foreach ($event in $ev) {
            if ($event.e -ne 'io' -or $null -eq (ConvertTo-DoubleOrNull $event.buf_sec)) { continue }
            if ($event.buf_sec -ge 30) { $bufferPrimed = $true }
            if ($bufferPrimed -and $event.buf_sec -gt 0) { $steadyIo.Add($event) }
        }
        $minBuf = if ($steadyIo.Count -gt 0) {
            ($steadyIo | Measure-Object -Property buf_sec -Minimum).Minimum
        } else {
            0
        }
        Add-Check '缓冲水位最低值' '≥ 30 秒' ("{0:N1} 秒" -f $minBuf) $(if ($minBuf -ge 30) { 'PASS' } else { 'FAIL' })
        $lastIo = $io | Select-Object -Last 1
        Add-Check '重连次数' '0' "$($lastIo.reconnect)" $(if ($null -ne (ConvertTo-LongOrNull $lastIo.reconnect) -and $lastIo.reconnect -eq 0) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check '连播 IO 采集' '有数据' '无 io 事件' 'FAIL'
    }
    Test-Crash
}

# ── 交叉验证 ────────────────────────────────────────────────

function Test-Crash {
    $crash = Invoke-Adb 'logcat' '-b' 'crash' '-d'
    $hit = @(($crash -split "`r?`n") | Where-Object { $_ -match $PKG })
    if ($hit.Count -gt 0) {
        Add-Check '崩溃检查' '无崩溃' "$($hit.Count) 行崩溃日志" 'FAIL'
        # 崩溃原文可能携带媒体路径或 endpoint；终端只输出计数，详情留在已脱敏的应用错误文件。
    } else {
        Add-Check '崩溃检查' '无崩溃' '干净' 'PASS'
    }
}

function Reset-CrashBuffer {
    # 只建立本轮验收的证据起点；不读取、不输出历史 crash 原文。
    $null = Invoke-Adb 'logcat' '-b' 'crash' '-c'
}

function Test-CrossCheck {
    Write-Section '系统层交叉验证'

    # dumpsys media.metrics 独立确认解码器，不依赖 APP 自报。必须按应用 UID
    # 过滤；直接抓最后几条 codec 会误把 SystemUI、媒体提供程序等进程的旧记录
    # 当成 LanPlay，既可能假通过，也可能假失败。
    $packageUidOutput = Invoke-Adb 'shell' 'cmd' 'package' 'list' 'packages' '-U' $PKG
    # `list packages <filter>` 是子串过滤：设备同时装有 `.test` 或
    # `.baselineprofile` 时，直接对整段输出做 -match 会误取第一个测试包 UID。
    # 系统媒体指标必须只关联主应用，否则硬解交叉验证会稳定误报 SKIP。
    $escapedPackage = [regex]::Escape($PKG)
    $packageUidLine = @(($packageUidOutput -split "`r?`n") | Where-Object {
            $_ -match "^package:$escapedPackage\s+uid:\d+\s*$"
        } | Select-Object -First 1)
    $appUid = if ($packageUidLine.Count -gt 0 -and $packageUidLine[0] -match 'uid:(\d+)') {
        $Matches[1]
    } else { $null }
    $mm = Invoke-Adb 'shell' 'dumpsys' 'media.metrics'
    $standardCodecLines = if ($appUid) {
        @(($mm -split "`r?`n") | Where-Object {
            $_ -match '^\s*\d+:\s+\{codec,' -and
            $_ -match "\($appUid,\s*\d+,\s*$appUid\)" -and
            $_ -match 'mediacodec\.mode=video' -and
            $_ -match 'mediacodec\.codec='
        } | Select-Object -Last 3)
    } else { @() }
    # HyperOS 3 / Android 16 使用厂商格式：
    #   {[pid: ..., mId: ...] c2.qti...: configure, ..., <package>, params: {...width/height...}}
    # 它没有 AOSP 的 UID 三元组或 mediacodec.mode 字段。用精确主包名和视频宽高
    # 双重约束，既兼容该格式，也不会把 `.test`、基准包或音频解码器混进来。
    $vendorCodecLines = @(($mm -split "`r?`n") | Where-Object {
            $_ -match '^\s*\d+:\s+\{\[pid:\s*\d+,\s*mId:\s*\d+\]\s+[^:\s]+:\s+configure,' -and
            $_ -match ",\s*$escapedPackage,\s+params:" -and
            $_ -match 'android\.media\.mediacodec\.(width|height)='
        } | Select-Object -Last 3)
    $codecLines = @(($standardCodecLines + $vendorCodecLines) | Select-Object -Last 3)
    if ($codecLines.Count -gt 0) {
        $hw = ($codecLines -join ' ') -match '(c2\.qti\.|OMX\.qcom\.)'
        $sample = if ($codecLines[-1] -match 'mediacodec\.codec=([^,\s)]+)') {
            $Matches[1]
        } elseif ($codecLines[-1] -match '\]\s+([^:\s]+):\s+configure,') {
            $Matches[1]
        } else {
            $codecLines[-1].Trim()
        }
        if ($sample.Length -gt 70) { $sample = $sample.Substring(0, 70) }
        Add-Check 'dumpsys 解码器交叉验证' '高通硬解' $sample $(if ($hw) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check 'dumpsys 解码器交叉验证' '高通硬解' '无本应用 video codec 记录' 'SKIP'
    }

    $disp = Invoke-Adb 'shell' 'dumpsys' 'display'
    $mode = @(($disp -split "`r?`n") | Where-Object { $_ -match 'mActiveSfDisplayMode' } | Select-Object -First 1)
    if ($mode.Count -gt 0 -and $mode[0] -match 'peakRefreshRate=([\d.]+)') {
        Add-Check '屏幕刷新率' '记录用（P-15 第 3 阶段）' "$($Matches[1]) Hz" 'INFO'
    }

    # 注意这里测的是 debug 构建：未优化 DEX 的 Code 段明显大于经过 R8 的 release。
    # APK 还包含 libVLC 原生库，文件体积不能用来直接推算运行时 Java 堆。
    # 先显式 GC，避免把已不可达但尚未触发回收的旁路索引临时数组误判成泄漏。
    # 需求 §9.1 的 350 MB 门禁应以 release 构建为准，debug 数字仅作趋势参考。
    Send-Cmd -Cmd 'gc'
    Start-Sleep -Seconds 2
    $mem = Invoke-Adb 'shell' 'dumpsys' 'meminfo' $PKG
    $totalLine = @(($mem -split "`r?`n") | Where-Object { $_ -match 'TOTAL PSS' }) | Select-Object -First 1
    $codeLine = @(($mem -split "`r?`n") | Where-Object { $_ -match '^\s+Code:' }) | Select-Object -First 1
    if ($totalLine -and $totalLine -match '(\d+)') {
        $mb = [int]$Matches[1] / 1024
        $codeMb = if ($codeLine -and $codeLine -match '(\d+)') { [int]$Matches[1] / 1024 } else { 0 }
        $net = $mb - $codeMb
        Add-Check '内存占用（debug 全量）' '参考值' ("{0:N0} MB（含 debug Code 段 {1:N0} MB）" -f $mb, $codeMb) 'INFO'
        Add-Check '内存占用（扣除 Code）' '≤ 350 MB' ("{0:N0} MB" -f $net) $(if ($net -le 350) { 'PASS' } else { 'FAIL' })
    } else {
        Add-Check '内存占用（播放）' '≤ 350 MB' '未取到' 'SKIP'
    }
}

# ── 主流程 ──────────────────────────────────────────────────

Write-Host ''
Write-Host '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' -ForegroundColor Cyan
Write-Host '  LanPlay 第 1 阶段自动验收' -ForegroundColor Cyan
Write-Host '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' -ForegroundColor Cyan

Initialize-Device
Reset-CrashBuffer
Install-Apk
Start-App

switch ($Scenario) {
    'connect' { Test-Connect }
    'offset' { Test-Connect; Test-Offset }
    'firstframe' { Test-Connect; Test-FirstFrame -Video $Video4K -LimitMs 3000 -Label '4K'; Test-CrossCheck }
    'firstframe1080' { Test-Connect; Test-FirstFrame -Video $Video1080 -LimitMs 2000 -Label '1080p'; Test-CrossCheck }
    'subtitle' {
        Test-Connect
        Test-FirstFrame -Video $Video1080 -LimitMs 2000 -Label '字幕样本' -ValidateSubtitle
    }
    'seek' { Test-Connect; Test-Seek }
    'reconnect' { Test-Connect; Test-Reconnect }
    'speedtest' { Test-Connect; Test-SpeedTest }
    'tune' { Test-Connect; Invoke-Tune }
    'longrun' { Test-Connect; Test-LongRun; Test-CrossCheck }
    'all' {
        Test-Connect
        Test-Offset
        Test-FirstFrame -Video $Video1080 -LimitMs 2000 -Label '1080p'
        Test-FirstFrame -Video $Video4K -LimitMs 3000 -Label '4K'
        Test-FirstFrame -Video $Video1080 -LimitMs 2000 -Label '字幕样本' -ValidateSubtitle
        Test-Seek
        Test-Reconnect
        Test-SpeedTest
        Test-LongRun
        Test-CrossCheck
    }
}

Write-Section '汇总'
$script:Results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

$pass = @($script:Results | Where-Object { $_.结果 -eq 'PASS' }).Count
$fail = @($script:Results | Where-Object { $_.结果 -eq 'FAIL' }).Count
$skip = @($script:Results | Where-Object { $_.结果 -eq 'SKIP' }).Count
$strictGate = $Scenario -in @('all', 'longrun', 'reconnect', 'subtitle', 'seek')
$gateFailed = $fail -gt 0 -or ($strictGate -and $skip -gt 0)
Write-Host ("通过 {0}   失败 {1}   跳过 {2}" -f $pass, $fail, $skip) `
    -ForegroundColor $(if (-not $gateFailed) { 'Green' } else { 'Red' })
if ($strictGate -and $skip -gt 0) {
    Write-Host '最终门禁不允许关键证据缺失；SKIP 已按失败处理。' -ForegroundColor Red
}

exit $(if ($gateFailed) { 1 } else { 0 })
