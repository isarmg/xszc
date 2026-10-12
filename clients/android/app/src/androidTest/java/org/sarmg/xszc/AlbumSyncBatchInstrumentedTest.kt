package org.sarmg.xszc

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's actual JSON encoding and intercepted requests, without a network connection. */
@RunWith(AndroidJUnit4::class)
class AlbumSyncBatchInstrumentedTest {
    private fun assets(count: Int = 20_001) = (0 until count)
        .map { "content://media/external/images/media/$it" }.toSet()

    private fun verify(ids: Set<String>, name: String = "相册 📷 \"旅行\"", replace: Boolean = true): List<String> {
        // Independent receiver contract from xszs routes.rs, not just the helper's budget.
        assertEquals(256 * 1024, AlbumSyncBatch.MAX_BODY_BYTES)
        val bodies = AlbumSyncBatch.bodies("album/中文", name, ids, replace)
        val received = mutableListOf<String>()
        for ((index, body) in bodies.withIndex()) {
            assertTrue(body.toByteArray(Charsets.UTF_8).size <= AlbumSyncBatch.MAX_BODY_BYTES)
            val value = JSONObject(body)
            assertEquals("album/中文", value.getString("source_album_id"))
            assertEquals(name, value.getString("name"))
            assertEquals(replace && index == 0, value.getBoolean("replace_members"))
            val chunk = value.getJSONArray("source_asset_ids")
            assertTrue(chunk.length() <= AlbumSyncBatch.MAX_ASSETS)
            for (position in 0 until chunk.length()) received += chunk.getString(position)
        }
        assertEquals(ids.sorted(), received)
        assertEquals(ids.size, received.toSet().size)
        return bodies
    }

    @Test fun realIdentifiersPreserveCompleteMembershipAcrossByteBoundaries() {
        assertTrue(verify(assets()).size > 2)
        verify(assets(10_001), replace = false)
    }

    @Test fun unicodeEscapesAndLongIdentifiersUseActualUtf8Bytes() {
        val ids = (0 until 12).map { "$it/" + "照片📷\"\\\n".repeat(3000) }.toSet()
        assertTrue(verify(ids).size > 1)
    }

    @Test fun emptyAlbumStillSendsOneReplacementAndSmallIdsRespectCountLimit() {
        assertEquals(1, verify(emptySet()).size)
        assertEquals(1, verify(emptySet(), replace = false).size)
        assertEquals(2, verify((0..10_000).map(Int::toString).toSet()).size)
    }

    @Test fun oversizedSingleIdOrMetadataIsRejectedBeforeAnyRequest() {
        var requests = 0
        val invalid = assets() + ("z" + "📷".repeat(100_000))
        assertThrows(IllegalArgumentException::class.java) {
            AlbumSyncBatch.send("album", "Camera", invalid, true) { requests++ }
        }
        assertEquals(0, requests)
        assertThrows(IllegalArgumentException::class.java) {
            AlbumSyncBatch.send("album", "相册".repeat(100_000), emptySet(), true) { requests++ }
        }
        assertEquals(0, requests)
    }

    @Test fun exactByteBoundaryIsAcceptedAndNextByteStartsAnotherBatch() {
        val overhead = AlbumSyncBatch.bodies("album/中文", "Camera", emptySet(), true).single()
            .toByteArray(Charsets.UTF_8).size
        val id = "a".repeat(AlbumSyncBatch.MAX_BODY_BYTES - overhead - 2)
        val bodies = verify(setOf(id, "z"), name = "Camera")
        assertEquals(2, bodies.size)
        assertEquals(AlbumSyncBatch.MAX_BODY_BYTES, bodies.first().toByteArray(Charsets.UTF_8).size)
    }

    @Test fun httpFailureStopsBeforeSendingLaterAppendBatches() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            assertEquals("/v1/albums", request.url.encodedPath)
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertTrue(buffer.size <= AlbumSyncBatch.MAX_BODY_BYTES)
            assertEquals(requests == 1, JSONObject(buffer.readUtf8()).getBoolean("replace_members"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (requests == 2) 503 else 200).message("Synthetic album response")
                .header("Retry-After", "2")
                .body("{}".toResponseBody()).build()
        }.build()
        val failure = assertThrows(UploadHttpFailure::class.java) {
            BackupApi("https://backup.example.test", "test-token", client)
                .syncAlbum("album", "Camera", assets(), true)
        }
        assertEquals(503, failure.status)
        assertEquals("http_503", failure.code)
        assertTrue(failure.retryable)
        assertEquals(2_000L, failure.retryAfterMs)
        assertEquals(2, requests)
    }
}
