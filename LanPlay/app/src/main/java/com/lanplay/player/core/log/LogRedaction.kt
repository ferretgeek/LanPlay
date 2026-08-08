package com.lanplay.player.core.log

/** 写入本机错误文件前的结构化脱敏；导出层仍会再执行一次兜底脱敏。 */
internal object LogRedaction {
    private val sensitiveKeys = setOf(
        "path", "relative_path", "file", "host", "ip", "address", "share", "user",
        "username", "domain", "endpoint", "url", "uri", "title", "actor", "name",
        "server_name", "password", "pass", "pin", "secret", "token", "first",
        "msg", "message", "detail",
    )
    private val smbUri = Regex("(?i)\\bsmb://[^\\s\\\"']+")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6 = Regex("(?i)(?<![0-9a-f:])(?:\\[[0-9a-f:]*:[0-9a-f:]+]|(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]*)(?![0-9a-f:])")
    private val smbEndpoint = Regex("(?i)\\b[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?:445\\b")
    private val windowsPath = Regex("(?i)\\b[A-Z]:\\\\[^\\r\\n\\\"']+")
    private val uncPath = Regex("\\\\\\\\[^\\s\\\"']+")
    private val mediaName = Regex(
        "(?i)[^\\s\\\"'「」/\\\\]+\\.(?:mp4|mkv|avi|mov|m4v|webm|ts|m2ts|mts|wmv|flv|mpg|mpeg|vob|iso|srt|ass|ssa|vtt|idx|sub|smi|ttml)",
    )
    private val quotedPrivateValue = Regex("「[^」]{1,512}」")

    fun field(key: String, value: Any?): Pair<String, Any?> {
        if (key.lowercase() in sensitiveKeys) return key to "<redacted>"
        return key to if (value is String) text(value) else value
    }

    fun text(value: String): String = value
        .replace(smbUri, "<smb-uri>")
        .replace(windowsPath, "<local-path>")
        .replace(uncPath, "<network-path>")
        .replace(ipv6, "<ip>")
        .replace(ipv4, "<ip>")
        .replace(smbEndpoint, "<smb-endpoint>")
        .replace(mediaName, "<media-file>")
        .replace(quotedPrivateValue, "「<redacted>」")
}
