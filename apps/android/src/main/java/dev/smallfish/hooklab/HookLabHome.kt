package dev.smallfish.hooklab

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.smallfish.hooklab.protocol.RuleWire
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ink=Color(0xFF0B111A)
private val panel=Color(0xFF17212D)
private val stroke=Color(0xFF2E4052)
private val accent=Color(0xFF64E7C2)
private val muted=Color(0xFF9CAEBE)
private val white=Color(0xFFF4F8FC)
private val danger=Color(0xFFFFAC91)

private data class AppEntry(val name:String,val pkg:String)
private data class CallEntry(
    val id:Long,val method:String,val args:String,val result:String,val status:String,
    val ns:Long,val pid:Int,val tid:Int
)
private data class Record(
    val classes:List<String> = emptyList(),val methods:List<String> = emptyList(),
    val browsed:String = "",val heartbeat:Long = -1,val rules:List<String> = emptyList(),
    val calls:List<CallEntry> = emptyList()
)
private enum class Tab { APPS, METHODS, LIVE }

class HookLabHome : ComponentActivity() {
    private val endpoint=Uri.parse("content://"+RuleWire.EVENT_AUTHORITY)
    private val calls=Uri.parse("content://"+RuleWire.EVENT_AUTHORITY+"/events")
    private val prefs by lazy { getSharedPreferences("hooklab.ui.v2",MODE_PRIVATE) }
    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var selected by mutableStateOf("")
    private var tab by mutableStateOf(Tab.APPS)
    private var record by mutableStateOf(Record())
    private var online by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var visibleNow by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var chosenClass by mutableStateOf("")
    private var errorMessage by mutableStateOf("")
    private var awaitingConsent by mutableStateOf<AppEntry?>(null)

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        selected=prefs.getString("selected","")?:""
        window.statusBarColor=android.graphics.Color.rgb(11,17,26)
        window.navigationBarColor=android.graphics.Color.rgb(11,17,26)
        lifecycleScope.launch { loadApps() }
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(
                primary=accent,onPrimary=ink,background=ink,onBackground=white,
                surface=panel,onSurface=white
            )) {
                val active=visibleNow
                LaunchedEffect(active,selected) {
                    if(active) while(isActive) {
                        refresh()
                        delay(1300)
                    }
                }
                Screen(
                    apps=apps,selected=selected,tab=tab,record=record,online=online,
                    busy=busy,chosen=chosenClass,query=query,error=errorMessage,
                    changeTab={tab=it;query=""},
                    search={query=it},consent={awaitingConsent=it},
                    browse={browseClass(it)},toggle={toggle(it)},
                    openTarget={openTarget()},
                    clear={lifecycleScope.launch {
                        withContext(Dispatchers.IO) { contentResolver.call(endpoint,"clear",null,null) }
                        refresh()
                    }}
                )
                awaitingConsent?.let { candidate ->
                    AlertDialog(
                        onDismissRequest={awaitingConsent=null},
                        title={Text("Authorize this application?")},
                        text={Text("Choose only an application you own or have explicit permission to test.\n\n"+
                            candidate.name+"\n"+candidate.pkg+"\n\nVector must approve its scope.")},
                        confirmButton={TextButton(onClick={
                            awaitingConsent=null
                            authorize(candidate)
                        }){Text("AUTHORIZE")}},
                        dismissButton={TextButton(onClick={awaitingConsent=null}){Text("CANCEL")}}
                    )
                }
            }
        }
    }
    override fun onResume() {super.onResume();visibleNow=true}
    override fun onPause() {visibleNow=false;super.onPause()}

    private suspend fun loadApps() {
        apps=withContext(Dispatchers.IO) {
            val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(intent,0).mapNotNull { item ->
                val info=item.activityInfo?.applicationInfo ?: return@mapNotNull null
                val id=info.packageName
                if(id==packageName||!RuleWire.packageName(id)||
                   (info.flags and ApplicationInfo.FLAG_SYSTEM)!=0) null
                else AppEntry(info.loadLabel(packageManager).toString(),id)
            }.distinctBy {it.pkg}.sortedBy {it.name.lowercase()}
        }
    }
    private fun authorize(candidate:AppEntry) {
        if(busy) return
        busy=true
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) {
                runCatching { HookLabApp.requireService().getScope().contains(candidate.pkg) }
            }
            result.onFailure {busy=false;errorMessage=it.message?:"Vector service unavailable"}
            result.onSuccess {scoped ->
                if(scoped) lifecycleScope.launch {setTarget(candidate)}
                else try {
                    HookLabApp.requireService().requestScope(listOf(candidate.pkg),
                        object:XposedService.OnScopeEventListener {
                            override fun onScopeRequestApproved(approved:List<String>) {
                                runOnUiThread {
                                    if(approved.contains(candidate.pkg))
                                        lifecycleScope.launch {setTarget(candidate)}
                                    else {busy=false;errorMessage="Scope was not approved"}
                                }
                            }
                            override fun onScopeRequestFailed(message:String) {
                                runOnUiThread {busy=false;errorMessage=message}
                            }
                        })
                } catch(ex:Exception) {busy=false;errorMessage=ex.message?:"Scope request failed"}
            }
        }
    }
    private suspend fun setTarget(candidate:AppEntry) {
        val outcome=withContext(Dispatchers.IO) { runCatching {
            if(!HookLabApp.remoteRules().edit()
                 .putString(RuleWire.SELECTED_TARGET,candidate.pkg).commit())
                error("Vector did not store the selected target")
            prefs.edit().putString("selected",candidate.pkg).commit()
            contentResolver.call(endpoint,"reset",null,null)
        }}
        busy=false
        outcome.onSuccess {
            selected=candidate.pkg;record=Record();chosenClass="";tab=Tab.METHODS
            query="";errorMessage=""
        }.onFailure {errorMessage=it.message?:"Target selection failed"}
    }
    private suspend fun refresh() {
        val answer=withContext(Dispatchers.IO) {runCatching {
            val connected=HookLabApp.connected()
            val meta=contentResolver.call(endpoint,"snapshot",null,null)
                ?:error("Trace provider unavailable")
            val cls=meta.getString("classes","").split('\n')
                .filter{RuleWire.classForTarget(selected,it)}
            val m=meta.getString("methods","").split('\n')
                .filter{RuleWire.valid(selected,it)}
            val rules=if(connected && selected.isNotBlank()) RuleWire.decode(
                selected,HookLabApp.remoteRules().getString(
                    RuleWire.RULES_PREFIX+selected,"")?:"") else emptyList()
            val recent=ArrayList<CallEntry>()
            contentResolver.query(calls,null,null,null,null).use { cursor ->
                if(cursor!=null) {
                    val id=cursor.getColumnIndexOrThrow("id")
                    val method=cursor.getColumnIndexOrThrow("method")
                    val args=cursor.getColumnIndexOrThrow("args")
                    val result=cursor.getColumnIndexOrThrow("result")
                    val status=cursor.getColumnIndexOrThrow("outcome")
                    val duration=cursor.getColumnIndexOrThrow("duration_ns")
                    val pid=cursor.getColumnIndexOrThrow("pid")
                    val tid=cursor.getColumnIndexOrThrow("tid")
                    while(cursor.moveToNext()) recent.add(CallEntry(
                        cursor.getLong(id),cursor.getString(method),cursor.getString(args),
                        cursor.getString(result),cursor.getString(status),
                        cursor.getLong(duration),cursor.getInt(pid),cursor.getInt(tid)))
                }
            }
            Pair(connected,Record(cls,m,meta.getString("method_class",""),
                meta.getLong("heartbeat",-1),rules,recent))
        }}
        answer.onSuccess{online=it.first;record=it.second;errorMessage=""}
            .onFailure{online=HookLabApp.connected();errorMessage=it.message?:"Unable to refresh"}
    }
    private fun browseClass(name:String) {
        if(!RuleWire.classForTarget(selected,name))return
        chosenClass=name
        lifecycleScope.launch {
            val outcome=withContext(Dispatchers.IO) {runCatching {
                HookLabApp.remoteRules().edit()
                    .putString(RuleWire.BROWSE_PREFIX+selected,name).apply()
            }}
            outcome.onFailure {errorMessage=it.message?:"Browse request failed"}
        }
    }
    private fun toggle(rule:String) {
        if(busy || !RuleWire.valid(selected,rule))return
        busy=true
        lifecycleScope.launch {
            val outcome=withContext(Dispatchers.IO) {runCatching {
                val store=HookLabApp.remoteRules()
                val existing=RuleWire.decode(selected,store.getString(
                    RuleWire.RULES_PREFIX+selected,"")?:"").toMutableList()
                if(existing.contains(rule))existing.remove(rule)else existing.add(rule)
                if(!store.edit().putString(RuleWire.RULES_PREFIX+selected,
                        RuleWire.encode(selected,existing)).commit())
                    error("Vector rejected the rule")
            }}
            busy=false
            outcome.onFailure {errorMessage=it.message?:"Rule update failed"}
            refresh()
        }
    }
    private fun openTarget() {
        val launch=packageManager.getLaunchIntentForPackage(selected)
        if(launch==null)Toast.makeText(this,"Target cannot be opened",Toast.LENGTH_SHORT).show()
        else startActivity(launch)
    }
}

