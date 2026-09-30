package com.tarjs.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tarjs.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink=Color(0xFF201A2B); private val Lavender=Color(0xFF6A4CE0); private val Bubble=Color(0xFFE9DFFF)
class MainActivity : ComponentActivity() { override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { TarTheme { TarApp() } } } }

@Composable
fun TarTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = lightColorScheme(primary = Lavender, background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF), onBackground = Ink), content = content)

class TarVm: ViewModel(){
    lateinit var db:ArchiveDb; lateinit var lock:AppLock
    var unlocked by mutableStateOf(false); var setup by mutableStateOf(false); var chats by mutableStateOf(emptyList<Chat>()); var selected by mutableStateOf<Chat?>(null); var messages by mutableStateOf(emptyList<Message>()); var searching by mutableStateOf(false); var query by mutableStateOf(""); var busy by mutableStateOf(false); var progress by mutableStateOf(0f); var status by mutableStateOf("") ; var error by mutableStateOf<String?>(null); var swapped by mutableStateOf(false)
    fun init(context:android.content.Context){ if(::db.isInitialized)return; db=ArchiveDb(context); lock=AppLock(context); setup=lock.configured; chats=db.chats() }
    fun unlock(value:String){ if(!setup){ lock.setPasscode(value); setup=true; unlocked=true } else unlocked=lock.verify(value) }
    fun open(chat:Chat){selected=chat; messages=db.messages(chat.id,query); swapped=false}
    fun refresh(){chats=db.chats(); selected?.let{messages=db.messages(it.id,query)}}
    fun search(value:String){query=value; selected?.let{messages=db.messages(it.id,value)}}
    fun clear(){db.clear(); refresh()}
}

@Composable fun TarApp(vm:TarVm=viewModel()){
    val context=LocalContext.current; LaunchedEffect(Unit){vm.init(context)}
    if(!vm.unlocked){ LockScreen(vm); return }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri:Uri? -> uri?.let{importUri(context,it,vm)} }
    Surface(Modifier.fillMaxSize()){ when { vm.selected!=null -> ChatScreen(vm); else -> HomeScreen(vm,{picker.launch(arrayOf("application/json","text/*"))}) } }
}

@Composable fun LockScreen(vm:TarVm){ var code by remember{mutableStateOf("")}; var confirm by remember{mutableStateOf("")}; val first=!vm.setup; Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center){ Text("TAR-JS",fontSize=38.sp,fontWeight=FontWeight.Bold,color=Lavender); Spacer(Modifier.height(12.dp)); Text(if(first)"A private home for exported Telegram chats." else "Unlock your private archive",fontSize=20.sp,color=Ink); Spacer(Modifier.height(28.dp)); OutlinedTextField(code,{code=it},label={Text(if(first)"Create passcode" else "Passcode")},singleLine=true); if(first){Spacer(Modifier.height(12.dp));OutlinedTextField(confirm,{confirm=it},label={Text("Confirm passcode")},singleLine=true)}; Spacer(Modifier.height(18.dp)); Button(onClick={if(!first||code==confirm)vm.unlock(code)},enabled=code.length>=4&&(!first||code==confirm),modifier=Modifier.fillMaxWidth().height(52.dp)){Text(if(first)"Create passcode" else "Unlock")}; if(vm.setup&&!vm.unlocked) Text("Wrong passcode. Chats remain locked.",color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp)) } }

@Composable fun HomeScreen(vm:TarVm,onAdd:()->Unit){ Scaffold(topBar={TopAppBar(title={Text("TAR-JS",fontWeight=FontWeight.Bold)},actions={IconButton(onClick={vm.clear()}){Icon(Icons.Default.Delete,"Clear archive")}})},floatingActionButton={FloatingActionButton(onClick=onAdd){Icon(Icons.Default.Add,"Add chats")}}){ pad-> Column(Modifier.padding(pad).padding(horizontal=16.dp)){ if(vm.chats.isEmpty()){Spacer(Modifier.height(70.dp));Text("Your chats, kept private.",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("Import a Telegram result.json from local storage to begin.",fontSize=16.sp,modifier=Modifier.padding(top=10.dp,bottom=22.dp));Button(onClick=onAdd){Text("Add your chats")}; Spacer(Modifier.height(18.dp));Text("Supported: full-account and single-chat Telegram exports.",color=Color.Gray)} else {Text("Your conversations",fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=18.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(vm.chats){chat->ChatRow(chat){vm.open(chat)}}} } } } }

@Composable fun ChatRow(chat:Chat,onClick:()->Unit){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick=onClick).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(52.dp).clip(CircleShape).background(Lavender),contentAlignment=Alignment.Center){Text(chat.title.take(1).uppercase(),color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold)};Column(Modifier.padding(start=14.dp).weight(1f)){Text(chat.title,fontWeight=FontWeight.SemiBold,fontSize=17.sp);Text(chat.preview.ifBlank{"No text messages"},maxLines=1,color=Color.Gray,fontSize=14.sp)};Text(chat.count.toString(),color=Color.Gray)}}

@Composable fun ChatScreen(vm:TarVm){
    val chat=vm.selected?:return
    var menu by remember{mutableStateOf(false)}
    Scaffold(topBar={TopAppBar(navigationIcon={IconButton(onClick={vm.selected=null}){Icon(Icons.Default.ArrowBack,"Back")}},title={Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(38.dp).clip(CircleShape).background(Lavender),contentAlignment=Alignment.Center){Text(chat.title.take(1),color=Color.White)};Text(chat.title,modifier=Modifier.padding(start=10.dp),fontWeight=FontWeight.SemiBold)}},actions={IconButton(onClick={vm.searching=!vm.searching}){Icon(Icons.Default.Search,"Search this chat")};IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){DropdownMenuItem(text={Text("Swap sides")},onClick={vm.swapped=!vm.swapped;menu=false});DropdownMenuItem(text={Text("Settings")},onClick={menu=false})}})}){pad->Column(Modifier.padding(pad)){if(vm.searching){OutlinedTextField(vm.query,vm::search,Modifier.fillMaxWidth().padding(10.dp),label={Text("Search this chat")},singleLine=true)};if(vm.messages.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(vm.query.isBlank())"No messages" else "No matches in this chat",color=Color.Gray)} else LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){items(vm.messages){m->MessageBubble(m,if(vm.swapped)!m.mine else m.mine)}}}}}

@Composable fun MessageBubble(m:Message,mine:Boolean){Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){Column(Modifier.widthIn(max=300.dp).clip(RoundedCornerShape(16.dp)).background(if(mine) Bubble else Color(0xFFEDEAF2)) .padding(11.dp)){if(!mine)Text(m.sender,fontSize=12.sp,color=Lavender,fontWeight=FontWeight.Bold);Text(m.text.ifBlank{"(media or service message)"},fontSize=16.sp);Text(m.date.replace("T"," ").take(16),fontSize=10.sp,color=Color.Gray,modifier=Modifier.align(Alignment.End))}}}

private fun importUri(context:android.content.Context,uri:Uri,vm:TarVm){ if(vm.busy)return; vm.busy=true;vm.status="Importing archive…";vm.error=null; kotlinx.coroutines.MainScope().launch{val result=withContext(Dispatchers.IO){val text=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()} ?: ""; context.openFileOutput("result.json.snapshot",0).use{it.write(text.toByteArray())};vm.db.importJson(text){done,total->vm.progress=if(total==0)1f else done.toFloat()/total}};vm.busy=false;vm.refresh();vm.status="";vm.error=result.error}}
