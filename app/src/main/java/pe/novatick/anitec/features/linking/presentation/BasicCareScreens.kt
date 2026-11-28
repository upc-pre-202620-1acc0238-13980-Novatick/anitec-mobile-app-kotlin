package pe.novatick.anitec.features.linking.presentation
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import pe.novatick.anitec.R
import pe.novatick.anitec.features.linking.domain.visitLocalMillis
import pe.novatick.anitec.features.livestock.domain.*
import pe.novatick.anitec.features.livestock.presentation.*
import pe.novatick.anitec.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable internal fun LatestCareSection(animal:AnimalDetail,open:(String)->Unit,openHistory:()->Unit){
 Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
  LsText(stringResource(R.string.care_history), Modifier.weight(1f), bold = true)
  if (!animal.recentCare.isNullOrEmpty()) {
   TextButton(openHistory, Modifier.testTag("care_view_all")) {
    val total = animal.attentionsCount
    val label = if (total != null) stringResource(R.string.care_view_all_count, total)
        else stringResource(R.string.care_view_all)
    LsText(label, size = 12, bold = true, color = PrimaryGreen)
   }
  }
 }
 val items = animal.recentCare
 when {
  items == null -> LsNotice(stringResource(R.string.care_unavailable))
  items.isEmpty() -> LsNotice(stringResource(R.string.care_empty))
  else -> HistoryCard(items.first(), tag = "care_latest") { open(items.first().id) }
 }
}
@Composable
fun BasicCareForm(
 vm: BasicCareViewModel,
 farmer: String = "",
 animal: String = "",
 animalContext: AnimalDetail? = null,
 back: () -> Unit,
 saved: (String, String) -> Unit,
) {
 val s by vm.state.collectAsState()
 val choices by vm.selection.collectAsState()
 val selectTarget = farmer.isEmpty() && animal.isEmpty()
 LaunchedEffect(farmer, animal) { vm.begin(farmer, animal) }
 LaunchedEffect(s.saved) {
  s.saved?.let { result ->
   vm.consumeSaved()
   saved(s.farmerId, result.animalId)
  }
 }
 val locked = s.busy || s.uncertain
 var picker by remember { mutableStateOf(false) }
 if (picker) {
  val initial = visitLocalMillis(s.date, "12:00") ?: System.currentTimeMillis()
  VisitDatePicker(initial, { picker = false }) {
   vm.date(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)))
   picker = false
  }
 }
 AnimalPage(stringResource(R.string.lk_record_care), back, busy = s.busy,
  subtitle = stringResource(R.string.care_form_sub), pinnedHeader = true,
 ) {
  if (selectTarget) {
   val farmers = choices.farmers.value.orEmpty()
   val owner = farmers.firstOrNull { it.id == s.farmerId }
   AnimalSelect(
    stringResource(R.string.care_farmer), owner?.name ?: stringResource(R.string.care_select_farmer),
    farmers.map { it.id to it.name }, !locked && farmers.isNotEmpty(), "care_farmer",
    valueColor = if (owner == null) NeutralSlate else LivestockVisuals.Ink,
    onSelect = vm::chooseFarmer,
   )
   if (choices.farmers.loading) LsLoading(stringResource(R.string.lk_loading))
   if (choices.farmers.value?.isEmpty() == true) LsNotice(stringResource(R.string.care_no_farmers))
   choices.farmers.error?.let {
    LsNotice(animalFailureText(it), true)
    LsAction(stringResource(R.string.lk_reload), vm::loadFarmers, enabled = !locked)
   }
   val inventory = choices.inventory.value
   val animals = inventory?.content.orEmpty().map { candidate ->
    val name = candidate.name?.takeIf(String::isNotBlank)
    val nameAndCode = if (name == null) candidate.code else stringResource(R.string.care_byline, name, candidate.code)
    val farm = inventory?.farmNames?.get(candidate.farmId)
    candidate.id to if (farm == null) nameAndCode else stringResource(R.string.care_byline, nameAndCode, farm)
   }
   val chosen = animals.firstOrNull { it.first == s.animalId }
   AnimalSelect(
    stringResource(R.string.care_animal), chosen?.second ?: stringResource(R.string.care_select_animal),
    animals, !locked && owner != null && animals.isNotEmpty(), "care_animal",
    valueColor = if (chosen == null) NeutralSlate else LivestockVisuals.Ink,
    onSelect = vm::chooseAnimal,
   )
   if (choices.inventory.loading) LsLoading(stringResource(R.string.lk_loading))
   if (inventory?.content?.isEmpty() == true) LsNotice(stringResource(R.string.lk_no_animals))
   choices.inventory.error?.let {
    LsNotice(animalFailureText(it), true)
    LsAction(stringResource(R.string.lk_reload), { vm.chooseFarmer(s.farmerId) }, enabled = !locked)
   }
   if (s.attempted && chosen == null) LsNotice(stringResource(R.string.care_target_required), true)
  } else {
   animalContext?.let { info ->
    val name = info.name?.takeIf(String::isNotBlank)
    val nameAndCode = if (name == null) info.code else stringResource(R.string.care_byline, name, info.code)
    LsNotice(stringResource(R.string.care_byline, nameAndCode, info.farmName))
   }
  }
  LsText(stringResource(R.string.care_date), size = 12)
  Surface(onClick = { picker = true }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("care_date"),
   shape = RoundedCornerShape(13.dp), color = LivestockVisuals.Pale,
  ) {
   Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
    LsText(if (validCareDate(s.date)) careDateLabel(s.date, LocalConfiguration.current.locales[0]) else "",
     Modifier.weight(1f), size = 12)
    Icon(painterResource(R.drawable.link_calendar_official), null, Modifier.size(20.dp), tint = NeutralSlate)
   }
  }
  if (s.attempted && (!validCareDate(s.date) || s.date > SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))) {
   LsText(stringResource(R.string.care_invalid), size = 11, color = MaterialTheme.colorScheme.error)
  }
  AnimalField(
   s.title, vm::title, stringResource(R.string.care_reason), "", "care_title",
   if (s.attempted && (s.title.isBlank() || s.title.length > 150)) stringResource(R.string.care_invalid) else null,
   !locked, placeholder = stringResource(R.string.care_reason_hint),
  )
  val descriptionLabel = stringResource(R.string.care_description)
  LsText(descriptionLabel, size = 12)
  BasicTextField(
   s.description, vm::description, enabled = !locked,
   textStyle = TextStyle(fontFamily = Poppins, fontSize = 12.sp, color = LivestockVisuals.Ink),
   cursorBrush = SolidColor(PrimaryGreen),
   modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp).background(LivestockVisuals.Pale, RoundedCornerShape(13.dp))
    .padding(13.dp).testTag("care_description").semantics { contentDescription = descriptionLabel },
   decorationBox = { editor ->
    Box {
     if (s.description.isEmpty()) LsText(stringResource(R.string.care_description_hint), size = 12, color = NeutralSlate)
     editor()
    }
   },
  )
  if (s.attempted && s.description.isBlank()) LsText(stringResource(R.string.care_invalid), size = 11, color = MaterialTheme.colorScheme.error)
  s.error?.let { LsNotice(animalFailureText(it), true) }
  if (s.uncertain) LsNotice(stringResource(R.string.care_uncertain), true)
  if (s.busy) LsLoading(stringResource(R.string.lk_loading))
  LsAction(stringResource(R.string.care_save), vm::save, Modifier.fillMaxWidth().testTag("care_save"),
   primary = true, enabled = !locked && s.saved == null,
  )
 }
}

