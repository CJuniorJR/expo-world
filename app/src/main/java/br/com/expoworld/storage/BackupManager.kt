package br.com.expoworld.storage

import android.net.Uri
import androidx.room.withTransaction
import br.com.expoworld.WorldApp
import br.com.expoworld.core.ExplorationBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class BackupManager(private val app: WorldApp) {
    suspend fun export(uri: Uri): Long = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("exploration-", ".export", app.cacheDir)
        try {
            val count = app.database.withTransaction {
                temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val encoder = ExplorationBackup.Encoder(writer)
                    var y = Int.MIN_VALUE; var x = Int.MIN_VALUE
                    while (true) {
                        coroutineContext.ensureActive()
                        val page = app.database.cells().exportPage(y, x)
                        if (page.isEmpty()) break
                        page.forEach { encoder.cell(br.com.expoworld.core.Cell(it.x, it.y), it.firstDiscoveredAtMillis) }
                        y = page.last().y; x = page.last().x
                    }
                    encoder.finish()
                }
            }
            val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("Não foi possível abrir o destino.")
            output.use { out -> temp.inputStream().use { it.copyTo(out) } }
            count
        } finally { temp.delete() }
    }
    suspend fun restore(uri: Uri): Long = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("exploration-", ".import", app.cacheDir)
        try {
            val input = app.contentResolver.openInputStream(uri) ?: error("Não foi possível abrir o arquivo.")
            input.use { source -> temp.outputStream().use { target ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break
                    total += read
                    require(total <= ExplorationBackup.MAX_BYTES) { "Backup maior que 128 MB." }
                    target.write(buffer, 0, read)
                }
            } }
            // Validate the entire immutable local copy before touching any exploration.
            temp.bufferedReader(Charsets.UTF_8).use { reader ->
                ExplorationBackup.decode(reader).forEach { coroutineContext.ensureActive() }
            }
            app.database.withTransaction {
                var added = 0L
                temp.bufferedReader(Charsets.UTF_8).use { reader ->
                    val iterator = ExplorationBackup.decodeRecords(reader).iterator()
                    while (iterator.hasNext()) {
                        coroutineContext.ensureActive()
                        val batch = ArrayList<ExploredCell>(1024)
                        while (batch.size < 1024 && iterator.hasNext()) {
                            val record = iterator.next(); batch.add(ExploredCell("real", record.cell.y, record.cell.x, record.firstDiscoveredAtMillis))
                        }
                        added += app.database.cells().insert(batch).count { it != -1L }
                    }
                }
                added
            }
        } finally { temp.delete() }
    }
}
