#requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$excludedDirectories = @(
    '.git', '.gradle', '.kotlin', 'build', 'artifacts', '.venv', '__pycache__',
    'LanPlay-before-takeover-20260727-1013'
)
$extensions = @('.kt', '.kts', '.java', '.xml', '.ps1', '.psd1', '.bat', '.py', '.toml', '.md', '.txt', '.json')
$patterns = [ordered]@{
    '私网 IPv4' = '(?<!\d)(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})(?!\d)'
    'Windows 盘符路径' = '(?i)(?<![<A-Z0-9_])[A-Z]:\\[^\s`"'']+'
    '下载站式媒体名' = '(?i)[A-Z0-9-]+\.(?:com|net|org|cc|tv|me)@[^\s`"'']+\.(?:mp4|mkv|avi|mov)'
}

$hits = [System.Collections.Generic.List[string]]::new()
Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
    $extensions -contains $_.Extension.ToLowerInvariant() -and
    $_.Name -ne 'local.properties' -and
    $_.Name -ne 'verify.local.psd1' -and
    -not ($_.Name -eq 'config.toml' -and $_.Directory.Name -eq 'lanplay-scraper') -and
    $_.Name -notmatch '\.(?:log|tmp)$' -and
    -not ($_.FullName.Substring($root.Length).Split([IO.Path]::DirectorySeparatorChar) |
        Where-Object { $_ -in $excludedDirectories })
} | ForEach-Object {
    $relative = [IO.Path]::GetRelativePath($root, $_.FullName)
    $lineNo = 0
    Get-Content -LiteralPath $_.FullName -Encoding UTF8 | ForEach-Object {
        $lineNo++
        $line = $_
        if ($line -match 'SENSITIVE-SCAN-ALLOW') { return }
        foreach ($entry in $patterns.GetEnumerator()) {
            if ($line -match $entry.Value) {
                $hits.Add("$relative`:$lineNo [$($entry.Key)]")
            }
        }
    }
}

if ($hits.Count -gt 0) {
    $hits | Sort-Object -Unique | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    exit 1
}
Write-Host '敏感信息扫描通过。'