@Composable fun BasicCareDetail(vm:BasicCareViewModel,animal:String,id:String,animalInfo:AnimalDetail?=null,back:()->Unit){
 val s by vm.state.collectAsState();LaunchedEffect(animal,id){vm.read(id,animal)}
 AnimalPage(stringResource(R.string.care_detail),back){
  val matches = s.readTarget == (animal to id)
  if(!matches || s.reading)LsLoading(stringResource(R.string.lk_loading))
  s.readError?.takeIf { matches }?.let{LsNotice(animalFailureText(it),true);LsAction(stringResource(R.string.lk_reload),{vm.read(id,animal)})}
  s.detail?.takeIf { matches }?.let{value->
   animalInfo?.takeIf{it.id==value.animalId}?.let{info->Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,LivestockVisuals.Border)){
    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
     AnimalSpeciesImage(info.species)
     Column{LsText(info.name?.takeIf(String::isNotBlank) ?: info.code,bold=true,color=PrimaryGreen);LsText(info.code+" · "+info.species,size=11,color=NeutralSlate)}
    }
   }}
   Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,LivestockVisuals.Border)){
    Column {
     CareDetailField(stringResource(R.string.care_related), value.title)
     HorizontalDivider(color = LivestockVisuals.Border)
     CareDetailField(stringResource(R.string.care_vet), value.vetName)
     HorizontalDivider(color = LivestockVisuals.Border)
     CareDetailField(stringResource(R.string.care_date_read), careDateLabel(value.date, LocalConfiguration.current.locales[0]))
    }
   }
   LsText(stringResource(R.string.care_summary),bold=true)
   CarePanel(value.description)
   LsText(stringResource(R.string.care_treatments), bold = true)
   if (value.treatments.isEmpty()) CarePanel(stringResource(R.string.care_no_treatments))
   value.treatments.forEach { CareDatedEntry(it.description, it.date, Color(0xFFB45309), R.drawable.ic_book) }
   LsText(stringResource(R.string.care_vaccinations), bold = true)
   if (value.vaccinations.isEmpty()) CarePanel(stringResource(R.string.care_no_vaccinations))
   value.vaccinations.forEach {
    CareDatedEntry(it.name, it.date, Color(0xFF3366DD), R.drawable.ic_vaccine)
   }
   LsText(stringResource(R.string.care_instructions), bold = true)
   val instructions = value.instructions
   if (instructions == null) CarePanel(stringResource(R.string.care_no_instructions))
   else {
    val accent = Color(0xFFB27300)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, accent)) {
     Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(painterResource(R.drawable.ls_document_official), null, Modifier.size(22.dp), tint = accent)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
       LsText(instructions.content, size = 12)
       instructions.authorName?.let { LsText(it, size = 10, color = NeutralSlate) }
      }
     }
    }
   }
  }
 }
}

