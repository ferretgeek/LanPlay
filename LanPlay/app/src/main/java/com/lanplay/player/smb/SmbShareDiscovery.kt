package com.lanplay.player.smb

import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接向导专用的共享枚举器（C-02）。
 *
 * SMBJ 的公开 API 只支持连接已知共享，不包含 MS-RPC 共享枚举。这里用 jCIFS-NG
 * 完成一次性的主机根目录查询；实际列目录、播放和预读仍全部走经过门禁的 SMBJ。
 */
@Singleton
class SmbShareDiscovery @Inject constructor() {

    suspend fun list(
        host: String,
        username: String,
        password: String,
        domain: String? = null,
        port: Int = 445,
        authMode: AuthMode = if (username.isBlank()) AuthMode.GUEST else AuthMode.ACCOUNT,
    ): List<String> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        SmbTarget(
            host = cleanHost,
            port = port,
            share = "IPC$",
            domain = domain,
            username = username,
            password = password,
            authMode = authMode,
        ).requireValid()
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // jCIFS 当前默认对 IPC$ 强制签名；这里仍显式钉住完整策略，避免升级后默认值漂移。
            setProperty("jcifs.smb.client.signingPreferred", "true")
            setProperty("jcifs.smb.client.signingEnforced", "true")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "true")
            setProperty("jcifs.smb.client.connTimeout", "5000")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        val context = when (authMode) {
            AuthMode.ANONYMOUS -> base.withAnonymousCredentials()
            AuthMode.GUEST -> base.withGuestCrendentials()
            AuthMode.ACCOUNT -> base.withCredentials(
                NtlmPasswordAuthenticator(domain.orEmpty(), username, password)
            )
        }
        val authority = if (':' in cleanHost && !cleanHost.startsWith('[')) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        val root = SmbFile("smb://$authority:$port/", context)
        try {
            root.list().orEmpty()
                .map { it.trim().trimEnd('/', '\\') }
                .filter { it.isNotBlank() && !it.endsWith("$") && it != "IPC$" }
                .distinctBy { it.lowercase() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        } finally {
            runCatching { root.close() }
        }
    }
}
