package org.sarmg.xszc

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountDialog(context: Context, config: SecureConfig, onDismiss: () -> Unit, onConfigFailure: (String) -> Unit, onLoggedIn: () -> Unit) {
    val savedRead = readConfiguration { config.savedConnection() }
    val savedConfiguration = savedRead.getOrNull()
    if (savedConfiguration == null) {
        val message = savedRead.exceptionOrNull()?.message ?: "本地凭据不可用"
        LaunchedEffect(message) { onConfigFailure(message) }
        ConfigurationUnavailableScreen(message) { (context as? android.app.Activity)?.recreate() }
        return
    }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var server by remember { mutableStateOf(savedConfiguration.server) }
    var password by remember { mutableStateOf(savedConfiguration.authorizationCode) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun login() {
        if (busy || server.isBlank() || password.isBlank()) return
        focus.clearFocus()
        busy = true; error = ""
        val (expected, saved) = try { config.connection() to config.savedConnection() }
            catch (failure: ConfigStorageException) {
                busy = false
                onConfigFailure(failure.message ?: "无法读取登录凭据")
                return
            }
        val address = server.trim().trimEnd('/')
        val code = password.trim()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    config.verifyWritable()
                    val reuse = saved.server == address && saved.authorizationCode == code &&
                        saved.token.isNotBlank() && saved.accountId.isNotBlank() && saved.deviceId.isNotBlank()
                    val api = BackupApi(address, if (reuse) saved.token else "")
                    val token = if (reuse) {
                        api.validateSavedSession(saved.accountId, saved.deviceId)
                        saved.token
                    } else api.bootstrap(code, Build.MODEL)
                    check(token.isNotBlank()) { "服务器没有返回有效登录凭据" }
                    check(config.connection() == expected) { "账户已改变，请重新登录" }
                    val store = TransferStore.bindAccount(context, address, code, api.accountId, api.deviceId)
                    Triple(api, token, store.profile)
                }
                config.saveAuthenticatedConnection(expected, address, code, result.first, result.second, result.third)
                withContext(Dispatchers.IO) {
                    androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(BackupScheduler.TAG).result.get()
                }
                config.saveSnapshot(BackupSnapshot(message = "实例已登录"))
                BackupScheduler.syncAutomatic(context, config)
                onLoggedIn()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: ConfigStorageException) { onConfigFailure(e.message ?: "无法保存登录凭据") }
            catch (e: Exception) { error = e.message ?: "登录失败，请检查服务器地址和密码" }
            finally { busy = false }
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogView = LocalView.current
        SideEffect { (dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0.2f) }
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("account.dialog"), shape = AppShapes.dialog,
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextField(server, { server = it }, placeholder = { Text("服务器地址") },
                        singleLine = true, enabled = !busy, shape = AppShapes.field,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account.server"),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    TextField(password, { password = it }, placeholder = { Text("密码") },
                        singleLine = true, enabled = !busy, shape = AppShapes.field,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { login() }),
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account.password"),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(shape = AppShapes.control, enabled = !busy, onClick = { focus.clearFocus(); onDismiss() },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.weight(1f).height(52.dp).testTag("account.cancel")) { Text("取消") }
                        Button(shape = AppShapes.control, enabled = !busy && server.isNotBlank() && password.isNotBlank(), onClick = ::login,
                            modifier = Modifier.weight(1f).height(52.dp).testTag("account.submit")) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(if (busy) "登录中…" else "登录")
                        }
                    }
                }
            }
        }
    }
}
