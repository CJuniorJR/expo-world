package br.com.expoworld.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.expoworld.WorldApp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageOperationsTest {
    @Test fun duplicateFileWorkIsRejectedAndCancelReleasesLock() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as WorldApp
        val started=CompletableDeferred<Unit>();val waiting=CompletableDeferred<Unit>()
        lateinit var model:StorageOperations
        instrumentation.runOnMainSync {
            app.storageBusy=false
            model=StorageOperations(app)
            model.run {started.complete(Unit);waiting.await();"Finished"}
        }
        withTimeout(3000){started.await()}
        var duplicates=0
        instrumentation.runOnMainSync {
            model.run {duplicates++;"Duplicate"}
            assertTrue(model.running.value);assertTrue(app.storageBusy)
            model.cancel()
        }
        repeat(60){if(model.running.value)delay(50)}
        instrumentation.runOnMainSync {
            assertEquals(0,duplicates);assertFalse(model.running.value);assertFalse(model.busy.value);assertFalse(app.storageBusy)
        }
    }
}
