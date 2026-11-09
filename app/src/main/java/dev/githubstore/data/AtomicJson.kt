package dev.githubstore.data

import android.util.AtomicFile
import java.io.File

class AtomicJson(file: File) {
    private val atomic = AtomicFile(file)
    @Synchronized fun read(): String? = runCatching { atomic.openRead().bufferedReader().use { it.readText() } }.getOrNull()
    @Synchronized fun write(text: String) {
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
