package br.com.expoworld.storage

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.expoworld.WorldApp
import kotlinx.coroutines.*

/** File work survives activity rotation; a single job owns the storage lock. */
class StorageOperations(private val app: WorldApp): ViewModel() {
    val busy=mutableStateOf(false)
    val running=mutableStateOf(false)
    val message=mutableStateOf("")
    val progress=mutableFloatStateOf(-1f)
    val preview=mutableStateOf<BackupPreview?>(null)
    val revision=mutableIntStateOf(0)
    val backup=BackupManager(app)
    private var job:Job?=null
    fun run(allowPreview:Boolean=false, action:suspend()->String) {
        if(running.value || app.tracking.value.active || (app.storageBusy && !allowPreview)) {
            message.value="Encerre a viagem e aguarde a operação atual.";return
        }
        app.storageBusy=true;busy.value=true;running.value=true;progress.floatValue=-1f;message.value="Processando arquivo…"
        job=viewModelScope.launch {
            try {message.value=action();revision.intValue++}
            catch(e:CancellationException){message.value="Operação cancelada. Os dados salvos foram preservados.";throw e}
            catch(e:Exception){message.value=if(e is MapExportUnavailable || e is VideoExportUnavailable)e.message.orEmpty() else "Não consegui concluir. Confira o arquivo e o espaço livre; os dados anteriores foram preservados."}
            finally {running.value=false;app.storageBusy=preview.value!=null;busy.value=app.storageBusy;progress.floatValue=-1f}
        }
    }
    fun cancel(){job?.cancel()}
    fun dismissPreview(){
        if(running.value)return
        backup.cancelPrepared();preview.value=null;app.storageBusy=false;busy.value=false
    }
    override fun onCleared(){
        preview.value=null;job?.cancel();backup.cancelPrepared()
        if(!running.value)app.storageBusy=false
        super.onCleared()
    }
}
