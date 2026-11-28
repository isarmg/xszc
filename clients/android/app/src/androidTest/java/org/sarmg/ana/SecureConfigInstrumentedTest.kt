package org.sarmg.ana

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureConfigInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "secure-config-test-${UUID.randomUUID()}"
    private val alias = "${context.packageName}.$name.aes-gcm"
    private val file get() = File(context.noBackupFilesDir, "$name.bin")
    private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun open() = SecureConfig(context, name)
    private fun seedToken() = androidConfigStore(context, name).update { it.set(MobileContractV1.TOKEN_KEY, "test-token") }

    @After fun cleanOnlyThisTestState() {
        listOf(file, File("${file.path}.new"), File("${file.path}.bak"),
            File(context.dataDir, "shared_prefs/$name.xml"), File(context.dataDir, "shared_prefs/$name.xml.bak")).forEach { it.delete() }
        keys().deleteEntry(alias)
    }

    @Test fun realKeystoreCredentialsReopenAndLogoutClearsLocalSecrets() {
        val config = open()
        assertFalse(keys().containsAlias(alias))
        seedToken()
        config.saveBackupPreferences(true, false, true, true, false, false, setOf("测试相册", "camera"))
        config.saveSnapshot(BackupSnapshot(state = "completed", completed = 3, uploadedBytes = 123456789L))
        assertEquals("test-token", config.connection().token)
        assertNull((keys().getKey(alias, null) as SecretKey).encoded)
        assertFalse(file.readText().contains("test-token"))
        val reopened = open()
        assertEquals(config.connection(), reopened.connection())
        val settings = reopened.settingsSnapshot()
        assertEquals(reopened.connection(), settings.connection)
        assertEquals(SecureConfig.BackupPreferences(true, false, true, true, false, false,
            setOf("测试相册", "camera")), settings.preferences)
        assertEquals(3, reopened.snapshot().completed)
        reopened.logout()
        val signedOut = open()
        assertEquals("", signedOut.connection().token)
    }

    @Test fun passwordLoginReusesDevicesAndSeparatesBackupSettings() {
        val address = "https://backup.example.com"
        val config = open()
        val firstAccount = UUID.randomUUID().toString()
        val firstDevice = UUID.randomUUID().toString()
        val firstProfile = deviceProfileKey(address, firstDevice)

        fun signIn(username: String, account: String, device: String, previousDevice: String?) {
            val expected = config.connection()
            assertEquals(previousDevice, config.rememberedDevice(address, username)?.deviceId)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("/v1/auth/login", request.url.encodedPath)
                assertEquals("POST", request.method)
                assertNull(request.header("Authorization"))
                val body = Buffer().also { request.body!!.writeTo(it) }
                val payload = JSONObject(body.readUtf8())
                assertEquals(username, payload.getString("username"))
                assertEquals(" exact password ", payload.getString("password"))
                assertEquals(previousDevice.orEmpty(), payload.optString("device_id"))
                val response = JSONObject().put("account_id", account).put("device_id", device)
                    .put("bearer_token", "token-$username")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response.toString().toResponseBody()).build()
            }.build()
            val api = BackupApi(address, "", client)
            val token = api.login(username, " exact password ", "Test device", previousDevice)
            config.saveAuthenticatedConnection(expected, address, username, api, token, deviceProfileKey(address, device))
        }

        signIn("first", firstAccount, firstDevice, null)
        assertEquals(firstProfile, config.profile)
        assertEquals("first", config.connection().username)
        config.saveBackupPreferences(true, false, true, true, false, false, setOf("first-album"))
        assertTrue(config.settingsSnapshot().preferences.auto)
        val secondAccount = UUID.randomUUID().toString()
        val secondDevice = UUID.randomUUID().toString()
        signIn("second", secondAccount, secondDevice, null)
        val secondPreferences = config.settingsSnapshot().preferences
        assertFalse(secondPreferences.auto)
        assertTrue(secondPreferences.albums.isEmpty())
        config.logout()
        val signedOut = open().connection()
        assertEquals("", signedOut.token)
        assertEquals(signedOutProfileKey(), signedOut.profile)
        signIn("FIRST", firstAccount, firstDevice, firstDevice)
        val firstSettings = open().settingsSnapshot()
        assertEquals(firstProfile, firstSettings.connection.profile)
        assertEquals(setOf("first-album"), firstSettings.preferences.albums)
        assertTrue(firstSettings.preferences.auto)
        assertEquals(secondDevice, open().rememberedDevice(address, "second")?.deviceId)
    }

    @Test fun deletedKeystoreKeyIsReportedAndCiphertextIsPreserved() {
        val config = open()
        seedToken()
        val before = file.readBytes()
        keys().deleteEntry(alias)
        assertThrows(ConfigStorageException::class.java) { config.settingsSnapshot() }
        assertThrows(ConfigStorageException::class.java) { open() }
        assertArrayEquals(before, file.readBytes())
        assertFalse(keys().containsAlias(alias))
    }

    @Test fun missingCiphertextIsReportedAndExistingKeyIsPreserved() {
        seedToken()
        assertTrue(file.delete())
        assertThrows(IllegalStateException::class.java) { open() }
        assertFalse(file.exists())
        assertTrue(keys().containsAlias(alias))
    }

    @Test fun obsoletePreferencesRequireExplicitNewInstallationAndAreNeverMigrated() {
        val previous = File(context.dataDir, "shared_prefs/$name.xml")
        previous.parentFile!!.mkdirs()
        val bytes = "old-format-test-fixture".toByteArray()
        previous.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { open() }
        assertArrayEquals(bytes, previous.readBytes())
        assertFalse(file.exists())
        assertFalse(keys().containsAlias(alias))
    }

    @Test fun interruptedObsoletePreferencesAreAlsoRejectedAndPreserved() {
        val backup = File(context.dataDir, "shared_prefs/$name.xml.bak")
        backup.parentFile!!.mkdirs()
        backup.writeText("old-format-backup-test-fixture")
        assertThrows(ConfigStorageException::class.java) { open() }
        assertEquals("old-format-backup-test-fixture", backup.readText())
        assertFalse(file.exists())
        assertFalse(keys().containsAlias(alias))
    }

    @Test fun existingInstanceQueueWithoutCredentialsIsPreservedAndRejected() {
        withIsolatedAppData { isolated, root ->
            val database = File(root, "databases/${"a".repeat(64)}/${MobileContractV1.DATABASE_FILENAME}")
            database.parentFile!!.mkdirs()
            database.writeText("current-instance-queue-test-fixture")
            assertThrows(IllegalStateException::class.java) { SecureConfig(isolated) }
            assertEquals("current-instance-queue-test-fixture", database.readText())
            assertFalse(File(isolated.noBackupFilesDir, "${MobileContractV1.PREFERENCES_FILE}.bin").exists())
        }
    }

    @Test fun signedOutGalleryCatalogueDoesNotPreventInitialConfiguration() {
        withIsolatedAppData { isolated, root ->
            val database = File(root, "databases/${signedOutProfileKey()}/${MobileContractV1.DATABASE_FILENAME}")
            database.parentFile!!.mkdirs()
            database.writeText("signed-out-catalogue-test-fixture")
            assertEquals(signedOutProfileKey(), SecureConfig(isolated).profile)
            assertEquals("signed-out-catalogue-test-fixture", database.readText())
        }
    }

    private fun withIsolatedAppData(block: (Context, File) -> Unit) {
        val root = File(context.cacheDir, "secure-config-app-test-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getDataDir(): File = root
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
            override fun getPackageName(): String = "${context.packageName}.${root.name}"
        }
        val isolatedAlias = "${isolated.packageName}.${MobileContractV1.PREFERENCES_FILE}.aes-gcm"
        try {
            block(isolated, root)
            assertFalse(keys().containsAlias(isolatedAlias))
        } finally {
            root.deleteRecursively()
            keys().deleteEntry(isolatedAlias)
        }
    }

    @Test fun interruptedInitialWriteIsNotTreatedAsNewInstallation() {
        val unfinished = File("${file.path}.new")
        unfinished.writeText("unfinished-test-fixture")
        assertThrows(IllegalStateException::class.java) { open() }
        assertEquals("unfinished-test-fixture", unfinished.readText())
        assertFalse(file.exists())
        assertFalse(keys().containsAlias(alias))
    }
}
