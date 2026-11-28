package org.sarmg.ana

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// Keep the existing local gallery directory used before login.
internal fun signedOutProfileKey(): String =
    "b3aba28d35866a66799b23731f20096d5837e799b6b8933a433e3d7b34c7b524"

internal fun deviceProfileKey(server: String, deviceId: String): String = MessageDigest.getInstance("SHA-256")
    .digest("ana-instance-v1\n${server.trim().trimEnd('/')}\n${java.util.UUID.fromString(deviceId)}".toByteArray())
    .joinToString("") { "%02x".format(it) }

/** One native handle per profile in this process. Reopening must not reset a live upload. */
internal object TransferStore {
    data class Session(val handle: Long, val paths: NativeStorage.Paths, val profile: String)
    private val sessions = mutableMapOf<String, Session>()

    @Synchronized fun open(context: Context, profile: String): Session = sessions.getOrPut(profile) {
        val paths = NativeStorage.prepare(context, profile)
        val handle = try { NativeBridgeV1.open(paths.database.path,
            MobileContractV1.putIdentity(JSONObject()).put("part_size", 16 * 1024 * 1024).toString()) }
        catch (error: Exception) { throw IllegalStateException("打开手机备份数据库失败：${error.message}", error) }
        check(handle != 0L) { "无法打开备份记录" }
        Session(handle, paths, profile)
    }

    /** A refreshed device session reopens its queue; confirm both the account and device. */
    fun bindAccount(context: Context, server: String, accountId: String, deviceId: String): Session {
        val target = open(context, deviceProfileKey(server, deviceId))
        val binding = try { command(target.handle, "binding") as? JSONObject }
        catch (error: Exception) { throw IllegalStateException("读取本地账户绑定失败：${error.message}", error) }
        check(binding == null || (binding.getString("server") == server &&
            java.util.UUID.fromString(binding.getString("account_id")) == java.util.UUID.fromString(accountId) &&
            java.util.UUID.fromString(binding.getString("device_id")) == java.util.UUID.fromString(deviceId))) {
            "服务器、账户或设备与本地备份记录不符。原备份队列已保留"
        }
        try { command(target.handle, "bind", JSONObject().put("server", server)
            .put("account_id", accountId).put("device_id", deviceId)) }
        catch (error: Exception) { throw IllegalStateException("服务器认证成功，但本地绑定失败：${error.message}", error) }
        return target
    }

    fun command(handle: Long, op: String, fields: JSONObject = JSONObject()): Any? {
        fields.put("op", op)
        return MobileContractV1.requireEnvelope(NativeBridgeV1.transfer(handle,
            MobileContractV1.putIdentity(JSONObject()).put("command", fields).toString())).opt("value")
    }
    fun gallery(handle: Long, op: String, fields: JSONObject = JSONObject()): Any? =
        command(handle, "gallery", JSONObject().put("command", fields.put("op", op)))
    fun batches(handle: Long) = command(handle, "batches") as JSONArray
    fun clearCompletedBatch(handle: Long, batch: String) {
        command(handle, "clear_completed_batch", JSONObject().put("batch_id", batch))
    }
    fun items(handle: Long, batch: String) = command(handle, "items", JSONObject().put("batch_id", batch)) as JSONArray
    fun setItem(handle: Long, batch: String, item: String, state: String, error: String? = null) {
        command(handle, "set_item", JSONObject().put("batch_id", batch).put("item_id", item)
            .put("state", state).put("error", error ?: JSONObject.NULL))
    }
    fun receipt(handle: Long, job: JSONObject, result: JSONObject, completed: JSONObject? = null) {
        val request = job.getJSONObject("request")
        check(result.getString("source_asset_id") == request.getString("source_asset_id") &&
            result.getString("source_resource_id") == request.getString("source_resource_id") &&
            result.getString("content_blake3") == request.getString("content_blake3") &&
            result.getLong("content_size") == request.getLong("content_size") &&
            result.getString("storage_encoding") == request.getString("storage_encoding")) {
            "服务器回执与上传内容不一致"
        }
        if (completed != null) {
            check(completed.getString("asset_id") == result.getString("asset_id") &&
                completed.getString("resource_id") == result.getString("resource_id")) {
                "服务器完成响应与资源清单不一致"
            }
        }
        command(handle, "receipt", JSONObject().put("job_id", job.getString("job_id"))
            .put("asset_id", result.getString("asset_id")).put("resource_id", result.getString("resource_id"))
            .put("content_blake3", request.getString("content_blake3")))
        MobileContractV1.requireEnvelope(NativeBridgeV1.markComplete(handle, job.getString("job_id")))
    }
}
