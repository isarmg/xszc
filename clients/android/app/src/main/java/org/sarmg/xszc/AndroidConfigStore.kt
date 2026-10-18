package org.sarmg.xszc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun androidConfigStore(context: Context, name: String): EncryptedConfigStore {
    require(name.matches(Regex("[A-Za-z0-9_-]+")))
    val file = File(context.noBackupFilesDir, "$name.bin")
    val alias = "${context.packageName}.$name.aes-gcm"
    return EncryptedConfigStore(AndroidConfigFile(context, name, file), AndroidConfigKeys(alias), alias,
        configLocks.computeIfAbsent(file.canonicalPath) { Any() })
}

// The app and WorkManager use one Android process. All instances share the same transaction lock.
private val configLocks = ConcurrentHashMap<String, Any>()

private class AndroidConfigFile(private val context: Context, private val name: String, private val file: File) :
    EncryptedConfigStore.ConfigFile {
    private val atomic = AtomicFile(file)
    override fun hasObsoleteState(): Boolean {
        val previous = File(context.dataDir, "shared_prefs/$name.xml")
        return previous.exists() || File("${previous.path}.bak").exists()
    }
    override fun hasBoundClientState(): Boolean {
        if (name != MobileContractV1.PREFERENCES_FILE) return false
        val databases = File(context.dataDir, "databases")
        if (!databases.exists()) return false
        val profiles = checkNotNull(databases.listFiles()) { "无法检查已有实例备份记录" }
        // An unpaired first gallery visit can create only this provisional catalogue.
        val unpaired = provisionalProfileKey("", "")
        fun hasDatabase(directory: File): Boolean = listOf("", "-wal", "-shm").any { suffix ->
            File(directory, MobileContractV1.DATABASE_FILENAME + suffix).exists()
        }
        return hasDatabase(databases) || profiles.any {
            it.name != unpaired && it.name.matches(Regex("[0-9a-f]{64}")) && hasDatabase(it)
        }
    }
    override fun read(): ByteArray? {
        try {
            return atomic.openRead().use { input ->
                val bytes = input.readNBytes(EncryptedConfigStore.MAX_FILE_SIZE + 1)
                check(bytes.size <= EncryptedConfigStore.MAX_FILE_SIZE) { "本地配置文件过大，原数据已保留" }
                bytes
            }
        } catch (failure: FileNotFoundException) {
            check(!file.exists() && !File("${file.path}.new").exists() && !File("${file.path}.bak").exists()) {
                "无法读取本地凭据或发现未完成写入，原数据已保留"
            }
            return null
        }
    }

    override fun write(bytes: ByteArray) {
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw ConfigStorageException("本地配置写入失败，已保留先前快照；请检查应用存储", failure)
        }
        // finishWrite publishes the new file. Never invoke rollback after this point:
        // a rename or verification failure can leave the commit result unknown.
        try {
            atomic.finishWrite(output)
            check(read()?.contentEquals(bytes) == true)
        } catch (failure: Exception) {
            throw ConfigStorageException("无法确认本地配置保存结果；请重新读取实际状态，原数据未被主动清除", failure)
        }
    }
}

private class AndroidConfigKeys(private val alias: String) : EncryptedConfigStore.ConfigKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun existing(): SecretKey? {
        val store = store()
        if (!store.containsAlias(alias)) return null
        return store.getKey(alias, null) as? SecretKey ?: error("本地配置密钥类型无效，已有密钥已保留")
    }
    override fun create(): SecretKey {
        check(!store().containsAlias(alias)) { "本地配置密钥已存在，不能覆盖" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
}
