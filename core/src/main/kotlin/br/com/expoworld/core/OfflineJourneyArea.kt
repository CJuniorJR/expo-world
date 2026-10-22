package br.com.expoworld.core

import kotlin.math.*

data class OfflineJourneyArea(val south:Double,val west:Double,val north:Double,val east:Double,val estimatedTiles:Long) {
    companion object {
        /** Conservative bounding rectangle, 200m margin, zoom 10–16; never a world-spanning download. */
        fun forPath(points:List<PocketPoint>):OfflineJourneyArea {
            require(points.size in 2..20_000)
            val south=(points.minOf{it.latitude}-.002).coerceAtLeast(-85.0)
            val north=(points.maxOf{it.latitude}+.002).coerceAtMost(85.0)
            val middle=(south+north)/2
            val padding=.002/cos(Math.toRadians(middle)).coerceAtLeast(.1)
            val west=(points.minOf{it.longitude}-padding).coerceAtLeast(-180.0)
            val east=(points.maxOf{it.longitude}+padding).coerceAtMost(180.0)
            val width=PocketPoint(middle,west).distance(PocketPoint(middle,east))
            val height=PocketPoint(south,(west+east)/2).distance(PocketPoint(north,(west+east)/2))
            require(east-west<1.0 && north-south<1.0 && width*height<=100_000_000) {"Região offline maior que 100 km². Escolha um roteiro menor."}
            fun tileX(lon:Double,z:Int)=floor((lon+180)/360*(1 shl z)).toInt()
            fun tileY(lat:Double,z:Int)=floor((1-asinh(tan(Math.toRadians(lat)))/PI)/2*(1 shl z)).toInt()
            val count=(10..16).sumOf{z->(tileX(east,z)-tileX(west,z)+1).toLong()*(tileY(south,z)-tileY(north,z)+1)}
            require(count<=4000){"Região com muitos tiles. Escolha um roteiro menor para preparar offline."}
            return OfflineJourneyArea(south,west,north,east,count)
        }
    }
}
