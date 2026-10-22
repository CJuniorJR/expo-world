package br.com.expoworld.storage

import android.net.Uri
import androidx.room.withTransaction
import br.com.expoworld.WorldApp
import br.com.expoworld.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.Reader
import java.io.Writer
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A fully validated, immutable private copy awaits the user's explicit restore action. */
data class BackupPreview(val formatVersion: Int, val cells: Long, val routes: Long = 0,
    val plannedRoutes: Long = 0, val expeditions: Long = 0, val streetVisits: Long = 0,
    val hasPreferences: Boolean = false)

class BackupManager internal constructor(private val context: android.content.Context,
    private val database: ExplorationDatabase,
    private val currentTheme: () -> ExplorerTheme = { ExplorerTheme.fromKey(context.getSharedPreferences("appearance", 0).getString("theme", null)) },
    private val applyTheme: (ExplorerTheme) -> Unit = { context.getSharedPreferences("appearance", 0).edit().putString("theme", it.key).apply() }) {
    constructor(app: WorldApp): this(app, app.database, { app.theme.value }, { app.selectTheme(it) })
    private val mutex = Mutex()
    private var prepared: File? = null
    private var preview: BackupPreview? = null
    private suspend fun inspect(file: File): BackupPreview {
        val checkContext = coroutineContext
        return file.bufferedReader().use { reader -> CompleteBackupCodec.validate(reader) { checkContext.ensureActive() } }
    }

    suspend fun export(uri: Uri): Long = withContext(Dispatchers.IO) { mutex.withLock {
        val temp = File.createTempFile("world-", ".export", context.cacheDir)
        try {
            val count = database.withTransaction {
                database.cells().archiveLastRealRoute()
                temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val encoder = CompleteBackupCodec.Encoder(writer)
                    val db = database.openHelper.readableDatabase
                    CompleteBackupCodec.columns.forEach { (table, columns) ->
                        val where = when (table) {
                            "cells", "street_visits" -> " WHERE mode = 'real'"
                            "route_sessions", "route_samples", "trip_cells", "trip_street_visits" -> " WHERE mode IN (SELECT id FROM saved_routes)"
                            else -> ""
                        }
                        val order = when (table) { "route_samples" -> " ORDER BY mode, sequence"; else -> "" }
                        db.query("SELECT ${columns.joinToString(",")} FROM $table$where$order").use { cursor ->
                            while (cursor.moveToNext()) {
                                coroutineContext.ensureActive()
                                val values = JSONArray()
                                columns.indices.forEach { index -> values.put(when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> org.json.JSONObject.NULL
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).let { value ->
                                        when {
                                            value.isFinite() -> value
                                            table == "route_samples" && columns[index] in listOf("accuracyMeters", "speedKmh", "rawSpeedMetersSecond", "monotonicSeconds") -> org.json.JSONObject.NULL
                                            table == "route_samples" && columns[index] in listOf("latitude", "longitude") && cursor.getLong(columns.indexOf("accepted")) == 0L && !value.isNaN() -> value.toString()
                                            else -> error("O banco contém um valor geográfico inválido que não pode ser exportado.")
                                        }
                                    }
                                    else -> cursor.getString(index)
                                }) }
                                encoder.row(table, values)
                            }
                        }
                    }
                    val prefs = JSONArray().put(currentTheme().key)
                        .put(context.getSharedPreferences("map", 0).getFloat("fogOpacity", .55f).coerceIn(.2f, .8f).toDouble())
                        .put(context.getSharedPreferences("map", 0).getString("cardTitle", "Minha expedição")?.take(40) ?: "Minha expedição")
                    encoder.row("preferences", prefs)
                    encoder.finish()
                }
            }
            // Export uses the same parser as import, so unsupported/corrupt local rows never produce a purported complete backup.
            inspect(temp)
            (context.contentResolver.openOutputStream(uri, "wt") ?: error("Não foi possível abrir o destino.")).use { output ->
                temp.inputStream().use { it.copyTo(output) }
            }
            count
        } finally { temp.delete() }
    } }
    suspend fun prepareRestore(uri: Uri): BackupPreview = withContext(Dispatchers.IO) { mutex.withLock {
        prepared?.delete(); prepared = null; preview = null
        val temp = File.createTempFile("world-", ".import", context.cacheDir)
        try {
            (context.contentResolver.openInputStream(uri) ?: error("Não foi possível abrir o arquivo.")).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192); var bytes = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count; require(bytes <= ExplorationBackup.MAX_BYTES) { "Backup maior que 128 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val header = temp.bufferedReader().use { CompleteBackupCodec.header(it) }
            val result = if (header == CompleteBackupCodec.HEADER) {
                inspect(temp)
            } else {
                var count = 0L
                temp.bufferedReader().use { reader -> ExplorationBackup.decodeRecords(reader).forEach { coroutineContext.ensureActive(); count++ } }
                BackupPreview(if (header?.startsWith("EXPO_WORLD\t1\t") == true) 1 else 2, count)
            }
            prepared = temp; preview = result; result
        } catch (error: Throwable) { temp.delete(); throw error }
    } }
    suspend fun restorePrepared(): Long = withContext(Dispatchers.IO) { mutex.withLock {
        val temp = checkNotNull(prepared) { "Escolha e confira um backup antes de restaurar." }
        val metadata = checkNotNull(preview)
        try {
            // Revalidate the private copy immediately before the transaction, including relationships and checksum.
            if (metadata.formatVersion == 3) inspect(temp)
            var preferences: JSONArray? = null
            val added = database.withTransaction {
                var cells = 0L
                val dao = database.cells()
                if (metadata.formatVersion < 3) {
                    temp.bufferedReader().use { reader ->
                        val iterator = ExplorationBackup.decodeRecords(reader).iterator()
                        while (iterator.hasNext()) {
                            coroutineContext.ensureActive()
                            val batch = ArrayList<ExploredCell>(1024)
                            while (batch.size < 1024 && iterator.hasNext()) { val row = iterator.next(); batch.add(ExploredCell("real", row.cell.y, row.cell.x, row.firstDiscoveredAtMillis)) }
                            cells += dao.insert(batch).count { it != -1L }
                        }
                    }
                } else {
                    val db = database.openHelper.writableDatabase
                    val existing = mutableSetOf<String>()
                    db.query("SELECT id FROM saved_routes").use { c -> while(c.moveToNext()) existing.add(c.getString(0)) }
                    val restoreContext = coroutineContext
                    temp.bufferedReader().use { reader -> CompleteBackupCodec.read(reader) { table, values ->
                        restoreContext.ensureActive()
                        if (table == "preferences") preferences = values
                        else {
                            val skip = when(table) { "saved_routes" -> values.getString(0) in existing
                                "route_sessions", "route_samples", "trip_cells", "trip_street_visits" -> values.getString(0) in existing; else -> false }
                            if (!skip) {
                                val columns = CompleteBackupCodec.columns.getValue(table)
                                val sql = "INSERT OR IGNORE INTO $table(${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})"
                                val parameters = Array<Any?>(values.length()) {
                                    if (values.isNull(it)) null
                                    else if (table == "route_samples" && it in 2..3 && values.get(it) is String) values.getString(it).toDouble()
                                    else values.get(it)
                                }
                                db.compileStatement(sql).use { statement ->
                                    parameters.forEachIndexed { index, value -> when(value) {
                                        null -> statement.bindNull(index+1)
                                        is Double -> statement.bindDouble(index+1,value)
                                        is Float -> statement.bindDouble(index+1,value.toDouble())
                                        is Number -> statement.bindLong(index+1,value.toLong())
                                        else -> statement.bindString(index+1,value.toString())
                                    } }
                                    if (statement.executeInsert() != -1L && table == "cells") cells++
                                }
                            }
                        }
                    } }
                }
                cells
            }
            preferences?.let { value ->
                applyTheme(ExplorerTheme.fromKey(value.getString(0)))
                context.getSharedPreferences("map", 0).edit().putFloat("fogOpacity", value.getDouble(1).toFloat()).putString("cardTitle", value.getString(2)).apply()
            }
            added
        } finally { temp.delete(); prepared = null; preview = null }
    } }
    suspend fun restore(uri: Uri): Long { prepareRestore(uri); return restorePrepared() }
    fun cancelPrepared() { prepared?.delete(); prepared = null; preview = null }
}

