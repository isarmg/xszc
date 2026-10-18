package org.sarmg.xszc

import android.content.Context
import org.json.JSONArray

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

    data class Connection(val server: String, val authorizationCode: String, val token: String,
        val accountId: String, val deviceId: String, val profile: String = provisionalProfileKey(server, authorizationCode))

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

    private fun profile(values: ConfigValues): String = values.entries["queue_profile_v04"]
        ?: provisionalProfileKey(values.string("server_url"), values.string("authorization_code"))
    private fun connection(values: ConfigValues, saved: Boolean = false): Connection {
        val active = saved || !values.boolean("account_signed_out", false)
        return Connection(values.string("server_url"), if (active) values.string("authorization_code") else "",
            if (active) values.string(MobileContractV1.TOKEN_KEY) else "", values.string("account_id_v04"),
            values.string("device_id_v04"), profile(values))
    }

    val profile: String get() = store.read(::profile)
    fun connection(): Connection = store.read { connection(it) }
    fun savedConnection(): Connection = store.read { connection(it, saved = true) }

    private fun setCredentials(values: ConfigValues, server: String, code: String) {
        val normalizedServer = server.trim().trimEnd('/')
        val normalizedCode = code.trim()
        if (normalizedServer != values.string("server_url") || normalizedCode != values.string("authorization_code")) {
            values.remove(MobileContractV1.TOKEN_KEY)
            values.remove("queue_profile_v04")
        }
        values.set("server_url", normalizedServer)
        values.set("authorization_code", normalizedCode)
    }
    fun saveCredentials(server: String, code: String) = store.update { setCredentials(it, server, code) }

    fun saveAuthenticatedConnection(expected: Connection, server: String, code: String,
        api: BackupApi, token: String, storageProfile: String) = store.update { values ->
        check(connection(values) == expected) { "配对信息已改变，请重新配对" }
        check(token.isNotBlank()) { "服务器没有返回有效配对凭据" }
        require(storageProfile.matches(Regex("[0-9a-f]{64}")))
        values.set("queue_profile_v04", storageProfile)
        values.set("server_url", server.trim().trimEnd('/'))
        values.set("authorization_code", code.trim())
        values.set("account_id_v04", api.accountId)
        values.set("device_id_v04", api.deviceId)
        values.set(MobileContractV1.TOKEN_KEY, token)
        values.set("account_signed_out", false)
    }

    val isLoggedIn: Boolean get() = store.read {
        !it.boolean("account_signed_out", false) && it.string("authorization_code").isNotBlank() &&
            it.string(MobileContractV1.TOKEN_KEY).isNotBlank()
    }
    val savedAuthorizationCode: String get() = store.read { it.string("authorization_code") }
    fun logout() = store.update { it.set("account_signed_out", true) }
    fun verifyWritable() = store.update { it.set("storage_verified", true) }

    var serverUrl: String
        get() = store.read { it.string("server_url") }
        set(value) = store.update { setCredentials(it, value, it.string("authorization_code")) }
    var authorizationCode: String
        get() = store.read { if (it.boolean("account_signed_out", false)) "" else it.string("authorization_code") }
        set(value) = store.update { setCredentials(it, it.string("server_url"), value) }
    var bearerToken: String
        get() = store.read { if (it.boolean("account_signed_out", false)) "" else it.string(MobileContractV1.TOKEN_KEY) }
        set(value) = store.update { it.set(MobileContractV1.TOKEN_KEY, value) }
    var accountId: String
        get() = store.read { it.string("account_id_v04") }
        set(value) = store.update { it.set("account_id_v04", value) }
    var deviceId: String
        get() = store.read { it.string("device_id_v04") }
        set(value) = store.update { it.set("device_id_v04", value) }
    var autoBackup: Boolean
        get() = store.read { it.boolean("auto_backup", false) }
        set(value) = store.update { it.set("auto_backup", value) }
    var wifiOnly: Boolean
        get() = store.read { it.boolean("wifi_only", true) }
        set(value) = store.update { it.set("wifi_only", value) }
    var chargingOnly: Boolean
        get() = store.read { it.boolean("charging_only", false) }
        set(value) = store.update { it.set("charging_only", value) }
    var backupPhotos: Boolean
        get() = store.read { it.boolean("backup_medias", true) }
        set(value) = store.update { it.set("backup_medias", value) }
    var backupVideos: Boolean
        get() = store.read { it.boolean("backup_videos", true) }
        set(value) = store.update { it.set("backup_videos", value) }
    var cameraOnly: Boolean
        get() = store.read { it.boolean("camera_only", true) }
        set(value) = store.update { it.set("camera_only", value) }
    var selectedAlbumIds: Set<String>
        get() = store.read(::albums)
        set(value) = store.update {
            val array = JSONArray()
            value.sorted().forEach(array::put)
            it.set("selected_album_ids", array.toString())
            it.set("album_selection_configured", true)
        }
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
        it.set("album_selection_configured", true)
    }
    val albumSelectionConfigured: Boolean
        get() = store.read { it.boolean("album_selection_configured", false) }
    var librarySyncSequence: Long
        get() = store.read { it.long("library_sync_sequence") }
        set(value) = store.update { it.set("library_sync_sequence", value) }

    fun snapshot(): BackupSnapshot = store.read {
        BackupSnapshot(state = it.string("backup_state", "idle"),
            message = it.string("backup_message", "尚未开始备份"), lastRunAt = it.long("last_run_at"),
            lastSuccessAt = it.long("last_success_at"), discovered = it.int("last_discovered"),
            completed = it.int("last_completed"), failed = it.int("last_failed"),
            uploadedBytes = it.long("last_uploaded_bytes"), currentItem = it.string("current_item"))
    }

    fun saveSnapshot(snapshot: BackupSnapshot) = store.update {
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
