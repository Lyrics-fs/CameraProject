package com.example.camera.model.calibration

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LookupTableRepositoryTest {

    private fun fiveEntries(): List<LookupEntry> = listOf(
        LookupEntry(10.0, 1.0),
        LookupEntry(20.0, 2.0),
        LookupEntry(30.0, 3.0),
        LookupEntry(40.0, 4.0),
        LookupEntry(50.0, 5.0),
    )

    @Test
    fun save_clears_isUploaded_and_rotates_uploadId() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = LookupTableRepository(ctx)
        val t1 = LookupTable("m1", fiveEntries(), 100, 1L, "")
        repo.save(t1)
        val id1 = repo.getLookupTableUploadId()
        repo.markAsUploaded()
        assertTrue(repo.isUploaded())
        val t2 = LookupTable("m2", fiveEntries(), 200, 2L, "")
        repo.save(t2)
        assertFalse(repo.isUploaded())
        val id2 = repo.getLookupTableUploadId()
        assertNotEquals(id1, id2)
    }
}
