@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tarjs.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tarjs.app.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF201A2B)
private val Lavender = Color(0xFF6A4CE0)
private val TelegramBlue = Color(0xFF3390EC)

private data class AppearanceState(
    val theme: ChatTheme,
    val selectTheme: (ChatTheme) -> Unit
)

private val LocalAppearanceState = staticCompositionLocalOf<AppearanceState> {
    error("Appearance state is not available")
}

@Composable
fun TarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val preferences = remember(context) { AppearancePreferences(context) }
    var selectedTheme by remember { mutableStateOf(preferences.theme) }
    val colors = when (selectedTheme) {
        ChatTheme.TELEGRAM_LIGHT -> lightColorScheme(
            primary = TelegramBlue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9ECFF),
            onPrimaryContainer = Color(0xFF003258),
            background = Color(0xFFF4F7FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFE7EBEF),
            onBackground = Color(0xFF17212B),
            onSurface = Color(0xFF17212B)
        )
        ChatTheme.TELEGRAM_DARK -> darkColorScheme(
            primary = Color(0xFF6AB3F3),
            onPrimary = Color(0xFF003258),
            primaryContainer = Color(0xFF244A68),
            onPrimaryContainer = Color(0xFFD9ECFF),
            background = Color(0xFF0E1621),
            surface = Color(0xFF17212B),
            surfaceVariant = Color(0xFF253341),
            onBackground = Color(0xFFE8F0F7),
            onSurface = Color(0xFFE8F0F7)
        )
        ChatTheme.TARJS_VIOLET -> lightColorScheme(
            primary = Lavender,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE9DFFF),
            onPrimaryContainer = Ink,
            background = Color(0xFFFBF8FF),
            surface = Color(0xFFFBF8FF),
            surfaceVariant = Color(0xFFEDEAF2),
            onBackground = Ink,
            onSurface = Ink
        )
    }
    val appearance = remember(selectedTheme) {
        AppearanceState(selectedTheme) { next ->
            preferences.setTheme(next)
            selectedTheme = next
        }
    }
    DisposableEffect(view, selectedTheme) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !selectedTheme.usesDarkSystemBars
                isAppearanceLightNavigationBars = !selectedTheme.usesDarkSystemBars
            }
        }
        onDispose { }
    }
    CompositionLocalProvider(LocalAppearanceState provides appearance) {
        MaterialTheme(colorScheme = colors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                content()
            }
        }
    }
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

    val isUnlocked = vm.lockState == LockState.NOT_SET_UP || vm.lockState is LockState.UNLOCKED
    val effectiveScreen = when {
        isUnlocked -> vm.screen
        vm.screen == NavigationScreen.Welcome -> NavigationScreen.Welcome
        else -> NavigationScreen.Lock
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = effectiveScreen,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { -it / 16 })
            },
            label = "screen transition"
        ) { currentScreen ->
            when (currentScreen) {
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
        if (vm.importPhase in setOf("downloading", "indexing") && effectiveScreen != NavigationScreen.ImportProgress) {
            ImportStatusBanner(vm, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ImportStatusBanner(vm: TarVm, modifier: Modifier = Modifier) {
    val progress = if (vm.importTotal > 0) vm.importProgress.toFloat() / vm.importTotal else 0f
    Surface(modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Indexing in background", fontWeight = FontWeight.Bold)
                    Text(vm.importStatus.ifBlank { "Preparing archive…" }, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vm.navigateTo(NavigationScreen.ImportProgress) }) { Text("View") }
            }
            if (vm.importTotal > 0) LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
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
            Button(onClick = vm::openPasscodeSettings, modifier = Modifier.fillMaxWidth()) {
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
                Text(if (setup) "Create app PIN" else "Unlock TAR-JS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (setup) "This 4-digit PIN protects indexed chats and is separate from your rclone password."
                    else "Chats stay locked until the correct 4-digit PIN is entered.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(4); vm.passcodeError = null },
                    label = { Text("4-digit PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (setup) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        confirm,
                        { confirm = it.filter(Char::isDigit).take(4); vm.passcodeError = null },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                vm.passcodeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { if (setup) vm.setupPasscode(code, confirm) else vm.attemptUnlock(code) },
                    enabled = code.length == 4 && (!setup || confirm.length == 4),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (setup) "Create PIN" else "Unlock") }
                TextButton(onClick = vm::cancelUnlock, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: TarVm) {
    BackHandler(enabled = vm.isPasscodeConfigured()) { vm.lockNow() }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = {
            TopAppBar(
                title = { Text("TAR-JS", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.navigateTo(NavigationScreen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                    if (vm.isPasscodeConfigured()) {
                        IconButton(onClick = vm::lockNow) { Icon(Icons.Default.Lock, "Lock") }
                    }
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
    var initialPositioned by remember(chat.id) { mutableStateOf(false) }
    LaunchedEffect(vm.messages, focus) {
        if (vm.messages.isEmpty()) return@LaunchedEffect
        val index = focus?.let { id -> vm.messages.indexOfFirst { it.id == id } } ?: -1
        when {
            index >= 0 -> {
                listState.scrollToItem(index + if (vm.hasOlder) 1 else 0)
                initialPositioned = true
                vm.consumeFocusMessage()
            }
            !initialPositioned -> {
                listState.scrollToItem(vm.messages.lastIndex + if (vm.hasOlder) 1 else 0)
                initialPositioned = true
            }
        }
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
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .drawBehind {
                    val dotColor = TelegramBlue.copy(alpha = 0.045f)
                    val gap = 38.dp.toPx()
                    var y = gap / 2f
                    var row = 0
                    while (y < size.height) {
                        var x = if (row % 2 == 0) gap / 2f else gap
                        while (x < size.width) {
                            drawCircle(dotColor, radius = 1.35.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
                            x += gap
                        }
                        y += gap
                        row += 1
                    }
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (vm.hasOlder) item { TextButton(onClick = { vm.loadOlderMessages(chat.id) }, modifier = Modifier.fillMaxWidth()) { Text("Load older messages") } }
            itemsIndexed(vm.messages, key = { _, message -> "${message.chatId}-${message.id}" }) { index, message ->
                Column {
                    val previous = vm.messages.getOrNull(index - 1)
                    if (needsDateSeparator(previous?.dateUnix, message.dateUnix)) DateSeparator(message.dateUnix)
                    MessageBubble(vm, message, MessageDirection.displayOnRight(message.mine, vm.swapped))
                }
            }
            if (vm.hasNewer) item { TextButton(onClick = { vm.loadNewerMessages(chat.id) }, modifier = Modifier.fillMaxWidth()) { Text("Load newer messages") } }
        }
    }
}

@Composable
private fun DateSeparator(epochSeconds: Long) {
    val label = remember(epochSeconds) {
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(epochSeconds * 1000L))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 1.dp
        ) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MessageBubble(vm: TarVm, message: Message, right: Boolean) {
    if (message.isService) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 1.dp
            ) {
                Text(message.text.ifBlank { "Service message" }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
        val bubbleColor = if (right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        val contentColor = if (right) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val accentColor = if (right) contentColor.copy(alpha = 0.86f) else MaterialTheme.colorScheme.primary
        val bubbleShape = if (right) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp)
        } else {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 5.dp, bottomEnd = 18.dp)
        }
        Surface(
            modifier = Modifier.widthIn(max = 322.dp),
            shape = bubbleShape,
            color = bubbleColor,
            contentColor = contentColor,
            tonalElevation = if (right) 0.dp else 1.dp,
            shadowElevation = 0.5.dp
        ) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                if (!right && message.sender.isNotBlank()) {
                    Text(message.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accentColor)
                }
                if (message.isForwarded) MessageContextBlock("Forwarded message", null, accentColor)
                if (message.replyToId != null) MessageContextBlock("Reply", "Message ${message.replyToId}", accentColor)
                if (message.mediaPath != null) MessageMedia(vm, message)
                if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (message.isEdited) Text("edited · ", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.68f))
                    Text(formatMessageTime(message), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.68f))
                }
            }
        }
    }
}

@Composable
private fun MessageContextBlock(label: String, detail: String?, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun MessageMedia(vm: TarVm, message: Message) {
    val path = message.mediaPath ?: return
    var uri by remember(path) { mutableStateOf<Uri?>(null) }
    var resolved by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        uri = vm.resolveMediaUri(path)
        resolved = true
    }
    val mediaUri = uri
    if (mediaUri == null) {
        if (!resolved) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        else if (vm.currentArchiveUsesRclone() && (vm.rcloneNeedsPassword || !vm.hasRememberedRclonePassword())) {
            FilledTonalButton(onClick = vm::openRcloneUnlockForMedia, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.LockOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Unlock rclone to load media")
            }
        } else Text("Media unavailable · ${message.fileName ?: path.substringAfterLast('/')}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }

    val lower = path.lowercase()
    when {
        lower.endsWith(".tgs") -> AnimatedTgsSticker(mediaUri)
        lower.endsWith(".webm") && (lower.contains("sticker") || lower.contains("stickers/")) -> ArchiveVideo(mediaUri, sticker = true)
        lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") -> ArchiveImage(mediaUri, sticker = message.mediaType == "sticker" || lower.contains("sticker"))
        message.mediaType == "video" || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> ArchiveVideo(mediaUri, sticker = false)
        message.mediaType == "voice" || message.mediaType == "audio" || lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".mp3") || lower.endsWith(".m4a") -> ArchiveAudio(mediaUri, message.mediaType == "voice")
        else -> OpenMediaButton(mediaUri, message.fileName ?: path.substringAfterLast('/'), message.mimeType ?: "application/octet-stream")
    }
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun ArchiveImage(uri: Uri, sticker: Boolean) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val target = if (sticker) 512 else 1280
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, target, target)
                }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else Image(
        image.asImageBitmap(),
        contentDescription = if (sticker) "Sticker" else "Photo",
        modifier = if (sticker) Modifier.sizeIn(maxWidth = 180.dp, maxHeight = 180.dp) else Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun AnimatedTgsSticker(uri: Uri) {
    val context = LocalContext.current
    var json by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        json = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    readGzipUtf8WithLimit(input, 4 * 1024 * 1024)
                }
            }.getOrNull()
        }
    }
    val stickerJson = json
    if (stickerJson == null) Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else {
        val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(stickerJson))
        LottieAnimation(composition, iterations = LottieConstants.IterateForever, modifier = Modifier.size(170.dp))
    }
}

@Composable
private fun ArchiveVideo(uri: Uri, sticker: Boolean) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(uri)
                if (sticker) {
                    setOnPreparedListener { player -> player.isLooping = true; player.setVolume(0f, 0f); start() }
                } else {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { seekTo(1) }
                }
            }
        },
        modifier = if (sticker) Modifier.size(180.dp) else Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))
    )
}

