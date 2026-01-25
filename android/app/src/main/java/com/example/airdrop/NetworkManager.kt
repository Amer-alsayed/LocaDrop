package com.example.airdrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.airdrop.MainActivity
import com.example.airdrop.ClipboardReceiver
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID

data class Device(
    val ip: String,
    val hostname: String,
    val os: String,
    var lastSeen: Long
)

data class DiscoveryInfo(val host: String, val os: String)
data class FileHeader(
    val filename: String, 
    val size: Long, 
    val type: String? = "file",
    val group_id: String? = null,
    val group_size: Long? = null
)
data class TransferStats(val speedMbps: Float, val etaSeconds: Long, val progress: Float)

object NetworkManager {
    const val BROADCAST_PORT = 45454
    const val TRANSFER_PORT = 45455
    const val BUFFER_SIZE = 1024 * 1024 // 1MB
    const val NOTIFICATION_ID = 101
    const val CHANNEL_ID = "transfer_channel_v2"

    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices

    private val _transferStatus = MutableStateFlow<String>("")
    val transferStatus: StateFlow<String> = _transferStatus

    private val _stats = MutableStateFlow(TransferStats(0f, 0, 0f))
    val stats: StateFlow<TransferStats> = _stats

    private val _confirmationRequest = MutableStateFlow<FileHeader?>(null)
    val confirmationRequest: StateFlow<FileHeader?> = _confirmationRequest
    
    private val _receivedText = MutableStateFlow<String?>(null)
    val receivedText: StateFlow<String?> = _receivedText
    
    private val _isDiscoverable = MutableStateFlow(false)
    val isDiscoverable: StateFlow<Boolean> = _isDiscoverable

    // Auto-accept tracking for batch file transfers (Group ID based)
    private var acceptedGroupId: String? = null

    // Batch Transfer / Folder Progress
    private var isBatchMode = false
    private var batchTotalSize = 0L
    private var batchTransferredBase = 0L
    private var batchStartTime = 0L
    private var currentBatchGroupId: String? = null // For Sending

    private var running = false
    // Use a list to track ALL active transfer coroutines
    private val transferJobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
    @Volatile private var isCancelled = false  // Volatile flag for immediate cancellation
    
    private var broadcastJob: kotlinx.coroutines.Job? = null
    private var discoveryJob: kotlinx.coroutines.Job? = null
    private var serverJob: kotlinx.coroutines.Job? = null
    
    private var broadcastSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null

    
    private val gson = Gson()
    
    private var currentSocket: Socket? = null
    private var confirmationDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    
    private val deviceName = Build.MODEL
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var appContext: Context

    private val cancelledGroups = java.util.concurrent.CopyOnWriteArraySet<String>()
    
    // Throttling for UI updates
    private var lastStatusUpdateTime = 0L
    private const val STATUS_UPDATE_INTERVAL = 500L // ms

    fun startBatch(totalSize: Long) {
        if (!isBatchMode) {
             isBatchMode = true
             batchTotalSize = totalSize
             batchTransferredBase = 0L
             batchStartTime = System.currentTimeMillis()
             
             // Reset cancellation flag for new batch
             isCancelled = false
             
             // Initial batch status
             updateStatusThrottled("Preparing Batch...", force = true)

             if (currentBatchGroupId == null) {
                  currentBatchGroupId = UUID.randomUUID().toString()
             }
        }
    }

    fun endBatch() {
        isBatchMode = false
        batchTotalSize = 0L
        batchTransferredBase = 0L
        currentBatchGroupId = null
    }

