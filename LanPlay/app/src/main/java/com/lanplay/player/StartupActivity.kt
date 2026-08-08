package com.lanplay.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lanplay.player.data.db.DatabaseBootstrap
import com.lanplay.player.data.db.DatabaseRecoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher 的轻量入口。数据库不可用时本 Activity 不创建任何 Room/DAO/Hilt 对象。 */
class StartupActivity : ComponentActivity() {
    private var checking by mutableStateOf(true)
    private var workingLabel by mutableStateOf("正在安全打开本机数据…")
    private var recoveryMessage by mutableStateOf<String?>(null)
    private var confirmEmptyRebuild by mutableStateOf(false)
    private var pendingBackup by mutableStateOf<Uri?>(null)

    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pendingBackup = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF4A6FA5),
                    background = Color(0xFFFAFBFC),
                    surface = Color.White,
                )
            ) {
                Surface(Modifier.fillMaxSize()) { StartupContent() }
            }
        }
        if (intent.getBooleanExtra(EXTRA_FORCE_RECOVERY, false)) {
            checking = false
            recoveryMessage = DatabaseBootstrap.failureMessage()
                ?: "无法打开本机加密数据库。旧数据未被覆盖。"
        } else {
            tryOpenDatabase()
        }
    }

    private fun tryOpenDatabase() {
        checking = true
        workingLabel = "正在安全打开本机数据…"
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { DatabaseBootstrap.preflight(applicationContext) }
            }.onSuccess {
                launchMain()
            }.onFailure {
                checking = false
                recoveryMessage = DatabaseBootstrap.failureMessage()
                    ?: "无法打开本机加密数据库。旧数据未被覆盖。"
            }
        }
    }

    private fun rebuild(backup: Uri?) {
        checking = true
        workingLabel = if (backup == null) "正在保留旧库并创建空数据库…" else "正在验证备份并准备恢复…"
        lifecycleScope.launch {
            runCatching {
                if (backup != null) DatabaseRecoveryManager.validateBackup(applicationContext, backup)
                val recoveryId = DatabaseRecoveryManager.rebuild(applicationContext)
                recoveryId
            }.onSuccess { recoveryId ->
                launchMain(backup, recoveryId)
            }.onFailure {
                checking = false
                recoveryMessage = it.message ?: "数据库恢复失败；旧数据仍保持原状。"
            }
        }
    }

    private fun launchMain(backup: Uri? = null, recoveryId: String? = null) {
        val next = Intent(this, MainActivity::class.java).apply {
            intent.extras?.let(::putExtras)
            removeExtra(EXTRA_FORCE_RECOVERY)
            backup?.let { putExtra(MainActivity.EXTRA_RECOVERY_BACKUP_URI, it.toString()) }
            recoveryId?.let { putExtra(MainActivity.EXTRA_RECOVERY_ID, it) }
        }
        startActivity(next)
        finish()
    }

    @Composable
    private fun StartupContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (checking) "LanPlay" else "本机数据需要恢复",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                if (checking) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(workingLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        text = recoveryMessage.orEmpty(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = ::tryOpenDatabase, modifier = Modifier.fillMaxWidth()) {
                        Text("重试打开")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { backupPicker.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("从备份恢复")
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = { confirmEmptyRebuild = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重建空库（保留旧库）")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "不会静默覆盖旧数据库。重建后，旧库与原密钥会整组保存在应用私有恢复目录。",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        pendingBackup?.let { uri ->
            AlertDialog(
                onDismissRequest = { pendingBackup = null },
                title = { Text("从备份恢复") },
                text = {
                    Text("将先验证所选备份，再完整保留当前旧库并创建新库。备份不含服务器密码，恢复后需要重新填写凭据。")
                },
                confirmButton = {
                    Button(onClick = { pendingBackup = null; rebuild(uri) }) { Text("开始恢复") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBackup = null }) { Text("取消") }
                },
            )
        }
        if (confirmEmptyRebuild) {
            AlertDialog(
                onDismissRequest = { confirmEmptyRebuild = false },
                title = { Text("重建空数据库？") },
                text = { Text("观看记录和服务器配置不会进入新库，但旧数据库会完整保留，可供后续人工恢复。") },
                confirmButton = {
                    Button(onClick = { confirmEmptyRebuild = false; rebuild(null) }) {
                        Text("保留旧库并重建")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmEmptyRebuild = false }) { Text("取消") }
                },
            )
        }
    }

    companion object {
        const val EXTRA_FORCE_RECOVERY = "database_recovery"
    }
}
