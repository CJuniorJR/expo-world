package br.com.expoworld.core

enum class SimulationScenario(val label: String) {
    NORMAL("Percurso normal"), TUNNEL("Túnel sem sinal"), JITTER("GPS oscilando"),
    JUMP("Salto de posição"), RECOVERY("Perda e retorno do sinal"), RETRACE("Ida e volta • rastro consolidado");
    fun fix(index: Int, time: Double): Fix? {
        if (this == RETRACE) {
            // Walk back over exactly the same offline Curitiba geometry. This is a
            // rendering test, not routing advice (some streets may be one-way).
            fun position(i: Int) = SimulatedRoute.fix(if (i <= SimulatedRoute.LAST_INDEX / 2) i else SimulatedRoute.LAST_INDEX - i, time)
            val current = position(index)
            val previous = position((index - 1).coerceAtLeast(0))
            return current.copy(speedMetersPerSecond = Geo.distance(previous, current), bearingDegrees = Geo.bearing(previous, current))
        }
        val point = SimulatedRoute.fix(index, time)
        return when {
            this == TUNNEL && index in 35..55 -> null
            this == RECOVERY && index in 30..50 -> null
            this == RECOVERY && index in 51..60 -> point.copy(accuracy = 65.0)
            this == JITTER && index in 30..60 && index % 3 != 0 -> point.copy(accuracy = 60.0, longitude = point.longitude + .0003)
            this == JUMP && index == 45 -> point.copy(longitude = point.longitude + .05)
            else -> point
        }
    }
}