@Composable
private fun ArchiveAudio(uri: Uri, voice: Boolean) {
    val context = LocalContext.current
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        MediaPlayer().apply {
            setDataSource(context, uri)
            setOnPreparedListener { prepared = true }
            setOnCompletionListener { playing = false }
            prepareAsync()
        }
    }
    DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    FilledTonalButton(
        onClick = { if (playing) player.pause() else player.start(); playing = !playing },
        enabled = prepared,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(8.dp))
        Text(if (voice) "${if (playing) "Pause" else "Play"} voice note" else "${if (playing) "Pause" else "Play"} audio")
    }
}

@Composable
private fun OpenMediaButton(uri: Uri, label: String, mimeType: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(intent) }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Icon(Icons.Default.InsertDriveFile, null); Spacer(Modifier.width(8.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    var bitmap by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = if (uri == null) null else withContext(Dispatchers.IO) { decodeBitmap(context, uri)?.asImageBitmap() }
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
    val appearance = LocalAppearanceState.current
    var customName by remember(chat?.id) { mutableStateOf(chat?.title.orEmpty()) }
    BackHandler { vm.navigateTo(if (chat != null) NavigationScreen.Chat else NavigationScreen.Home) }
    Scaffold(
        modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = { vm.navigateTo(if (chat != null) NavigationScreen.Chat else NavigationScreen.Home) }) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Chat theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Changes apply immediately", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(ChatTheme.entries, key = { it.name }) { theme ->
                val (label, detail) = when (theme) {
                    ChatTheme.TELEGRAM_LIGHT -> "Telegram Light" to "Clean blue bubbles and a bright chat background"
                    ChatTheme.TELEGRAM_DARK -> "Telegram Dark" to "Deep navy surfaces with a softer blue accent"
                    ChatTheme.TARJS_VIOLET -> "TAR-JS Violet" to "The original lavender TAR-JS palette"
                }
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(detail) },
                    leadingContent = {
                        RadioButton(
                            selected = appearance.theme == theme,
                            onClick = { appearance.selectTheme(theme) }
                        )
                    },
                    modifier = Modifier.clickable { appearance.selectTheme(theme) }
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("App PIN") },
                    supportingContent = { Text(if (vm.isPasscodeConfigured()) "On" else "Off — optional") },
                    trailingContent = {
                        if (vm.isPasscodeConfigured()) {
                            TextButton(onClick = vm::disablePasscode) { Text("Turn off") }
                        } else {
                            TextButton(onClick = vm::openPasscodeSettings) { Text("Turn on") }
                        }
                    }
                )
            }
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
    var bitmap by remember(file?.absolutePath, file?.lastModified()) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(file?.absolutePath, file?.lastModified()) {
        bitmap = if (file?.isFile == true) withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() } else null
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
    if (message.dateUnix > 0) return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.dateUnix * 1000))
    return message.date.replace('T', ' ').take(16)
}
