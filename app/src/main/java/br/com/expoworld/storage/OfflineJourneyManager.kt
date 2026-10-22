package br.com.expoworld.storage



import android.os.Handler

import android.os.Looper

import br.com.expoworld.BuildConfig

import br.com.expoworld.WorldApp

import br.com.expoworld.core.*

import kotlinx.coroutines.*

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.update

import org.json.JSONObject

import org.maplibre.android.geometry.LatLngBounds

import org.maplibre.android.offline.*

import java.io.File

import java.util.UUID



/** Route text and geometry are local even when the provider prohibits map prefetching. */

data class OfflineJourney(val id:String,val title:String,val plan:PocketPlan,val regionId:Long?=null,

    val mapComplete:Boolean=false,val completed:Long=0,val required:Long=0,val bytes:Long=0,val message:String="")

data class OfflineJourneyState(val journeys:List<OfflineJourney> = emptyList(),val busy:Boolean=false,val message:String="",val activeId:String?=null)

class OfflineJourneyManager(private val app:WorldApp) {

    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)

    private val directory=File(app.filesDir,"offline-journeys")

    private val manager by lazy { OfflineManager.getInstance(app) }

    private val main=Handler(Looper.getMainLooper())

    val state=MutableStateFlow(OfflineJourneyState())

    private var active:OfflineRegion?=null

    private var generation=0

    val mapsAllowed get()=BuildConfig.OFFLINE_MAPS_ALLOWED

    val policyMessage get()=if(mapsAllowed) "Download limitado à região do roteiro, zoom 10–16. Recalcular percursos continua exigindo internet."

        else "O MapTiler Cloud exige acordo/plano Custom para baixar regiões. Neste app, roteiro e orientações ficam no aparelho; o download do mapa está desativado até configurar um provedor autorizado."

    init { refresh() }

    fun refresh() { scope.launch {

        val rows=withContext(Dispatchers.IO) {

            directory.listFiles().orEmpty().filter { it.extension=="json" && it.length()<=2_100_000 }.take(5).mapNotNull { file ->

                runCatching {

                    val json=JSONObject(file.readText());val plan=PocketPlanCodec.decode(json.getString("plan"))

                    OfflineJourney(json.getString("id"),json.getString("title"),plan,

                        if(json.isNull("regionId"))null else json.getLong("regionId"),false,message="Roteiro e orientações salvos no aparelho.")

                }.getOrNull()

            }

        }

        state.update { it.copy(journeys=rows) }

        manager.listOfflineRegions(object:OfflineManager.ListOfflineRegionsCallback {

            override fun onList(regions:Array<OfflineRegion>?) {

                rows.forEach { row -> regions.orEmpty().firstOrNull { it.id==row.regionId }?.let { region ->

                    region.getStatus(object:OfflineRegion.OfflineRegionStatusCallback {

                        override fun onStatus(status:OfflineRegionStatus?) { if(status!=null)updateStatus(row.id,status,false) }

                        override fun onError(error:String?) { updateRow(row.id) { it.copy(mapComplete=false,message="Não consegui verificar o mapa baixado.") } }

                    })

                } }

            }

            override fun onError(error:String) { state.update { it.copy(message="Não consegui verificar os mapas locais. Seus roteiros continuam salvos.") } }

        })

    } }

    fun prepare(plan:PocketPlan,title:String="Meu passeio offline") {

        if(state.value.busy) return

        val existing=state.value.journeys.firstOrNull { it.plan.id==plan.id }

        if(existing?.mapComplete==true) {state.update{it.copy(message="Este roteiro e seu mapa já estão preparados.")};return}

        if(existing==null && state.value.journeys.size>=5) {state.update{it.copy(message="Limite de cinco passeios. Remova um pacote antes de preparar outro.")};return}

        val token=++generation

        state.update{it.copy(busy=true,message="Salvando roteiro e orientações no aparelho…")}

        scope.launch {

            try {

                val row=existing ?: OfflineJourney(UUID.randomUUID().toString(),title.trim().ifBlank{"Meu passeio offline"}.take(80),plan,message="Roteiro e orientações salvos.")

                persist(row)

                // Include route geometry/instructions in complete backups without redistributing provider tiles.

                app.database.cells().putPlannedRoute(PlannedRouteRow("offline-${row.id}",row.title,System.currentTimeMillis(),PocketPlanCodec.encode(plan)))

                state.update{it.copy(journeys=it.journeys.filterNot { item->item.id==row.id }+row,activeId=if(token==generation)row.id else it.activeId)}

                if(token!=generation) return@launch

                if(!mapsAllowed) {state.update{it.copy(busy=false,activeId=null,message=policyMessage)};return@launch}

                val style=BuildConfig.MAP_STYLE_URL.ifBlank { if(BuildConfig.MAPTILER_KEY.isBlank()) "" else "https://api.maptiler.com/maps/streets-v4/style.json?key=${BuildConfig.MAPTILER_KEY}" }

                check(style.startsWith("https://")) { "Configure um estilo HTTPS de um provedor autorizado para mapas offline." }

                val bounds=OfflineJourneyArea.forPath(plan.path.points)

                val definition=OfflineTilePyramidRegionDefinition(style,LatLngBounds.from(bounds.north,bounds.east,bounds.south,bounds.west),10.0,16.0,1f,false)

                fun create() {

                    if(token!=generation)return

                    manager.createOfflineRegion(definition,JSONObject().put("expoWorldOfflineJourney",row.id).toString().toByteArray(),object:OfflineManager.CreateOfflineRegionCallback {

                        override fun onCreate(region:OfflineRegion) {

                            if(token!=generation) {removeRegion(region) {};return}

                            active=region

                            scope.launch {

                            try {

                            persist(row.copy(regionId=region.id))

                            if(token!=generation){region.setDownloadState(OfflineRegion.STATE_INACTIVE);return@launch}

                            updateRow(row.id){it.copy(regionId=region.id,message="Baixando mapa…")}

                            region.setObserver(object:OfflineRegion.OfflineRegionObserver {

                                override fun onStatusChanged(status:OfflineRegionStatus) {

                                    if(token!=generation)return

                                    if(status.completedResourceSize>50L*1024*1024 || status.completedTileCount>6000) {

                                        fail(row.id,"O pacote excedeu 50 MB ou 6.000 tiles. Roteiro salvo; mapa incompleto.");return

                                    }

                                    updateStatus(row.id,status,true)

                                    if(status.isComplete) {

                                        region.setDownloadState(OfflineRegion.STATE_INACTIVE);active=null

                                        state.update{it.copy(busy=false,activeId=null,message="Mapa, roteiro e orientações preparados. Cobertura limitada à região e zoom 10–16.")}

                                    }

                                }

                                override fun onError(error:OfflineRegionError) { if(token==generation)fail(row.id,"Falha ao baixar o mapa. Roteiro salvo; mapa incompleto. Confira internet e espaço livre e tente novamente.") }

                                override fun mapboxTileCountLimitExceeded(limit:Long) {if(token==generation)fail(row.id,"Limite de tiles atingido. Roteiro salvo; mapa incompleto.")}

                            })

                            region.setDownloadState(OfflineRegion.STATE_ACTIVE)

                            }catch(e:CancellationException){throw e}catch(_:Exception){if(token==generation)fail(row.id,"Não consegui salvar os dados do mapa. Download interrompido.")}

                            }

                        }

                        override fun onError(error:String) { if(token==generation)fail(row.id,"Não consegui criar o pacote de mapa. Roteiro e orientações continuam salvos.") }

                    })

                }

                if(existing?.regionId!=null) manager.getOfflineRegion(existing.regionId,object:OfflineManager.GetOfflineRegionCallback {

                    override fun onRegion(region:OfflineRegion) {removeRegion(region){create()}}

                    override fun onRegionNotFound(){create()}

                    override fun onError(error:String) {if(token==generation)fail(row.id,"Não consegui substituir o mapa anterior. Tente novamente.")}

                }) else create()

            } catch(e:CancellationException){throw e} catch(e:Exception) {

                state.update{it.copy(busy=false,activeId=null,message=e.message?.takeIf{m->m.length<200 && !m.contains("http")} ?: "Não consegui preparar o passeio offline.")}

            }

        }

    }

    fun cancel() {

        generation++;active?.setDownloadState(OfflineRegion.STATE_INACTIVE);active=null

        val id=state.value.activeId

        if(id!=null) updateRow(id){it.copy(mapComplete=false,message="Download interrompido. Roteiro salvo; mapa incompleto.")}

        state.update{it.copy(busy=false,activeId=null,message="Download interrompido. Roteiro e orientações continuam no aparelho.")}

    }

    fun delete(id:String) {

        if(state.value.busy) return

        val row=state.value.journeys.firstOrNull{it.id==id} ?: return

        fun finish() {scope.launch {

            val deleted=withContext(Dispatchers.IO){val file=File(directory,"$id.json");!file.exists() || file.delete()}

            if(!deleted){state.update{it.copy(message="Não consegui remover o pacote. Confira o armazenamento e tente novamente.")};return@launch}

            state.update{it.copy(journeys=it.journeys.filterNot{r->r.id==id},message="Pacote removido. Trajetos e descobertas preservados.")}

        }}

        if(row.regionId==null)finish() else manager.getOfflineRegion(row.regionId,object:OfflineManager.GetOfflineRegionCallback {

            override fun onRegion(region:OfflineRegion) {removeRegion(region){finish()} }

            override fun onRegionNotFound(){finish()}

            override fun onError(error:String){state.update{it.copy(message="Não consegui remover o mapa. Tente novamente.")}}

        })

    }

    private fun fail(id:String,message:String){generation++;active?.setDownloadState(OfflineRegion.STATE_INACTIVE);active=null;updateRow(id){it.copy(mapComplete=false,message=message)};state.update{it.copy(busy=false,activeId=null,message=message)}}

    private fun updateStatus(id:String,status:OfflineRegionStatus,downloading:Boolean){updateRow(id){it.copy(mapComplete=status.isComplete,completed=status.completedResourceCount,required=if(status.isRequiredResourceCountPrecise)status.requiredResourceCount else 0,bytes=status.completedResourceSize,

        message=if(status.isComplete)"Mapa preparado • zoom 10–16" else if(downloading)"Baixando mapa…" else "Mapa incompleto; tente preparar novamente.")}}

    private fun updateRow(id:String,block:(OfflineJourney)->OfflineJourney){state.update{it.copy(journeys=it.journeys.map{row->if(row.id==id)block(row) else row})}}

    private suspend fun persist(row:OfflineJourney)=withContext(Dispatchers.IO){

        directory.mkdirs();val destination=File(directory,"${row.id}.json");val pending=File(directory,"${row.id}.tmp")

        pending.writeText(JSONObject().put("id",row.id).put("title",row.title).put("plan",PocketPlanCodec.encode(row.plan)).put("regionId",row.regionId ?: JSONObject.NULL).toString())

        check(pending.renameTo(destination)){"Não consegui salvar o roteiro offline."}

    }

    private fun removeRegion(region:OfflineRegion,done:()->Unit){region.setDownloadState(OfflineRegion.STATE_INACTIVE);region.delete(object:OfflineRegion.OfflineRegionDeleteCallback{override fun onDelete(){main.post{done()}};override fun onError(error:String){state.update{it.copy(busy=false,activeId=null,message="Não consegui remover o pacote de mapa. Tente novamente.")}}})}

}

