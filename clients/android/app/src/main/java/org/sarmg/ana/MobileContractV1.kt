package org.sarmg.ana

import org.json.JSONObject

object MobileContractV1 {
    const val PRODUCT = "ana"
    const val APPLICATION_VERSION = "1.0.0"
    const val REVISION = 1
    const val STATE_EPOCH = "ana-mobile-v1"

    const val DATABASE_FILENAME = "client-v1.sqlite"
    const val STAGING_DIRECTORY = "backup-staging-v1"
    const val PREFERENCES_FILE = "ana_secure_v1"
    const val TOKEN_KEY = "bearer_token_v1"
    const val WORK_TAG = "ana-v1"
    const val NOW_WORK = "ana-now-v1"
    const val PERIODIC_WORK = "ana-periodic-v1"
    const val NOTIFICATION_CHANNEL = "ana-v1"

    fun putIdentity(target: JSONObject): JSONObject = target
        .put("product", PRODUCT)
        .put("application_version", APPLICATION_VERSION)
        .put("revision", REVISION)
        .put("state_epoch", STATE_EPOCH)

    fun requireEnvelope(raw: String): JSONObject {
        val envelope = JSONObject(raw)
        val allowed = setOf(
            "product",
            "application_version",
            "revision",
            "state_epoch",
            "ok",
            "value",
            "error",
        )
        val keys = mutableSetOf<String>()
        val iterator = envelope.keys()
        while (iterator.hasNext()) keys += iterator.next()
        require(keys == allowed) { "ana 原生模块返回的响应字段与当前接口不匹配" }
        require(envelope.getString("product") == PRODUCT) { "ana 原生模块的产品标识不匹配" }
        require(envelope.getString("application_version") == APPLICATION_VERSION) {
            "ana 原生模块的应用版本不匹配"
        }
        require(envelope.getInt("revision") == REVISION) { "ana 原生模块的接口修订号不匹配" }
        require(envelope.getString("state_epoch") == STATE_EPOCH) { "ana 原生模块的状态版本不匹配" }
        if (!envelope.getBoolean("ok")) {
            error(envelope.optString("error", "ana 原生模块操作失败"))
        }
        return envelope
    }

    fun requireIdentity(value: JSONObject) {
        // Runtime metadata supplied by the server-owned protocol via Rust FFI.
        require(value.getString("upload_operation_header").isNotBlank()) { "ana 原生模块未提供上传请求头" }
        require(value.getString("product") == PRODUCT) { "ana 任务的产品标识不匹配" }
        require(value.getString("application_version") == APPLICATION_VERSION) {
            "ana 任务的应用版本不匹配"
        }
        require(value.getInt("revision") == REVISION) { "ana 任务的接口修订号不匹配" }
        require(value.getString("state_epoch") == STATE_EPOCH) { "ana 任务的状态版本不匹配" }
    }
}
