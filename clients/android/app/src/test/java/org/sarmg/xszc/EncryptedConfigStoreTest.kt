package org.sarmg.xszc

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

class EncryptedConfigStoreTest {
    private class MemoryFile : EncryptedConfigStore.ConfigFile {
        var bytes: ByteArray? = null
        var failWrites = false
        var failAfterCommit = false
        var obsolete = false
        var boundClientState = false
        override fun read(): ByteArray? = bytes?.clone()
        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("injected storage failure")
            this.bytes = bytes.clone()
            if (failAfterCommit) throw IOException("injected unknown commit result")
        }
        override fun hasObsoleteState(): Boolean = obsolete
        override fun hasBoundClientState(): Boolean = boundClientState
    }
    private class MemoryKeys : EncryptedConfigStore.ConfigKeys {
        var key: SecretKey? = null
        var creations = 0
        override fun existing(): SecretKey? = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey().also { key = it; creations++ }
        }
    }
    private class Fixture {
        val file = MemoryFile()
        val keys = MemoryKeys()
        val lock = Any()
        fun open(identity: String = "test-config") = EncryptedConfigStore(file, keys, identity, lock)
    }

    @Test fun firstReadDoesNotCreatePersistentIdentity() {
        val f = Fixture()
        assertEquals("", f.open().read { it.string("token") })
        assertNull(f.file.bytes)
        assertEquals(0, f.keys.creations)
    }

    @Test fun encryptedSnapshotReopensAndUsesFreshNonceWithTheSameKey() {
        val f = Fixture()
        val secret = "only-a-test-token-测试🔐"
        f.open().update { it.set("token", secret); it.set("flag", true); it.set("sequence", Long.MAX_VALUE) }
        val first = f.file.bytes!!.clone()
        assertFalse(first.toString(Charsets.UTF_8).contains(secret))
        assertEquals(secret, f.open().read { it.string("token") })
        assertTrue(f.open().read { it.boolean("flag", false) })
        assertEquals(Long.MAX_VALUE, f.open().read { it.long("sequence") })
        f.open().update { it.set("token", secret) }
        assertFalse(first.contentEquals(f.file.bytes!!))
        assertEquals(1, f.keys.creations)
    }

    @Test fun missingKeyNeverGeneratesAReplacement() {
        val f = Fixture()
        f.open().update { it.set("token", "test-token") }
        val before = f.file.bytes!!.clone()
        f.keys.key = null
        assertThrows(IllegalStateException::class.java) { f.open().read { it.string("token") } }
        assertThrows(IllegalStateException::class.java) { f.open().update { it.set("token", "replacement") } }
        assertArrayEquals(before, f.file.bytes)
        assertEquals(1, f.keys.creations)
    }

    @Test fun transactionUsesTheValidatedKeyAndNeverLooksUpAReplacement() {
        val f = Fixture()
        f.open().update { it.set("token", "committed") }
        val original = f.keys.key!!
        var lookups = 0
        val disappearing = object : EncryptedConfigStore.ConfigKeys {
            override fun existing(): SecretKey? = if (lookups++ == 0) original else null
            override fun create(): SecretKey = error("must never replace a validated key")
        }
        EncryptedConfigStore(f.file, disappearing, "test-config", f.lock).update { it.set("flag", true) }
        assertEquals(1, lookups)
        assertEquals("committed", f.open().read { it.string("token") })
        assertTrue(f.open().read { it.boolean("flag", false) })
    }

    @Test fun missingFileWithExistingKeyNeverInitializesEmptyState() {
        val f = Fixture()
        f.open().update { it.set("token", "test-token") }
        f.file.bytes = null
        assertThrows(IllegalStateException::class.java) { f.open().update { it.set("token", "replacement") } }
        assertNull(f.file.bytes)
        assertEquals(1, f.keys.creations)
    }

    @Test fun obsoletePreferencesAreRejectedWithoutSideEffects() {
        val f = Fixture()
        f.file.obsolete = true
        assertThrows(IllegalStateException::class.java) { f.open().read { it.string("token") } }
        assertThrows(IllegalStateException::class.java) { f.open().update { it.set("token", "replacement") } }
        assertEquals(0, f.keys.creations)
        assertNull(f.file.bytes)
    }

    @Test fun missingCredentialsDoNotSilentlyDetachExistingClientQueues() {
        val f = Fixture()
        f.file.boundClientState = true
        assertThrows(IllegalStateException::class.java) { f.open().read { it.string("token") } }
        assertThrows(IllegalStateException::class.java) { f.open().update { it.set("token", "replacement") } }
        assertEquals(0, f.keys.creations)
        assertNull(f.file.bytes)
    }

    @Test fun tamperingAndWrongStoreIdentityCannotDecryptTheSnapshot() {
        val f = Fixture()
        f.open().update { it.set("token", "test-token") }
        assertThrows(IllegalStateException::class.java) { f.open("another-config").read { it.string("token") } }
        f.file.bytes!!.let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val corrupted = f.file.bytes!!.clone()
        assertThrows(IllegalStateException::class.java) { f.open().update { it.set("token", "replacement") } }
        assertArrayEquals(corrupted, f.file.bytes)
        assertEquals(1, f.keys.creations)
    }

    @Test fun failedCommitIsVisibleAndDoesNotPublishAnUncommittedValue() {
        val f = Fixture()
        val store = f.open()
        store.update { it.set("token", "committed") }
        val before = f.file.bytes!!.clone()
        f.file.failWrites = true
        assertTrue(assertThrows(ConfigStorageException::class.java) {
            store.update { it.set("token", "uncommitted") }
        }.cause is IOException)
        assertArrayEquals(before, f.file.bytes)
        assertEquals("committed", store.read { it.string("token") })
        assertEquals("committed", f.open().read { it.string("token") })
    }

    @Test fun failedFirstWritePreservesTheKeyAndNeverGuessesThatItCanReinitialize() {
        val f = Fixture()
        f.file.failWrites = true
        assertThrows(ConfigStorageException::class.java) { f.open().update { it.set("token", "first-token") } }
        assertNotNull(f.keys.key)
        assertNull(f.file.bytes)
        f.file.failWrites = false
        assertThrows(ConfigStorageException::class.java) { f.open().read { it.string("token") } }
        assertThrows(ConfigStorageException::class.java) { f.open().update { it.set("token", "replacement") } }
        assertEquals(1, f.keys.creations)
        assertNull(f.file.bytes)
    }

    @Test fun unknownCommitResultIsReportedAndAReadReturnsTheActualPublishedState() {
        val f = Fixture()
        val store = f.open()
        store.update { it.set("token", "before") }
        f.file.failAfterCommit = true
        assertThrows(ConfigStorageException::class.java) { store.update { it.set("token", "published") } }
        assertEquals("published", store.read { it.string("token") })
        assertEquals("published", f.open().read { it.string("token") })
    }

    @Test fun concurrentInstancesDoNotLoseReadModifyWriteUpdates() {
        val f = Fixture()
        val stores = List(4) { f.open() }
        val executor = Executors.newFixedThreadPool(stores.size)
        try {
            executor.invokeAll(stores.map { store -> Callable {
                repeat(30) { store.update { it.set("count", it.int("count") + 1) } }
            } }).forEach { it.get() }
        } finally { executor.shutdownNow() }
        assertEquals(120, f.open().read { it.int("count") })
        assertEquals(1, f.keys.creations)
    }

    @Test fun rejectedBusinessValidationKeepsItsClassificationAndHasNoStorageSideEffects() {
        val f = Fixture()
        val failure = assertThrows(IllegalStateException::class.java) {
            f.open().update { it.set("token", "uncommitted"); check(false) { "配对信息已改变" } }
        }
        assertFalse(failure is ConfigStorageException)
        assertEquals("配对信息已改变", failure.message)
        assertEquals(0, f.keys.creations)
        assertNull(f.file.bytes)
    }

    @Test fun rejectedTransactionLeavesTheCommittedSnapshotUnchanged() {
        val f = Fixture()
        f.open().update { it.set("token", "committed") }
        val before = f.file.bytes!!.clone()
        assertThrows(IllegalArgumentException::class.java) {
            f.open().update { it.set("token", "uncommitted"); require(false) }
        }
        assertArrayEquals(before, f.file.bytes)
    }

    @Test fun oversizedInputIsRejectedBeforeCreatingAKey() {
        val f = Fixture()
        assertThrows(ConfigStorageException::class.java) {
            f.open().update { it.set("token", "x".repeat(EncryptedConfigStore.MAX_FILE_SIZE)) }
        }
        assertEquals(0, f.keys.creations)
        assertNull(f.file.bytes)
    }
}