@Composable
internal fun MedicalHistoryScreen(
 vm: BasicCareViewModel, animalId: String, animal: AnimalDetail?, back: () -> Unit, open: (String) -> Unit,
) {
 val history by vm.history.collectAsState()
 LaunchedEffect(animalId) { if (vm.history.value.animalId != animalId) vm.readHistory(animalId) }
 val result = if (history.animalId == animalId) history.result else LinkRead(loading = true)
 AnimalPage(stringResource(R.string.care_history), back, titleSize = 19, pinnedHeader = true, scrollContent = false) {
  animal?.takeIf { it.id == animalId }?.let { info ->
   LsText(stringResource(R.string.care_animal_context, info.name?.takeIf(String::isNotBlank) ?: info.species, info.code), bold = true)
  }
  val error = result.error
  val rows = result.value
  when {
   result.loading -> LsLoading(stringResource(R.string.lk_loading))
   error != null -> {
    LsNotice(animalFailureText(error), true)
    LsAction(stringResource(R.string.lk_reload), { vm.readHistory(animalId) })
   }
   rows.isNullOrEmpty() -> LsNotice(stringResource(R.string.care_empty))
   else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    items(rows, key = { it.id }) { row -> HistoryCard(row) { open(row.id) } }
   }
  }
 }
}

@Composable
private fun HistoryCard(row: CareSummary, tag: String = "care_history_" + row.id, onClick: () -> Unit) {
 Surface(
  onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag),
  shape = RoundedCornerShape(15.dp), color = Color.White, border = BorderStroke(1.dp, LivestockVisuals.Border),
 ) {
  Row(
   Modifier.height(IntrinsicSize.Min).padding(horizontal = 13.dp, vertical = 12.dp),
   verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
   Box(Modifier.width(4.dp).fillMaxHeight().background(PrimaryGreenAccent, RoundedCornerShape(4.dp)))
   Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    LsText(row.title, size = 12, bold = true)
    val date = careDateLabel(row.date, LocalConfiguration.current.locales[0])
    LsText(stringResource(R.string.care_byline, date, row.vetName), size = 10, color = NeutralSlate)
    Text(row.summary, fontFamily = Poppins, fontSize = 10.sp, lineHeight = 15.sp,
     color = LivestockVisuals.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
   }
   Icon(painterResource(R.drawable.visit_chevron_official), null, Modifier.size(17.dp), tint = Color.Unspecified)
  }
 }
}

@Composable
private fun CareDetailField(label: String, value: String) {
 Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp)) {
  LsText(label, size = 10, color = NeutralSlate)
  LsText(value, size = 12, bold = true)
 }
}

@Composable
private fun CarePanel(value: String) {
 Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = Color(0xFFEEF3F7)) {
  LsText(value, Modifier.padding(13.dp), size = 12, color = LivestockVisuals.Secondary)
 }
}

@Composable
private fun CareDatedEntry(title: String, date: String, accent: Color, icon: Int? = null) {
 Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, accent)) {
  Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
   icon?.let {
    Box(Modifier.size(32.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
     Icon(painterResource(it), null, Modifier.size(20.dp), tint = accent)
    }
   }
   Column {
    LsText(title, size = 12, bold = true, color = accent)
    LsText(careDateLabel(date, LocalConfiguration.current.locales[0]), size = 10, color = NeutralSlate)
   }
  }
 }
}