/** Bounded NDJSON records; checksum covers the exact UTF-8 records, including table names. */
internal object CompleteBackupCodec {
    const val HEADER = "EXPO_WORLD\t3\t10"
    val columns = linkedMapOf(
        "expeditions" to listOf("id","title","createdAtMillis"),
        "saved_routes" to listOf("id","title","startedAtMillis","activity","favorite","expeditionId","distanceMeters","recordedSeconds"),
        "route_sessions" to listOf("mode","startedAtMillis","endedAtMillis","completed","activity","newAreaSquareMeters","archivedId","newStreetMeters"),
        "route_samples" to listOf("mode","sequence","latitude","longitude","elapsedSeconds","speedKmh","breakBefore","stopId","stopSeconds","accuracyMeters","accepted","rawSpeedMetersSecond","monotonicSeconds","streetIds"),
        "trip_cells" to listOf("mode","y","x","elapsedSeconds"),
        "trip_street_visits" to listOf("mode","dataset","streetId","bin","neighborhoodId","meters"),
        "cells" to listOf("mode","y","x","firstDiscoveredAtMillis"),
        "street_visits" to listOf("mode","activity","dataset","streetId","bin","neighborhoodId","meters"),
        "planned_routes" to listOf("id","title","createdAtMillis","payload")
    )
    fun header(reader: Reader): String? {
        val line = StringBuilder()
        while(true) {
            val ch = reader.read(); if(ch == -1) return line.takeIf { it.isNotEmpty() }?.toString()
            if(ch == 10) return line.toString()
            require(line.length < 160) { "Cabeçalho inválido no backup." };line.append(ch.toChar())
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    class Encoder(private val writer: Writer) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var bytes = HEADER.length + 1L; private var count = 0L; private var cells = 0L
        init { writer.write("$HEADER\n") }
        fun row(table: String, values: JSONArray) {
            val line = "$table\t$values\n"; val data = line.toByteArray(Charsets.UTF_8)
            bytes += data.size; require(bytes + 128 <= ExplorationBackup.MAX_BYTES) { "Backup maior que 128 MB." }
            count++; if (table == "cells") cells++
            digest.update(data); writer.write(line)
        }
        fun finish(): Long { writer.write("END\t$count\t${hex(digest.digest())}\n"); writer.flush(); return cells }
    }
    fun read(reader: Reader, accept: (String, JSONArray) -> Unit) {
        val input = reader.buffered(); var total = 0L
        fun line(): String? {
            val value = StringBuilder()
            while(true) {
                val ch = input.read(); if(ch == -1) return value.takeIf { it.isNotEmpty() }?.toString()
                require(++total <= ExplorationBackup.MAX_BYTES) { "Backup maior que 128 MB." }
                if(ch == 10) return value.toString()
                require(value.length < 4_000_000) { "Registro excessivo no backup." }; value.append(ch.toChar())
            }
        }
        require(line() == HEADER) { "Formato de backup incompatível." }
        val digest = MessageDigest.getInstance("SHA-256"); var rows = 0L
        while(true) {
            val row = line() ?: error("Backup incompleto.")
            if(row.startsWith("END\t")) {
                val fields = row.split('\t')
                require(fields.size == 3 && fields[1].toLongOrNull() == rows && fields[2] == hex(digest.digest())) { "Checksum inválido: backup corrompido." }
                require(line() == null) { "Conteúdo extra no backup." }; break
            }
            require(++rows <= 7_000_000) { "Backup contém registros demais." }
            val delimiter = row.indexOf('\t'); require(delimiter > 0)
            val table = row.substring(0,delimiter); require(table in columns || table == "preferences") { "Registro desconhecido no backup." }
            digest.update((row + "\n").toByteArray(Charsets.UTF_8)); accept(table,JSONArray(row.substring(delimiter+1)))
        }
    }
    fun validate(reader: Reader, checkCancellation: () -> Unit = {}): BackupPreview {
        var cells=0L; var visits=0L;var planned=0L;var preferences=false;var samples=0L;var tripCells=0L
        val expeditions=mutableSetOf<String>();val routes=mutableMapOf<String,Pair<Long,String>>()
        val sessions=mutableSetOf<String>();val sampleRoutes=mutableSetOf<String>()
        val seenPlans=mutableSetOf<String>()
        var lastSampleMode="";var lastSequence=-1L;var lastElapsed=-1.0
        fun text(row:JSONArray,index:Int,max:Int=160):String { val v=row.get(index);require(v is String && v.length in 1..max && v.none { it.code < 32 });return v }
        fun number(row:JSONArray,index:Int,min:Double=0.0,max:Double=1e15):Double { val v=row.get(index);require(v is Number);val n=v.toDouble();require(n.isFinite() && n in min..max);return n }
        fun integer(row:JSONArray,index:Int,min:Long=0,max:Long=Long.MAX_VALUE):Long { val n=number(row,index,min.toDouble(),max.toDouble());require(n%1.0==0.0);return n.toLong() }
        fun rawCoordinate(row: JSONArray, index: Int) {
            val value = row.get(index)
            require((value is Number && value.toDouble().isFinite()) || value == "Infinity" || value == "-Infinity") { "Coordenada bruta inválida." }
        }
        fun date(row:JSONArray,index:Int)=integer(row,index,1,4102444800000L)
        fun activity(row:JSONArray,index:Int):String = text(row,index,10).also { require(it in listOf("car","walk","bike")) }
        fun cell(row:JSONArray,y:Int,x:Int) { integer(row,x,-2003751,2003750);integer(row,y,-2003751,2003750) }
        fun route(row:JSONArray,index:Int=0):String = text(row,index).also { require(it in routes) { "Trajeto referenciado não existe." } }
        fun optional(row:JSONArray,index:Int,validate:()->Unit) { if(!row.isNull(index)) validate() }
        read(reader) { table,row ->
            checkCancellation()
            require(row.length() == (columns[table]?.size ?: 3)) { "Colunas inválidas no backup." }
            when(table) {
                "expeditions" -> {require(expeditions.size < 5_000);require(expeditions.add(text(row,0)));text(row,1,80);date(row,2)}
                "saved_routes" -> {require(routes.size < 10_000);val id=text(row,0);require(id.startsWith("saved:"));text(row,1,80);val start=date(row,2);val kind=activity(row,3);integer(row,4,0,1);optional(row,5){require(text(row,5) in expeditions)};optional(row,6){number(row,6)};optional(row,7){number(row,7,0.0,31_622_400.0)};require(routes.put(id,start to kind)==null)}
                "route_sessions" -> {val id=route(row);require(sessions.add(id));require(date(row,1)==routes[id]!!.first);optional(row,2){require(date(row,2)>=date(row,1))};integer(row,3,0,1);require(activity(row,4)==routes[id]!!.second);optional(row,5){number(row,5)};optional(row,6){require(text(row,6)==id)};optional(row,7){number(row,7)}}
                "route_samples" -> {
                    require(++samples <= 1_000_000);val id=route(row);require(id in sessions);val sequence=integer(row,1,0,Int.MAX_VALUE.toLong());val accepted=integer(row,10,0,1)==1L
                    if (accepted) { number(row,2,-90.0,90.0);number(row,3,-180.0,180.0) } else { rawCoordinate(row,2);rawCoordinate(row,3) }
                    val elapsed=number(row,4,0.0,31_622_400.0)
                    if(id==lastSampleMode) {require(sequence>lastSequence && elapsed>=lastElapsed)} else {require(id !in sampleRoutes);sampleRoutes.add(id)}
                    lastSampleMode=id;lastSequence=sequence;lastElapsed=elapsed
                    optional(row,5){number(row,5,0.0,3600.0)};integer(row,6,0,1);integer(row,7,-1,Int.MAX_VALUE.toLong());number(row,8,0.0,31_622_400.0)
                    optional(row,9){number(row,9,if(accepted) 0.0 else -1e15,1e15)};optional(row,11){number(row,11,-1e15,1e15)};optional(row,12){number(row,12,-1e15,1e15)};optional(row,13){text(row,13,20_000)}
                }
                "trip_cells" -> {require(++tripCells<=5_000_000);route(row);cell(row,1,2);number(row,3,-1.0,31_622_400.0)}
                "trip_street_visits" -> {route(row);text(row,1);text(row,2);integer(row,3,0,1_000_000);text(row,4);number(row,5,0.0,20.0)}
                "cells" -> {require(++cells<=ExplorationBackup.MAX_CELLS);require(text(row,0)=="real");cell(row,1,2);optional(row,3){date(row,3)}}
                "street_visits" -> {require(++visits<=2_000_000);require(text(row,0)=="real");activity(row,1);text(row,2);text(row,3);integer(row,4,0,1_000_000);text(row,5);number(row,6,0.0,20.0)}
                "planned_routes" -> {require(++planned<=10_000);require(seenPlans.add(text(row,0)));text(row,1,80);date(row,2);PocketPlanCodec.decode(row.getString(3))}
                "preferences" -> {require(!preferences);preferences=true;require(text(row,0,40) in ExplorerTheme.entries.map { it.key });number(row,1,.2,.8);require(row.get(2) is String && row.getString(2).length<=40)}
            }
        }
        require(sessions==routes.keys && sampleRoutes==routes.keys) { "O backup possui trajetos sem sessão ou registros GPS." }
        return BackupPreview(3,cells,routes.size.toLong(),planned,expeditions.size.toLong(),visits,preferences)
    }
}
