@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tarjs.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tarjs.app.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF201A2B)
private val Lavender = Color(0xFF6A4CE0)
private val Bubble = Color(0xFFE9DFFF)

@Composable
fun TarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Lavender,
            background = Color(0xFFFBF8FF),
            surface = Color(0xFFFBF8FF),
            onBackground = Ink
        ),
        content = content
    )
}

@Composable
fun TarApp(vm: TarVm = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.init(context) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::scanSafTree) }
    val configPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importRcloneConfig) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::beginProfilePhoto) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val isUnlocked = vm.lockState is LockState.UNLOCKED
    val effectiveScreen = when {
        isUnlocked -> vm.screen
        vm.screen == NavigationScreen.Welcome -> NavigationScreen.Welcome
        else -> NavigationScreen.Lock
    }

    when (effectiveScreen) {
        NavigationScreen.Welcome -> WelcomeScreen(vm)
        NavigationScreen.Lock -> LockScreen(vm)
        NavigationScreen.Home -> HomeScreen(vm)
        NavigationScreen.SourcePicker -> SourcePickerScreen(
            onLocal = { type -> vm.chooseSafSource(type); treePicker.launch(null) },
            onRclone = { configPicker.launch(arrayOf("*/*")) },
            onBack = vm::openHome
        )
        NavigationScreen.SafBrowser -> SafBrowserScreen(vm)
        NavigationScreen.RcloneConfig -> RcloneConfigScreen(vm)
        NavigationScreen.ImportProgress -> ImportProgressScreen(vm)
        NavigationScreen.Chat -> ChatScreen(vm, onPickPhoto = { avatarPicker.launch(arrayOf("image/*")) })
        NavigationScreen.ChatSearch -> ChatSearchScreen(vm)
        NavigationScreen.ProfilePhoto -> ProfilePhotoScreen(vm, onPickAnother = { avatarPicker.launch(arrayOf("image/*")) })
        NavigationScreen.Settings -> SettingsScreen(vm)
    }
}

@Composable
private fun WelcomeScreen(vm: TarVm) {
    val context = LocalContext.current
    BackHandler { (context as? Activity)?.finish() }
    Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues()), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Forum, null, Modifier.padding(22.dp).size(56.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            Text("TAR-JS", fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("A private, native reader for exported Telegram chats.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                "Import result.json from local storage, Google Drive, OneDrive or rclone. TAR-JS keeps the source read-only and builds a searchable local index.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = vm::beginSetupOrUnlock, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.lockState == LockState.NOT_SET_UP) "Get started" else "Unlock TAR-JS")
            }
        }
    }
}

@Composable
private fun LockScreen(vm: TarVm) {
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val setup = vm.lockState == LockState.NOT_SET_UP
    BackHandler { vm.cancelUnlock() }
    Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues()), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(24.dp), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(if (setup) "Create app passcode" else "Unlock TAR-JS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (setup) "This passcode protects indexed chats and is separate from your rclone password."
                    else "Chats stay locked until the correct app passcode is entered.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; vm.passcodeError = null },
                    label = { Text("Passcode") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (setup) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(confirm, { confirm = it; vm.passcodeError = null }, label = { Text("Confirm passcode") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                vm.passcodeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { if (setup) vm.setupPasscode(code, confirm) else vm.attemptUnlock(code) },
                    enabled = code.length >= 4 && (!setup || confirm.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (setup) "Create passcode" else "Unlock") }
                TextButton(onClick = vm::cancelUnlock, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: TarVm) {
    BackHandler { vm.lockNow() }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = {
            TopAppBar(
                title = { Text("TAR-JS", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.navigateTo(NavigationScreen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = vm::lockNow) { Icon(Icons.Default.Lock, "Lock") }
                }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = vm::openSourcePicker) { Icon(Icons.Default.Add, "Add chats") } }
    ) { pad ->
        if (vm.chats.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(56.dp))
                Text("Your chats, kept private.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("Add a Telegram export to build a local searchable archive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(22.dp))
                Button(onClick = vm::openSourcePicker) { Text("Add your chats") }
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(vm.chats, key = { it.id }) { chat -> ChatRow(vm, chat) { vm.selectChat(chat) } }
            }
        }
    }
}

@Composable
private fun ChatRow(vm: TarVm, chat: Chat, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(vm, chat, 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chat.preview.ifBlank { "No text messages" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Badge { Text(chat.count.toString()) }
    }
}

@Composable
private fun SourcePickerScreen(onLocal: (String) -> Unit, onRclone: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text("Add your chats") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SourceCard(Icons.Default.Folder, "Local folder", "Phone, SD card or USB") { onLocal("local") } }
            item { SourceCard(Icons.Default.Cloud, "Google Drive", "Use Android's document provider") { onLocal("google_drive") } }
            item { SourceCard(Icons.Default.CloudQueue, "OneDrive", "Use Android's document provider") { onLocal("onedrive") } }
            item { SourceCard(Icons.Default.Lock, "Rclone config", "B2, crypt and other rclone remotes") { onRclone() } }
        }
    }
}

