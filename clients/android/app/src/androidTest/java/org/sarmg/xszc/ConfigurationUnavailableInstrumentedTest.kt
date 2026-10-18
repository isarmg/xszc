package org.sarmg.xszc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConfigurationUnavailableInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "config-ui-test-${UUID.randomUUID()}"
    private val alias get() = "${context.packageName}.$name.aes-gcm"
    private val file get() = File(context.noBackupFilesDir, "$name.bin")
    private var profile: String? = null
    private fun configured(): SecureConfig = SecureConfig(context, name).also {
        it.saveCredentials("https://config-ui.example.test", UUID.randomUUID().toString())
        profile = it.profile
    }
    @After fun cleanThisTestState() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        listOf(file, File("${file.path}.new"), File("${file.path}.bak")).forEach { it.delete() }
        profile?.let { key ->
            val database = File(context.dataDir, "databases/$key")
            if (database.exists()) {
                runCatching { NativeBridgeV1.close(TransferStore.open(context, key).handle) }
                database.deleteRecursively()
                File(context.dataDir, "files/$key").deleteRecursively()
            }
        }
    }

    @Test fun settingsHandleAKeyLostAfterThePageIsDisplayed() {
        val config = configured()
        compose.setContent { AppTheme {
            SettingsScreen(context, config, {}, {}, {}, {})
        } }
        compose.onNodeWithText("仅 Wi-Fi 上传").performScrollTo().assertIsDisplayed()
        val before = file.readBytes()
        compose.runOnIdle { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
        compose.onNodeWithText("仅 Wi-Fi 上传").performClick()
        compose.onNodeWithTag("config.unavailable").assertIsDisplayed()
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun navigationHandlesCiphertextDamagedAfterTheAppIsDisplayed() {
        val config = configured()
        compose.setContent { AppTheme { AppNavigation(context, config) {} } }
        compose.onNodeWithTag("navigation.3").assertIsDisplayed()
        val broken = "damaged-config-test-fixture".toByteArray()
        compose.runOnIdle { file.writeBytes(broken) }
        compose.onNodeWithTag("navigation.3").performClick()
        compose.onNodeWithTag("config.unavailable").assertIsDisplayed()
        assertArrayEquals(broken, file.readBytes())
    }

    @Test fun unreadableConfigurationShowsTheReasonAndOffersReadRetry() {
        var retries = 0
        compose.setContent { AppTheme {
            ConfigurationUnavailableScreen("本地凭据密钥缺失，原数据已保留") { retries++ }
        } }
        compose.onNodeWithTag("config.unavailable").assertIsDisplayed()
        compose.onNodeWithText("本地凭据密钥缺失，原数据已保留").assertIsDisplayed()
        compose.onNodeWithTag("config.retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