    fun cancelTransfer() {
        Log.d("Network", "Executing Cancel Transfer - Cancelling ${transferJobs.size} active jobs")
        
        // Set volatile flag FIRST - all loops will see this immediately
        isCancelled = true
        
        // Cancel ALL active transfer jobs
        transferJobs.forEach { job -> 
            job.cancel()
        }
        transferJobs.clear()
        
        try {
            currentSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Add current groups to blocklist
        acceptedGroupId?.let { 
            cancelledGroups.add(it) 
            Log.d("Network", "Blocked RX Group: $it")
        }
        currentBatchGroupId?.let {
             cancelledGroups.add(it)
             Log.d("Network", "Blocked TX Group: $it")
        }
        
        // Reset state
        acceptedGroupId = null
        endBatch()
        
        // Force immediate status update
        updateStatusThrottled("Cancelled", force = true)
        cancelNotification()
    }
    
    // Wrapper to prevent UI flashing
    private fun updateStatusThrottled(status: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || (now - lastStatusUpdateTime > STATUS_UPDATE_INTERVAL)) {
            _transferStatus.value = status
            lastStatusUpdateTime = now
        }
    }
    
    fun confirmTransfer(accept: Boolean) {
        confirmationDeferred?.complete(accept)
    }

    fun clearReceivedText() {
        _receivedText.value = null
    }

