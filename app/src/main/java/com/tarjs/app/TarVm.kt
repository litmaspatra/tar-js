package com.tarjs.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tarjs.app.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavigationScreen {
    Welcome, Lock, SourcePicker, RcloneConfig, SafBrowser,
    ImportProgress, Home, Chat, ChatSearch, ProfilePhoto, Settings
}

class TarVm : ViewModel() {
    private lateinit var appContext: Context
    private lateinit var db: ArchiveDb
    private lateinit var lock: AppLock
    private lateinit var chatSearch: ChatSearch
    private lateinit var configManager: RcloneConfigManager
    private lateinit var passwordManager: RclonePasswordManager
    private var statusJob: Job? = null
    private var searchJob: Job? = null

    var lockState: LockState by mutableStateOf(LockState.NOT_SET_UP)
        private set
    var passcodeInput by mutableStateOf("")
    var passcodeError: String? by mutableStateOf(null)
    var failureCount by mutableIntStateOf(0)
        private set

    var screen by mutableStateOf(NavigationScreen.Welcome)
        private set

    var chats by mutableStateOf<List<Chat>>(emptyList())
        private set
    var selectedChat by mutableStateOf<Chat?>(null)
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var swapped by mutableStateOf(false)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    var hasNewer by mutableStateOf(false)
        private set
    var focusMessageId by mutableStateOf<Long?>(null)
        private set

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<Message>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var searchError: String? by mutableStateOf(null)
        private set

    var importProgress by mutableIntStateOf(0)
        private set
    var importTotal by mutableIntStateOf(0)
        private set
    var importStatus by mutableStateOf("")
        private set
    var importError: String? by mutableStateOf(null)
        private set

    var safFound by mutableStateOf<SafArchiveSource.FoundArchive?>(null)
        private set
    var pendingSourceType by mutableStateOf("saf")
        private set
    var sourceBusy by mutableStateOf(false)
        private set
    var sourceError: String? by mutableStateOf(null)
        private set

    var rcloneConfigPath by mutableStateOf<String?>(null)
        private set
    var rcloneEncrypted by mutableStateOf(false)
        private set
    var rcloneNeedsPassword by mutableStateOf(false)
        private set
    var rcloneRemotes by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedRemote by mutableStateOf<String?>(null)
        private set
    var remotePath by mutableStateOf("")
        private set
    var remoteEntries by mutableStateOf<List<RcloneEntry>>(emptyList())
        private set

    var pendingAvatarUri by mutableStateOf<Uri?>(null)
        private set
    var avatarZoom by mutableFloatStateOf(1f)
    var avatarOffsetX by mutableFloatStateOf(0f)
    var avatarOffsetY by mutableFloatStateOf(0f)

    fun init(context: Context) {
        if (::db.isInitialized) return
        appContext = context.applicationContext
        db = ArchiveDb(appContext)
        lock = AppLock(appContext)
        chatSearch = ChatSearch(db)
        configManager = RcloneConfigManager(appContext)
        passwordManager = RclonePasswordManager(appContext)
        lockState = lock.state
        failureCount = lock.failureCount
        chats = db.chats()
        rcloneConfigPath = appContext.getSharedPreferences("rclone-state", Context.MODE_PRIVATE).getString("configPath", null)
        screen = if (lock.configured) NavigationScreen.Lock else NavigationScreen.Welcome
        startImportStatusPolling()
    }

    fun beginSetupOrUnlock() {
        screen = NavigationScreen.Lock
        passcodeError = null
        passcodeInput = ""
    }

    fun setupPasscode(passcode: String, confirmation: String) {
        passcodeError = when {
            passcode.length < 4 -> "Use at least 4 characters"
            passcode != confirmation -> "Passcodes do not match"
            else -> null
        }
        if (passcodeError != null) return
        runCatching { lock.setPasscode(passcode) }
            .onSuccess {
                lockState = lock.state
                passcodeInput = ""
                openHome()
            }
            .onFailure { passcodeError = it.message ?: "Could not create passcode" }
    }

