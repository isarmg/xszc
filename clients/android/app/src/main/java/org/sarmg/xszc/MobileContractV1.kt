package org.sarmg.xszc

import org.json.JSONObject

object MobileContractV1 {
    const val PRODUCT = "xszc"
    const val APPLICATION_VERSION = "1.0.0"
    const val REVISION = 1
    const val STATE_EPOCH = "xszc-mobile-v1"

    const val DATABASE_FILENAME = "client-v1.sqlite"
    const val STAGING_DIRECTORY = "backup-staging-v1"
    const val PREFERENCES_FILE = "xszc_secure_v1"
    const val TOKEN_KEY = "bearer_token_v1"
    const val WORK_TAG = "xszc-v1"
    const val NOW_WORK = "xszc-now-v1"
    const val PERIODIC_WORK = "xszc-periodic-v1"
    const val NOTIFICATION_CHANNEL = "xszc-v1"

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
        require(keys == allowed) { "Rust Client returned an unknown current envelope shape" }
        require(envelope.getString("product") == PRODUCT) { "Rust Client product mismatch" }
        require(envelope.getString("application_version") == APPLICATION_VERSION) {
            "Rust Client application version mismatch"
        }
        require(envelope.getInt("revision") == REVISION) { "Rust Client revision mismatch" }
        require(envelope.getString("state_epoch") == STATE_EPOCH) { "Rust Client state epoch mismatch" }
        if (!envelope.getBoolean("ok")) {
            error(envelope.optString("error", "Rust Client operation failed"))
        }
        return envelope
    }

    fun requireIdentity(value: JSONObject) {
        require(value.getString("product") == PRODUCT) { "Rust Client job product mismatch" }
        require(value.getString("application_version") == APPLICATION_VERSION) {
            "Rust Client job application version mismatch"
        }
        require(value.getInt("revision") == REVISION) { "Rust Client job revision mismatch" }
        require(value.getString("state_epoch") == STATE_EPOCH) { "Rust Client job state epoch mismatch" }
    }
}
