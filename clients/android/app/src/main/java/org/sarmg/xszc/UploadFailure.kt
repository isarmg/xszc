package org.sarmg.xszc

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal class UploadHttpFailure(
    val status: Int,
    val code: String,
    val retryable: Boolean,
    val retryAfterMs: Long?,
    explanation: String? = null,
) : IOException(explanation ?: if (status == 401 || status == 403)
    "上传授权被拒绝（HTTP $status，$code）；请检查配对凭据或服务端权限"
    else if (retryable) "上传暂时失败（HTTP $status，$code）"
    else "上传被服务端拒绝（HTTP $status，$code）；请检查文件和服务端配置") {
    companion object {
        fun parse(status: Int, text: String, retryAfter: String?, nowMs: Long = System.currentTimeMillis()): UploadHttpFailure {
            val body = runCatching { JSONObject(text) }.getOrNull()
            val code = body?.optString("code")?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) }
                ?: "http_$status"
            val retryable = (body?.opt("retryable") as? Boolean)
                ?: (status == 408 || status == 425 || status == 429 || status >= 500)
            val wait = retryAfter?.trim()?.let { value ->
                if (value.matches(Regex("[0-9]+"))) {
                    val seconds = value.toLongOrNull() ?: Long.MAX_VALUE
                    if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
                } else runCatching {
                    (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs).coerceAtLeast(0)
                }.getOrNull()
            }
            val explanation = if (status == 400 && code == "bad_request" &&
                body?.optString("message") == "asset cannot have more than 16 tags")
                "每项媒体最多添加16个标签，请先移除一个标签" else null
            return UploadHttpFailure(status, code, retryable, wait, explanation)
        }
    }
}

internal data class UploadFailurePolicy(val retryable: Boolean, val retryAfterMs: Long? = null)
internal fun uploadFailurePolicy(error: Exception): UploadFailurePolicy = when (error) {
    is UploadHttpFailure -> UploadFailurePolicy(error.retryable, error.retryAfterMs)
    is kotlinx.coroutines.CancellationException -> UploadFailurePolicy(true)
    is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLPeerUnverifiedException,
    is java.net.UnknownServiceException -> UploadFailurePolicy(false)
    is IOException -> UploadFailurePolicy(true)
    else -> UploadFailurePolicy(false)
}

