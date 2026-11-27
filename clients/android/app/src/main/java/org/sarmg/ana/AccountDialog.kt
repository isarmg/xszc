package org.sarmg.ana

import android.content.Context
import android.os.Build
import android.widget.Toast
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
    val savedRead = readConfiguration { config.connection() }
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
    var username by remember { mutableStateOf(savedConfiguration.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun login() {
        if (busy || server.isBlank() || username.isBlank() || password.isEmpty()) return
        focus.clearFocus()
        busy = true; error = ""
        val expected = try { config.connection() }
            catch (failure: ConfigStorageException) {
                busy = false
                onConfigFailure(failure.message ?: "无法读取登录凭据")
                return
            }
        val serverInput = server.trim()
        val account = username.trim()
        val secret = password
        scope.launch {
            try {
                val currentToken = withContext(Dispatchers.IO) {
                    val api = BackupApi(serverInput, "")
                    val address = api.serverUrl
                    config.verifyWritable()
                    val previous = config.rememberedDevice(address, account)
                    val token = api.login(account, secret, Build.MODEL, previous?.deviceId)
                    check(token.isNotBlank()) { "服务器没有返回有效登录凭据" }
                    check(config.connection() == expected) { "账户已改变，请重新登录" }
                    val boundAddress = previous?.takeIf { it.accountId.equals(api.accountId, true) &&
                        it.deviceId.equals(api.deviceId, true) }?.server ?: address
                    val store = TransferStore.bindAccount(context, boundAddress, api.accountId, api.deviceId)
                    config.saveAuthenticatedConnection(expected, boundAddress, account, api, token, store.profile)
                    token
                }
                try {
                    withContext(Dispatchers.IO) {
                        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(BackupScheduler.TAG).result.get()
                        BackupScheduler.syncAutomatic(context, config)
                        if (expected.token.isNotBlank() && expected.token != currentToken) {
                            try { BackupApi(expected.server, expected.token).revokeSession() }
                            catch (_: Exception) { /* The active credential has already changed. */ }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, "账户已登录，后台备份调度未完成", Toast.LENGTH_LONG).show() }
                finally { onLoggedIn() }
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
                    TextField(username, { username = it }, placeholder = { Text("用户名") },
                        singleLine = true, enabled = !busy, shape = AppShapes.field,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account.username"),
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
                        Button(shape = AppShapes.control, enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = ::login,
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
