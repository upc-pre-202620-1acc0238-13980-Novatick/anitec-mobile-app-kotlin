package pe.novatick.anitec.linking
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import pe.novatick.anitec.core.network.AuthorizedRequests
import pe.novatick.anitec.features.auth.domain.*
import pe.novatick.anitec.features.auth.infrastructure.Envelope
import pe.novatick.anitec.features.linking.infrastructure.*
import pe.novatick.anitec.features.linking.domain.filterLinkedAnimals
import pe.novatick.anitec.features.livestock.domain.*
import pe.novatick.anitec.features.livestock.infrastructure.LivestockApi
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class BasicCareRepositoryTest {
 private val server=MockWebServer();private var role=Role.VETERINARIO;private var afterResponse:()->Unit={}
 private val owner="10000000-0000-4000-8000-000000000001";private val vet="20000000-0000-4000-8000-000000000001";private val animal="60000000-0000-4000-8000-000000000001";private val farm="50000000-0000-4000-8000-000000000001";private val care="90000000-0000-4000-8000-000000000001"
 private lateinit var repo:LinkingRepositoryImpl
 private val input get()=NewBasicCare(animal," Control ","Description","2020-02-29")
 @Before fun setup(){server.start();val retrofit=Retrofit.Builder().baseUrl(server.url("/api/v1/")).buildConverter().build();val auth=object:AuthorizedRequests{override suspend fun <T> execute(request:suspend(String)->Response<Envelope<T>>):T{val r=request("Bearer synthetic");afterResponse();if(!r.isSuccessful)throw AuthFailure(AuthProblem.SERVER,r.code());return r.body()!!.data!!}};repo=LinkingRepositoryImpl(retrofit.create(LinkingApi::class.java),auth,retrofit.create(LivestockApi::class.java)){Profile(if(role==Role.GANADERO)owner else vet,"qa@example.invalid","QA","User",role)}}
 private fun Retrofit.Builder.buildConverter()=addConverterFactory(GsonConverterFactory.create())
 @After fun stop(){server.shutdown()}
 private fun reply(data:String,code:Int=200){server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody("""{"success":true,"data":$data}"""))}
 private fun detail(id:String=care)="""{"id":"$id","animalId":"$animal","title":"Control","description":"Description","attentionDate":"2020-02-29","veterinarianId":"$vet","vetName":"QA Vet"}"""
 private fun scope(animalFarm:String=farm,attentionsCount:Long=0){reply("""{"activeLinksCount":1,"farmers":[{"farmerId":"$owner","farmerName":"Farmer","animalsCount":1,"farms":[{"farmId":"$farm","farmName":"Farm","animalsCount":1}]}]}""");reply("""{"id":"$animal","code":"AN-001","species":"Bovino","speciesId":"70000000-0000-4000-8000-000000000001","sex":"HEMBRA","status":"ACTIVO","farmId":"$animalFarm","farmName":"Farm","version":0,"attentionsCount":$attentionsCount,"recentAttentions":[]}""")}
 @Test fun exactFourFieldPost201AndServerTitleTrimming()=runBlocking{scope();reply(detail(),201);assertEquals(care,repo.createCare(owner,input).id);server.takeRequest();server.takeRequest();val request=server.takeRequest();assertEquals("/api/v1/attentions",request.path);assertEquals("POST",request.method);val json=com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject;assertEquals(setOf("animalId","title","description","attentionDate"),json.keySet());assertEquals(input.title,json["title"].asString);assertEquals(input.date,json["attentionDate"].asString)}
 @Test fun roleAndValidationNeverWrite()=runBlocking{role=Role.GANADERO;try{repo.createCare(owner,input);fail()}catch(e:AuthFailure){assertEquals(403,e.status)};role=Role.VETERINARIO;try{repo.createCare(owner,input.copy(title="x".repeat(151)));fail()}catch(e:AuthFailure){assertEquals(400,e.status)};assertEquals(0,server.requestCount)}
 @Test fun ownerCanReadButWrongAnimalAndHttpErrorsNeverBecomeData()=runBlocking{role=Role.GANADERO;reply(detail());assertEquals(care,repo.care(care,animal).id);assertEquals("/api/v1/attentions/$care",server.takeRequest().path);reply(detail());try{repo.care(care,farm);fail()}catch(e:AuthFailure){assertEquals(AuthProblem.INVALID_RESPONSE,e.problem)};server.enqueue(MockResponse().setResponseCode(403));try{repo.care(care,animal);fail()}catch(e:AuthFailure){assertEquals(403,e.status)}}
 @Test fun failedPostDoesNotRetryOrInventSuccess()=runBlocking{scope();server.enqueue(MockResponse().setResponseCode(503));try{repo.createCare(owner,input);fail()}catch(e:AuthFailure){assertEquals(503,e.status)};assertEquals(3,server.requestCount)}
 @Test fun latestMetadataKeepsUnknownDistinctFromEmptyAndSortsCivilDates(){
  assertNull(pe.novatick.anitec.features.livestock.infrastructure.recentCare(null));assertTrue(pe.novatick.anitec.features.livestock.infrastructure.recentCare(emptyList())!!.isEmpty())
  val rows=listOf(pe.novatick.anitec.features.livestock.infrastructure.CareSummaryDto(care,"Old","Vet","2020-02-29","Old"),pe.novatick.anitec.features.livestock.infrastructure.CareSummaryDto(animal,"New","Vet","2021-01-01","New"))
  assertEquals("New",pe.novatick.anitec.features.livestock.infrastructure.recentCare(rows)!!.first().title)
 }
 @Test fun revokedLinkBeforePreflightNeverWrites()=runBlocking {
  reply("""{"activeLinksCount":0,"farmers":[]}""")
  try{repo.createCare(owner,input);fail()}catch(e:AuthFailure){assertEquals(403,e.status)}
  assertEquals(1,server.requestCount);assertEquals("GET",server.takeRequest().method)
 }
 @Test fun animalFromAnotherFarmNeverWrites()=runBlocking {
  scope(care)
  try{repo.createCare(owner,input);fail()}catch(e:AuthFailure){assertEquals(403,e.status)}
  assertEquals(2,server.requestCount);repeat(2){assertEquals("GET",server.takeRequest().method)}
 }
 @Test fun revocationBetweenPreflightAndPostIsNotSuccessOrRetry()=runBlocking {
  scope();server.enqueue(MockResponse().setResponseCode(403))
  try{repo.createCare(owner,input);fail()}catch(e:AuthFailure){assertEquals(403,e.status)}
  assertEquals(3,server.requestCount);server.takeRequest();server.takeRequest();assertEquals("POST",server.takeRequest().method)
 }
 @Test fun ownerForbiddenReadDoesNotReturnForeignCare()=runBlocking {
  role=Role.GANADERO;server.enqueue(MockResponse().setResponseCode(403))
  try{repo.care(care,animal);fail()}catch(e:AuthFailure){assertEquals(403,e.status)}
  assertEquals(1,server.requestCount);assertEquals("GET",server.takeRequest().method)
 }
 @Test fun accountChangeDuringReadDiscardsPreviousAccountResponse()=runBlocking {
  reply(detail());afterResponse={role=Role.GANADERO}
  try{repo.care(care,animal);fail()}catch(e:AuthFailure){assertEquals(AuthProblem.RELOGIN,e.problem)}
  assertEquals(1,server.requestCount)
 }
 @Test fun accountChangeDuringPostCannotConfirmSavedForNewAccount()=runBlocking {
  scope();reply(detail(),201);afterResponse={if(server.requestCount==3)role=Role.GANADERO}
  try{repo.createCare(owner,input);fail()}catch(e:AuthFailure){assertEquals(AuthProblem.RELOGIN,e.problem)}
  assertEquals(3,server.requestCount)
 }
 @Test fun historyUsesAnimalUuidAndReturnsZeroOneAndAllRowsInStableOrder()=runBlocking {
  val newer="90000000-0000-4000-8000-000000000003"
  val older="90000000-0000-4000-8000-000000000002"
  for (viewer in listOf(Role.GANADERO, Role.VETERINARIO)) {
   role=viewer
   reply("[]")
   assertTrue(repo.medicalHistory(animal).isEmpty())
   reply("[ "+detail()+" ]")
   assertEquals(care,repo.medicalHistory(animal).single().id)
   val next=detail(newer).replace("2020-02-29","2021-01-01")
   reply("["+detail(older)+","+detail()+","+next+"]")
   assertEquals(listOf(newer,care,older),repo.medicalHistory(animal).map{it.id})
   repeat(3) {
    val request=server.takeRequest()
    assertEquals("GET",request.method)
    assertEquals("/api/v1/animals/$animal/medical-history",request.path)
   }
  }
 }
 @Test fun historyRejectsMismatchedOrRepeatedEntriesAndDenials()=runBlocking {
  for (body in listOf("["+detail().replace(animal,farm)+"]","["+detail()+","+detail()+"]")) {
   reply(body)
   try{repo.medicalHistory(animal);fail()}catch(e:AuthFailure){assertEquals(AuthProblem.INVALID_RESPONSE,e.problem)}
  }
  for (status in listOf(401,403,404)) {
   server.enqueue(MockResponse().setResponseCode(status))
   try{repo.medicalHistory(animal);fail()}catch(e:AuthFailure){assertEquals(status,e.status)}
  }
  assertEquals(5,server.requestCount)
 }
 @Test fun historyDiscardsResponseAfterAccountChange()=runBlocking {
  afterResponse={role=Role.GANADERO}
  reply("[ "+detail()+" ]")
  try{repo.medicalHistory(animal);fail()}catch(e:AuthFailure){assertEquals(AuthProblem.RELOGIN,e.problem)}
 }
 @Test fun fullDetailReadsContractualClinicalFieldsAndNullableInstructions()=runBlocking {
  val clinical=detail().dropLast(1)+""","treatments":[{"description":"Curación","date":"2020-02-29"}],"vaccinations":[{"name":"Vacuna QA","date":"2020-02-28"}],"instructions":{"content":"Reposo","authorName":"QA Vet","updatedAt":"2020-02-29T18:00:00Z"}}"""
  reply(clinical)
  val result=repo.care(care,animal)
  assertEquals(CareTreatment("Curación","2020-02-29"),result.treatments.single())
  assertEquals(CareVaccination("Vacuna QA","2020-02-28"),result.vaccinations.single())
  assertEquals(CareInstructions("Reposo","QA Vet","2020-02-29T18:00:00Z"),result.instructions)
  assertEquals("/api/v1/attentions/$care",server.takeRequest().path)
  reply(detail().dropLast(1)+""","treatments":[],"vaccinations":[],"instructions":null}""")
  val empty=repo.care(care,animal)
  assertTrue(empty.treatments.isEmpty())
  assertTrue(empty.vaccinations.isEmpty())
  assertNull(empty.instructions)
 }
 @Test fun linkedFarmMenuIncludesEmptyFarmsAndFiltersByIdAndSpecies()=runBlocking {
  val second="50000000-0000-4000-8000-000000000002"
  val empty="50000000-0000-4000-8000-000000000003"
  val bovine="70000000-0000-4000-8000-000000000001"
  val ovine="70000000-0000-4000-8000-000000000002"
  reply("""{"activeLinksCount":1,"farmers":[{"farmerId":"$owner","farmerName":"QA","animalsCount":2,"farms":[{"farmId":"$farm","farmName":"Demo","animalsCount":1},{"farmId":"$second","farmName":"Demo","animalsCount":1},{"farmId":"$empty","farmName":"Vacía","animalsCount":0}]}]}""")
  fun animalRow(id:String,location:String,kind:String,code:String) =
   """{"id":"$id","code":"$code","species":"QA","speciesId":"$kind","farmId":"$location","ownerId":"$owner","status":"ACTIVO","version":0}"""
  val cow=animalRow(animal,farm,bovine,"BOV-001")
  val sheep=animalRow(care,second,ovine,"OV-001")
  reply("""{"totalActive":1,"content":[$cow]}""")
  reply("""{"totalActive":1,"content":[$sheep]}""")
  reply("""{"totalActive":0,"content":[]}""")
  val inventory=repo.animals(owner)
  assertEquals(mapOf(farm to "Demo",second to "Demo",empty to "Vacía"),inventory.farmNames)
  assertEquals(listOf(animal,care),inventory.content.filterLinkedAnimals("",null,null).map{it.id})
  assertEquals(listOf(animal),inventory.content.filterLinkedAnimals("bov",bovine,farm).map{it.id})
  assertEquals(listOf(care),inventory.content.filterLinkedAnimals("",ovine,second).map{it.id})
  assertTrue(inventory.content.filterLinkedAnimals("",ovine,farm).isEmpty())
  assertTrue(inventory.content.filterLinkedAnimals("",null,empty).isEmpty())
  server.takeRequest()
  for (farmId in listOf(farm,second,empty)) assertEquals("/api/v1/farms/$farmId/animals",server.takeRequest().path)
 }
 @Test fun linkedAnimalKeepsServerAttentionTotalBeyondPreviewSize()=runBlocking {
  scope(attentionsCount=12)
  val result=repo.animal(owner,animal)
  assertEquals(12L,result.attentionsCount)
  assertTrue(result.recentCare!!.isEmpty())
  assertEquals("Farm",result.farmName)
 }
}
