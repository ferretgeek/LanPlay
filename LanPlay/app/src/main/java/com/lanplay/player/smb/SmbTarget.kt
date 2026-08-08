package com.lanplay.player.smb

/** 认证模式（需求 §5.4） */
enum class AuthMode { ANONYMOUS, GUEST, ACCOUNT }

/**
 * 一个 SMB 共享的连接目标。
 *
 * password 只在内存中存在；持久化时由 [com.lanplay.player.data.crypto.CredentialCipher]
 * 加密，任何情况下都不写入日志或指标。
 */
data class SmbTarget(
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String? = null,
    val username: String = "",
    val password: String = "",
    val authMode: AuthMode = AuthMode.ACCOUNT,
) {
    /** 连接身份的稳定标识，用于判断是否需要重建 Session。不含密码。 */
    val identity: String
        get() = "${host.trim().lowercase()}:$port/${share.trim().lowercase()}|" +
            "${domain.orEmpty().trim().lowercase()}\\${username.trim().lowercase()}|$authMode"

    fun requireValid(): SmbTarget = apply {
        require(host.isNotBlank()) { "主机地址不能为空" }
        require(port in 1..65535) { "端口必须在 1～65535 之间" }
        require(host.none { it.code < 0x20 || it in "/\\@?#" }) { "主机地址包含非法字符" }
        require(share.isNotBlank()) { "共享名不能为空" }
        require(share != "." && share != "..") { "共享名无效" }
        require(share.none { it.code < 0x20 || it == '/' || it == '\\' }) {
            "共享名只能包含一个路径段"
        }
    }

    /** 持久日志和异常不得通过 data class 默认实现泄露内网身份。 */
    override fun toString(): String = "SmbTarget(port=$port, mode=$authMode)"
}

/** 目录项。relativePath 相对共享根，统一用 '/' 分隔，SMB 侧转换由 IO 层负责。 */
data class SmbEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

/** SMB 层错误码，与设计系统 §8.3 的中文错误文案一一对应 */
object SmbErrorCode {
    const val AUTH_FAILED = "SMB_AUTH_FAILED"
    const val CONNECT_FAILED = "SMB_CONNECT_FAILED"
    const val SHARE_NOT_FOUND = "SMB_SHARE_NOT_FOUND"
    const val ACCESS_DENIED = "SMB_ACCESS_DENIED"
    const val FILE_NOT_FOUND = "SMB_FILE_NOT_FOUND"
    const val READ_FAILED = "SMB_READ_FAILED"
    const val NOT_CONFIGURED = "SMB_NOT_CONFIGURED"
}

class SmbException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
