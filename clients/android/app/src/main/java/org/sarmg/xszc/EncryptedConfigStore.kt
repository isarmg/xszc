package org.sarmg.xszc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ConfigStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A single encrypted snapshot; the platform adapter owns atomic file replacement and key custody. */
internal class EncryptedConfigStore(
    private val file: ConfigFile,
    private val keys: ConfigKeys,
    private val identity: String,
    private val lock: Any,
) {
    internal interface ConfigFile {
        fun read(): ByteArray?
        fun write(bytes: ByteArray)
        fun hasObsoleteState(): Boolean = false
        fun hasBoundClientState(): Boolean = false
    }
    internal interface ConfigKeys {
        fun existing(): SecretKey?
        fun create(): SecretKey
    }

    fun <T> read(block: (ConfigValues) -> T): T = synchronized(lock) { storageOperation { block(load().values) } }

    fun update(block: (ConfigValues) -> Unit) = synchronized(lock) {
        val loaded = storageOperation { load() }
        val values = loaded.values
        block(values)
        storageOperation {
            // Validate and serialize before creating a persistent key or touching the file.
            val plaintext = encode(values.entries)
            val key = loaded.key ?: keys.create()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == IV_SIZE) { "平台返回了不支持的配置加密参数" }
            cipher.updateAAD(aad())
            val bytes = HEADER + cipher.iv + cipher.doFinal(plaintext)
            file.write(bytes)
        }
    }

    private fun <T> storageOperation(block: () -> T): T = try { block() }
        catch (failure: ConfigStorageException) { throw failure }
        catch (failure: Exception) { throw ConfigStorageException("本地配置存储失败：${failure.message ?: "请检查应用存储和系统密钥"}", failure) }

    private data class Loaded(val values: ConfigValues, val key: SecretKey?)

    private fun load(): Loaded {
        check(!file.hasObsoleteState()) { "本地凭据不是当前格式；请保留原数据，确认当前开发版本需要显式新安装并重新配对" }
        val bytes = file.read()
        val key = keys.existing()
        if (bytes == null) {
            check(key == null) { "本地凭据文件缺失，已有密钥被保留；请检查应用数据，不能自动重建身份" }
            check(!file.hasBoundClientState()) { "本地凭据缺失，但已有实例备份记录；原任务已保留，不能自动重建身份" }
            return Loaded(ConfigValues(), null)
        }
        check(bytes.size in (HEADER.size + IV_SIZE + TAG_SIZE)..MAX_FILE_SIZE &&
            bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            "本地凭据格式无效，原数据已保留"
        }
        check(key != null) { "本地凭据密钥缺失，原数据已保留，不能自动重建身份" }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_SIZE * 8, bytes.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)))
            cipher.updateAAD(aad())
            cipher.doFinal(bytes, HEADER.size + IV_SIZE, bytes.size - HEADER.size - IV_SIZE)
        } catch (failure: Exception) {
            throw IllegalStateException("无法解密本地凭据，原数据与密钥已保留", failure)
        }
        return Loaded(ConfigValues(decode(plaintext)), key)
    }

    private fun aad(): ByteArray = HEADER + identity.toByteArray(Charsets.UTF_8)

    private fun encode(values: Map<String, String>): ByteArray {
        require(values.size <= MAX_ENTRIES) { "本地配置条目过多" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(values.size)
            values.toSortedMap().forEach { (key, value) ->
                for (part in listOf(key, value)) {
                    val bytes = part.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_FILE_SIZE) { "本地配置过大" }
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                }
            }
        }
        return output.toByteArray().also {
            require(it.size + HEADER.size + IV_SIZE + TAG_SIZE <= MAX_FILE_SIZE) { "本地配置过大" }
        }
    }

    private fun decode(bytes: ByteArray): MutableMap<String, String> {
        try {
            val values = linkedMapOf<String, String>()
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val count = input.readInt()
                check(count in 0..MAX_ENTRIES)
                repeat(count) {
                    fun readString(): String {
                        val length = input.readInt()
                        check(length in 0..input.available())
                        val raw = ByteArray(length)
                        input.readFully(raw)
                        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                    }
                    val key = readString()
                    check(!values.containsKey(key))
                    values[key] = readString()
                }
                check(input.available() == 0)
            }
            return values
        } catch (failure: Exception) {
            throw IllegalStateException("本地配置内容无效，原数据已保留", failure)
        }
    }

    companion object {
        private val HEADER = "XSZC-CONFIG-V1\n".toByteArray(Charsets.US_ASCII)
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        internal const val MAX_FILE_SIZE = 1024 * 1024
        private const val MAX_ENTRIES = 256
    }
}

internal class ConfigValues(internal val entries: MutableMap<String, String> = linkedMapOf()) {
    fun string(key: String, default: String = ""): String = entries[key] ?: default
    fun boolean(key: String, default: Boolean): Boolean = entries[key]?.toBooleanStrict() ?: default
    fun long(key: String, default: Long = 0): Long = entries[key]?.toLong() ?: default
    fun int(key: String, default: Int = 0): Int = entries[key]?.toInt() ?: default
    fun set(key: String, value: Any) { entries[key] = value.toString() }
    fun remove(key: String) { entries.remove(key) }
}
