package com.example.pocketruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppErrorStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun errorRecordsSurviveStoreRecreationAndCanBeCleared() {
        val runtimeRoot = File(temporaryFolder.root, "root")
        val appId = "123e4567-e89b-12d3-a456-426614174000"

        AppErrorStore(runtimeRoot).record(appId, "A runtime error")
        val restoredStore = AppErrorStore(runtimeRoot)

        assertEquals("A runtime error", restoredStore.get(appId))
        restoredStore.clear(appId)
        assertNull(restoredStore.get(appId))
    }

    @Test
    fun errorRecordsRequireAValidApplicationId() {
        val store = AppErrorStore(File(temporaryFolder.root, "root"))

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.record("../other-app", "A runtime error")
        }
    }
}
