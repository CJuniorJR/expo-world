"""Generate the offline street fixture from the downloaded OSRM/OSM route."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
route = json.loads((root / 'scripts/fixtures/sao-paulo-route.json').read_text())['routes'][0]
folder = root / 'core/src/main/kotlin/br/com/expoworld/core'
points = ',\n'.join(f'        Fix({lat}, {lon}, 5.0, 0.0)' for lon, lat in route['geometry']['coordinates'])
(folder / 'SimulatedRoute.kt').write_text('''package br.com.expoworld.core

/** Offline street geometry: © OpenStreetMap contributors, ODbL.
 * Retrieved with OSRM on 2026-10-15; Rua Augusta / Alameda Itu, São Paulo.
 * Source: https://router.project-osrm.org/route/v1/driving/
 * -46.6510,-23.5650;-46.6598,-23.5580;-46.6632,-23.5606?overview=full&geometries=geojson
 * https://www.openstreetmap.org/copyright
 * Virtual seconds remain physical seconds for filtering and speed analysis.
 */
object SimulatedRoute {
    const val PLAYBACK_DELAY_MILLIS = 125L // 8x playback, about 35 seconds.
    private val streets = listOf(
''' + points + '''
    )
    private val lengths = streets.zipWithNext().map { (a, b) -> Geo.distance(a, b) }
    private val totalMeters = lengths.sum()
    private fun speed(second: Int): Double = when (val phase = second % 120) {
        in 0..20 -> 6.0 + phase * .6
        in 21..35 -> 18.0
        in 36..50 -> 18.0 - (phase - 35) * 1.0
        in 51..60 -> 3.0 - (phase - 50) * .3
        in 61..80 -> 0.0
        in 81..100 -> (phase - 80) * .9
        else -> 18.0
    }
    private val distances = buildList {
        add(0.0)
        var second = 1
        while (last() < totalMeters) {
            add((last() + speed(second++)).coerceAtMost(totalMeters))
        }
        repeat(15) { add(totalMeters) }
    }
    val LAST_INDEX = distances.lastIndex
    fun fix(index: Int, time: Double): Fix {
        val i = index.coerceIn(0, LAST_INDEX)
        var remaining = distances[i]
        var leg = 0
        while (leg < lengths.lastIndex && remaining > lengths[leg]) {
            remaining -= lengths[leg++]
        }
        val a = streets[leg]; val b = streets[leg + 1]
        val fraction = (remaining / lengths[leg]).coerceIn(0.0, 1.0)
        val metersPerSecond = if (i == 0) speed(0) else distances[i] - distances[i - 1]
        return Fix(a.latitude + (b.latitude - a.latitude) * fraction,
            a.longitude + (b.longitude - a.longitude) * fraction, 5.0, time, metersPerSecond)
    }
}
''', encoding='utf-8')
print(f'Generated street route: {route["distance"]} meters')
