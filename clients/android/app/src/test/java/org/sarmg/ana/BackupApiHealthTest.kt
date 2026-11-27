package org.sarmg.ana

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupApiHealthTest {
    private fun api(status: Int, url: String = "https://backup.example.test", inspect: (Request) -> Unit = {}): BackupApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Test response")
                .body("".toResponseBody())
                .build()
        }.build()
        return BackupApi(url, "test-device-token", client)
    }

    @Test
    fun usesCanonicalAnonymousHealthEndpoint() {
        assertTrue(api(204) { request ->
            assertEquals("https://backup.example.test/healthz", request.url.toString())
            assertEquals("GET", request.method)
            assertNull(request.header("Authorization"))
        }.health())
    }

    @Test
    fun acceptsServerAddressWithTrailingSlash() {
        assertTrue(api(204, "https://backup.example.test/") { request ->
            assertEquals("/healthz", request.url.encodedPath)
        }.health())
    }

    @Test
    fun rejectsWrongEndpointAndUnhealthyResponses() {
        for (status in listOf(200, 302, 404, 503)) {
            val error = assertThrows(IllegalStateException::class.java) { api(status).health() }
            assertTrue(error.message.orEmpty().contains(status.toString()))
        }
    }
}
