package pe.novatick.anitec.features.linking.presentation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pe.novatick.anitec.features.auth.domain.*
import pe.novatick.anitec.features.linking.domain.LinkedFarmer
import pe.novatick.anitec.features.linking.domain.LinkingRepository
import pe.novatick.anitec.features.livestock.domain.*
import java.text.SimpleDateFormat
import java.util.*

data class BasicCareState(val animalId:String="",val farmerId:String="",val title:String="",val description:String="",val date:String="",
 val attempted:Boolean=false,val busy:Boolean=false,val uncertain:Boolean=false,val error:AuthFailure?=null,val saved:BasicCare?=null,
 val detail:BasicCare?=null,val reading:Boolean=false,val readError:AuthFailure?=null,val readTarget:Pair<String,String>?=null)
data class CareSelection(
 val farmers: LinkRead<List<LinkedFarmer>> = LinkRead(),
 val inventory: LinkRead<Inventory> = LinkRead(),
)

class BasicCareViewModel(private val repo:LinkingRepository,private val role:Role,dispatcher:CoroutineDispatcher=Dispatchers.Main.immediate){
 data class History(val animalId: String? = null, val result: LinkRead<List<CareSummary>> = LinkRead())

 private val historyState = MutableStateFlow(History())
 val history = historyState.asStateFlow()
 private var historyRequest: Job? = null
 private var detailRequest: Job? = null

 fun readHistory(animalId: String) {
  if (!job.isActive) return
  historyRequest?.cancel()
  historyState.value = History(animalId, LinkRead(loading = true))
  historyRequest = scope.launch {
   val result = try {
    LinkRead(value = repo.medicalHistory(animalId))
   } catch (e: CancellationException) {
    throw e
   } catch (e: Exception) {
    LinkRead(error = e as? AuthFailure ?: AuthFailure(AuthProblem.INVALID_RESPONSE))
   }
   ensureActive()
   historyState.value = History(animalId, result)
  }
 }

 private val job=SupervisorJob();private val scope=CoroutineScope(job+dispatcher);private var formTicket=0L;private var readTicket=0L
 private val mutable=MutableStateFlow(BasicCareState());val state=mutable.asStateFlow()
 private val selectionState = MutableStateFlow(CareSelection())
 val selection = selectionState.asStateFlow()
 private var farmersRequest: Job? = null
 private var animalsRequest: Job? = null
 private var selectingTarget = false

 fun close() {
  job.cancel()
  mutable.value = BasicCareState()
  historyState.value = History()
  selectionState.value = CareSelection()
 }

 fun begin(farmer: String, animal: String) {
  if (!canEditForm()) return
  formTicket++
  farmersRequest?.cancel()
  animalsRequest?.cancel()
  selectionState.value = CareSelection()
  selectingTarget = farmer.isEmpty() && animal.isEmpty()
  mutable.value = BasicCareState(
   animalId = animal, farmerId = farmer,
   date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
  )
  if (selectingTarget) loadFarmers()
 }

 private fun canEditForm(): Boolean {
  val current = state.value
  return job.isActive && !current.busy && !current.uncertain && current.saved == null
 }

 fun loadFarmers() {
  if (role != Role.VETERINARIO || !selectingTarget || !canEditForm()) return
  farmersRequest?.cancel()
  selectionState.update { it.copy(farmers = LinkRead(loading = true)) }
  farmersRequest = scope.launch {
   val result = readSelection { repo.farmers() }
   ensureActive()
   selectionState.update { it.copy(farmers = result) }
  }
 }

 fun chooseFarmer(id: String) {
  if (!selectingTarget || !canEditForm() ||
      selection.value.farmers.value?.any { it.id == id } != true) return
  if (state.value.farmerId == id &&
      (selection.value.inventory.loading || selection.value.inventory.value != null)) return
  animalsRequest?.cancel()
  mutable.update { it.copy(farmerId = id, animalId = "", error = null) }
  selectionState.update { it.copy(inventory = LinkRead(loading = true)) }
  animalsRequest = scope.launch {
   val result = readSelection { repo.animals(id) }
   ensureActive()
   if (state.value.farmerId == id) selectionState.update { it.copy(inventory = result) }
  }
 }

 fun chooseAnimal(id: String) {
  if (!selectingTarget || !canEditForm()) return
  val owner = state.value.farmerId
  if (selection.value.inventory.value?.content?.any { it.id == id && it.ownerId == owner } == true) {
   mutable.update { it.copy(animalId = id, error = null) }
  }
 }

 private suspend fun <T> readSelection(read: suspend () -> T): LinkRead<T> = try {
  LinkRead(value = read())
 } catch (e: CancellationException) {
  throw e
 } catch (e: Exception) {
  LinkRead(error = e as? AuthFailure ?: AuthFailure(AuthProblem.INVALID_RESPONSE))
 }
 private fun edit(change:(BasicCareState)->BasicCareState){if(job.isActive && !state.value.busy && !state.value.uncertain)mutable.update{change(it).copy(error=null)}}
 fun title(value:String)=edit{it.copy(title=value)};fun description(value:String)=edit{it.copy(description=value)};fun date(value:String)=edit{it.copy(date=value)}
 fun save(){val s=state.value;if(!job.isActive || s.busy || s.uncertain || s.saved!=null)return
  mutable.update{it.copy(attempted=true)};if(role!=Role.VETERINARIO){mutable.update{it.copy(error=AuthFailure(AuthProblem.FORBIDDEN,403))};return}
  if (selectingTarget &&
      selection.value.inventory.value?.content?.any { it.id == s.animalId && it.ownerId == s.farmerId } != true) return
  val input=NewBasicCare(s.animalId,s.title,s.description,s.date);if(!careInputValid(input))return
  val ticket=formTicket;mutable.update{it.copy(busy=true,error=null)};scope.launch{
   try{val result=repo.createCare(s.farmerId,input);ensureActive();if(ticket==formTicket)mutable.update{it.copy(busy=false,saved=result)}}
   catch(e:CancellationException){throw e}catch(e:Exception){ensureActive();if(ticket==formTicket){val error=e as? AuthFailure ?: AuthFailure(AuthProblem.INVALID_RESPONSE);mutable.update{it.copy(busy=false,error=error,uncertain=error.problem in listOf(AuthProblem.NETWORK,AuthProblem.INVALID_RESPONSE) || error.status>=500)}}}
  }
 }
 fun consumeSaved() {
  if (mutable.value.saved != null) {
   historyRequest?.cancel()
   historyState.value = History()
  }
  mutable.update { it.copy(saved = null) }
 }
 fun read(id:String,animal:String){if(!job.isActive)return;val ticket=++readTicket;detailRequest?.cancel();mutable.update{it.copy(detail=null,reading=true,readError=null,readTarget=animal to id)};detailRequest=scope.launch{
  try{val result=repo.care(id,animal);ensureActive();if(ticket==readTicket)mutable.update{it.copy(detail=result,reading=false)}}
  catch(e:CancellationException){throw e}catch(e:Exception){ensureActive();if(ticket==readTicket)mutable.update{it.copy(reading=false,readError=e as? AuthFailure ?: AuthFailure(AuthProblem.INVALID_RESPONSE))}}
 }}
}
