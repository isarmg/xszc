package org.sarmg.ana

import org.json.JSONObject

/** /v1/albums limits both the encoded UTF-8 body and the number of identifiers. */
internal object AlbumSyncBatch {
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_ASSETS = 10_000

    fun bodies(albumId: String, name: String, assetIds: Set<String>, replaceMembers: Boolean): List<String> {
        val metadata = "{\"source_album_id\":" + JSONObject.quote(albumId) +
            ",\"name\":" + JSONObject.quote(name) + ",\"replace_members\":"
        fun prefix(first: Boolean) = metadata + (replaceMembers && first) + ",\"source_asset_ids\":["
        val suffix = "]}"
        val batches = mutableListOf<String>()
        var first = true
        var body = StringBuilder(prefix(first))
        var bytes = body.toString().toByteArray(Charsets.UTF_8).size + suffix.length
        var count = 0
        require(bytes <= MAX_BODY_BYTES) { "相册信息超过服务器请求大小上限" }
        for (assetId in assetIds.sorted()) {
            val encoded = JSONObject.quote(assetId)
            val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size
            if (count > 0 && (count == MAX_ASSETS || bytes.toLong() + encodedBytes + 1 > MAX_BODY_BYTES)) {
                batches += body.append(suffix).toString()
                first = false
                body = StringBuilder(prefix(first))
                bytes = body.toString().toByteArray(Charsets.UTF_8).size + suffix.length
                count = 0
            }
            val extra = encodedBytes.toLong() + if (count == 0) 0 else 1
            require(bytes.toLong() + extra <= MAX_BODY_BYTES) { "单项相册标识超过服务器请求大小上限" }
            if (count > 0) body.append(',')
            body.append(encoded)
            bytes += extra.toInt()
            count++
        }
        batches += body.append(suffix).toString()
        return batches
    }

    fun <T> send(albumId: String, name: String, assetIds: Set<String>, replaceMembers: Boolean,
        sendBody: (String) -> T): T {
        // Validate every body before the first replacement request changes membership.
        val pending = bodies(albumId, name, assetIds, replaceMembers).iterator()
        var response = sendBody(pending.next())
        while (pending.hasNext()) response = sendBody(pending.next())
        return response
    }
}
