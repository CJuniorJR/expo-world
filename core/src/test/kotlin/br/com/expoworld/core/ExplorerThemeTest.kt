package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test

class ExplorerThemeTest {
    @Test fun missingOrUnknownPreferenceKeepsOriginalGreen() {
        assertEquals(7, ExplorerTheme.entries.size)
        assertEquals(ExplorerTheme.GREEN, ExplorerTheme.fromKey(null))
        assertEquals(ExplorerTheme.GREEN, ExplorerTheme.fromKey("missing"))
        ExplorerTheme.entries.forEach { assertEquals(it, ExplorerTheme.fromKey(it.key)) }
    }
    @Test fun defaultPreservesExistingCarAndTraceColors() {
        assertEquals(0x007469, ExplorerTheme.GREEN.carBody)
        assertEquals(0x128F7E, ExplorerTheme.GREEN.carHighlight)
        assertEquals("#C9FFF1", TracePresentation.color(60.0))
        assertEquals("#75A09E", TracePresentation.color(30.0))
        assertEquals(0x70E1BA, ExplorerTheme.GREEN.accent)
    }
    @Test fun everyThemeKeepsStopsBlackUnknownGrayAndItsGradientEndpoint() {
        ExplorerTheme.entries.forEach { theme ->
            ActivityKind.entries.forEach { activity ->
                assertEquals("#000000", TracePresentation.color(0.0, activity.brightKmh, activity.stopKmh, theme))
                assertEquals(TracePresentation.UNKNOWN_COLOR, TracePresentation.color(null, activity.brightKmh, activity.stopKmh, theme))
                assertEquals(ExplorerTheme.hex(theme.traceLight), TracePresentation.color(activity.brightKmh, activity.brightKmh, activity.stopKmh, theme))
            }
        }
    }
    @Test fun themesRecolorSavedGeometryWithoutChangingStopsOrSignalGaps() {
        val samples = listOf(TraceSample(0, -25.43, -49.27, 0.0, 30.0, true, -1, 0.0),
            TraceSample(1, -25.431, -49.27, 5.0, 30.0, false, -1, 0.0),
            TraceSample(2, -25.432, -49.27, 10.0, 0.0, false, 0, 5.0),
            TraceSample(3, -25.44, -49.28, 30.0, 40.0, true, -1, 0.0))
        ExplorerTheme.entries.forEach { theme ->
            val json = TracePresentation.lineGeoJson(samples, ActivityKind.CAR, theme)
            assertTrue(json.contains(TracePresentation.color(30.0, theme = theme)))
            assertTrue(json.contains("#000000"))
            assertFalse(json.contains("Point"))
            assertFalse(json.contains("-49.28"))
        }
    }
}
