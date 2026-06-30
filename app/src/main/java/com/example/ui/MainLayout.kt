package com.example.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import android.widget.Toast
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.network.RemoteFile
import com.example.utils.QrCodeUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(viewModel: FileSharingViewModel) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    androidx.activity.compose.BackHandler(enabled = true) {
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }

    // State bindings
    val localSharedRoot by viewModel.localSharedRoot.collectAsState()
    val currentLocalDir by viewModel.currentLocalDir.collectAsState()
    val localFiles by viewModel.localFilesList.collectAsState()

    val remoteIp by viewModel.remoteIp.collectAsState()
    val remotePort by viewModel.remotePort.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val remoteFiles by viewModel.remoteFilesList.collectAsState()
    val currentRemoteSubPath by viewModel.currentRemoteSubPath.collectAsState()

    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val localIp by viewModel.localIpAddress.collectAsState()
    val hostPermissions by viewModel.hostPermissions.collectAsState()
    val remotePermissions by viewModel.remotePermissions.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val overallProgress by viewModel.overallProgress.collectAsState()
    val activeCollision by viewModel.activeCollision.collectAsState()
    val sharedUri by viewModel.sharedUri.collectAsState()
    val localFolderStats by viewModel.localFolderStats.collectAsState()
    val sharedFolderHistory by viewModel.sharedFolderHistory.collectAsState()

    // Dialog flags
    var showQrDialog by remember { mutableStateOf(false) }
    var showScannerDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var isCreatingLocalFolder by remember { mutableStateOf(true) }
    var inputFolderName by remember { mutableStateOf("") }
    var ipInputText by remember { mutableStateOf(remoteIp) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var customPathInput by remember { mutableStateOf("") }
    var customPathError by remember { mutableStateOf("") }
    var activePortraitPane by remember { mutableStateOf(0) }

    // Keep manual IP input synced with state
    LaunchedEffect(remoteIp) {
        if (ipInputText != remoteIp) {
            ipInputText = remoteIp
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEDF3E8))
                    .padding(12.dp)
            ) {
                // Row 1: App Title & Logs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Logo",
                            tint = Color(0xFF386B40),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "WiFi File Share",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF191C19)
                        )
                    }
                    IconButton(
                        onClick = { showLogsDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFBFDF8), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.ListAlt,
                            contentDescription = "Logs",
                            tint = Color(0xFF386B40),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Row 2: Connection input & Scan & Connect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ipInputText,
                        onValueChange = {
                            ipInputText = it
                            viewModel.setRemoteIp(it)
                        },
                        placeholder = { Text("IP или URL устройства", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5B625A)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color(0xFF191C19),
                            fontWeight = FontWeight.Bold
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Wifi",
                                tint = Color(0xFF386B40),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFFFBFDF8),
                            unfocusedContainerColor = Color(0xFFF0F4EC),
                            focusedBorderColor = Color(0xFF386B40),
                            unfocusedBorderColor = Color(0xFFDCE5D5)
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    IconButton(
                        onClick = { showScannerDialog = true },
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0xFFDCE5D5), RoundedCornerShape(14.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = Color(0xFF191C19),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Button(
                        onClick = {
                            if (connectionState == ConnectionState.CONNECTED) {
                                viewModel.disconnectFromRemote()
                            } else {
                                viewModel.connectToRemote()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connectionState == ConnectionState.CONNECTED) Color(0xFFD11A2A) else Color(0xFF386B40)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp),
                        enabled = connectionState == ConnectionState.CONNECTED || (connectionState != ConnectionState.CONNECTING && ipInputText.isNotEmpty())
                    ) {
                        if (connectionState == ConnectionState.CONNECTING) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = if (connectionState == ConnectionState.CONNECTED) "Откл." else "Подкл.",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 3: Action sub-bar (Set Root, Refresh, Status Pill, Share IP)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.setLocalSharedRoot(java.io.File("/storage/emulated/0"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBFDF8)),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFC4C8BA)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderShared,
                                contentDescription = "Folder",
                                tint = Color(0xFF191C19),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Телефон", color = Color(0xFF191C19), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }

                        IconButton(
                            onClick = {
                                viewModel.refreshLocalFiles()
                                viewModel.updateLocalIp()
                                if (connectionState == ConnectionState.CONNECTED) {
                                    viewModel.refreshRemoteFiles()
                                }
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(0xFFFBFDF8), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFFC4C8BA), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFF191C19),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    val statusLabel = if (isServerRunning) "Раздача" else "Оффлайн"
                    val statusColor = if (isServerRunning) Color(0xFF386B40) else Color(0xFF72796F)
                    val statusBg = if (isServerRunning) Color(0xFFDCE5D5) else Color(0xFFEDF3E8)
                    Row(
                        modifier = Modifier
                            .background(statusBg, RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusColor, CircleShape)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                            color = statusColor
                        )
                    }

                    IconButton(
                        onClick = { showQrDialog = true },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFFFBFDF8), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFFC4C8BA), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF386B40),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            // Modern Contextual Bottom Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFEDF3E8),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Color(0xFFDCE5D5))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Action 1: Copy Connection URL
                    Column(
                        modifier = Modifier
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("WiFi Share", "http://${localIp ?: "127.0.0.1"}:9090")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Ссылка подключения скопирована!", Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF386B40), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Копировать", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF191C19))
                    }

                    // Action 2: New Folder
                    Column(
                        modifier = Modifier
                            .clickable {
                                isCreatingLocalFolder = (activePortraitPane == 0 || isLandscape)
                                inputFolderName = ""
                                showCreateFolderDialog = true
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFDCE5D5), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = Color(0xFF191C19), modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Папка+", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF191C19))
                    }

                    // Action 3: Delete Note
                    Column(
                        modifier = Modifier
                            .clickable {
                                Toast.makeText(context, "Используйте значок корзины рядом с файлом для удаления", Toast.LENGTH_LONG).show()
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFDCE5D5), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF191C19), modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Удаление", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF191C19))
                    }

                    // Action 4: Settings (Folder & Permissions Settings)
                    Column(
                        modifier = Modifier
                            .clickable {
                                customPathInput = localSharedRoot.absolutePath
                                customPathError = ""
                                showSettingsDialog = true
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFDCE5D5), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF191C19), modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Настройки", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF191C19))
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFFBFDF8))
        ) {
            // Transfer progress bar
            if (transferProgress != null || overallProgress != null) {
                val progress = transferProgress ?: 0f
                val overallText = overallProgress ?: ""
                val isPausedState by FileSharingViewModel.isPaused.collectAsState()
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (overallText.isNotEmpty()) "$overallText (${(progress * 100).toInt()}%)" else "Копирование файла... ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF386B40),
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            androidx.compose.material3.TextButton(
                                onClick = { FileSharingViewModel.isPaused.value = !FileSharingViewModel.isPaused.value },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isPausedState) "Продолжить" else "Пауза",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF386B40)
                                )
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { 
                                    FileSharingViewModel.isCancelled.value = true
                                    FileSharingViewModel.isPaused.value = false
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Отмена",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Red
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        color = Color(0xFF386B40),
                        trackColor = Color(0xFFDCE5D5),
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            // Dual Pane Explorer with Geometric side tab layout in portrait
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LocalPane(
                            currentLocalDir = currentLocalDir,
                            localSharedRoot = localSharedRoot,
                            localFiles = localFiles,
                            viewModel = viewModel,
                            onCreateFolder = {
                                isCreatingLocalFolder = true
                                inputFolderName = ""
                                showCreateFolderDialog = true
                            },
                            connectionActive = connectionState == ConnectionState.CONNECTED,
                            remoteWriteAllowed = remotePermissions.allowWrite
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        RemotePane(
                            connectionState = connectionState,
                            remoteFiles = remoteFiles,
                            currentRemoteSubPath = currentRemoteSubPath,
                            remotePermissions = remotePermissions,
                            viewModel = viewModel,
                            onCreateFolder = {
                                isCreatingLocalFolder = false
                                inputFolderName = ""
                                showCreateFolderDialog = true
                            }
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Left navigation tab shown if we are on the Remote pane
                    if (activePortraitPane == 1) {
                        SideNavigationTab(
                            label = "МОИ ФАЙЛЫ",
                            isLeft = true,
                            onClick = { activePortraitPane = 0 }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(8.dp)
                    ) {
                        AnimatedContent<Int>(
                            targetState = activePortraitPane,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                                            slideOutHorizontally { width -> -width } + fadeOut()
                                } else {
                                    slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                            slideOutHorizontally { width -> width } + fadeOut()
                                }    .using(SizeTransform(clip = false))
                            },
                            label = "PaneTransition"
                        ) { pane ->
                            if (pane == 0) {
                                LocalPane(
                                    currentLocalDir = currentLocalDir,
                                    localSharedRoot = localSharedRoot,
                                    localFiles = localFiles,
                                    viewModel = viewModel,
                                    onCreateFolder = {
                                        isCreatingLocalFolder = true
                                        inputFolderName = ""
                                        showCreateFolderDialog = true
                                    },
                                    connectionActive = connectionState == ConnectionState.CONNECTED,
                                    remoteWriteAllowed = remotePermissions.allowWrite
                                )
                            } else {
                                RemotePane(
                                    connectionState = connectionState,
                                    remoteFiles = remoteFiles,
                                    currentRemoteSubPath = currentRemoteSubPath,
                                    remotePermissions = remotePermissions,
                                    viewModel = viewModel,
                                    onCreateFolder = {
                                        isCreatingLocalFolder = false
                                        inputFolderName = ""
                                        showCreateFolderDialog = true
                                    }
                                )
                            }
                        }
                    }

                    // Right navigation tab shown if we are on the Local pane
                    if (activePortraitPane == 0) {
                        SideNavigationTab(
                            label = "УДАЛЕННЫЕ",
                            isLeft = false,
                            onClick = { activePortraitPane = 1 }
                        )
                    }
                }
            }
        }

        // --- Dialogs Section ---

        // Settings Dialog (Folder & Permissions Settings)
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color(0xFF386B40))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Настройки раздачи", fontWeight = FontWeight.Bold, color = Color(0xFF191C19))
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Section 1: Info and Stats
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF0F4EC), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Текущая расшаренная папка:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF386B40)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = localSharedRoot.absolutePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF191C19)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Статистика содержимого:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF72796F)
                                )
                                IconButton(
                                    onClick = { viewModel.calculateLocalFolderStats() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh Stats",
                                        tint = Color(0xFF386B40),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            
                            if (localFolderStats.isCalculating) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF386B40))
                                    Text("Идет подсчет...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF72796F))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Папок: ${localFolderStats.totalFolders}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF191C19))
                                    Text("Файлов: ${localFolderStats.totalFiles}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF191C19))
                                    Text("Общий объем: ${formatBytesSize(localFolderStats.totalSize)} (оценочно)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF191C19))
                                }
                            }
                        }

                        // Section 2: Quick Presets
                        Column {
                            Text(
                                "Быстрый выбор папки:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF191C19)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val presetsRow1 = listOf(
                                Pair("Телефон", "/storage/emulated/0"),
                                Pair("Загрузки", "/storage/emulated/0/Download"),
                                Pair("Камера", "/storage/emulated/0/DCIM")
                            )
                            val presetsRow2 = listOf(
                                Pair("Картинки", "/storage/emulated/0/Pictures"),
                                Pair("Документы", "/storage/emulated/0/Documents"),
                                Pair("Музыка", "/storage/emulated/0/Music")
                            )
                            val presetsRow3 = listOf(
                                Pair("Песочница приложения", (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath + "/Shared")
                            )
                            
                            val presetRows = listOf(presetsRow1, presetsRow2, presetsRow3)
                            
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                presetRows.forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowItems.forEach { (name, path) ->
                                            val isSelected = localSharedRoot.absolutePath == path
                                            val presetFile = File(path)
                                            val exists = presetFile.exists() || name.startsWith("Песочница")
                                            
                                            val chipBg = if (isSelected) Color(0xFF386B40) else if (exists) Color(0xFFEDF3E8) else Color(0xFFF0F0F0)
                                            val chipText = if (isSelected) Color.White else if (exists) Color(0xFF191C19) else Color.LightGray
                                            
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .background(chipBg, RoundedCornerShape(100.dp))
                                                    .clickable(enabled = exists) {
                                                        if (name.startsWith("Песочница") && !presetFile.exists()) {
                                                            presetFile.mkdirs()
                                                        }
                                                        viewModel.setLocalSharedRoot(presetFile)
                                                        customPathInput = presetFile.absolutePath
                                                        customPathError = ""
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = chipText,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 3: Custom path input
                        Column {
                            Text(
                                "Свой путь:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF191C19)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customPathInput,
                                    onValueChange = { 
                                        customPathInput = it
                                        customPathError = ""
                                    },
                                    placeholder = { Text("/storage/emulated/0/...", style = MaterialTheme.typography.bodyMedium) },
                                    modifier = Modifier.weight(1f),
                                    isError = customPathError.isNotEmpty(),
                                    maxLines = 2,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Button(
                                    onClick = {
                                        val f = File(customPathInput)
                                        if (f.exists() && f.isDirectory) {
                                            viewModel.setLocalSharedRoot(f)
                                            customPathError = ""
                                        } else {
                                            customPathError = "Путь не найден или не является папкой"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF386B40)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("ОК", color = Color.White)
                                }
                            }
                            if (customPathError.isNotEmpty()) {
                                Text(customPathError, color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }

                        // Section 3.5: Shared history
                        if (sharedFolderHistory.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "История раздач:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF191C19)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    sharedFolderHistory.forEach { path ->
                                        val isCurrent = localSharedRoot.absolutePath == path
                                        val historyFile = File(path)
                                        val exists = historyFile.exists()
                                        
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isCurrent) Color(0xFFEDF3E8) else Color(0xFFF7F9F6))
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isCurrent) Color(0xFF386B40) else Color(0xFFE2E8DF),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable(enabled = exists) {
                                                    viewModel.setLocalSharedRoot(historyFile)
                                                    customPathInput = path
                                                    customPathError = ""
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                tint = if (isCurrent) Color(0xFF386B40) else Color(0xFF72796F),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = historyFile.name.ifEmpty { "Внутренняя память" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (exists) Color(0xFF191C19) else Color.LightGray
                                                )
                                                Text(
                                                    text = path.replace("/storage/emulated/0", "Внутренняя память"),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF72796F),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.removeFromSharedHistory(path) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Remove from history",
                                                    tint = Color.Red.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 4: Permissions (Rights)
                        Column {
                            Text(
                                "Права доступа (для клиентов):",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF191C19)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateHostPermissions(!hostPermissions.allowRead, hostPermissions.allowWrite, hostPermissions.allowDelete)
                            }.padding(vertical = 4.dp)) {
                                Checkbox(
                                    checked = hostPermissions.allowRead,
                                    onCheckedChange = { checked ->
                                        viewModel.updateHostPermissions(checked, hostPermissions.allowWrite, hostPermissions.allowDelete)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Разрешить скачивание (Чтение)", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF191C19))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateHostPermissions(hostPermissions.allowRead, !hostPermissions.allowWrite, hostPermissions.allowDelete)
                            }.padding(vertical = 4.dp)) {
                                Checkbox(
                                    checked = hostPermissions.allowWrite,
                                    onCheckedChange = { checked ->
                                        viewModel.updateHostPermissions(hostPermissions.allowRead, checked, hostPermissions.allowDelete)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Разрешить загрузку (Запись)", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF191C19))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateHostPermissions(hostPermissions.allowRead, hostPermissions.allowWrite, !hostPermissions.allowDelete)
                            }.padding(vertical = 4.dp)) {
                                Checkbox(
                                    checked = hostPermissions.allowDelete,
                                    onCheckedChange = { checked ->
                                        viewModel.updateHostPermissions(hostPermissions.allowRead, hostPermissions.allowWrite, checked)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Разрешить удаление", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF191C19))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSettingsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF386B40)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Закрыть", color = Color.White)
                    }
                }
            )
        }

        // QR Code Display Dialog
        if (showQrDialog) {
            val ipText = "http://${localIp ?: "127.0.0.1"}:9090"
            val qrBitmap = QrCodeUtils.generateQrCode(ipText, 512, 512)

            Dialog(onDismissRequest = { showQrDialog = false }) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Поделиться подключением",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Отсканируйте для соединения",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR IP",
                                modifier = Modifier
                                    .size(240.dp)
                                    .background(Color.White)
                                    .padding(8.dp)
                            )
                        } else {
                            Text("Ошибка генерации QR-кода", color = MaterialTheme.colorScheme.error)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = ipText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, ipText)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Поделиться IP")
                                context.startActivity(shareIntent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Поделиться текстом")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { showQrDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Закрыть")
                        }
                    }
                }
            }
        }

        // QR Code Scanner Dialog
        if (showScannerDialog) {
            QrCodeScannerDialog(
                onDismiss = { showScannerDialog = false },
                onQrCodeScanned = { decodedIp ->
                    showScannerDialog = false
                    // Clean URL scheme if scanned as http://192.168.x.x:9090
                    val cleanedIp = decodedIp
                        .removePrefix("http://")
                        .removePrefix("https://")
                        .substringBefore(":") // Get IP part only if contains port
                    
                    viewModel.setRemoteIp(cleanedIp)
                    viewModel.connectToRemote()
                }
            )
        }

        // Folder Creation Dialog
        if (showCreateFolderDialog) {
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text(if (isCreatingLocalFolder) "Создать локальную папку" else "Создать удаленную папку") },
                text = {
                    OutlinedTextField(
                        value = inputFolderName,
                        onValueChange = { inputFolderName = it },
                        label = { Text("Имя папки") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (inputFolderName.isNotEmpty()) {
                                if (isCreatingLocalFolder) {
                                    viewModel.createLocalSubfolder(inputFolderName)
                                } else {
                                    viewModel.createRemoteSubfolder(inputFolderName)
                                }
                                showCreateFolderDialog = false
                            }
                        }
                    ) {
                        Text("Создать")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        // Overwrite Collision Dialog
        activeCollision?.let { collision ->
            AlertDialog(
                onDismissRequest = { 
                    collision.onDecision(OverwriteDecision.SKIP)
                },
                title = {
                    Text(
                        text = "Файл уже существует",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Файл с именем \"${collision.fileName}\" уже существует в папке назначения.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Выберите действие для продолжения:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { collision.onDecision(OverwriteDecision.REPLACE) },
                                modifier = Modifier.weight(1f).testTag("dialog_replace_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF386B40),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Заменить", style = MaterialTheme.typography.labelLarge)
                            }
                            
                            OutlinedButton(
                                onClick = { collision.onDecision(OverwriteDecision.SKIP) },
                                modifier = Modifier.weight(1f).testTag("dialog_skip_button"),
                                border = BorderStroke(1.dp, Color(0xFF386B40)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF386B40)
                                )
                            ) {
                                Text("Пропустить", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { collision.onDecision(OverwriteDecision.REPLACE_ALL) },
                                modifier = Modifier.weight(1f).testTag("dialog_replace_all_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1B4D24),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Заменить все", style = MaterialTheme.typography.labelMedium)
                            }
                            
                            OutlinedButton(
                                onClick = { collision.onDecision(OverwriteDecision.SKIP_ALL) },
                                modifier = Modifier.weight(1f).testTag("dialog_skip_all_button"),
                                border = BorderStroke(1.dp, Color(0xFF1B4D24)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF1B4D24)
                                )
                            ) {
                                Text("Пропустить все", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                },
                dismissButton = null
            )
        }

        // Logs Display Dialog
        if (showLogsDialog) {
            Dialog(onDismissRequest = { showLogsDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Лог событий",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(logs) { log ->
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { showLogsDialog = false },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("ОК")
                        }
                    }
                }
            }
        }

        // System Shared URI Incoming Dialog (Send via Send Intent)
        sharedUri?.let { uri ->
            Dialog(onDismissRequest = { viewModel.setSharedUri(null) }) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Отправить через WiFi Share",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Получен файл из системы для отправки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Option 1: Send to current connection
                        Button(
                            onClick = {
                                viewModel.uploadUriToRemote(uri) {
                                    viewModel.setSharedUri(null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connectionState == ConnectionState.CONNECTED
                        ) {
                            Icon(imageVector = Icons.Default.Link, contentDescription = "Active")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("В установленное соединение")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Option 2: Setup new connection
                        var showLocalNewConnectionInput by remember { mutableStateOf(false) }

                        if (!showLocalNewConnectionInput) {
                            OutlinedButton(
                                onClick = { showLocalNewConnectionInput = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "New")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Создать новое соединение")
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = ipInputText,
                                        onValueChange = {
                                            ipInputText = it
                                            viewModel.setRemoteIp(it)
                                        },
                                        label = { Text("IP или URL устройства") },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = Color(0xFF191C19),
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        trailingIcon = {
                                            IconButton(onClick = { showScannerDialog = true }) {
                                                Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = "Scanner")
                                            }
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        viewModel.connectToRemote {
                                            // Once connected, trigger upload
                                            viewModel.uploadUriToRemote(uri) {
                                                viewModel.setSharedUri(null)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = ipInputText.isNotEmpty()
                                ) {
                                    Text("Подключиться и отправить")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        TextButton(
                            onClick = { viewModel.setSharedUri(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Отмена")
                        }
                    }
                }
            }
        }
    }
}

// --- Sub-Panes Components ---

@Composable
fun LocalPane(
    currentLocalDir: File,
    localSharedRoot: File,
    localFiles: List<File>,
    viewModel: FileSharingViewModel,
    onCreateFolder: () -> Unit,
    connectionActive: Boolean,
    remoteWriteAllowed: Boolean
) {
    val context = LocalContext.current
    val extFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
    val sandboxShared = File(extFilesDir, "Shared")
    
    val locations = listOf(
        Pair("Память", File("/storage/emulated/0")),
        Pair("Загрузки", File("/storage/emulated/0/Download")),
        Pair("Камера", File("/storage/emulated/0/DCIM")),
        Pair("Документы", File("/storage/emulated/0/Documents")),
        Pair("Песочница", sandboxShared)
    )

    val selectedLocalPaths by viewModel.selectedLocalPaths.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateLocalUp() },
                        enabled = currentLocalDir.absolutePath != "/storage/emulated/0" && currentLocalDir.absolutePath != "/" && currentLocalDir.parentFile != null,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Local Up", modifier = Modifier.size(20.dp))
                    }

                    val displayPath = currentLocalDir.absolutePath.replace("/storage/emulated/0", "Внутренняя память")
                    Text(
                        text = displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(0.4f)
                            .padding(horizontal = 4.dp)
                    )

                    Row(
                        modifier = Modifier
                            .weight(0.6f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        locations.forEach { (name, targetFile) ->
                            val exists = targetFile.exists() || name == "Песочница"
                            if (exists) {
                                val isSelected = currentLocalDir.absolutePath == targetFile.absolutePath
                                val chipBg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                val chipTextColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                                
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(chipBg)
                                        .clickable {
                                            if (name == "Песочница" && !targetFile.exists()) {
                                                targetFile.mkdirs()
                                            }
                                            viewModel.navigateLocalDown(targetFile)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = chipTextColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(onClick = onCreateFolder, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "Create Folder", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }

                    IconButton(onClick = { viewModel.refreshLocalFiles() }, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Local", modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateLocalUp() },
                        enabled = currentLocalDir.absolutePath != "/storage/emulated/0" && currentLocalDir.absolutePath != "/" && currentLocalDir.parentFile != null
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Local Up")
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Мои файлы",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        val displayPath = currentLocalDir.absolutePath.replace("/storage/emulated/0", "Внутренняя память")
                        Text(
                            text = displayPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = onCreateFolder) {
                        Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "Create Folder", tint = MaterialTheme.colorScheme.primary)
                    }

                    IconButton(onClick = { viewModel.refreshLocalFiles() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Local")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    locations.forEach { (name, targetFile) ->
                        val exists = targetFile.exists() || name == "Песочница"
                        if (exists) {
                            val isSelected = currentLocalDir.absolutePath == targetFile.absolutePath
                            val chipBg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            val chipTextColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(chipBg)
                                    .clickable {
                                        if (name == "Песочница" && !targetFile.exists()) {
                                            targetFile.mkdirs()
                                        }
                                        viewModel.navigateLocalDown(targetFile)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = chipTextColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (localFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = "Empty", tint = Color.LightGray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Здесь пока пусто", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(localFiles) { file ->
                        val isSelected = selectedLocalPaths.contains(file.absolutePath)
                        LocalFileItem(
                            file = file,
                            isSelected = isSelected,
                            onFolderClick = { viewModel.navigateLocalDown(file) },
                            onToggleSelection = { viewModel.toggleLocalSelection(file.absolutePath) }
                        )
                    }
                }
            }

            if (selectedLocalPaths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.copySelectedLocalToRemote() },
                        enabled = connectionActive && remoteWriteAllowed,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Отправить", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.deleteSelectedLocal() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Удалить", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { viewModel.clearLocalSelection() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Deselect All", tint = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun RemotePane(
    connectionState: ConnectionState,
    remoteFiles: List<RemoteFile>,
    currentRemoteSubPath: String,
    remotePermissions: com.example.network.FileServer.ConnectionPermissions,
    viewModel: FileSharingViewModel,
    onCreateFolder: () -> Unit
) {
    val selectedRemotePaths by viewModel.selectedRemotePaths.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                    .padding(if (isLandscape) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.navigateRemoteUp() },
                    enabled = currentRemoteSubPath.isNotEmpty(),
                    modifier = if (isLandscape) Modifier.size(36.dp) else Modifier
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Remote Up",
                        modifier = if (isLandscape) Modifier.size(20.dp) else Modifier
                    )
                }

                Column(modifier = Modifier.weight(1f).padding(horizontal = if (isLandscape) 4.dp else 0.dp)) {
                    if (!isLandscape) {
                        Text(
                            text = "Удаленные файлы",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    val displayPath = if (currentRemoteSubPath.isEmpty()) "/" else "/$currentRemoteSubPath"
                    Text(
                        text = if (isLandscape) "Удаленно: $displayPath" else displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = if (isLandscape) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onCreateFolder,
                    enabled = connectionState == ConnectionState.CONNECTED && remotePermissions.allowWrite,
                    modifier = if (isLandscape) Modifier.size(36.dp) else Modifier
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = "Create Remote Folder",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = if (isLandscape) Modifier.size(20.dp) else Modifier
                    )
                }

                IconButton(
                    onClick = { viewModel.refreshRemoteFiles() },
                    enabled = connectionState == ConnectionState.CONNECTED,
                    modifier = if (isLandscape) Modifier.size(36.dp) else Modifier
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Remote",
                        modifier = if (isLandscape) Modifier.size(20.dp) else Modifier
                    )
                }
            }

            // Connection state placeholder
            when (connectionState) {
                ConnectionState.DISCONNECTED -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Подключитесь к устройству, чтобы увидеть его файлы", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ConnectionState.CONNECTING -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                ConnectionState.ERROR -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Ошибка соединения. Проверьте IP и WiFi.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ConnectionState.CONNECTED -> {
                    if (remoteFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(imageVector = Icons.Default.Folder, contentDescription = "Empty", tint = Color.LightGray, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("На удаленном устройстве пусто", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(remoteFiles) { remoteFile ->
                                val isSelected = selectedRemotePaths.contains(remoteFile.relativePath)
                                RemoteFileItem(
                                    remoteFile = remoteFile,
                                    isSelected = isSelected,
                                    onFolderClick = { viewModel.navigateRemoteDown(remoteFile) },
                                    onToggleSelection = { viewModel.toggleRemoteSelection(remoteFile.relativePath) }
                                )
                            }
                        }
                    }
                }
            }

            if (connectionState == ConnectionState.CONNECTED && selectedRemotePaths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.copySelectedRemoteToLocal(remoteFiles) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Скачать", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.deleteSelectedRemote() },
                        enabled = remotePermissions.allowDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Удалить", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { viewModel.clearRemoteSelection() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Deselect All", tint = Color.Gray)
                    }
                }
            }
        }
    }
}

// --- Individual Item Row components ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalFileItem(
    file: File,
    isSelected: Boolean,
    onFolderClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (file.isDirectory) {
                            onFolderClick()
                        } else {
                            onToggleSelection()
                        }
                    },
                    onLongClick = {
                        onToggleSelection()
                    }
                )
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = if (file.isDirectory) "Dir" else "File",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else (if (file.isDirectory) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Gray),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = file.name,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteFileItem(
    remoteFile: RemoteFile,
    isSelected: Boolean,
    onFolderClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (remoteFile.isDirectory) {
                            onFolderClick()
                        } else {
                            onToggleSelection()
                        }
                    },
                    onLongClick = {
                        onToggleSelection()
                    }
                )
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (remoteFile.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = if (remoteFile.isDirectory) "Dir" else "File",
                tint = if (isSelected) MaterialTheme.colorScheme.secondary else (if (remoteFile.isDirectory) MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f) else Color.Gray),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = remoteFile.name,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
fun SideNavigationTab(
    label: String,
    isLeft: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(42.dp)
            .background(Color(0xFFFBFDF8))
            .border(
                width = 1.dp,
                color = Color(0xFFDCE5D5),
                shape = RoundedCornerShape(0.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight()
        ) {
            Icon(
                imageVector = if (isLeft) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                contentDescription = label,
                tint = Color(0xFF72796F),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                color = Color(0xFF72796F),
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = if (isLeft) -90f else 90f
                    }
                    .wrapContentWidth(unbounded = true)
            )
        }
    }
}

private fun formatBytesSize(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val index = if (digitGroups < units.size) digitGroups else units.size - 1
    return String.format(Locale.US, "%.2f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
}

