package org.sarmg.xszc

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual JSON and HTTP request construction, with an in-memory server response. */
@RunWith(AndroidJUnit4::class)
class TagActionsInstrumentedTest {
    private fun asset(count: Int = 0) = RemoteAsset("photo", 1, "photo", false, false, false,
        (1..count).map { "tag-$it" }, emptyList())

    private fun api(requests: MutableList<String>, respond: (Request) -> Pair<Int, String>): BackupApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url.encodedPath}"
            val (status, body) = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Tag fixture").body(body.toResponseBody()).build()
        }.build()
        return BackupApi("https://backup.example.test", "fixture-token", client)
    }

    @Test fun existingTagCanBeAddedToAnotherPhotoWithoutCreatingItAgain() {
        val requests = mutableListOf<String>()
        api(requests) { request ->
            when (request.method + " " + request.url.encodedPath) {
                "GET /v1/tags" -> 200 to """[{"tag_id":"existing","name":"旅行"}]"""
                "POST /v1/tags/existing/assets/photo" -> 204 to ""
                else -> error("Unexpected duplicate tag creation")
            }
        }.addTag(asset(), " 旅行 ")
        assertEquals(listOf("GET /v1/tags", "POST /v1/tags/existing/assets/photo"), requests)
    }

    @Test fun fifteenthTagAllowsCreationAndAttachmentOfSixteenth() {
        val requests = mutableListOf<String>()
        api(requests) { request ->
            when (request.url.encodedPath) {
                "/v1/tags" -> if (request.method == "GET") 200 to "[]" else {
                    val body = Buffer(); request.body!!.writeTo(body)
                    assertEquals("new", JSONObject(body.readUtf8()).getString("name"))
                    201 to """{"tag_id":"created","name":"new"}"""
                }
                "/v1/tags/created/assets/photo" -> 204 to ""
                else -> error("Unexpected request")
            }
        }.addTag(asset(15), "new")
        assertEquals(3, requests.size)
    }

    @Test fun fullAndLegacyOverfullAssetsRejectNewTagsBeforeRequests() {
        for (count in listOf(16, 17)) {
            val requests = mutableListOf<String>()
            val api = api(requests) { error("Must not create an unused tag") }
            val error = assertThrows(IllegalStateException::class.java) { api.addTag(asset(count), "new") }
            assertTrue(error.message.orEmpty().contains("16"))
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun alreadyAttachedTagReachesIdempotentServerAtLimitAndEmptyInputIsIgnored() {
        val requests = mutableListOf<String>()
        val api = api(requests) { request ->
            if (request.method == "GET") 200 to """[{"tag_id":"existing","name":"tag-1"}]"""
            else {
                assertEquals("/v1/tags/existing/assets/photo", request.url.encodedPath)
                204 to ""
            }
        }
        api.addTag(asset(16), " tag-1 ")
        api.addTag(asset(17), "tag-1")
        assertEquals(4, requests.size)
        api.addTag(asset(), " \n ")
        assertEquals(4, requests.size)
    }

    @Test fun globalTagCountDoesNotLimitOnePhotosTags() {
        val requests = mutableListOf<String>()
        val tags = (1..20).joinToString(",", "[", "]") { """{"tag_id":"id-$it","name":"tag-$it"}""" }
        api(requests) { request ->
            if (request.method == "GET") 200 to tags else {
                assertEquals("/v1/tags/id-20/assets/photo", request.url.encodedPath)
                204 to ""
            }
        }.addTag(asset(), "tag-20")
        assertEquals(2, requests.size)
    }

    @Test fun exactCaseMatchUsesTheCorrectTag() {
        val requests = mutableListOf<String>()
        api(requests) { request ->
            if (request.method == "GET") 200 to """[{"tag_id":"upper","name":"Trip"},{"tag_id":"lower","name":"trip"}]""" else {
                assertEquals("/v1/tags/lower/assets/photo", request.url.encodedPath)
                204 to ""
            }
        }.addTag(asset(), "trip")
    }

    @Test fun serverLimitAfterStaleGalleryGetsUsefulMessage() {
        val requests = mutableListOf<String>()
        val api = api(requests) { request ->
            if (request.method == "GET") 200 to """[{"tag_id":"new","name":"new"}]"""
            else 400 to """{"code":"bad_request","message":"asset cannot have more than 16 tags","retryable":false}"""
        }
        val error = assertThrows(UploadHttpFailure::class.java) { api.addTag(asset(15), "new") }
        assertEquals("每项媒体最多添加16个标签，请先移除一个标签", error.message)
    }

    @Test fun failedTagListDoesNotBlindlyCreateDuplicates() {
        val requests = mutableListOf<String>()
        val api = api(requests) { 503 to "{}" }
        assertThrows(UploadHttpFailure::class.java) { api.addTag(asset(), "旅行") }
        assertEquals(listOf("GET /v1/tags"), requests)
    }
}
