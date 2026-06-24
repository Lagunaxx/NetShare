package com.example.ui

import android.app.Application
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

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class FileSharingViewModel(application: Application) : AndroidViewModel(application) {

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

    private val _sharedUri = MutableStateFlow<Uri?>(null)
    val sharedUri = _sharedUri.asStateFlow()

    private val fileClient = FileClient { logMsg -> addLog(logMsg) }
    private var fileServer: FileServer? = null

    init {
        refreshLocalFiles()
        updateLocalIp()
        viewModelScope.launch(Dispatchers.IO) {
            startServer() // Start hosting in background to prevent NetworkOnMainThreadException
        }
    }

    fun addLog(msg: String) {
        val current = _logs.value.toMutableList()
        current.add(0, msg)
        _logs.value = current.take(50) // keep last 50 logs
    }

    fun updateLocalIp() {
        _localIpAddress.value = NetworkUtils.getLocalIpAddress()
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
            _currentLocalDir.value = folder
            refreshLocalFiles()
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
        val root = _localSharedRoot.value
        val current = _currentLocalDir.value
        if (current != root) {
            val parent = current.parentFile
            if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
                _currentLocalDir.value = parent
                refreshLocalFiles()
            }
        }
    }

    fun navigateLocalDown(dir: File) {
        if (dir.isDirectory) {
            _currentLocalDir.value = dir
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
            refreshRemoteFiles()
        }
    }

    fun navigateRemoteDown(dir: RemoteFile) {
        if (dir.isDirectory) {
            _currentRemoteSubPath.value = dir.relativePath
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
        addLog("Downloading: ${remoteFile.name}...")

        viewModelScope.launch(Dispatchers.IO) {
            if (remoteFile.isDirectory) {
                // To download a directory, we must create it locally and then download its children
                // For simplicity, let's create the folder and tell the user,
                // and explain they should copy individual files inside it.
                localDest.mkdirs()
                _transferProgress.value = null
                addLog("Downloaded directory structure: ${remoteFile.name}")
                refreshLocalFiles()
            } else {
                fileClient.downloadFile(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = remoteFile.relativePath,
                    localDestFile = localDest,
                    onProgress = { progress ->
                        _transferProgress.value = progress
                    },
                    onSuccess = {
                        _transferProgress.value = null
                        addLog("Downloaded: ${remoteFile.name}")
                        refreshLocalFiles()
                    },
                    onError = { err ->
                        _transferProgress.value = null
                        addLog("Download failed: $err")
                    }
                )
            }
        }
    }

    fun copyLocalToRemote(localFile: File) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val remotePath = if (_currentRemoteSubPath.value.isEmpty()) localFile.name else "${_currentRemoteSubPath.value}/${localFile.name}"
        _transferProgress.value = 0f
        addLog("Uploading: ${localFile.name}...")

        viewModelScope.launch(Dispatchers.IO) {
            if (localFile.isDirectory) {
                // To upload a directory, we create it on the remote device
                fileClient.createFolder(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = remotePath,
                    onSuccess = {
                        _transferProgress.value = null
                        addLog("Uploaded directory structure: ${localFile.name}")
                        refreshRemoteFiles()
                    },
                    onError = { err ->
                        _transferProgress.value = null
                        addLog("Upload directory failed: $err")
                    }
                )
            } else {
                fileClient.uploadFile(
                    ip = _remoteIp.value,
                    port = _remotePort.value,
                    remoteRelPath = remotePath,
                    localSourceFile = localFile,
                    onProgress = { progress ->
                        _transferProgress.value = progress
                    },
                    onSuccess = {
                        _transferProgress.value = null
                        addLog("Uploaded: ${localFile.name}")
                        refreshRemoteFiles()
                    },
                    onError = { err ->
                        _transferProgress.value = null
                        addLog("Upload failed: $err")
                    }
                )
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
