package shop.terramoor.github.io.diagnostics

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** A private tail retained across a SystemUI or settings process restart. */
internal object DiagnosticStore {
    const val MAX_LINES = 4096
    const val MAX_LINE_LENGTH = 512
    const val BATCH_SIZE = 32

    @Synchronized
    fun append(context: Context, batch: List<String>): Boolean {
        val file = file(context)
        val all = (read(file) + batch).distinct()
        val latestStages = all.indices.reversed().mapNotNull { index ->
            val match = Regex("\\[tint/[^\\]]+\\]").find(all[index])
            match?.value?.let { it to index }
        }.distinctBy { it.first }.map { it.second }.toSet()
        val retained = all.indices.toList().takeLast((MAX_LINES - latestStages.size).coerceAtLeast(0)).toSet() + latestStages
        val lines = all.filterIndexed { index, _ -> index in retained }
        val stream = file.startWrite()
        try {
            stream.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        return true
    }

    @Synchronized
    fun snapshot(context: Context): List<String> = read(file(context))

    private fun file(context: Context) = AtomicFile(
        File(context.createDeviceProtectedStorageContext().filesDir, "terramoor-diagnostics.txt"),
    )

    private fun read(file: AtomicFile): List<String> = try {
        file.openRead().bufferedReader(Charsets.UTF_8).use { it.readLines().takeLast(MAX_LINES) }
    } catch (_: FileNotFoundException) {
        emptyList()
    }
}
