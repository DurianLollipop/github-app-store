package dev.githubstore

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.githubstore.data.TaskRepository
import dev.githubstore.domain.*
import dev.githubstore.download.DownloadEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadResumeStateTest {
    private fun isolated(block: (TaskRepository, ContextWrapper) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "resume-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        try { block(TaskRepository(context), context) } finally { folder.deleteRecursively() }
    }
    private fun enqueue(repository: TaskRepository): DownloadTask {
        val app = StoreApp(id = "test-app", name = "Test", repository = "test/app", packageName = "test.app",
            category = "测试", tagline = "", description = "", license = "", color = 0, symbol = "T", tvStatus = "")
        val asset = ReleaseAsset(1, "app.apk", "https://github.com/test/app/releases/download/v1/app.apk", 1024, "a".repeat(64))
        return repository.enqueue(app, AppRelease(1, "v1", "v1", "", "", false, "", listOf(asset)), asset)
    }
    @Test fun processRestartKeepsBytesAndMarksActiveTaskInterrupted() = isolated { repository, context ->
        val task = enqueue(repository)
        val chunks = DownloadEngine.checkpointDir(repository.partial(task.id)).apply { mkdirs() }
        File(chunks, "0.chunk").writeBytes(ByteArray(128))
        File(chunks, "1.chunk").writeBytes(ByteArray(32))
        repository.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, downloaded = 160) }
        val restored = TaskRepository(context)
        assertEquals(TaskStatus.INTERRUPTED, restored.find(task.id)!!.status)
        assertEquals(160L, restored.find(task.id)!!.downloaded)
        assertFalse(restored.partial(task.id).exists())
        assertEquals(128L, File(DownloadEngine.checkpointDir(restored.partial(task.id)), "0.chunk").length())
        assertEquals(32L, File(DownloadEngine.checkpointDir(restored.partial(task.id)), "1.chunk").length())
    }
    @Test fun replacedAssetWithSameUrlDoesNotReuseOldReadyTask() = isolated { repository, _ ->
        val task = enqueue(repository)
        repository.update(task.id) { it.copy(status = TaskStatus.READY) }
        val app = StoreApp(task.appId, task.appName, task.repository, task.packageName, "", "", "", "", 0xFF000000, "T")
        val same = repository.enqueue(app, AppRelease(1, "v1", "", "", "", false, "", listOf(task.asset)), task.asset)
        assertEquals(task.id, same.id)
        val replaced = task.asset.copy(size = 2048, sha256 = "b".repeat(64), updatedAt = "2026-11-11")
        val fresh = repository.enqueue(app, AppRelease(1, "v1", "", "", "", false, "", listOf(replaced)), replaced)
        assertNotEquals(task.id, fresh.id)
        assertEquals(TaskStatus.QUEUED, fresh.status)
    }
    @Test fun clearingCachePreservesActiveSegmentDirectoryButRemovesCancelledSegments() = isolated { repository, _ ->
        val task = enqueue(repository)
        val chunk = File(DownloadEngine.checkpointDir(repository.partial(task.id)).apply { mkdirs() }, "0.chunk")
        chunk.writeBytes(ByteArray(128))
        assertEquals(0L, repository.clearCompletedFiles())
        assertTrue(chunk.exists())
        repository.update(task.id) { it.copy(status = TaskStatus.CANCELLED) }
        assertEquals(128L, repository.clearCompletedFiles())
        assertFalse(chunk.exists())
    }
}
