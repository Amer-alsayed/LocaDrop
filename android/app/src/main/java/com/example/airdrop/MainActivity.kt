package com.example.airdrop

import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.os.Parcelable
import android.Manifest // Import permissions
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CornerBasedShape 
import androidx.documentfile.provider.DocumentFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream 

class MainActivity : ComponentActivity() {
    private var selectedIp: String? = null

    // Multi-file picker for selecting multiple files at once
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedIp?.let { ip ->
                // Send files sequentially for reliability
                Thread {
                    uris.forEach { uri ->
                        NetworkManager.sendFileBlocking(ip, uri)
                    }
                }.start()
            }
        }
    }
    
    private var selectedFolderIp: String? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        
        val ip = selectedFolderIp ?: return@registerForActivityResult
        
        NetworkManager.setStatus("Preparing folder...")
        
        Thread {
            try {
                val folder = DocumentFile.fromTreeUri(this, uri)
                if (folder == null) {
                    NetworkManager.setStatus("Error: Could not access folder")
                    return@Thread
                }
                
                NetworkManager.setStatus("Scanning folder...")
                val folderName = folder.name ?: "Folder"
                
                val rootNode = DocumentFileNode(folder)
                val scanResult = FolderScanner.scan(rootNode, folderName)
                
                NetworkManager.startBatch(scanResult.totalSize)

                for (file in scanResult.files) {
                    NetworkManager.sendFileBlocking(
                        ip,
                        file.uri,
                        file.relativePath,
                        knownSize = file.size
                    )
                }
                
                NetworkManager.endBatch()
                NetworkManager.setStatus("Folder Sent!")
                
            } catch (e: Exception) {
                android.util.Log.e("FolderPicker", "Error sending folder", e)
                NetworkManager.setStatus("Error: ${e.message}")
                NetworkManager.endBatch()
            }
        }.start()
    }
    
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val incomingFiles = mutableListOf<Uri>()
        intent?.let {
            if (it.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                (it.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                    incomingFiles.add(uri)
                }
            } else if (it.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                it.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach { uri ->
                    (uri as? Uri)?.let { incomingFiles.add(it) }
                }
            }
            Unit
        }

        
        NetworkManager.initialize(this)
        
        // Always start service on app open
        val serviceIntent = android.content.Intent(this, TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            val darkColors = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color.White,
                onPrimary = androidx.compose.ui.graphics.Color.Black,
                background = androidx.compose.ui.graphics.Color.Black,
                surface = androidx.compose.ui.graphics.Color.Black,
                onBackground = androidx.compose.ui.graphics.Color.White,
                onSurface = androidx.compose.ui.graphics.Color.White,
                primaryContainer = androidx.compose.ui.graphics.Color(0xFF222222), // Dark Gray for cards
                onPrimaryContainer = androidx.compose.ui.graphics.Color.White
            )

            val view = androidx.compose.ui.platform.LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    window.statusBarColor = android.graphics.Color.BLACK
                    androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                }
            }

            MaterialTheme(colorScheme = darkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val sharedFiles = remember { mutableStateListOf<Uri>().apply { addAll(incomingFiles) } }
                    
                    MainScreen(
                        sharedFiles = sharedFiles,
                        onSendClick = { ip ->
                            selectedIp = ip
                            filePicker.launch("*/*")
                        },
                        onSendFolderClick = { ip ->
                             selectedFolderIp = ip
                             folderPicker.launch(null)
                        },
                        onSendSharedFiles = { ip, uris ->
                            uris.forEach { uri -> NetworkManager.sendFile(ip, uri) }
                            // Optional: Close app or clear selection. 
                            // specific behavior: clear list so UI reverts to normal
                            sharedFiles.clear()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    sharedFiles: List<Uri>,
    onSendClick: (String) -> Unit,
    onSendFolderClick: (String) -> Unit,
    onSendSharedFiles: (String, List<Uri>) -> Unit
) {
    val devices by NetworkManager.devices.collectAsState()
    val status by NetworkManager.transferStatus.collectAsState()
    val stats by NetworkManager.stats.collectAsState()
    val confirmationRequest by NetworkManager.confirmationRequest.collectAsState()
    val isDiscoverable by NetworkManager.isDiscoverable.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showTextDialog by remember { mutableStateOf(false) }
    var textToSend by remember { mutableStateOf("") }
    var selectedDeviceIp by remember { mutableStateOf<String?>(null) }

    if (!isDiscoverable) {
        val activity = context as? android.app.Activity
        activity?.finish()
        return
    }

    val receivedText by NetworkManager.receivedText.collectAsState()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Send Text") },
            text = { 
                OutlinedTextField(
                    value = textToSend, 
                    onValueChange = { textToSend = it },
                    label = { Text("Enter message") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { 
                    selectedDeviceIp?.let { ip ->
                        NetworkManager.sendText(ip, textToSend)
                    }
                    showTextDialog = false 
                    textToSend = ""
                }) {
                    Text("Send")
                }
            },
            dismissButton = {
                Button(onClick = { showTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (sharedFiles.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ready to share ${sharedFiles.size} file(s)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }

    if (receivedText != null) {
        AlertDialog(
            onDismissRequest = { NetworkManager.clearReceivedText() },
            title = { Text("Text Received") },
            text = { 
                OutlinedTextField(
                    value = receivedText ?: "",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(receivedText ?: ""))
                    NetworkManager.clearReceivedText()
                }) {
                    Text("Copy & Close")
                }
            },
            dismissButton = {
                Button(onClick = { NetworkManager.clearReceivedText() }) {
                    Text("Close")
                }
            }
        )
    }

    if (confirmationRequest != null) {
        AlertDialog(
            onDismissRequest = { NetworkManager.confirmTransfer(false) },
            title = { Text(text = "Accept File?") },
            text = { Text(text = "Incoming file: ${confirmationRequest?.filename}\nSize: ${formatSize(confirmationRequest?.size ?: 0)}") },
            confirmButton = {
                Button(onClick = { NetworkManager.confirmTransfer(true) }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                Button(onClick = { NetworkManager.confirmTransfer(false) }) {
                    Text("Reject")
                }
            }
        )
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "LocalDrop",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Status: $status")
                
                if (status.isNotEmpty() && (status.startsWith("Sending") || status.startsWith("Receiving"))) {
                    LinearProgressIndicator(
                        progress = stats.progress,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${(stats.progress * 100).toInt()}%")
                        Text(String.format("%.1f Mbps", stats.speedMbps))
                        Text("${stats.etaSeconds}s left")
                    }
                    
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                         Button(onClick = { NetworkManager.cancelTransfer() }, modifier = Modifier.weight(1f).padding(end=4.dp)) {
                             Text("Cancel/Pause")
                         }
                    }
                } else if (status == "Cancelled" || status == "Error sending") {
                    Text("Transfer Stopped", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text("Available Devices:", style = MaterialTheme.typography.titleMedium)
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            items(devices.values.toList()) { device ->
                DeviceItem(
                    device = device, 
                    onSendFile = { 
                        if (sharedFiles.isNotEmpty()) {
                            onSendSharedFiles(device.ip, sharedFiles)
                        } else {
                            onSendClick(device.ip)
                        }
                    },
                    onSendFolder = { onSendFolderClick(device.ip) },
                    onSendText = { 
                        selectedDeviceIp = device.ip
                        showTextDialog = true
                    },
                    hasSharedFiles = sharedFiles.isNotEmpty()
                )
            }

        }
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun DeviceItem(
    device: Device, 
    onSendFile: () -> Unit, 
    onSendText: () -> Unit, 
    onSendFolder: () -> Unit,
    hasSharedFiles: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasSharedFiles) {
                    Modifier.clickable { onSendFile() }
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.hostname, style = MaterialTheme.typography.titleMedium)
                    Text(text = "${device.os} • ${device.ip}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            if (hasSharedFiles) {
                // When sharing files from another app, show a prominent Send button
                Button(
                    onClick = onSendFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send to ${device.hostname}")
                }
            } else {
                // Normal mode with all buttons - same height for all
                val buttonHeight = 48.dp
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onSendText,
                        modifier = Modifier.weight(1f).height(buttonHeight)
                    ) {
                        Text("Text", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onSendFolder,
                        modifier = Modifier.weight(1f).height(buttonHeight)
                    ) {
                        Text("Folder", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onSendFile,
                        modifier = Modifier.weight(1f).height(buttonHeight)
                    ) {
                        Text("Files", maxLines = 1)
                    }
                }
            }
        }
    }
}