    fun setStatus(status: String) {
        _transferStatus.value = status
    }

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        notificationManager = NotificationManagerCompat.from(appContext)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "File Transfer"
            val importance = NotificationManager.IMPORTANCE_HIGH 
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Shows progress of file transfers"
            }
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "${m}m ${s}s"
    }

    private fun formatSize(bytes: Long): String {
        return android.text.format.Formatter.formatShortFileSize(appContext, bytes)
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun start() {
        if (running) return
        running = true
        _isDiscoverable.value = true
        
        broadcastJob = GlobalScope.launch(Dispatchers.IO) { broadcastPresence() }
        discoveryJob = GlobalScope.launch(Dispatchers.IO) { listenForDiscovery() }
        GlobalScope.launch(Dispatchers.IO) { pruneDevices() } 
        serverJob = GlobalScope.launch(Dispatchers.IO) { acceptTransfers() }
    }
    
    fun stop() {
        if (!running) return
        running = false
        _isDiscoverable.value = false
        
        // Cancel ALL transfer jobs
        transferJobs.forEach { it.cancel() }
        transferJobs.clear()
        
        broadcastJob?.cancel()
        discoveryJob?.cancel()
        serverJob?.cancel()
        
        try { currentSocket?.close() } catch (e: Exception) {}
        try { broadcastSocket?.close() } catch (e: Exception) {}
        try { discoverySocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
    }

    private suspend fun broadcastPresence() {
        val message = gson.toJson(DiscoveryInfo(deviceName, "android")).toByteArray()
        var broadcastLoopCount = 0
        var cachedInterfaces: List<java.net.NetworkInterface> = emptyList()

        try {
            broadcastSocket = DatagramSocket()
            broadcastSocket?.broadcast = true
            
            while (running) {
                try {
                    val globalPacket = DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), BROADCAST_PORT)
                    broadcastSocket?.send(globalPacket)
                    
                    if (broadcastLoopCount % 30 == 0) {
                        cachedInterfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                    }
                    broadcastLoopCount++

                    for (networkInterface in cachedInterfaces) {
                        if (networkInterface.isLoopback || !networkInterface.isUp) continue
                        
                        for (interfaceAddress in networkInterface.interfaceAddresses) {
                            val broadcast = interfaceAddress.broadcast
                            if (broadcast != null) {
                                try {
                                    val packet = DatagramPacket(message, message.size, broadcast, BROADCAST_PORT)
                                    broadcastSocket?.send(packet)
                                } catch (e: Exception) { }
                            }
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                } catch (e: Exception) {
                    if (!running) break
                    kotlinx.coroutines.delay(5000)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            broadcastSocket?.close()
        }
    }

    private suspend fun listenForDiscovery() {
        val buffer = ByteArray(1024)
        try {
            discoverySocket = DatagramSocket(BROADCAST_PORT)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet) 
                    val ip = packet.address.hostAddress ?: continue
                    val data = String(packet.data, 0, packet.length)
                    val info = gson.fromJson(data, DiscoveryInfo::class.java)
                    if (info.host == deviceName) continue

                    val newMap = _devices.value.toMutableMap()
                    newMap[ip] = Device(ip, info.host, info.os, System.currentTimeMillis())
                    _devices.emit(newMap)
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            discoverySocket?.close()
        }
    }

    private suspend fun acceptTransfers() {
        try {
            serverSocket = ServerSocket(TRANSFER_PORT)
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    val senderIp = socket.inetAddress.hostAddress ?: "unknown"
                    
                    // Add job to list for proper cancellation tracking
                    val job = GlobalScope.launch(Dispatchers.IO) { receiveFile(socket, senderIp) }
                    transferJobs.add(job)
                    
                    // Clean up completed jobs periodically
                    transferJobs.removeAll { !it.isActive }
                    
                } catch (e: Exception) { 
                    if (!running) break
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
             serverSocket?.close()
        }
    }


    private suspend fun pruneDevices() {
        while (running) {
             val now = System.currentTimeMillis()
             val currentMap = _devices.value
             val toRemove = currentMap.filter { (now - it.value.lastSeen) > 3000 } 
             if (toRemove.isNotEmpty()) {
                 val newMap = currentMap.toMutableMap()
                 toRemove.keys.forEach { newMap.remove(it) }
                 _devices.emit(newMap)
             }
             kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun receiveFile(socket: Socket, senderIp: String) {
        currentSocket = socket
        withContext(Dispatchers.IO) {
            try {
                // ... (read header len) ...
                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()
                val dataLenBytes = ByteArray(4)
                if (inputStream.read(dataLenBytes) != 4) return@withContext
                val headerLen = ByteBuffer.wrap(dataLenBytes).order(ByteOrder.BIG_ENDIAN).int
                
                val headerBytes = ByteArray(headerLen)
                var read = 0
                while (read < headerLen) {
                    val c = inputStream.read(headerBytes, read, headerLen - read)
                    if (c == -1) break
                    read += c
                }
                
                val header = gson.fromJson(String(headerBytes), FileHeader::class.java)
                
                // CHECK CANCELLATION
                if (header.group_id != null && cancelledGroups.contains(header.group_id)) {
                    Log.d("Network", "Rejecting file from cancelled group: ${header.group_id}")
                    socket.close()
                    return@withContext
                }

                // Group Based Auto-Accept Check
                var autoAccepted = false
                val groupId = header.group_id
                
                Log.d("Network", "Header ID: $groupId | Accepted ID: $acceptedGroupId")

                if (groupId != null && groupId == acceptedGroupId) {
                    autoAccepted = true
                    if (!isBatchMode) { 
                        updateStatusThrottled("Receiving ${header.filename}") 
                    }
                } else if (groupId != null && groupId != acceptedGroupId) {
                    // New group, reset accepted
                    Log.d("Network", "New Group ID detected. Resetting accepted ID.")
                    acceptedGroupId = null
                } else if (groupId == null) {
                    // Single file (no group), always confirm
                    Log.d("Network", "No Group ID. Resetting accepted ID.")
                    acceptedGroupId = null 
                }
                
                if (!autoAccepted) {
                    Log.d("Network", "Requesting confirmation...")
                    // Force update for confirmation
                    updateStatusThrottled("Requesting confirmation for ${header.filename}", force = true)
                    
                    // ... (Notification logic same as before) ...
                    
                    if (ActivityCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        // ... Notification logic omit ...
                        val acceptIntent = android.content.Intent(appContext, TransferService::class.java).apply {
                            action = "ACTION_ACCEPT"
                        }
                        val acceptPending = android.app.PendingIntent.getService(appContext, 1, acceptIntent, android.app.PendingIntent.FLAG_IMMUTABLE)
                        
                        val rejectIntent = android.content.Intent(appContext, TransferService::class.java).apply {
                            action = "ACTION_REJECT"
                        }
                        val rejectPending = android.app.PendingIntent.getService(appContext, 2, rejectIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

                        val fullScreenIntent = android.content.Intent(appContext, MainActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        val fullScreenPending = android.app.PendingIntent.getActivity(appContext, 0, fullScreenIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

                        val title = if (header.group_id != null) "Incoming Folder/Batch" else "Incoming File: ${header.filename}"
                        val text = if (header.group_size != null) "Total Size: ${formatSize(header.group_size)}" else "Size: ${formatSize(header.size)}"
                        
                        // If we are already in batch mode and just re-confirming (shouldn't happen with auto-accept, but safe-guard),
                        // or if we want to ensure unique IDs for notifications in batch:
                        val notifIdToUse = if (isBatchMode || header.group_id != null) NOTIFICATION_ID else NOTIFICATION_ID + 1

                        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(title)
                            .setContentText(text)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setDefaults(NotificationCompat.DEFAULT_ALL)
                            .setFullScreenIntent(fullScreenPending, true)
                            .setAutoCancel(true)
                            .addAction(android.R.drawable.ic_menu_add, "Accept", acceptPending)
                            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPending)
                        
                        notificationManager.notify(notifIdToUse, builder.build())
                    }

                    val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    confirmationDeferred = deferred
                    _confirmationRequest.emit(header)
                    
                    val accepted = deferred.await()
                    confirmationDeferred = null
                    _confirmationRequest.emit(null)
                    
                    // Cancel the specific notification we showed
                    notificationManager.cancel(if (isBatchMode || header.group_id != null) NOTIFICATION_ID else NOTIFICATION_ID + 1)
                    
                    if (!accepted) {
                         Log.d("Network", "User Rejected")
                         if (header.group_id != null) cancelledGroups.add(header.group_id)
                         
                        updateStatusThrottled("Transfer rejected", force = true)
                        socket.close()
                        return@withContext
                    }
                    
                    // User accepted
                    if (header.group_id != null) {
                        acceptedGroupId = header.group_id
                        Log.d("Network", "User Accepted. Authorized Group ID: $acceptedGroupId")
                    }
                }


                // --- TEXT HANDLING START ---
                if (header.type == "text") {
                    // ... (existing text handling) ...
                    updateStatusThrottled("Receiving Text...")
                    
                    // Send 0 Offset
                    val offsetBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(0L).array()
                    outputStream.write(offsetBuf)

                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = 0L
                    val sb = StringBuilder()
                    
                    while (received < header.size) {
                        val count = inputStream.read(buffer)
                        if (count == -1) break
                        sb.append(String(buffer, 0, count))
                        received += count
                    }
                    
                    val textContent = sb.toString()
                    
                    val copyIntent = android.content.Intent(appContext, ClipboardReceiver::class.java).apply {
                        action = "ACTION_COPY"
                        putExtra("text_content", textContent)
                    }
                    val copyPending = android.app.PendingIntent.getBroadcast(appContext, 3, copyIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

                    val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("Text Received")
                        .setContentText(textContent.take(50) + "...")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .addAction(android.R.drawable.ic_menu_save, "Copy to Clipboard", copyPending)
                    
                    notificationManager.notify(NOTIFICATION_ID + 2, builder.build())
                    
                    _receivedText.emit(textContent)
                    updateStatusThrottled("Text Received", force = true)
                    cancelNotification() 
                    socket.close()
                    return@withContext
                }
                // --- TEXT HANDLING END ---

                if (!isBatchMode) {
                    updateStatusThrottled("Receiving ${header.filename}")
                }
                
                if (header.group_id != null && header.group_size != null) {
                    if (!isBatchMode) {
                         startBatch(header.group_size)
                    }
                }

                // ... (File I/O Setup) ...
                
                var offset = 0L
                var uri = getExistingUri(header.filename)
                
                if (uri != null && offset == 0L) {
                     var size = 0L
                    appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                        }
                    }
                    if (size < header.size) {
                         offset = size
                         Log.d("Network", "Resuming from $offset")
                    } else if (size == header.size) {
                         uri = null
                    }
                } 
                
                if (uri == null) {
                    uri = getSaveUri(header.filename)
                    offset = 0L
                }

                val offsetBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(offset).array()
                outputStream.write(offsetBuf)

                if (uri == null) return@withContext
                
                val pfd = appContext.contentResolver.openFileDescriptor(uri, "wa") 
                val fileOutput = java.io.FileOutputStream(pfd?.fileDescriptor)
                
                val buffer = ByteArray(BUFFER_SIZE)
                var received = offset
                val startTime = System.currentTimeMillis()
                
                while (received < header.size) {
                    // Check VOLATILE cancellation flag FIRST - immediate response
                    if (isCancelled) {
                        Log.d("Network", "Transfer cancelled via volatile flag")
                        break
                    }
                    if (header.group_id != null && cancelledGroups.contains(header.group_id)) {
                        Log.d("Network", "Transfer cancelled via group blocklist")
                        break
                    }
                    if (!running) break
                    
                    val count = inputStream.read(buffer)
                    if (count == -1) break
                    fileOutput.write(buffer, 0, count)
                    received += count
                    
                    updateStats(header.filename, received, header.size, startTime, "Receiving", offset)
                }
                
                fileOutput.close()
                pfd?.close()
                
                if (!isBatchMode) {
                     updateStatusThrottled("Received ${header.filename}")
                }
                
                cancelNotification()
                
                if (isBatchMode) {
                    batchTransferredBase += header.size
                    val pct = (batchTransferredBase.toFloat() / batchTotalSize.toFloat()) * 100
                    updateStatusThrottled("Receiving Batch: ${pct.toInt()}%")
                }
                
            } catch (e: Exception) {
                Log.e("Network", "Receive error", e)
                updateStatusThrottled("Error receiving file", force = true)
                cancelNotification()
            } finally {
                socket.close()
            }
        }
    }

    private fun updateStats(filename: String, current: Long, total: Long, startTime: Long, mode: String, offset: Long = 0) {
        // If batch mode, use batch stats
        val effectiveCurrent = if (isBatchMode) batchTransferredBase + current else current
        val effectiveTotal = if (isBatchMode) batchTotalSize else total
        val effectiveStartTime = if (isBatchMode) batchStartTime else startTime
        
        // Throttle statistical updates to ~100ms
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdateTime < 100 && current != total) return
        
        val elapsed = (now - effectiveStartTime) / 1000.0
        if (elapsed > 0) {
            val speedBps = (effectiveCurrent - offset) / elapsed 
            val speedMbps = (speedBps * 8) / (1024 * 1024)
            val eta = if (speedBps > 0) (effectiveTotal - effectiveCurrent) / speedBps else 0.0
            val progress = effectiveCurrent.toFloat() / effectiveTotal
            
            // For batch mode, avoid re-emitting every small change if it doesn't impact visible % significantly
            // But we need smooth bar. 100ms throttle is good.
            _stats.value = TransferStats(speedMbps.toFloat(), eta.toLong(), progress)
        }
        lastStatusUpdateTime = now
    }

    private fun getExistingUri(filename: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(filename)
            appContext.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }
        }
        return null
    }

    private fun getSaveUri(filename: String): Uri? {
        val normalizedFilename = filename.replace("\\", "/")
        val contentValues = ContentValues().apply {
            val fileObj = java.io.File(normalizedFilename)
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileObj.name)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // If there's a path structure, append it to downloads
                val parent = fileObj.parent
                if (parent != null) {
                    val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + parent + "/"
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                } else {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
        }
        return appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
    }

    fun sendFile(ip: String, uri: Uri, groupId: String? = null, groupSize: Long? = null) {
        val job = GlobalScope.launch(Dispatchers.IO) {
            try {
                _transferStatus.emit("Preparing...")
                var filename = "unknown"
                var size = 0L
                appContext.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val n = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val s = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        if (n != -1) filename = it.getString(n)
                        if (s != -1) size = it.getLong(s)
                    }
                }
                
                if (size == 0L) return@launch
                
                // Use explicit args or batch state
                val finalGroupId = groupId ?: currentBatchGroupId
                val finalGroupSize = groupSize ?: if (isBatchMode) batchTotalSize else null

                _transferStatus.emit("Sending $filename")
                
                val header = gson.toJson(FileHeader(filename, size, "file", finalGroupId, finalGroupSize)).toByteArray()
                val socketChannel = SocketChannel.open()
                socketChannel.connect(InetSocketAddress(ip, TRANSFER_PORT))
                
                val headerLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()
                socketChannel.write(ByteBuffer.wrap(headerLenBuf))
                socketChannel.write(ByteBuffer.wrap(header))
                
                val offsetBuf = ByteBuffer.allocate(8)
                socketChannel.read(offsetBuf)
                offsetBuf.flip()
                val offset = offsetBuf.long
                
                if (offset > 0) Log.d("Network", "Resuming sending from $offset")

                val fileDescriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
                val fileChannel = FileInputStream(fileDescriptor?.fileDescriptor).channel
                
                var transferred = offset
                val startTime = System.currentTimeMillis()
                val chunkSize = 1024 * 1024L
                
                while (transferred < size) {
                    val count = fileChannel.transferTo(transferred, minOf(chunkSize, size - transferred), socketChannel)
                    transferred += count
                    updateStats(filename, transferred, size, startTime, "Sending", offset)
                }
                
                fileChannel.close()
                socketChannel.close()
                fileDescriptor?.close()
                
                _transferStatus.emit("Sent $filename")
                cancelNotification()

            } catch (e: Exception) {
                Log.e("Network", "Send error", e)
                _transferStatus.emit("Error sending")
                cancelNotification()
            }
        }
    }

    // Blocking version for sequential multi-file sending
    fun sendFileBlocking(ip: String, uri: Uri, remoteFilename: String? = null, groupId: String? = null, groupSize: Long? = null) {
        try {
            _transferStatus.value = "Preparing..."
            var filename = remoteFilename ?: "unknown"
            var size = 0L
            appContext.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (filename == "unknown" && n !=-1) filename = it.getString(n)
                    val s = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    // REMOVED unconditional overwrite: if (n != -1) filename = it.getString(n)
                    if (s != -1) size = it.getLong(s)
                }
            }
            
            if (size == 0L) return
            
            // Use explicit args or batch state
            val finalGroupId = groupId ?: currentBatchGroupId
            val finalGroupSize = groupSize ?: if (isBatchMode) batchTotalSize else null

            _transferStatus.value = "Sending $filename"
            
            val header = gson.toJson(FileHeader(filename, size, "file", finalGroupId, finalGroupSize)).toByteArray()
            val socketChannel = SocketChannel.open()
            socketChannel.connect(InetSocketAddress(ip, TRANSFER_PORT))
            
            val headerLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()
            socketChannel.write(ByteBuffer.wrap(headerLenBuf))
            socketChannel.write(ByteBuffer.wrap(header))
            
            val offsetBuf = ByteBuffer.allocate(8)
            socketChannel.read(offsetBuf)
            offsetBuf.flip()
            val offset = offsetBuf.long
            
            if (offset > 0) Log.d("Network", "Resuming sending from $offset")

            val fileDescriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
            val fileChannel = FileInputStream(fileDescriptor?.fileDescriptor).channel
            
            var transferred = offset
            val startTime = System.currentTimeMillis()
            val chunkSize = 1024 * 1024L
            
            while (transferred < size) {
                val count = fileChannel.transferTo(transferred, minOf(chunkSize, size - transferred), socketChannel)
                transferred += count
                updateStats(filename, transferred, size, startTime, "Sending", offset)
            }
            
            fileChannel.close()
            socketChannel.close()
            fileDescriptor?.close()
            
            if (isBatchMode) {
                batchTransferredBase += size
            }
            
            _transferStatus.value = "Sent $filename"
            cancelNotification()

        } catch (e: Exception) {
            Log.e("Network", "Send error", e)
            _transferStatus.value = "Error sending"
            cancelNotification()
        }
    }


    fun sendText(ip: String, text: String) {
        val job = GlobalScope.launch(Dispatchers.IO) {
            try {
                _transferStatus.emit("Preparing Text...")
                val data = text.toByteArray(Charsets.UTF_8)
                val size = data.size.toLong()
                val filename = "Text Message"
                
                val header = gson.toJson(FileHeader(filename, size, "text")).toByteArray()
                val socketChannel = SocketChannel.open()
                socketChannel.connect(InetSocketAddress(ip, TRANSFER_PORT))
                
                // Send Header
                val headerLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()
                socketChannel.write(ByteBuffer.wrap(headerLenBuf))
                socketChannel.write(ByteBuffer.wrap(header))
                
                // Read Offset (Always 0 for text)
                val offsetBuf = ByteBuffer.allocate(8)
                socketChannel.read(offsetBuf)
                
                // Send Data
                socketChannel.write(ByteBuffer.wrap(data))
                
                socketChannel.close()
                _transferStatus.emit("Sent Text")
                cancelNotification()
                
            } catch (e: Exception) {
                Log.e("Network", "Send text error", e)
                _transferStatus.emit("Error sending text")
                cancelNotification()
            }
        }
    }

    fun sendLocalFile(ip: String, file: java.io.File, remoteFilename: String? = null) {
        val job = GlobalScope.launch(Dispatchers.IO) {
            try {
                _transferStatus.emit("Preparing...")
                val filename = file.name
                val size = file.length()
                
                if (size == 0L) {
                    _transferStatus.emit("Error: Empty file")
                    return@launch
                }
                val finalFilename = remoteFilename ?: filename
                _transferStatus.emit("Sending $finalFilename")
                
                val header = gson.toJson(FileHeader(finalFilename, size)).toByteArray()
                val socketChannel = SocketChannel.open()
                socketChannel.connect(InetSocketAddress(ip, TRANSFER_PORT))
                
                val headerLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()
                socketChannel.write(ByteBuffer.wrap(headerLenBuf))
                socketChannel.write(ByteBuffer.wrap(header))
                
                val offsetBuf = ByteBuffer.allocate(8)
                socketChannel.read(offsetBuf)
                offsetBuf.flip()
                val offset = offsetBuf.long
                
                if (offset > 0) Log.d("Network", "Resuming sending from $offset")

                val fileChannel = FileInputStream(file).channel
                
                var transferred = offset
                val startTime = System.currentTimeMillis()
                val chunkSize = 1024 * 1024L
                
                while (transferred < size) {
                    val count = fileChannel.transferTo(transferred, minOf(chunkSize, size - transferred), socketChannel)
                    transferred += count
                    updateStats(filename, transferred, size, startTime, "Sending", offset)
                }
                
                fileChannel.close()
                socketChannel.close()
                
                _transferStatus.emit("Sent $filename")
                cancelNotification()
                
                
                // Clean up only if it was a temp zip (not applicable anymore for folders)
                if (remoteFilename == null && file.name.endsWith(".zip") && file.parent?.contains("cache") == true) {
                     try { file.delete() } catch (e: Exception) {}
                }

            } catch (e: Exception) {
                Log.e("Network", "Send error", e)
                _transferStatus.emit("Error sending")
                cancelNotification()
            }
        }
    }
}