@Composable private fun Screen(
    apps:List<AppEntry>, selected:String, tab:Tab, record:Record,
    online:Boolean,busy:Boolean,chosen:String,query:String,error:String,
    changeTab:(Tab)->Unit,search:(String)->Unit,consent:(AppEntry)->Unit,
    browse:(String)->Unit,toggle:(String)->Unit,openTarget:()->Unit,clear:()->Unit
) {
    Column(Modifier.fillMaxSize().background(ink).safeDrawingPadding()) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(accent,RoundedCornerShape(12.dp)),
                contentAlignment=Alignment.Center) {
                Text("H",color=ink,fontSize=23.sp,fontWeight=FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HOOK LAB",color=white,fontSize=20.sp,fontWeight=FontWeight.ExtraBold)
                Text("ANDROID · ON DEVICE",color=muted,fontSize=10.sp)
            }
            Text(if(online)"● VECTOR" else "○ OFFLINE",
                color=if(online)accent else danger,fontSize=11.sp)
        }
        Row(Modifier.fillMaxWidth().background(panel),
            horizontalArrangement=Arrangement.SpaceEvenly) {
            listOf(Tab.APPS to "APPS",Tab.METHODS to "METHODS",Tab.LIVE to "LIVE").forEach{(p,t)->
                Text(t,modifier=Modifier.clickable{changeTab(p)}.padding(14.dp),
                    color=if(p==tab)accent else muted,fontWeight=FontWeight.Bold,fontSize=11.sp)
            }
        }
        if(error.isNotEmpty())Note("CONTROL ERROR",error,danger)
        when(tab) {
            Tab.APPS -> Column(Modifier.padding(16.dp)) {
                Header("CHOOSE AN APPLICATION")
                Spacer(Modifier.height(8.dp))
                Text("Only installed user applications. Scope approval required.",
                    color=muted,fontSize=11.sp)
                Search(query,search)
                LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    items(apps.filter{query.isBlank()||it.pkg.contains(query,true)||
                        it.name.contains(query,true)},key={it.pkg}){app->
                        Tile {
                            Row(Modifier.fillMaxWidth().clickable{consent(app)},
                                verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)){
                                    Text(app.name,color=white,fontWeight=FontWeight.SemiBold)
                                    Text(app.pkg,color=muted,fontSize=10.sp)
                                }
                                Text(if(app.pkg==selected)"ACTIVE" else "SELECT →",
                                    color=accent,fontSize=11.sp)
                            }
                        }
                    }
                }
            }
            Tab.METHODS -> LazyColumn(contentPadding=PaddingValues(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item{Header("METHOD EXPLORER")}
                if(selected.isBlank()) item{
                    Note("NO TARGET","Choose an authorized target on the Apps page.",muted)
                } else {
                    item {
                        Tile {
                            Text(selected,color=accent,fontSize=12.sp)
                            Spacer(Modifier.height(8.dp))
                            val fresh=record.heartbeat>=0 &&
                                SystemClock.elapsedRealtime()-record.heartbeat<8000
                            Text(if(fresh)"● TARGET COMMUNICATING" else "○ AWAITING TARGET",
                                color=if(fresh)accent else muted,fontSize=11.sp)
                            Spacer(Modifier.height(9.dp))
                            Button(onClick=openTarget) {Text("OPEN TARGET ↗")}
                        }
                    }
                    item{Header("CLASSES  "+record.classes.size)}
                    item{Search(query,search)}
                    items(record.classes.filter{query.isBlank()||
                        it.contains(query,true)},key={it}){name->
                        Tile {
                            Column(Modifier.fillMaxWidth().clickable{browse(name)}) {
                                Text(name.substringAfterLast('.'),color=white,fontSize=13.sp)
                                Text(name,color=muted,fontSize=10.sp)
                                Text("BROWSE →",color=accent,fontSize=10.sp)
                            }
                        }
                    }
                    if(chosen.isNotEmpty()) {
                        item{Note("SELECTED CLASS",chosen,accent)}
                        if(record.browsed==chosen) items(record.methods,key={it}){method->
                            Tile {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(method.substringAfter('#'),modifier=Modifier.weight(1f),
                                        color=white,fontFamily=FontFamily.Monospace,fontSize=11.sp)
                                    Switch(checked=record.rules.contains(method),
                                        enabled=online&&!busy,
                                        onCheckedChange={toggle(method)})
                                }
                            }
                        } else item{
                            Note("LOADING METHODS","Open or restart target if needed.",muted)
                        }
                    }
                    item{Text("Exact rules only · max "+RuleWire.MAX_RULES+
                        " · Native methods require a separate engine.",
                        color=muted,fontSize=11.sp)}
                }
            }
            Tab.LIVE -> LazyColumn(contentPadding=PaddingValues(16.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)) {
                item{Header("LIVE CALLS  "+record.calls.size)}
                item{TextButton(onClick=clear){Text("CLEAR HISTORY",color=danger)}}
                if(record.calls.isEmpty())item{
                    Note("AWAITING EVENTS","Choose app → class → method, then trigger it.",muted)
                }
                items(record.calls,key={it.id}){event->
                    Tile {
                        Text(event.method.substringAfter('#'),color=white,
                            fontFamily=FontFamily.Monospace,fontSize=12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(event.args+"  →  "+event.result,color=muted,fontSize=11.sp)
                        Text(event.status+" · "+
                            String.format(Locale.ROOT,"%.3f ms",event.ns/1000000.0)+
                            " · PID "+event.pid,color=if(event.status=="throw")danger else accent,
                            fontSize=10.sp)
                    }
                }
            }
        }
    }
}
@Composable private fun Header(s:String){Text(s,color=white,fontWeight=FontWeight.Bold,fontSize=14.sp)}
@Composable private fun Note(a:String,b:String,c:Color){
    Column(Modifier.fillMaxWidth().padding(14.dp)){
        Text(a,color=c,fontSize=11.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(b,color=muted,fontSize=11.sp)
    }
}
@Composable private fun Tile(content:@Composable ColumnScope.()->Unit){
    Surface(shape=RoundedCornerShape(14.dp),color=panel,border=BorderStroke(1.dp,stroke),
        modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),content=content)
    }
}
@Composable private fun Search(value:String,onChange:(String)->Unit){
    OutlinedTextField(value=value,onValueChange=onChange,
        modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),
        label={Text("Search")},singleLine=true)
}