@Composable
private fun SourceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun SafBrowserScreen(vm: TarVm) {
    BackHandler { vm.openSourcePicker() }
    val found = vm.safFound
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text("Telegram archive") }, navigationIcon = { IconButton(onClick = vm::openSourcePicker) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(20.dp)) {
            if (vm.sourceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp)) }
            if (found != null) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column { Text("result.json detected", fontWeight = FontWeight.Bold); Text("${found.archiveSize} messages detected", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.importFoundSafArchive() }, enabled = !vm.sourceBusy, modifier = Modifier.fillMaxWidth()) { Text("Use this folder") }
            }
        }
    }
}

@Composable
private fun RcloneConfigScreen(vm: TarVm) {
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(vm.hasRememberedRclonePassword()) }
    BackHandler {
        if (vm.selectedRemote != null && vm.remotePath.isNotBlank()) vm.remoteParent()
        else if (vm.selectedRemote != null) vm.clearSelectedRemote()
        else vm.openSourcePicker()
    }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text(vm.selectedRemote ?: "Rclone") }, navigationIcon = { IconButton(onClick = {
            if (vm.selectedRemote != null && vm.remotePath.isNotBlank()) vm.remoteParent() else if (vm.selectedRemote != null) vm.clearSelectedRemote() else vm.openSourcePicker()
        }) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.sourceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            when {
                vm.rcloneNeedsPassword -> {
                    Column(Modifier.padding(20.dp)) {
                        Text("Unlock encrypted rclone config", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(password, { password = it }, label = { Text("Rclone config password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { rememberPassword = !rememberPassword }.padding(vertical = 8.dp)) {
                            Checkbox(rememberPassword, { rememberPassword = it }); Text("Remember password securely")
                        }
                        Button(onClick = { vm.unlockRclone(password, rememberPassword) }, enabled = password.isNotBlank() && !vm.sourceBusy, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
                    }
                }
                vm.selectedRemote == null -> {
                    LazyColumn {
                        item { Text("Choose a remote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
                        items(vm.rcloneRemotes) { remote -> ListItem(headlineContent = { Text(remote) }, leadingContent = { Icon(Icons.Default.Storage, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { vm.selectRemote(remote) }) }
                    }
                }
                else -> RemoteBrowser(vm)
            }
        }
    }
}

@Composable
private fun RemoteBrowser(vm: TarVm) {
    val hasResult = vm.remoteEntries.any { !it.isDir && it.name.equals("result.json", true) }
    Column(Modifier.fillMaxSize()) {
        if (hasResult) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("Telegram archive detected", fontWeight = FontWeight.Bold); Text("result.json is in this folder") }
                    Button(onClick = vm::importCurrentRemoteFolder, enabled = !vm.sourceBusy) { Text("Use") }
                }
            }
        }
        Text(if (vm.remotePath.isBlank()) "Root" else vm.remotePath, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) {
            items(vm.remoteEntries.sortedWith(compareByDescending<RcloneEntry> { it.isDir }.thenBy { it.name.lowercase() })) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = { Text(if (entry.isDir) "Folder" else if (entry.name.equals("result.json", true)) "Telegram export index" else "File") },
                    leadingContent = { Icon(if (entry.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                    trailingContent = { if (entry.isDir) Icon(Icons.Default.ChevronRight, null) },
                    modifier = if (entry.isDir) Modifier.clickable {
                        val next = listOf(vm.remotePath.trim('/'), entry.name).filter(String::isNotBlank).joinToString("/")
                        vm.browseRemote(next)
                    } else Modifier
                )
            }
        }
    }
}

@Composable
private fun ImportProgressScreen(vm: TarVm) {
    BackHandler { vm.openHome() }
    val progress = if (vm.importTotal > 0) vm.importProgress.toFloat() / vm.importTotal else 0f
    Scaffold(modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()), topBar = { TopAppBar(title = { Text("Importing chats") }) }) { pad ->
        Column(Modifier.padding(pad).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text(if (vm.importTotal > 0) "${vm.importProgress} / ${vm.importTotal} messages indexed" else vm.importStatus.ifBlank { "Preparing archive…" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            vm.importError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = vm::cancelImport) { Text("Cancel") }
            Text("You can leave TAR-JS; indexing continues in the background.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun ChatScreen(vm: TarVm, onPickPhoto: () -> Unit) {
    val chat = vm.selectedChat ?: return
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focus = vm.focusMessageId
    LaunchedEffect(vm.messages, focus) {
        val index = focus?.let { id -> vm.messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) listState.scrollToItem(index + if (vm.hasOlder) 1 else 0)
        else if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex + if (vm.hasOlder) 1 else 0)
    }
    BackHandler { vm.openHome() }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::openHome) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = { Row(verticalAlignment = Alignment.CenterVertically) { ChatAvatar(vm, chat, 38.dp); Spacer(Modifier.width(10.dp)); Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Search this chat") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { menu = false; vm.openChatSearch() })
                        DropdownMenuItem(text = { Text("Swap sides") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = { menu = false; vm.toggleSwapSides(chat.id) })
                        DropdownMenuItem(text = { Text("Set profile photo") }, leadingIcon = { Icon(Icons.Default.AddAPhoto, null) }, onClick = { menu = false; onPickPhoto() })
                        DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menu = false; vm.navigateTo(NavigationScreen.Settings) })
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (vm.hasOlder) item { TextButton(onClick = { vm.loadOlderMessages(chat.id) }, modifier = Modifier.fillMaxWidth()) { Text("Load older messages") } }
            items(vm.messages, key = { "${it.chatId}-${it.id}" }) { message -> MessageBubble(message, MessageDirection.displayOnRight(message.mine, vm.swapped)) }
            if (vm.hasNewer) item { TextButton(onClick = { vm.loadNewerMessages(chat.id) }, modifier = Modifier.fillMaxWidth()) { Text("Load newer messages") } }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, right: Boolean) {
    if (message.isService) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text(message.text.ifBlank { "Service message" }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(17.dp)).background(if (right) Bubble else Color(0xFFEDEAF2)).padding(11.dp)) {
            if (!right) Text(message.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Lavender)
            if (message.isForwarded) Text("Forwarded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (message.replyToId != null) Text("Reply to ${message.replyToId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (message.text.isNotBlank()) Text(message.text)
            else if (message.mediaPath != null) Text(message.fileName ?: message.mediaPath.substringAfterLast('/'), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.align(Alignment.End)) {
                if (message.isEdited) Text("edited · ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(formatMessageTime(message), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun ChatSearchScreen(vm: TarVm) {
    val chat = vm.selectedChat ?: return
    BackHandler { vm.navigateTo(NavigationScreen.Chat) }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text("Search ${chat.title}") }, navigationIcon = { IconButton(onClick = { vm.navigateTo(NavigationScreen.Chat) }) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = { vm.search(it, chat.id) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Search this chat") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )
            if (vm.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.searchResults, key = { "${it.chatId}-${it.id}" }) { result ->
                    ListItem(
                        headlineContent = { Text(result.sender.ifBlank { chat.title }) },
                        supportingContent = { Text(result.text.ifBlank { result.fileName ?: "Media" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(formatMessageTime(result), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable { vm.jumpToSearchResult(result) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfilePhotoScreen(vm: TarVm, onPickAnother: () -> Unit) {
    val chat = vm.selectedChat ?: return
    val context = LocalContext.current
    val uri = vm.pendingAvatarUri
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) { decodeBitmap(context, uri)?.asImageBitmap() }
    }
    BackHandler { vm.navigateTo(NavigationScreen.Chat) }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text("Set profile photo") }, navigationIcon = { IconButton(onClick = { vm.navigateTo(NavigationScreen.Chat) }) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Adjust ${chat.title}'s photo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.size(240.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        vm.avatarOffsetX = (vm.avatarOffsetX + drag.x / 240f).coerceIn(-1f, 1f)
                        vm.avatarOffsetY = (vm.avatarOffsetY + drag.y / 240f).coerceIn(-1f, 1f)
                    }
                }, contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(it, null, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = vm.avatarZoom; scaleY = vm.avatarZoom
                        translationX = vm.avatarOffsetX * size.width * .35f
                        translationY = vm.avatarOffsetY * size.height * .35f
                    }, contentScale = ContentScale.Crop)
                } ?: Icon(Icons.Default.AccountCircle, null, Modifier.size(100.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Zoom"); Slider(vm.avatarZoom, { vm.avatarZoom = it }, valueRange = 1f..3f)
            Text("Horizontal position"); Slider(vm.avatarOffsetX, { vm.avatarOffsetX = it }, valueRange = -1f..1f)
            Text("Vertical position"); Slider(vm.avatarOffsetY, { vm.avatarOffsetY = it }, valueRange = -1f..1f)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPickAnother) { Text("Choose another") }
                Button(onClick = vm::saveProfilePhoto, enabled = uri != null) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: TarVm) {
    val chat = vm.selectedChat
    var customName by remember(chat?.id) { mutableStateOf(chat?.title.orEmpty()) }
    BackHandler { vm.navigateTo(if (chat != null) NavigationScreen.Chat else NavigationScreen.Home) }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = { vm.navigateTo(if (chat != null) NavigationScreen.Chat else NavigationScreen.Home) }) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                ListItem(
                    headlineContent = { Text("Remember rclone password") },
                    supportingContent = { Text(if (vm.hasRememberedRclonePassword()) "Saved securely with Android Keystore" else "Not saved") },
                    trailingContent = { if (vm.hasRememberedRclonePassword()) TextButton(onClick = vm::forgetRclonePassword) { Text("Forget") } }
                )
            }
            if (chat != null) item {
                Column(Modifier.padding(16.dp)) {
                    Text("Chat appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(customName, { customName = it }, label = { Text("Custom name") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.setCustomChatName(chat.id, customName) }, modifier = Modifier.padding(top = 8.dp)) { Text("Save name") }
                }
            }
        }
    }
}

@Composable
private fun ChatAvatar(vm: TarVm, chat: Chat, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val pref = remember(chat.id, chat.title) { vm.avatarPreference(chat.id) }
    val file = pref?.imagePath?.let { File(context.filesDir, it) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.absolutePath, file?.lastModified()) {
        value = if (file?.isFile == true) withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() } else null
    }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!, null, Modifier.fillMaxSize().graphicsLayer {
                scaleX = pref?.zoom ?: 1f; scaleY = pref?.zoom ?: 1f
                translationX = (pref?.offsetX ?: 0f) * size.toPx() * .35f
                translationY = (pref?.offsetY ?: 0f) * size.toPx() * .35f
            }, contentScale = ContentScale.Crop)
        } else Text(chat.title.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private fun decodeBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
    else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

private fun formatMessageTime(message: Message): String {
    if (message.dateUnix > 0) return SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(message.dateUnix * 1000))
    return message.date.replace('T', ' ').take(16)
}
