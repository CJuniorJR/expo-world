package br.com.expoworld.core

enum class SimulationScenario(val label: String) {
    NORMAL("Percurso normal"), TUNNEL("Túnel sem sinal"), JITTER("GPS oscilando"),
    JUMP("Salto de posição"), RECOVERY("Perda e retorno do sinal");
    fun fix(index: Int, time: Double): Fix? {
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
