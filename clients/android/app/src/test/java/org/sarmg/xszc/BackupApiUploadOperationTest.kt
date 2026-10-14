package org.sarmg.xszc

import java.io.IOException
import java.util.UUID
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupApiUploadOperationTest {
    @Test
    fun createRetriesSendStableGenerationOnActualRequests() {
        val oldGeneration = UUID.randomUUID().toString()
        val newGeneration = UUID.randomUUID().toString()
        val operations = mutableListOf<String?>()
        val manifest = "{\"source_asset_id\":\"photo\",\"source_resource_id\":\"original\"}"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/v1/uploads", request.url.encodedPath)
            assertEquals("Bearer fixture-token", request.header("Authorization"))
            assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
            val body = Buffer()
            request.body!!.writeTo(body)
            assertEquals(manifest, body.readUtf8())
            operations.add(request.header("X-Upload-Operation"))
            // Simulate a lost create response, without invoking Android's JSON stub.
            throw IOException("response lost")
        }.build()
        val api = BackupApi("https://backup.example.test", "fixture-token", client)
        for (generation in listOf(oldGeneration, newGeneration, oldGeneration)) {
            assertThrows(IOException::class.java) { api.createUpload(manifest, generation) }
        }
        assertEquals(listOf(oldGeneration, newGeneration, oldGeneration), operations)
    }
}
