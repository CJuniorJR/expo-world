package br.com.expoworld.core

import org.locationtech.jts.geom.*
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters

/** Bounded viewport geometry, built on Dispatchers.Default by the UI controller.
 * Adjacent cells are compressed into row runs, then topologically unioned before
 * subtraction. No overlapping/touching invalid polygon holes are sent to MapLibre.
 */
object FogGeometry {
    private val factory = GeometryFactory()
    private val smooth = BufferParameters(4)
    private fun rectangle(w: Double, s: Double, e: Double, n: Double): Polygon = factory.createPolygon(arrayOf(
        Coordinate(w, s), Coordinate(e, s), Coordinate(e, n), Coordinate(w, n), Coordinate(w, s)
    ))
    fun geometry(cells: List<Cell>): Geometry {
        require(cells.size <= ExplorationConfig.MAX_VIEW_CELLS)
        val world = rectangle(-180.0, -Geo.MAX_LAT, 180.0, Geo.MAX_LAT)
        if (cells.isEmpty()) return world
        val rectangles = ArrayList<Geometry>()
        val size = ExplorationConfig.CELL_PROJECTED_METERS
        cells.groupBy { it.y }.forEach { (y, row) ->
            val xs = row.map { it.x }.distinct().sorted()
            var first = xs.first(); var last = first
            fun flush() { rectangles.add(rectangle(first * size, y * size, (last + 1) * size, (y + 1) * size)) }
            xs.drop(1).forEach { x -> if (x == last + 1) last = x else { flush(); first = x; last = x } }
            flush()
        }
        val projected = UnaryUnionOp.union(rectangles)
        // A metric opening rounds square corners conservatively: it never
        // extends exploration into cells that were not visited. Low quadrant
        // count bounds the number of vertices on the mobile renderer.
        val radius = size * .4
        val rounded = BufferOp.bufferOp(BufferOp.bufferOp(projected, -radius, smooth), radius, smooth)
        rounded.apply(object : CoordinateFilter {
            override fun filter(coordinate: Coordinate) {
                coordinate.x = Geo.longitude(coordinate.x)
                coordinate.y = Geo.latitude(coordinate.y)
            }
        })
        rounded.geometryChanged()
        return world.difference(rounded)
    }
    fun geoJson(cells: List<Cell>): String = geoJson(geometry(cells))
    fun geoJson(shape: Geometry): String {
        fun ring(line: LineString) = line.coordinates.joinToString(",", "[", "]") { "[${it.x},${it.y}]" }
        fun polygon(p: Polygon): String = (listOf(ring(p.exteriorRing)) + (0 until p.numInteriorRing).map { ring(p.getInteriorRingN(it)) }).joinToString(",", "[", "]")
        val polygons = (0 until shape.numGeometries).mapNotNull { shape.getGeometryN(it) as? Polygon }
        return "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":" + polygons.joinToString(",", "[", "]", transform = ::polygon) + "}}"
    }
}
