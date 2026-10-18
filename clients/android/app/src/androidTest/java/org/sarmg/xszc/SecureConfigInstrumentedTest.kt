package org.sarmg.xszc

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
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

    @After fun cleanOnlyThisTestState() {
        listOf(file, File("${file.path}.new"), File("${file.path}.bak"),
            File(context.dataDir, "shared_prefs/$name.xml"), File(context.dataDir, "shared_prefs/$name.xml.bak")).forEach { it.delete() }
        keys().deleteEntry(alias)
    }

    @Test fun realKeystoreCredentialsReopenAndLogoutPreservesReusableCredentials() {
        val config = open()
        assertFalse(keys().containsAlias(alias))
        config.saveCredentials(" https://backup.example.com/ ", " test-code ")
        config.bearerToken = "test-token"
        config.accountId = "test-account"
        config.deviceId = "test-device"
        config.saveBackupPreferences(true, false, true, true, false, false, setOf("测试相册", "camera"))
        config.saveSnapshot(BackupSnapshot(state = "completed", completed = 3, uploadedBytes = 123456789L))
        assertTrue(config.isLoggedIn)
        assertNull((keys().getKey(alias, null) as SecretKey).encoded)
        assertFalse(file.readText().contains("test-token"))
        assertFalse(file.readText().contains("test-code"))
        val reopened = open()
        assertEquals(config.connection(), reopened.connection())
        val settings = reopened.settingsSnapshot()
        assertEquals(reopened.connection(), settings.connection)
        assertEquals(SecureConfig.BackupPreferences(true, false, true, true, false, false,
            setOf("测试相册", "camera")), settings.preferences)
        assertEquals(3, reopened.snapshot().completed)
        reopened.logout()
        val signedOut = open()
        assertFalse(signedOut.isLoggedIn)
        assertEquals("", signedOut.connection().token)
        assertEquals("test-token", signedOut.savedConnection().token)
        assertEquals("test-code", signedOut.savedAuthorizationCode)
    }

    @Test fun deletedKeystoreKeyIsReportedAndCiphertextIsPreserved() {
        val config = open()
        config.bearerToken = "test-token"
        val before = file.readBytes()
        keys().deleteEntry(alias)
        assertThrows(ConfigStorageException::class.java) { config.settingsSnapshot() }
        assertThrows(ConfigStorageException::class.java) { open() }
        assertArrayEquals(before, file.readBytes())
        assertFalse(keys().containsAlias(alias))
    }

    @Test fun missingCiphertextIsReportedAndExistingKeyIsPreserved() {
        open().bearerToken = "test-token"
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

    @Test fun firstUnpairedGalleryCatalogueDoesNotPreventInitialConfiguration() {
        withIsolatedAppData { isolated, root ->
            val database = File(root, "databases/${provisionalProfileKey("", "")}/${MobileContractV1.DATABASE_FILENAME}")
            database.parentFile!!.mkdirs()
            database.writeText("unpaired-catalogue-test-fixture")
            assertEquals(provisionalProfileKey("", ""), SecureConfig(isolated).profile)
            assertEquals("unpaired-catalogue-test-fixture", database.readText())
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
