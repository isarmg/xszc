package org.sarmg.ana

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class BackupApiResourceBoundaryTest {
    @Test fun onlyHttpsSameOriginResourcesAreAccepted() {
        val api = BackupApi("https://backup.example.test", "secret")
        assertEquals("https://backup.example.test/v1/resources/id/content", api.resolve("/v1/resources/id/content"))
        assertEquals("https://backup.example.test/a", api.resolve("https://backup.example.test/a"))
        for (url in listOf("http://backup.example.test/a", "https://evil.example/a", "//evil.example/a",
            "https://backup.example.test:444/a", "https://user:pass@backup.example.test/a")) {
            assertThrows(IllegalArgumentException::class.java) { api.resolve(url) }
        }
    }
    @Test fun streamingRejectsRedirectResponsesWithoutWritingTheirBody() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("backup.example.test", chain.request().url.host)
            assertEquals("/image", chain.request().url.encodedPath)
            assertEquals("Bearer secret", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302)
                .message("Moved").header("Location", "https://evil.example/content")
                .body("redirect response".toResponseBody()).build()
        }.build()
        val output = ByteArrayOutputStream()
        val api = BackupApi("https://backup.example.test/", "secret", client)
        assertThrows(IllegalStateException::class.java) { runBlocking { api.downloadCancellable("/image", output) } }
        assertEquals(1, requests)
        assertEquals(0, output.size())
    }
}
