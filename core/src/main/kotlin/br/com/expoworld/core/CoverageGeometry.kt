package br.com.expoworld.core

/** Geographic row rectangles, without a world subtraction or large topological union. */
object CoverageGeometry {
    fun geoJson(bounds: List<CellBounds>): String {
        val size = ExplorationConfig.CELL_PROJECTED_METERS
        return bounds.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { b ->
            val w = Geo.longitude(b.minX * size).coerceIn(-180.0, 180.0)
            val e = Geo.longitude((b.maxX + 1.0) * size).coerceIn(-180.0, 180.0)
            val s = Geo.latitude(b.minY * size).coerceIn(-Geo.MAX_LAT, Geo.MAX_LAT)
            val n = Geo.latitude((b.maxY + 1.0) * size).coerceIn(-Geo.MAX_LAT, Geo.MAX_LAT)
            """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]]}}"""
        }
    }
    fun rows(cells: List<Cell>): List<CellBounds> = cells.groupBy { it.y }.flatMap { (y, row) ->
        val xs = row.map { it.x }.distinct().sorted()
        if (xs.isEmpty()) emptyList() else buildList {
            var first = xs.first(); var last = first
            for (x in xs.drop(1)) {
                if (x != last + 1) { add(CellBounds(first, last, y, y)); first = x }
                last = x
            }
            add(CellBounds(first, last, y, y))
        }
    }
}
