package br.com.expoworld.core

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class ExplorationBackupTest {
    @Test fun encoderCannotProduceBackupLargerThanRestoreLimit() {
        val writer = StringWriter(); val encoder = ExplorationBackup.Encoder(writer, 256)
        try {
            repeat(100) { encoder.cell(Cell(it, it), 1_760_000_000_000L) }
            fail("Must enforce both cell and byte limits")
        } catch (expected: IllegalArgumentException) {
            assertTrue(writer.toString().toByteArray().size < 256)
        }
    }
    private fun encode(cells: List<Cell>): String = StringWriter().also { writer ->
        val encoder = ExplorationBackup.Encoder(writer)
        cells.forEach { encoder.cell(it) }; assertEquals(cells.size.toLong(), encoder.finish())
    }.toString()
    private fun invalid(text: String) {
        try { ExplorationBackup.decode(StringReader(text)).toList(); fail("Must reject invalid backup") }
        catch (expected: IllegalArgumentException) { }
        catch (expected: IllegalStateException) { }
    }
    @Test fun roundTripIncludesNegativeCoordinatesAndEmptyExploration() {
        for (cells in listOf(emptyList(), listOf(Cell(0, 0), Cell(-100, -250), Cell(30, 200)))) {
            assertEquals(cells, ExplorationBackup.decode(StringReader(encode(cells))).toList())
        }
    }
    @Test fun corruptedTruncatedIncompatibleAndTrailingDataAreRejected() {
        val text = encode(listOf(Cell(10, -20)))
        invalid(text.replace("10\t-20", "11\t-20"))
        invalid(text.substringBefore("END"))
        invalid(text.replace("EXPO_WORLD\t2\t10", "EXPO_WORLD\t2\t20"))
        invalid(text + "EXTRA\n")
        invalid(text.replace("END\t1", "END\t2"))
    }
    @Test fun untrustedLinesAndImpossibleCoordinatesAreBounded() {
        invalid("EXPO_WORLD\t1\t10\n" + "9".repeat(1000) + "\n")
        invalid("EXPO_WORLD\t1\t10\n2147483647\t0\n")
        invalid("EXPO_WORLD\t1\t10\n0\t-2147483648\n")
    }
    @Test fun streamingLargeBackupRetainsEveryCell() {
        val cells = (0 until 20_000).map { Cell(-it, it / 20) }
        val sequence = ExplorationBackup.decode(StringReader(encode(cells)))
        assertEquals(cells, sequence.toList())
    }
}
