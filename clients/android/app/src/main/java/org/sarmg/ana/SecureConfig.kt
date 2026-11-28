package org.sarmg.ana

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class BackupSnapshot(
    val state: String = "idle",
    val message: String = "尚未开始备份",
    val lastRunAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val discovered: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val uploadedBytes: Long = 0L,
    val currentItem: String = "",
)

class SecureConfig internal constructor(context: Context, name: String) {
    constructor(context: Context) : this(context, MobileContractV1.PREFERENCES_FILE)
    private val store = try { androidConfigStore(context, name) }
        catch (failure: Exception) { throw ConfigStorageException("无法打开应用私有配置目录，请检查存储状态", failure) }

    init { store.read { } }

    data class Connection(val server: String, val username: String, val token: String,
        val accountId: String, val deviceId: String, val profile: String)

    data class RememberedDevice(val server: String, val accountId: String, val deviceId: String)

    data class BackupPreferences(val auto: Boolean, val wifi: Boolean, val charging: Boolean,
        val photos: Boolean, val videos: Boolean, val camera: Boolean, val albums: Set<String>)
    data class SettingsSnapshot(val connection: Connection, val preferences: BackupPreferences)

    private fun albums(values: ConfigValues): Set<String> = try {
        val array = JSONArray(values.string("selected_album_ids", "[]"))
        buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
    } catch (failure: Exception) { throw ConfigStorageException("本地相册配置无效，原数据已保留", failure) }

    fun settingsSnapshot(): SettingsSnapshot = store.read {
        try {
            SettingsSnapshot(connection(it), BackupPreferences(it.boolean("auto_backup", false),
                it.boolean("wifi_only", true), it.boolean("charging_only", false), it.boolean("backup_medias", true),
                it.boolean("backup_videos", true), it.boolean("camera_only", true), albums(it)))
        } catch (failure: ConfigStorageException) { throw failure }
        catch (failure: Exception) { throw ConfigStorageException("本地备份设置无效，原数据已保留", failure) }
    }

    private fun profile(values: ConfigValues): String =
        if (values.string(MobileContractV1.TOKEN_KEY).isBlank()) unpairedProfileKey()
        else values.string("queue_profile", unpairedProfileKey())

    private fun connection(values: ConfigValues): Connection =
        Connection(values.string("server_url"), values.string("account_username_v1"), values.string(MobileContractV1.TOKEN_KEY),
            values.string("account_id"), values.string("device_id"), profile(values))

    val profile: String get() = store.read(::profile)
    fun connection(): Connection = store.read(::connection)

    private fun accountKey(server: String, username: String): String {
        val origin = BackupApi.serverOrigin(server)
        return "${origin.scheme}://${origin.host}:${origin.port}\n${username.lowercase(Locale.ROOT)}"
    }

    private fun deviceHistory(values: ConfigValues) = JSONObject(values.string("account_devices_v1", "{}"))

    private fun rememberDevice(history: JSONObject, username: String, device: RememberedDevice) {
        if (device.server.isBlank() || device.accountId.isBlank() || device.deviceId.isBlank()) return
        history.put(accountKey(device.server, username), JSONObject().put("server", device.server)
            .put("account_id", device.accountId).put("device_id", device.deviceId))
    }

    fun rememberedDevice(server: String, username: String): RememberedDevice? = store.read { values ->
        val entry = deviceHistory(values).optJSONObject(accountKey(server, username))
        entry?.let { RememberedDevice(it.getString("server"), it.getString("account_id"), it.getString("device_id")) }
    }

    private val backupKeys = listOf("auto_backup", "wifi_only", "charging_only", "backup_medias", "backup_videos",
        "camera_only", "selected_album_ids", "backup_state",
        "backup_message", "last_run_at", "last_success_at", "last_discovered", "last_completed", "last_failed",
        "last_uploaded_bytes", "current_item")

    private fun switchBackupSettings(values: ConfigValues, nextProfile: String) {
        val previous = profile(values)
        if (previous == nextProfile) return
        val history = JSONObject(values.string("account_backup_settings_v1", "{}"))
        val current = JSONObject()
        val next = history.optJSONObject(nextProfile)
        for (key in backupKeys) {
            values.entries[key]?.let { current.put(key, it) }
            if (next != null && next.has(key)) values.set(key, next.getString(key)) else values.remove(key)
        }
        history.put(previous, current)
        history.remove(nextProfile)
        values.set("account_backup_settings_v1", history.toString())
    }

    fun saveAuthenticatedConnection(expected: Connection, server: String, username: String,
        api: BackupApi, token: String, storageProfile: String) = store.update { values ->
        check(connection(values) == expected) { "账户已改变，请重新登录" }
        check(token.isNotBlank()) { "服务器没有返回有效登录凭据" }
        require(storageProfile.matches(Regex("[0-9a-f]{64}")))
        val history = deviceHistory(values)
        rememberDevice(history, username, RememberedDevice(server, api.accountId, api.deviceId))
        values.set("account_devices_v1", history.toString())
        switchBackupSettings(values, storageProfile)
        values.set("queue_profile", storageProfile)
        values.set("server_url", server)
        values.set("account_username_v1", username)
        values.set("account_id", api.accountId)
        values.set("device_id", api.deviceId)
        values.set(MobileContractV1.TOKEN_KEY, token)
    }

    fun logout(expected: Connection? = null) = store.update {
        check(expected == null || connection(it) == expected) { "账户已改变，请重新操作" }
        switchBackupSettings(it, unpairedProfileKey())
        it.remove(MobileContractV1.TOKEN_KEY)
        it.remove("queue_profile")
        it.remove("account_id")
        it.remove("device_id")
    }
    fun verifyWritable() = store.update { it.set("storage_verified", true) }

    fun saveBackupPreferences(auto: Boolean, wifi: Boolean, charging: Boolean, photos: Boolean,
        videos: Boolean, camera: Boolean, albums: Set<String>) = store.update {
        it.set("auto_backup", auto)
        it.set("wifi_only", wifi)
        it.set("charging_only", charging)
        it.set("backup_medias", photos)
        it.set("backup_videos", videos)
        it.set("camera_only", camera)
        val array = JSONArray()
        albums.sorted().forEach(array::put)
        it.set("selected_album_ids", array.toString())
    }

    fun snapshot(): BackupSnapshot = store.read {
        BackupSnapshot(state = it.string("backup_state", "idle"),
            message = it.string("backup_message", "尚未开始备份"), lastRunAt = it.long("last_run_at"),
            lastSuccessAt = it.long("last_success_at"), discovered = it.int("last_discovered"),
            completed = it.int("last_completed"), failed = it.int("last_failed"),
            uploadedBytes = it.long("last_uploaded_bytes"), currentItem = it.string("current_item"))
    }

    fun saveSnapshot(snapshot: BackupSnapshot, expected: Connection? = null) = store.update {
        if (expected != null && connection(it) != expected) {
            throw kotlinx.coroutines.CancellationException("账户已退出或切换")
        }
        it.set("backup_state", snapshot.state)
        it.set("backup_message", snapshot.message)
        it.set("last_run_at", snapshot.lastRunAt)
        it.set("last_success_at", snapshot.lastSuccessAt)
        it.set("last_discovered", snapshot.discovered)
        it.set("last_completed", snapshot.completed)
        it.set("last_failed", snapshot.failed)
        it.set("last_uploaded_bytes", snapshot.uploadedBytes)
        it.set("current_item", snapshot.currentItem)
    }
}

internal fun <T> readConfiguration(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (failure: ConfigStorageException) { Result.failure(failure) }
