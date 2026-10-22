package br.com.expoworld.core
import org.junit.Assert.*
import org.junit.Test
class TripRecordQualityTest {
    @Test fun separatesMatchedGeometryFromRejectedReadingsWithoutInventingCredit() {
        val first = TraceSample(0,-25.43,-49.27,0.0,5.0,true,-1,0.0,5.0)
        val next = first.copy(sequence=1,latitude=-25.4301,elapsedSeconds=5.0,breakBefore=false,streetIds="dataset:road")
        val bad = next.copy(sequence=2,latitude=-25.44,elapsedSeconds=10.0,accepted=false,accuracyMeters=50.0)
        val resumed = next.copy(sequence=3,elapsedSeconds=15.0,breakBefore=true)
        val unknown = resumed.copy(sequence=4,latitude=-25.4302,elapsedSeconds=20.0,breakBefore=false,streetIds=null)
        val q = TripRecordQuality.from(RecordedTrace(0,20000,listOf(first,next,bad,resumed,unknown)))
        assertTrue(q.hasMetadata); assertEquals(1,q.rejectedReadings); assertEquals(1,q.inaccurateReadings)
        assertTrue(q.recognizedMeters in 10.0..12.0); assertTrue(q.unrecognizedMeters in 10.0..12.0)
    }
    @Test fun legacyMetadataRemainsUnknown() {
        val p = TraceSample(0,-25.43,-49.27,0.0,null,true,-1,0.0)
        assertFalse(TripRecordQuality.from(RecordedTrace(0,0,listOf(p))).hasMetadata)
    }
}