    fun attemptUnlock(passcode: String) {
        if (!lock.configured) return
        if (lock.verify(passcode)) {
            lockState = lock.state
            failureCount = 0
            passcodeError = null
            passcodeInput = ""
            openHome()
            silentUnlockRememberedRclone()
        } else {
            failureCount = lock.failureCount
            lockState = lock.state
            passcodeError = if (lock.isLockedAfterFailures) "Too many failed attempts" else "Incorrect passcode"
        }
    }

    fun cancelUnlock() {
        lock.cancelUnlock()
        lockState = lock.state
        screen = NavigationScreen.Welcome
    }

    fun lockNow() {
        lock.lockApp()
        lockState = lock.state
        selectedChat = null
        messages = emptyList()
        screen = NavigationScreen.Lock
    }

    fun openHome() {
        viewModelScope.launch {
            chats = withContext(Dispatchers.IO) { db.chats() }
            selectedChat = null
            screen = NavigationScreen.Home
        }
    }

    fun openSourcePicker() { screen = NavigationScreen.SourcePicker }
    fun navigateTo(target: NavigationScreen) { screen = target }

    fun selectChat(chat: Chat) {
        selectedChat = chat
        focusMessageId = null
        searchQuery = ""
        searchResults = emptyList()
        viewModelScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val page = db.messages(chat.id, olderThanDate = Long.MAX_VALUE, limit = 120)
                val swap = db.getSwapSides(chat.id)
                page to swap
            }
            messages = pair.first
            swapped = pair.second
            updatePageFlags()
            screen = NavigationScreen.Chat
        }
    }

    fun toggleSwapSides(chatId: Long) {
        val next = !swapped
        swapped = next
        viewModelScope.launch(Dispatchers.IO) { db.setSwapSides(chatId, next) }
    }

    fun openChatSearch() {
        searchQuery = ""
        searchResults = emptyList()
        searchError = null
        screen = NavigationScreen.ChatSearch
    }

    fun search(query: String, chatId: Long) {
        searchQuery = query
        searchError = null
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            chatSearch.cancel()
            return
        }
        isSearching = true
        searchJob = viewModelScope.launch {
            val state = chatSearch.search(chatId, query, viewModelScope)
            if (searchQuery.trim() == state.query) {
                searchResults = state.results
                searchError = state.error
                isSearching = false
            }
        }
    }

    fun jumpToSearchResult(message: Message) {
        val chat = selectedChat ?: return
        viewModelScope.launch {
            messages = chatSearch.jumpToMessage(chat.id, message.id, viewModelScope)
            focusMessageId = message.id
            updatePageFlags()
            screen = NavigationScreen.Chat
        }
    }

    fun loadOlderMessages(chatId: Long) {
        val before = messages.firstOrNull()?.dateUnix ?: return
        viewModelScope.launch {
            val older = chatSearch.loadOlder(chatId, before, viewModelScope)
            messages = (older + messages).distinctBy { it.id }
            updatePageFlags()
        }
    }

    fun loadNewerMessages(chatId: Long) {
        val after = messages.lastOrNull()?.dateUnix ?: return
        viewModelScope.launch {
            val newer = chatSearch.loadNewer(chatId, after, viewModelScope)
            messages = (messages + newer).distinctBy { it.id }
            updatePageFlags()
        }
    }

    private fun updatePageFlags() {
        val chat = selectedChat ?: return
        val first = messages.firstOrNull()?.dateUnix
        val last = messages.lastOrNull()?.dateUnix
        viewModelScope.launch {
            val flags = withContext(Dispatchers.IO) {
                (first?.let { db.hasOlderMessages(chat.id, it) } ?: false) to
                    (last?.let { db.hasNewerMessages(chat.id, it) } ?: false)
            }
            hasOlder = flags.first
            hasNewer = flags.second
        }
    }

    fun chooseSafSource(type: String) { pendingSourceType = type }

    fun scanSafTree(treeUri: Uri) {
        sourceBusy = true
        sourceError = null
        safFound = null
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                SafArchiveSource.takePersistableReadPermission(appContext, treeUri)
                SafArchiveSource.findResultJsonWithContext(appContext, treeUri)
            }
            sourceBusy = false
            if (found == null) sourceError = "No valid Telegram result.json found in this folder tree"
            else { safFound = found; screen = NavigationScreen.SafBrowser }
        }
    }

    fun importFoundSafArchive(sourceType: String = pendingSourceType) {
        val found = safFound ?: return
        if (sourceBusy) return
        sourceBusy = true
        sourceError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val id = UUID.randomUUID().toString()
                SafArchiveSource.snapshotResultJson(appContext, found.resultJsonUri, id).map { snapshot ->
                    SafArchiveSource.saveArchiveMetadata(
                        appContext,
                        SafArchiveSource.ArchiveSource(id, sourceType, found.parentFolderUri.toString(), SafArchiveSource.sha256(snapshot), System.currentTimeMillis())
                    )
                    snapshot
                }
            }
            sourceBusy = false
            result.onSuccess { snapshot ->
                ArchiveImportService.start(appContext, snapshot)
                screen = NavigationScreen.ImportProgress
            }.onFailure { sourceError = it.message ?: "Import failed" }
        }
    }

    fun importRcloneConfig(uri: Uri) {
        sourceBusy = true
        sourceError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { configManager.importConfigFromUri(uri, "rclone.conf") }
            sourceBusy = false
            result.onSuccess { file ->
                rcloneConfigPath = file.absolutePath
                appContext.getSharedPreferences("rclone-state", Context.MODE_PRIVATE).edit().putString("configPath", file.absolutePath).apply()
                val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                rcloneEncrypted = configManager.isEncrypted(text)
                if (rcloneEncrypted) {
                    val remembered = passwordManager.getRememberedPassword()
                    if (remembered != null) unlockRclone(remembered, remember = true, silent = true)
                    else { rcloneNeedsPassword = true; screen = NavigationScreen.RcloneConfig }
                } else {
                    viewModelScope.launch {
                        runCatching { withContext(Dispatchers.IO) { RcloneRuntime.unlock(file, null); RcloneRuntime.listRemotes() } }
                            .onSuccess { rcloneRemotes = it; rcloneNeedsPassword = false; screen = NavigationScreen.RcloneConfig }
                            .onFailure { sourceError = it.message ?: "Unable to open rclone config"; screen = NavigationScreen.RcloneConfig }
                    }
                }
            }.onFailure { sourceError = it.message ?: "Unable to import rclone config" }
        }
    }

    fun unlockRclone(password: String, remember: Boolean, silent: Boolean = false) {
        val path = rcloneConfigPath ?: return
        sourceBusy = true
        sourceError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { passwordManager.unlockAndMaybeRemember(path, password, remember) }
            sourceBusy = false
            result.onSuccess {
                rcloneNeedsPassword = false
                rcloneRemotes = withContext(Dispatchers.IO) { RcloneRuntime.listRemotes() }
                if (!silent) screen = NavigationScreen.RcloneConfig
            }.onFailure {
                rcloneNeedsPassword = true
                sourceError = "Incorrect rclone configuration password"
                if (!silent) screen = NavigationScreen.RcloneConfig
            }
        }
    }

    private fun silentUnlockRememberedRclone() {
        val path = rcloneConfigPath ?: return
        val remembered = passwordManager.getRememberedPassword() ?: return
        unlockRclone(remembered, remember = true, silent = true)
    }

    fun forgetRclonePassword() = passwordManager.forgetPassword()
    fun hasRememberedRclonePassword(): Boolean = passwordManager.hasRememberedPassword()

    fun clearSelectedRemote() { selectedRemote = null; remotePath = ""; remoteEntries = emptyList() }

    fun selectRemote(remote: String) {
        selectedRemote = remote.removeSuffix(":")
        remotePath = ""
        browseRemote("")
        screen = NavigationScreen.RcloneConfig
    }

    fun browseRemote(path: String) {
        val remote = selectedRemote ?: return
        sourceBusy = true
        sourceError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                configManager.validateRemotePath(remote, path).mapCatching { valid -> RcloneRuntime.list(valid.remote, valid.path) }
            }
            sourceBusy = false
            result.onSuccess { entries -> remotePath = path.trim('/'); remoteEntries = entries }
                .onFailure { sourceError = it.message ?: "Unable to read remote folder" }
        }
    }

    fun remoteParent() {
        val parent = remotePath.split('/').filter(String::isNotBlank).dropLast(1).joinToString("/")
        browseRemote(parent)
    }

    fun importCurrentRemoteFolder() {
        val remote = selectedRemote ?: return
        val resultEntry = remoteEntries.firstOrNull { !it.isDir && it.name.equals("result.json", ignoreCase = true) }
            ?: run { sourceError = "This folder does not contain result.json"; return }
        if (sourceBusy) return
        sourceBusy = true
        sourceError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val id = UUID.randomUUID().toString()
                    val dir = File(appContext.filesDir, "archives/$id").also { it.mkdirs() }
                    val snapshot = File(dir, "result.json")
                    val remoteFile = listOf(remotePath.trim('/'), resultEntry.name).filter(String::isNotBlank).joinToString("/")
                    RcloneRuntime.copyToLocal(remote, remoteFile, snapshot)
                    val text = snapshot.readText(Charsets.UTF_8)
                    SafArchiveSource.validateTelegramExport(text).getOrThrow()
                    SafArchiveSource.saveArchiveMetadata(
                        appContext,
                        SafArchiveSource.ArchiveSource(id, "rclone", "$remote:$remotePath", SafArchiveSource.sha256(snapshot), System.currentTimeMillis())
                    )
                    snapshot
                }
            }
            sourceBusy = false
            result.onSuccess { ArchiveImportService.start(appContext, it); screen = NavigationScreen.ImportProgress }
                .onFailure { sourceError = it.message ?: "Unable to import remote result.json" }
        }
    }

    fun beginProfilePhoto(uri: Uri) {
        val pref = selectedChat?.let { db.getAvatarPreference(it.id) }
        pendingAvatarUri = uri
        avatarZoom = pref?.zoom ?: 1f
        avatarOffsetX = pref?.offsetX ?: 0f
        avatarOffsetY = pref?.offsetY ?: 0f
        screen = NavigationScreen.ProfilePhoto
    }

    fun saveProfilePhoto() {
        val chat = selectedChat ?: return
        val uri = pendingAvatarUri ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(appContext.filesDir, "chat-avatars").also { it.mkdirs() }
                    val file = File(dir, "${chat.id}.img")
                    appContext.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().buffered().use { input.copyTo(it, 256 * 1024) } }
                        ?: error("Unable to read selected image")
                    require(file.length() > 0L) { "Selected image is empty" }
                    db.setAvatarPreference(chat.id, AvatarPreference("chat-avatars/${file.name}", avatarZoom, avatarOffsetX, avatarOffsetY))
                }
            }
            result.onSuccess { pendingAvatarUri = null; screen = NavigationScreen.Chat }
                .onFailure { sourceError = it.message ?: "Unable to save profile photo" }
        }
    }

    fun avatarPreference(chatId: Long): AvatarPreference? = db.getAvatarPreference(chatId)

    fun setCustomChatName(chatId: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) { db.setCustomChatName(chatId, name) }
    }

    fun cancelImport() { ArchiveImportService.cancel(appContext) }

    private fun startImportStatusPolling() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            while (isActive) {
                val status = ArchiveImportService.status(appContext)
                importProgress = status.done
                importTotal = status.total
                importStatus = status.message
                importError = status.error
                if (status.phase == "complete") {
                    chats = withContext(Dispatchers.IO) { db.chats() }
                    if (screen == NavigationScreen.ImportProgress) screen = NavigationScreen.Home
                }
                delay(700)
            }
        }
    }

    override fun onCleared() {
        chatSearch.cancel()
        if (::db.isInitialized) db.close()
        super.onCleared()
    }
}
