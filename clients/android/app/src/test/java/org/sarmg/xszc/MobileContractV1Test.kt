package org.sarmg.xszc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileContractV1Test {
    @Test
    fun currentEpochUsesDeclaredSandboxPaths() {
        val sandbox = Files.createTempDirectory("media-mobile-revision1-sandbox-").toFile()
        try {
            val currentDatabase = File(sandbox, MobileContractV1.DATABASE_FILENAME)
            val currentStaging = File(sandbox, MobileContractV1.STAGING_DIRECTORY)
            currentDatabase.writeText("current sqlite bytes")
            assertTrue(currentStaging.mkdir())

            assertEquals(1, MobileContractV1.REVISION)
            assertEquals("client-v1.sqlite", currentDatabase.name)
            assertEquals("backup-staging-v1", currentStaging.name)
            assertEquals(sandbox.canonicalFile, currentDatabase.parentFile.canonicalFile)
            assertEquals(sandbox.canonicalFile, currentStaging.parentFile.canonicalFile)
            assertTrue(currentDatabase.isFile)
            assertTrue(currentStaging.isDirectory)
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
