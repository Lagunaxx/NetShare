package com.example.ui

import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.network.FileClient
import com.example.network.FileServer
import com.example.network.RemoteFile
import com.example.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import android.content.Intent

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class FolderStats(
    val folderPath: String = "",
    val totalFiles: Int = 0,
    val totalFolders: Int = 0,
    val totalSize: Long = 0L,
    val isCalculating: Boolean = false
)

enum class OverwriteDecision {
    REPLACE,
    REPLACE_ALL,
    SKIP,
    SKIP_ALL,
    ASK
}

data class CollisionInfo(
    val fileName: String,
    val isUpload: Boolean,
    val onDecision: (OverwriteDecision) -> Unit
)

class FileSharingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        val isPaused = MutableStateFlow(false)
        val isCancelled = MutableStateFlow(false)
        
        val currentFileName = MutableStateFlow<String?>(null)
        val currentFileProgress = MutableStateFlow<Float?>(null)
        val overallProgressStr = MutableStateFlow<String?>(null)
        val isTransferActive = MutableStateFlow(false)
    }

    private fun startTransferService() {
        val context = getApplication<Application>()
        val intent = Intent(context, com.example.network.FileTransferService::class.java).apply {
            action = com.example.network.FileTransferService.ACTION_START
        }
        isTransferActive.value = true
        isPaused.value = false
        isCancelled.value = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopTransferService() {
        isTransferActive.value = false
        val context = getApplication<Application>()
        val intent = Intent(context, com.example.network.FileTransferService::class.java)
        context.stopService(intent)
    }

    private fun startTransfer() {
        startTransferService()
    }

    private fun endTransfer() {
        _transferProgress.value = null
        _overallProgress.value = null
        currentFileName.value = null
        currentFileProgress.value = null
        overallProgressStr.value = null
        stopTransferService()
    }

    private val _localFolderStats = MutableStateFlow(FolderStats())
    val localFolderStats = _localFolderStats.asStateFlow()

    private val _sharedFolderHistory = MutableStateFlow<List<String>>(emptyList())
    val sharedFolderHistory = _sharedFolderHistory.asStateFlow()

    private val _localSharedRoot = MutableStateFlow<File>(
        File("/storage/emulated/0").let {
            if (it.exists()) it else File(application.getExternalFilesDir(null) ?: application.filesDir, "Shared").apply { mkdirs() }
        }
    )
    val localSharedRoot = _localSharedRoot.asStateFlow()

    private val _currentLocalDir = MutableStateFlow<File>(_localSharedRoot.value)
    val currentLocalDir = _currentLocalDir.asStateFlow()

    private val _localFilesList = MutableStateFlow<List<File>>(emptyList())
    val localFilesList = _localFilesList.asStateFlow()

    // Connection configuration
    private val _remoteIp = MutableStateFlow("")
    val remoteIp = _remoteIp.asStateFlow()

    private val _remotePort = MutableStateFlow(9090)
    val remotePort = _remotePort.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _remoteFilesList = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFilesList = _remoteFilesList.asStateFlow()

    private val _currentRemoteSubPath = MutableStateFlow("")
    val currentRemoteSubPath = _currentRemoteSubPath.asStateFlow()

    // Host Server Settings & State
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String?>(null)
    val localIpAddress = _localIpAddress.asStateFlow()

    private val _hostPermissions = MutableStateFlow(FileServer.ConnectionPermissions())
    val hostPermissions = _hostPermissions.asStateFlow()

    private val _remotePermissions = MutableStateFlow(FileServer.ConnectionPermissions())
    val remotePermissions = _remotePermissions.asStateFlow()

    // UI Feedback state
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _transferProgress = MutableStateFlow<Float?>(null)
    val transferProgress = _transferProgress.asStateFlow()

    private val _overallProgress = MutableStateFlow<String?>(null)
    val overallProgress = _overallProgress.asStateFlow()

    init {
        viewModelScope.launch {
            _transferProgress.collect { progress ->
                currentFileProgress.value = progress
            }
        }
        viewModelScope.launch {
            _overallProgress.collect { overall ->
                overallProgressStr.value = overall
            }
        }
    }

    private var totalFilesToTransfer = 0
    private var currentFileIndex = 0

    private val _activeCollision = MutableStateFlow<CollisionInfo?>(null)
    val activeCollision = _activeCollision.asStateFlow()

    private var sessionOverwriteDecision = OverwriteDecision.ASK

    private val _selectedLocalPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedLocalPaths = _selectedLocalPaths.asStateFlow()

    private val _selectedRemotePaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedRemotePaths = _selectedRemotePaths.asStateFlow()

    fun toggleLocalSelection(path: String) {
        val current = _selectedLocalPaths.value.toMutableSet()
        if (current.contains(path)) {
            current.remove(path)
        } else {
            current.add(path)
        }
        _selectedLocalPaths.value = current
    }

    fun toggleRemoteSelection(path: String) {
        val current = _selectedRemotePaths.value.toMutableSet()
        if (current.contains(path)) {
            current.remove(path)
        } else {
            current.add(path)
        }
        _selectedRemotePaths.value = current
    }

    fun clearLocalSelection() {
        _selectedLocalPaths.value = emptySet()
    }

    fun clearRemoteSelection() {
        _selectedRemotePaths.value = emptySet()
    }

    fun deleteSelectedLocal() {
        val selected = _selectedLocalPaths.value.toList()
        if (selected.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            selected.forEach { path ->
                val file = File(path)
                try {
                    if (file.exists() && file.deleteRecursively()) {
                        successCount++
                    }
                } catch (e: Exception) {
                    addLog("Error deleting ${file.name}: ${e.message}")
                }
            }
            addLog("Deleted $successCount local items")
            clearLocalSelection()
            refreshLocalFiles()
        }
    }

    fun deleteSelectedRemote() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val selected = _selectedRemotePaths.value.toList()
        if (selected.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            selected.forEach { path ->
                val latch = java.util.concurrent.CountDownLatch(1)
                fileClient.deleteFile(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = path,
                    onSuccess = {
                        count++
                        latch.countDown()
                    },
                    onError = { err ->
                        addLog("Error deleting remote $path: $err")
                        latch.countDown()
                    }
                )
                latch.await()
            }
            addLog("Deleted $count remote items")
            clearRemoteSelection()
            refreshRemoteFiles()
        }
    }

    private fun countLocalFilesRecursive(file: File): Int {
        if (!file.isDirectory) return 1
        var count = 1
        val children = file.listFiles()
        if (children != null) {
            for (child in children) {
                count += countLocalFilesRecursive(child)
            }
        }
        return count
    }

    private suspend fun countRemoteFilesRecursive(remoteRelPath: String, isDirectory: Boolean): Int {
        if (!isDirectory) return 1
        var count = 1
        val latch = java.util.concurrent.CountDownLatch(1)
        var children: List<RemoteFile>? = null
        fileClient.listFiles(
            ip = _remoteIp.value,
            port = _remotePort.value,
            relativePath = remoteRelPath,
            onSuccess = { list ->
                children = list
                latch.countDown()
            },
            onError = {
                latch.countDown()
            }
        )
        latch.await()
        val list = children
        if (list != null) {
            for (child in list) {
                count += countRemoteFilesRecursive(child.relativePath, child.isDirectory)
            }
        }
        return count
    }

    private suspend fun getRemoteFiles(remoteDirPath: String): List<RemoteFile>? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<RemoteFile>? = null
        fileClient.listFiles(
            ip = _remoteIp.value,
            port = _remotePort.value,
            relativePath = remoteDirPath,
            onSuccess = { list ->
                result = list
                latch.countDown()
            },
            onError = {
                latch.countDown()
            }
        )
        latch.await()
        return result
    }

    private suspend fun checkRemoteFileExists(remotePath: String): Boolean {
        val parent = if (remotePath.contains("/")) remotePath.substringBeforeLast("/") else ""
        val fileName = if (remotePath.contains("/")) remotePath.substringAfterLast("/") else remotePath
        val list = getRemoteFiles(parent) ?: return false
        return list.any { it.name == fileName && !it.isDirectory }
    }

    private fun checkLocalFileExists(localFile: File): Boolean {
        return localFile.exists() && !localFile.isDirectory
    }

    private suspend fun resolveCollision(fileName: String, isUpload: Boolean): OverwriteDecision {
        val deferred = kotlinx.coroutines.CompletableDeferred<OverwriteDecision>()
        _activeCollision.value = CollisionInfo(
            fileName = fileName,
            isUpload = isUpload,
            onDecision = { decision ->
                _activeCollision.value = null
                deferred.complete(decision)
            }
        )
        return deferred.await()
    }

    private suspend fun shouldProceedTransfer(fileName: String, exists: Boolean, isUpload: Boolean): Boolean {
        if (isCancelled.value) return false
        if (!exists) return true
        
        when (sessionOverwriteDecision) {
            OverwriteDecision.SKIP_ALL -> {
                addLog("Skipped (Skip all): $fileName")
                return false
            }
            OverwriteDecision.REPLACE_ALL -> {
                return true
            }
            OverwriteDecision.SKIP -> {
                addLog("Skipped: $fileName")
                return false
            }
            OverwriteDecision.REPLACE -> {
                return true
            }
            OverwriteDecision.ASK -> {
                val decision = resolveCollision(fileName, isUpload)
                if (decision == OverwriteDecision.SKIP_ALL) {
                    sessionOverwriteDecision = OverwriteDecision.SKIP_ALL
                    addLog("Skipped (Skip all initiated): $fileName")
                    return false
                }
                if (decision == OverwriteDecision.REPLACE_ALL) {
                    sessionOverwriteDecision = OverwriteDecision.REPLACE_ALL
                    return true
                }
                if (decision == OverwriteDecision.SKIP) {
                    addLog("Skipped: $fileName")
                    return false
                }
                if (decision == OverwriteDecision.REPLACE) {
                    return true
                }
                return true
            }
        }
    }

    private suspend fun uploadDirectoryRecursive(localDir: File, baseRemoteParent: String): Boolean {
        if (isCancelled.value) return false
        currentFileIndex++
        _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
        
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        fileClient.createFolder(
            ip = _remoteIp.value,
            port = _remotePort.value,
            remoteRelPath = baseRemoteParent,
            onSuccess = {
                success = true
                latch.countDown()
            },
            onError = { err ->
                addLog("Create remote folder failed: $err")
                latch.countDown()
            }
        )
        latch.await()
        if (!success) return false

        val children = localDir.listFiles() ?: return true
        for (child in children) {
            if (isCancelled.value) return false
            val childRemotePath = "$baseRemoteParent/${child.name}"
            if (child.isDirectory) {
                if (!uploadDirectoryRecursive(child, childRemotePath)) {
                    return false
                }
            } else {
                currentFileIndex++
                _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
                
                val exists = checkRemoteFileExists(childRemotePath)
                if (!shouldProceedTransfer(child.name, exists, isUpload = true)) {
                    continue
                }
                
                val fileLatch = java.util.concurrent.CountDownLatch(1)
                var fileSuccess = false
                currentFileName.value = child.name
                addLog("Uploading: ${child.name}...")
                fileClient.uploadFile(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = childRemotePath,
                    localSourceFile = child,
                    onProgress = { progress ->
                        _transferProgress.value = progress
                    },
                    onSuccess = {
                        fileSuccess = true
                        fileLatch.countDown()
                    },
                    onError = { err ->
                        addLog("Upload failed for ${child.name}: $err")
                        fileLatch.countDown()
                    }
                )
                fileLatch.await()
                if (!fileSuccess) return false
            }
        }
        return true
    }

    private suspend fun downloadDirectoryRecursive(remoteRelPath: String, localDestDir: File): Boolean {
        if (isCancelled.value) return false
        currentFileIndex++
        _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
        
        localDestDir.mkdirs()
        val latch = java.util.concurrent.CountDownLatch(1)
        var remoteFilesList: List<RemoteFile>? = null
        var listError: String? = null
        fileClient.listFiles(
            ip = _remoteIp.value,
            port = _remotePort.value,
            relativePath = remoteRelPath,
            onSuccess = { list ->
                remoteFilesList = list
                latch.countDown()
            },
            onError = { err ->
                listError = err
                latch.countDown()
            }
        )
        latch.await()

        if (remoteFilesList == null) {
            addLog("Failed to list remote folder $remoteRelPath: $listError")
            return false
        }

        for (remoteChild in remoteFilesList!!) {
            if (isCancelled.value) return false
            val childLocalDest = File(localDestDir, remoteChild.name)
            if (remoteChild.isDirectory) {
                if (!downloadDirectoryRecursive(remoteChild.relativePath, childLocalDest)) {
                    return false
                }
            } else {
                currentFileIndex++
                _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
                
                val exists = checkLocalFileExists(childLocalDest)
                if (!shouldProceedTransfer(remoteChild.name, exists, isUpload = false)) {
                    continue
                }
                
                val fileLatch = java.util.concurrent.CountDownLatch(1)
                var downloadSuccess = false
                currentFileName.value = remoteChild.name
                addLog("Downloading: ${remoteChild.name}...")
                fileClient.downloadFile(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = remoteChild.relativePath,
                    localDestFile = childLocalDest,
                    onProgress = { progress ->
                        _transferProgress.value = progress
                    },
                    onSuccess = {
                        downloadSuccess = true
                        fileLatch.countDown()
                    },
                    onError = { err ->
                        addLog("Download failed for ${remoteChild.name}: $err")
                        fileLatch.countDown()
                    }
                )
                fileLatch.await()
                if (!downloadSuccess) return false
            }
        }
        return true
    }

    fun copySelectedLocalToRemote() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val selected = _selectedLocalPaths.value.toList()
        if (selected.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                startTransfer()
                sessionOverwriteDecision = OverwriteDecision.ASK
                var count = 0
                var total = 0
                for (path in selected) {
                    if (isCancelled.value) break
                    val file = File(path)
                    if (file.exists()) {
                        total += countLocalFilesRecursive(file)
                    }
                }
                if (isCancelled.value) return@launch
                totalFilesToTransfer = total
                currentFileIndex = 0
                
                for (path in selected) {
                    if (isCancelled.value) break
                    val file = File(path)
                    if (!file.exists()) continue
                    val remotePath = if (_currentRemoteSubPath.value.isEmpty()) file.name else "${_currentRemoteSubPath.value}/${file.name}"
                    _transferProgress.value = 0f
                    
                    if (file.isDirectory) {
                        addLog("Uploading directory ${file.name} recursively...")
                        if (uploadDirectoryRecursive(file, remotePath)) {
                            count++
                        }
                    } else {
                        currentFileIndex++
                        _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
                        
                        val exists = checkRemoteFileExists(remotePath)
                        if (!shouldProceedTransfer(file.name, exists, isUpload = true)) {
                            continue
                        }
                        
                        currentFileName.value = file.name
                        addLog("Uploading: ${file.name}...")
                        val latch = java.util.concurrent.CountDownLatch(1)
                        fileClient.uploadFile(
                            ip = _remoteIp.value,
                            port = _remotePort.value,
                            remoteRelPath = remotePath,
                            localSourceFile = file,
                            onProgress = { progress ->
                                _transferProgress.value = progress
                            },
                            onSuccess = {
                                count++
                                latch.countDown()
                            },
                            onError = { err ->
                                addLog("Upload failed: $err")
                                latch.countDown()
                            }
                        )
                        latch.await()
                    }
                }
                addLog("Uploaded $count items")
                clearLocalSelection()
                refreshRemoteFiles()
            } finally {
                endTransfer()
            }
        }
    }

    fun copySelectedRemoteToLocal(remoteFiles: List<RemoteFile>) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val selectedPaths = _selectedRemotePaths.value
        val selected = remoteFiles.filter { selectedPaths.contains(it.relativePath) }
        if (selected.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                startTransfer()
                sessionOverwriteDecision = OverwriteDecision.ASK
                var count = 0
                var total = 0
                for (remoteFile in selected) {
                    if (isCancelled.value) break
                    total += countRemoteFilesRecursive(remoteFile.relativePath, remoteFile.isDirectory)
                }
                if (isCancelled.value) return@launch
                totalFilesToTransfer = total
                currentFileIndex = 0
                
                for (remoteFile in selected) {
                    if (isCancelled.value) break
                    val localDest = File(_currentLocalDir.value, remoteFile.name)
                    _transferProgress.value = 0f
                    
                    if (remoteFile.isDirectory) {
                        addLog("Downloading directory ${remoteFile.name} recursively...")
                        if (downloadDirectoryRecursive(remoteFile.relativePath, localDest)) {
                            count++
                        }
                    } else {
                        currentFileIndex++
                        _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
                        
                        val exists = checkLocalFileExists(localDest)
                        if (!shouldProceedTransfer(remoteFile.name, exists, isUpload = false)) {
                            continue
                        }
                        
                        currentFileName.value = remoteFile.name
                        addLog("Downloading: ${remoteFile.name}...")
                        val latch = java.util.concurrent.CountDownLatch(1)
                        fileClient.downloadFile(
                            ip = _remoteIp.value,
                            port = _remotePort.value,
                            remoteRelPath = remoteFile.relativePath,
                            localDestFile = localDest,
                            onProgress = { progress ->
                                _transferProgress.value = progress
                            },
                            onSuccess = {
                                count++
                                latch.countDown()
                            },
                            onError = { err ->
                                addLog("Download failed: $err")
                                latch.countDown()
                            }
                        )
                        latch.await()
                    }
                }
                addLog("Downloaded $count items")
                clearRemoteSelection()
                refreshLocalFiles()
            } finally {
                endTransfer()
            }
        }
    }

    private val _sharedUri = MutableStateFlow<Uri?>(null)
    val sharedUri = _sharedUri.asStateFlow()

    private val fileClient = FileClient { logMsg -> addLog(logMsg) }
    private var fileServer: FileServer? = null

    init {
        loadSharedFolderHistory()
        refreshLocalFiles()
        updateLocalIp()
        calculateLocalFolderStats()
        viewModelScope.launch(Dispatchers.IO) {
            startServer() // Start hosting in background to prevent NetworkOnMainThreadException
        }
    }

    private fun loadSharedFolderHistory() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("file_sharing_prefs", Context.MODE_PRIVATE)
            val historyStr = prefs.getString("shared_history_list", "") ?: ""
            val list = if (historyStr.isNotEmpty()) {
                historyStr.split(":::").filter { it.isNotEmpty() && File(it).exists() }
            } else {
                listOf(_localSharedRoot.value.absolutePath)
            }
            _sharedFolderHistory.value = list
            if (historyStr.isEmpty() && list.isNotEmpty()) {
                saveSharedFolderHistory(list)
            }
        } catch (e: Exception) {
            Log.e("FileSharingViewModel", "Error loading history: ${e.message}")
        }
    }

    private fun saveSharedFolderHistory(list: List<String>) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("file_sharing_prefs", Context.MODE_PRIVATE)
            val historyStr = list.joinToString(":::")
            prefs.edit().putString("shared_history_list", historyStr).apply()
            _sharedFolderHistory.value = list
        } catch (e: Exception) {
            Log.e("FileSharingViewModel", "Error saving history: ${e.message}")
        }
    }

    fun addToSharedHistory(path: String) {
        val currentList = _sharedFolderHistory.value.toMutableList()
        currentList.remove(path)
        currentList.add(0, path)
        val trimmed = currentList.take(10) // keep last 10
        saveSharedFolderHistory(trimmed)
    }

    fun removeFromSharedHistory(path: String) {
        val currentList = _sharedFolderHistory.value.toMutableList()
        currentList.remove(path)
        saveSharedFolderHistory(currentList)
    }

    fun addLog(msg: String) {
        val current = _logs.value.toMutableList()
        current.add(0, msg)
        _logs.value = current.take(50) // keep last 50 logs
    }

    fun updateLocalIp() {
        _localIpAddress.value = NetworkUtils.getLocalIpAddress()
    }

    fun calculateLocalFolderStats() {
        val root = _localSharedRoot.value
        _localFolderStats.value = FolderStats(folderPath = root.absolutePath, isCalculating = true)
        
        viewModelScope.launch(Dispatchers.IO) {
            var filesCount = 0
            var foldersCount = 0
            var sizeSum = 0L
            var elementsChecked = 0
            val maxElements = 1000
            
            fun traverse(file: java.io.File, depth: Int): Boolean {
                if (elementsChecked >= maxElements) return false
                if (file.isDirectory) {
                    val list = file.listFiles()
                    if (list != null) {
                        for (child in list) {
                            elementsChecked++
                            if (elementsChecked >= maxElements) return false
                            if (child.isDirectory) {
                                foldersCount++
                                if (depth < 2) {
                                    if (!traverse(child, depth + 1)) return false
                                }
                            } else {
                                filesCount++
                                sizeSum += child.length()
                            }
                        }
                    }
                } else {
                    filesCount++
                    sizeSum += file.length()
                }
                return true
            }
            
            try {
                if (root.exists()) {
                    traverse(root, 0)
                }
                _localFolderStats.value = FolderStats(
                    folderPath = root.absolutePath,
                    totalFiles = filesCount,
                    totalFolders = foldersCount,
                    totalSize = sizeSum,
                    isCalculating = false
                )
            } catch (e: Exception) {
                _localFolderStats.value = FolderStats(
                    folderPath = root.absolutePath,
                    isCalculating = false
                )
            }
        }
    }

    fun setRemoteIp(ip: String) {
        _remoteIp.value = ip.trim()
    }

    fun setRemotePort(port: Int) {
        _remotePort.value = port
    }

    fun setLocalSharedRoot(folder: File) {
        if (folder.exists() && folder.isDirectory) {
            _localSharedRoot.value = folder
            clearLocalSelection()
            calculateLocalFolderStats()
            addToSharedHistory(folder.absolutePath)
            addLog("Shared folder changed: ${folder.absolutePath}")
            // Restart server context with new shared root
            if (_isServerRunning.value) {
                stopServer()
                startServer()
            }
        }
    }

    fun refreshLocalFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentDir = _currentLocalDir.value
                val rawFiles = currentDir.listFiles()
                val filesList = mutableListOf<File>()
                if (rawFiles != null) {
                    filesList.addAll(rawFiles)
                }

                if (currentDir.absolutePath == "/storage/emulated/0") {
                    val standardFolders = listOf("Download", "DCIM", "Pictures", "Documents", "Music", "Movies")
                    val systemFiles = standardFolders.map { File(currentDir, it) }
                    val appSandboxShared = File(getApplication<Application>().getExternalFilesDir(null), "Shared").apply { mkdirs() }
                    
                    val existingNames = filesList.map { it.name.lowercase() }.toSet()
                    for (folder in systemFiles) {
                        if (!existingNames.contains(folder.name.lowercase())) {
                            filesList.add(folder)
                        }
                    }
                    if (!existingNames.contains("shared")) {
                        filesList.add(appSandboxShared)
                    }
                }

                // Sort directories first, then files
                _localFilesList.value = filesList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            } catch (e: Exception) {
                addLog("Error reading local folder: ${e.message}")
            }
        }
    }

    fun navigateLocalUp() {
        val current = _currentLocalDir.value
        if (current.absolutePath == "/storage/emulated/0" || current.absolutePath == "/") {
            return
        }
        val parent = current.parentFile
        if (parent != null && parent.exists()) {
            _currentLocalDir.value = parent
            clearLocalSelection()
            refreshLocalFiles()
        }
    }

    fun navigateLocalDown(dir: File) {
        if (dir.isDirectory) {
            _currentLocalDir.value = dir
            clearLocalSelection()
            refreshLocalFiles()
        }
    }

    // Host Server Functions
    fun startServer() {
        if (_isServerRunning.value) return
        fileServer = FileServer(
            getSharedRoot = { _localSharedRoot.value },
            getPermissions = { _hostPermissions.value },
            onLog = { msg -> addLog(msg) }
        )
        val serverUrl = fileServer?.start()
        if (serverUrl != null) {
            _isServerRunning.value = true
            updateLocalIp()
        }
    }

    fun stopServer() {
        fileServer?.stop()
        fileServer = null
        _isServerRunning.value = false
    }

    fun updateHostPermissions(allowRead: Boolean, allowWrite: Boolean, allowDelete: Boolean) {
        _hostPermissions.value = FileServer.ConnectionPermissions(allowRead, allowWrite, allowDelete)
        addLog("Host rights updated: R=$allowRead, W=$allowWrite, D=$allowDelete")
    }

    // Remote client connections
    fun connectToRemote(onConnected: () -> Unit = {}) {
        val ip = _remoteIp.value
        if (ip.isEmpty()) {
            addLog("Error: Remote IP is empty")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        addLog("Connecting to $ip:${_remotePort.value}...")

        fileClient.testConnection(
            ip = ip,
            port = _remotePort.value,
            onSuccess = { response ->
                _connectionState.value = ConnectionState.CONNECTED
                addLog("Successfully connected to: " + response.optString("deviceName", "Remote Device"))
                
                // Read permissions
                _remotePermissions.value = FileServer.ConnectionPermissions(
                    allowRead = response.optBoolean("allowRead", true),
                    allowWrite = response.optBoolean("allowWrite", true),
                    allowDelete = response.optBoolean("allowDelete", true)
                )

                _currentRemoteSubPath.value = ""
                refreshRemoteFiles()
                onConnected()
            },
            onError = { error ->
                _connectionState.value = ConnectionState.ERROR
                addLog("Connection failed: $error")
            }
        )
    }

    fun disconnectFromRemote() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _remoteFilesList.value = emptyList()
        _currentRemoteSubPath.value = ""
        addLog("Disconnected from remote host")
    }

    fun refreshRemoteFiles() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        viewModelScope.launch(Dispatchers.IO) {
            fileClient.listFiles(
                ip = _remoteIp.value,
                port = _remotePort.value,
                relativePath = _currentRemoteSubPath.value,
                onSuccess = { list ->
                    // Sort folders first, then files
                    _remoteFilesList.value = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                },
                onError = { err ->
                    addLog("Error reading remote folders: $err")
                }
            )
        }
    }

    fun navigateRemoteUp() {
        val current = _currentRemoteSubPath.value
        if (current.isNotEmpty()) {
            val idx = current.lastIndexOf('/')
            val parent = if (idx != -1) current.substring(0, idx) else ""
            _currentRemoteSubPath.value = parent
            clearRemoteSelection()
            refreshRemoteFiles()
        }
    }

    fun navigateRemoteDown(dir: RemoteFile) {
        if (dir.isDirectory) {
            _currentRemoteSubPath.value = dir.relativePath
            clearRemoteSelection()
            refreshRemoteFiles()
        }
    }

    // Operations
    fun createLocalSubfolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newDir = File(_currentLocalDir.value, name)
                if (newDir.exists()) {
                    addLog("Folder already exists")
                    return@launch
                }
                val created = newDir.mkdirs()
                if (created) {
                    addLog("Folder created: $name")
                    refreshLocalFiles()
                } else {
                    addLog("Failed to create folder")
                }
            } catch (e: Exception) {
                addLog("Error creating folder: ${e.message}")
            }
        }
    }

    fun createRemoteSubfolder(name: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val path = if (_currentRemoteSubPath.value.isEmpty()) name else "${_currentRemoteSubPath.value}/$name"
        fileClient.createFolder(
            ip = _remoteIp.value,
            port = _remotePort.value,
            remoteRelPath = path,
            onSuccess = {
                addLog("Remote folder created: $name")
                refreshRemoteFiles()
            },
            onError = { err ->
                addLog("Error creating remote folder: $err")
            }
        )
    }

    fun deleteLocalFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deleted = file.deleteRecursively()
                if (deleted) {
                    addLog("Deleted local: ${file.name}")
                    refreshLocalFiles()
                } else {
                    addLog("Failed to delete local: ${file.name}")
                }
            } catch (e: Exception) {
                addLog("Error deleting local file: ${e.message}")
            }
        }
    }

    fun deleteRemoteFile(remoteFile: RemoteFile) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        fileClient.deleteFile(
            ip = _remoteIp.value,
            port = _remotePort.value,
            remoteRelPath = remoteFile.relativePath,
            onSuccess = {
                addLog("Remote file deleted: ${remoteFile.name}")
                refreshRemoteFiles()
            },
            onError = { err ->
                addLog("Error deleting remote file: $err")
            }
        )
    }

    fun copyRemoteToLocal(remoteFile: RemoteFile) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val localDest = File(_currentLocalDir.value, remoteFile.name)
        _transferProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                startTransfer()
                sessionOverwriteDecision = OverwriteDecision.ASK
                totalFilesToTransfer = countRemoteFilesRecursive(remoteFile.relativePath, remoteFile.isDirectory)
                currentFileIndex = 0
                if (isCancelled.value) return@launch
                
                if (remoteFile.isDirectory) {
                    addLog("Downloading directory ${remoteFile.name} recursively...")
                    if (downloadDirectoryRecursive(remoteFile.relativePath, localDest)) {
                        addLog("Downloaded directory recursively: ${remoteFile.name}")
                        refreshLocalFiles()
                    } else {
                        addLog("Download directory failed: ${remoteFile.name}")
                    }
                } else {
                    val exists = checkLocalFileExists(localDest)
                    if (!shouldProceedTransfer(remoteFile.name, exists, isUpload = false)) {
                        return@launch
                    }
                    
                    if (isCancelled.value) return@launch
                    currentFileIndex++
                    _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
                    currentFileName.value = remoteFile.name
                    addLog("Downloading: ${remoteFile.name}...")
                    fileClient.downloadFile(
                        ip = _remoteIp.value,
                        port = _remotePort.value,
                        remoteRelPath = remoteFile.relativePath,
                        localDestFile = localDest,
                        onProgress = { progress ->
                            _transferProgress.value = progress
                        },
                        onSuccess = {
                            addLog("Downloaded: ${remoteFile.name}")
                            refreshLocalFiles()
                        },
                        onError = { err ->
                            addLog("Download failed: $err")
                        }
                    )
                }
            } finally {
                endTransfer()
            }
        }
    }

    fun copyLocalToRemote(localFile: File) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val remotePath = if (_currentRemoteSubPath.value.isEmpty()) localFile.name else "${_currentRemoteSubPath.value}/${localFile.name}"
        _transferProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                startTransfer()
                sessionOverwriteDecision = OverwriteDecision.ASK
                totalFilesToTransfer = countLocalFilesRecursive(localFile)
                currentFileIndex = 0
                if (isCancelled.value) return@launch
                
                if (localFile.isDirectory) {
                    addLog("Uploading directory ${localFile.name} recursively...")
                    if (uploadDirectoryRecursive(localFile, remotePath)) {
                        addLog("Uploaded directory recursively: ${localFile.name}")
                        refreshRemoteFiles()
                    } else {
                        addLog("Upload directory failed: ${localFile.name}")
                    }
                } else {
                    val exists = checkRemoteFileExists(remotePath)
                    if (!shouldProceedTransfer(localFile.name, exists, isUpload = true)) {
                        return@launch
                    }
                    
                    if (isCancelled.value) return@launch
                    currentFileIndex++
                    _overallProgress.value = "Копирование: $currentFileIndex из $totalFilesToTransfer файлов"
                    currentFileName.value = localFile.name
                    addLog("Uploading: ${localFile.name}...")
                    fileClient.uploadFile(
                        ip = _remoteIp.value,
                        port = _remotePort.value,
                        remoteRelPath = remotePath,
                        localSourceFile = localFile,
                        onProgress = { progress ->
                            _transferProgress.value = progress
                        },
                        onSuccess = {
                            addLog("Uploaded: ${localFile.name}")
                            refreshRemoteFiles()
                        },
                        onError = { err ->
                            addLog("Upload failed: $err")
                        }
                    )
                }
            } finally {
                endTransfer()
            }
        }
    }

    // Share Intent logic
    fun setSharedUri(uri: Uri?) {
        _sharedUri.value = uri
        if (uri != null) {
            addLog("Received share file from system: $uri")
        }
    }

    fun uploadUriToRemote(uri: Uri, onComplete: () -> Unit = {}) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            addLog("Error: No remote host connected to send shared file")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val fileName = getFileNameFromUri(contentResolver, uri) ?: "shared_file"
                val inputStream = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open input stream")

                val tempFile = File(getApplication<Application>().cacheDir, fileName)
                tempFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }

                val remoteRelPath = if (_currentRemoteSubPath.value.isEmpty()) fileName else "${_currentRemoteSubPath.value}/$fileName"
                _transferProgress.value = 0f
                addLog("Sending shared file: $fileName...")

                fileClient.uploadFile(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = remoteRelPath,
                    localSourceFile = tempFile,
                    onProgress = { progress ->
                        _transferProgress.value = progress
                    },
                    onSuccess = {
                        _transferProgress.value = null
                        tempFile.delete()
                        addLog("Shared file sent successfully: $fileName")
                        refreshRemoteFiles()
                        _sharedUri.value = null
                        onComplete()
                    },
                    onError = { err ->
                        _transferProgress.value = null
                        tempFile.delete()
                        addLog("Shared file send failed: $err")
                    }
                )
            } catch (e: Exception) {
                _transferProgress.value = null
                addLog("Shared file error: ${e.message}")
            }
        }
    }

    private fun getFileNameFromUri(contentResolver: ContentResolver, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    override fun onCleared() {
        super.onCleared()
        stopServer()
    }
}
