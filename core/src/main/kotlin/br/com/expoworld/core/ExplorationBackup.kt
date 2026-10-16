package br.com.expoworld.core

import java.io.Reader
import java.io.Writer
import java.security.MessageDigest

/** Portable real exploration only. Streaming, versioned, bounded and checksummed. */
object ExplorationBackup {
    const val MAX_CELLS = 5_000_000L
    const val MAX_BYTES = 128L * 1024 * 1024
    private const val HEADER = "EXPO_WORLD\t1\t10"
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun validate(cell: Cell) {
        val boundX = kotlin.math.ceil(Geo.R * Math.PI / ExplorationConfig.CELL_PROJECTED_METERS).toInt()
        val boundY = kotlin.math.ceil(Geo.project(Geo.MAX_LAT, 0.0).y / ExplorationConfig.CELL_PROJECTED_METERS).toInt()
        require(cell.x in -boundX until boundX && cell.y in -boundY until boundY) { "Coordenada inválida no backup." }
    }
    class Encoder(private val writer: Writer) {
        private val checksum = digest()
        private var count = 0L
        init { writer.write("$HEADER\n") }
        fun cell(cell: Cell) {
            validate(cell); require(++count <= MAX_CELLS) { "Backup excede o limite de células." }
            val line = "${cell.x}\t${cell.y}\n"
            checksum.update(line.toByteArray(Charsets.UTF_8)); writer.write(line)
        }
        fun finish(): Long {
            writer.write("END\t$count\t${hex(checksum.digest())}\n"); writer.flush(); return count
        }
    }
    fun decode(reader: Reader): Sequence<Cell> = sequence {
        val input = reader.buffered()
        // Bound each line too: an untrusted file must not allocate an enormous string.
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val char = input.read()
                if (char == -1) return if (out.isEmpty()) null else out.toString()
                if (char == 10) return out.toString()
                require(out.length < 160) { "Linha inválida no backup." }
                out.append(char.toChar())
            }
        }
        require(line() == HEADER) { "Backup incompatível: use um arquivo Expo World válido." }
        val checksum = digest()
        var count = 0L
        while (true) {
            val text = line() ?: error("Backup incompleto.")
            val parts = text.split('\t')
            if (parts.firstOrNull() == "END") {
                require(parts.size == 3 && parts[1].toLongOrNull() == count && parts[2] == hex(checksum.digest())) { "Backup corrompido ou incompleto." }
                require(line() == null) { "Dados extras no backup." }
                break
            }
            require(++count <= MAX_CELLS && parts.size == 2) { "Backup inválido ou muito grande." }
            val cell = Cell(parts[0].toIntOrNull() ?: error("Coordenada inválida."), parts[1].toIntOrNull() ?: error("Coordenada inválida."))
            validate(cell)
            checksum.update((text + "\n").toByteArray(Charsets.UTF_8))
            yield(cell)
        }
    }
}
