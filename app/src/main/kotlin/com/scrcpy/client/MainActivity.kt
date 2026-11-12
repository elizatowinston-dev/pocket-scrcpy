package com.scrcpy.client

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.scrcpy.adb.AdbClient
import com.scrcpy.adb.AdbCrypto
import com.scrcpy.client.usb.AdbUsbTransport
import com.scrcpy.protocol.ControlMessage
import com.scrcpy.protocol.TouchEventAction
import com.scrcpy.protocol.TouchMapper
import com.scrcpy.protocol.TouchPointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

private enum class UsbPermissionResult {
    GRANTED,
    DENIED,
    TIMED_OUT
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.scrcpy.client.USB_PERMISSION"
        private const val USB_PERMISSION_TIMEOUT_MS = 30_000L
    }

    private lateinit var sessionManager: SessionManager
    private var usbAttachNotice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(applicationContext)

        // Start Foreground Service to keep session alive during screen off / app switch
        val serviceIntent = Intent(this, SessionService::class.java)
        startForegroundService(serviceIntent)

        // Immersive mode for Pixel 4a 5G
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        handleUsbIntent(intent)

        setContent {
            ScrcpyApp(
                sessionManager = sessionManager,
                usbAttachNotice = usbAttachNotice,
                onUsbTcpipFallback = { onProgress -> runUsbTcpipFallback(onProgress) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val label = device?.productName ?: device?.deviceName ?: "USB device"
            usbAttachNotice = "USB attached: $label. Tap 'USB OTG Setup (adb tcpip 5555)' to enable Wi-Fi ADB."
        }
    }

    private fun findAdbUsbDevice(usbManager: UsbManager): Pair<UsbDevice?, String?> {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            return null to "No USB device detected. Connect the Xperia using a USB-C to USB-C cable and try again."
        }
        val adbDevice = devices.firstOrNull { device ->
            (0 until device.interfaceCount).any { idx ->
                AdbUsbTransport.isAdbInterface(device.getInterface(idx))
            }
        }
        if (adbDevice == null) {
            val names = devices.joinToString(", ") {
                it.productName ?: String.format(Locale.US, "%04x:%04x", it.vendorId, it.productId)
            }
            return null to "USB device detected ($names), but no ADB interface was found. Enable USB Debugging on the Xperia and switch USB mode to File Transfer or Charging."
        }
        return adbDevice to null
    }

    private suspend fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice): UsbPermissionResult {
        if (usbManager.hasPermission(device)) {
            return UsbPermissionResult.GRANTED
        }
        return withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_USB_PERMISSION) return
                        val permDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (permDevice != null && permDevice.deviceId != device.deviceId) return

                        try {
                            unregisterReceiver(this)
                        } catch (_: Exception) {}

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) {
                            cont.resume(if (granted) UsbPermissionResult.GRANTED else UsbPermissionResult.DENIED)
                        }
                    }
                }

                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }

                cont.invokeOnCancellation {
                    try {
                        unregisterReceiver(receiver)
                    } catch (_: Exception) {}
                }

                val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(
                    this@MainActivity,
                    device.deviceId,
                    permissionIntent,
                    flags
                )
                usbManager.requestPermission(device, pendingIntent)
            }
        } ?: UsbPermissionResult.TIMED_OUT
    }

    private suspend fun runUsbTcpipFallback(onProgress: (String) -> Unit): String {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val (targetUsb, lookupError) = findAdbUsbDevice(usbManager)
        if (targetUsb == null) {
            return lookupError ?: "No ADB USB device found."
        }

        val deviceLabel = targetUsb.productName ?: targetUsb.deviceName
        if (!usbManager.hasPermission(targetUsb)) {
            onProgress("Requesting USB host permission for $deviceLabel...")
            when (requestUsbPermission(usbManager, targetUsb)) {
                UsbPermissionResult.GRANTED -> Unit
                UsbPermissionResult.DENIED -> {
                    return "USB permission denied for $deviceLabel. Tap 'USB OTG Setup' again and approve the USB permission prompt."
                }
                UsbPermissionResult.TIMED_OUT -> {
                    return "Timed out waiting for USB permission approval. Please try again."
                }
            }
        }

        return withContext(Dispatchers.IO) {
            var transport: AdbUsbTransport? = null
            var client: AdbClient? = null
            val cancelHandle = coroutineContext.job.invokeOnCompletion {
                try {
                    client?.close()
                } catch (_: Exception) {}
                try {
                    transport?.close()
                } catch (_: Exception) {}
            }
            try {
                withContext(Dispatchers.Main) {
                    onProgress("Opening USB connection to $deviceLabel...")
                }
                val connection = usbManager.openDevice(targetUsb)
                    ?: return@withContext "Could not open USB device ($deviceLabel) after permission was granted. Unplug and reconnect the USB-C cable."

                transport = try {
                    AdbUsbTransport(usbManager, targetUsb, connection)
                } catch (e: IOException) {
                    connection.close()
                    return@withContext "USB interface error: ${e.message}"
                }

                withContext(Dispatchers.Main) {
                    onProgress("Connecting ADB over USB... If prompted on the Xperia, tap \"Allow USB debugging\".")
                }
                val keyPair = AdbCrypto.loadOrGenerateKeyPair(File(applicationContext.noBackupFilesDir, "adb"))
                client = AdbClient(transport.inputStream, transport.outputStream, keyPair)
                client.connect()

                withContext(Dispatchers.Main) {
                    onProgress("Sending 'adb tcpip 5555' to Xperia...")
                }
                val stream = client.open("tcpip:5555")
                val responseText = buildString {
                    val buf = ByteArray(256)
                    try {
                        while (true) {
                            val n = stream.inputStream.read(buf)
                            if (n <= 0) break
                            append(String(buf, 0, n, Charsets.UTF_8))
                            if (length >= 512) break
                        }
                    } catch (_: Exception) {
                        // adbd restarts in TCP/IP mode and may close the USB stream immediately
                    }
                }.trim()

                if (responseText.contains("error", ignoreCase = true) ||
                    responseText.contains("failed", ignoreCase = true) ||
                    responseText.contains("invalid", ignoreCase = true)
                ) {
                    "ADB tcpip:5555 failed: $responseText"
                } else {
                    val detail = if (responseText.isNotEmpty()) " ($responseText)" else ""
                    "Success$detail! Xperia is now listening on TCP port 5555. Disconnect the USB-C cable and connect over Wi-Fi or hotspot."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "USB TCP/IP setup failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                cancelHandle.dispose()
                try {
                    client?.close()
                } catch (_: Exception) {}
                try {
                    transport?.close()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        sessionManager.disconnect()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrcpyApp(
    sessionManager: SessionManager,
    usbAttachNotice: String?,
    onUsbTcpipFallback: suspend (onProgress: (String) -> Unit) -> String
) {
    val state by sessionManager.state.collectAsState()
    val stats by sessionManager.stats.collectAsState()
    val userMsg by sessionManager.userMessage.collectAsState()

    var host by remember { mutableStateOf("192.168.43.1") }
    var port by remember { mutableStateOf("5555") }
    var showUsbDialog by remember { mutableStateOf(false) }
    var usbInProgress by remember { mutableStateOf(false) }
    var usbStatus by remember { mutableStateOf<String?>(null) }
    var usbJob by remember { mutableStateOf<Job?>(null) }

    var turnScreenOff by remember { mutableStateOf(true) }
    var stayAwake by remember { mutableStateOf(true) }
    var enableAudio by remember { mutableStateOf(false) }

    var boundSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    val coroutineScope = rememberCoroutineScope()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val currentState = state) {
                is SessionState.Idle, is SessionState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Scrcpy for Android", fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Control your Sony Xperia from Pixel 4a 5G",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        if (currentState is SessionState.Error) {
                            Text(
                                "Error: ${currentState.error}",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        val notice = userMsg ?: usbAttachNotice
                        if (notice != null) {
                            Text(
                                notice,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Target IP (Hotspot / Wi-Fi)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("ADB Port (Default 5555)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Turn off Xperia screen")
                            Switch(checked = turnScreenOff, onCheckedChange = { turnScreenOff = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Forward Audio (48kHz Raw PCM)")
                            Switch(checked = enableAudio, onCheckedChange = { enableAudio = it })
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = {
                                if (!usbInProgress) {
                                    showUsbDialog = true
                                    usbInProgress = true
                                    usbStatus = "Checking attached USB devices..."
                                    usbJob = coroutineScope.launch {
                                        try {
                                            usbStatus = onUsbTcpipFallback { progress ->
                                                usbStatus = progress
                                            }
                                        } finally {
                                            usbInProgress = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("USB OTG Setup (adb tcpip 5555)")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                boundSurface?.let { surface ->
                                    sessionManager.connect(
                                        host = host,
                                        port = port.toIntOrNull() ?: 5555,
                                        videoSurface = surface,
                                        enableAudio = enableAudio,
                                        turnScreenOff = turnScreenOff,
                                        stayAwake = stayAwake
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect to Xperia")
                        }
                    }

                    // Hidden SurfaceView pre-binding
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        boundSurface = holder.surface
                                    }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                                    override fun surfaceDestroyed(h: SurfaceHolder) {
                                        boundSurface = null
                                    }
                                })
                            }
                        },
                        modifier = Modifier.size(1.dp)
                    )

                    if (showUsbDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                if (!usbInProgress) {
                                    showUsbDialog = false
                                }
                            },
                            title = { Text("USB OTG `adb tcpip 5555`") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (usbInProgress) {
                                        CircularProgressIndicator()
                                    }
                                    Text(usbStatus ?: "Running USB OTG setup...")
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (usbInProgress) {
                                            usbJob?.cancel()
                                            usbInProgress = false
                                        }
                                        showUsbDialog = false
                                    }
                                ) {
                                    Text(if (usbInProgress) "Cancel" else "OK")
                                }
                            }
                        )
                    }
                }
                is SessionState.Connecting -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(currentState.step)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { sessionManager.disconnect() }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
                is SessionState.Connected -> {
                    MirrorScreen(
                        sessionManager = sessionManager,
                        stats = stats,
                        onDisconnect = { sessionManager.disconnect() }
                    )
                }
            }
        }
    }
}

@Composable
fun MirrorScreen(
    sessionManager: SessionManager,
    stats: SessionStats,
    onDisconnect: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    var showKeyboardBar by remember { mutableStateOf(false) }
    var isAudioActive by remember { mutableStateOf(false) }
    var isScreenOff by remember { mutableStateOf(false) }

    var viewW by remember { mutableStateOf(1080f) }
    var viewH by remember { mutableStateOf(2400f) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Surface bound to decoder
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {
                            viewW = w.toFloat()
                            viewH = height.toFloat()
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })

                    // Multi-touch handling directly on view
                    setOnTouchListener { _, event ->
                        val touchAction = TouchEventAction.fromAndroidActionMasked(event.actionMasked)
                        val pointers = List(event.pointerCount) { index ->
                            TouchPointerInput(
                                pointerId = event.getPointerId(index).toLong(),
                                x = event.getX(index),
                                y = event.getY(index),
                                pressure = event.getPressure(index)
                            )
                        }
                        val messages = TouchMapper.buildTouchControlMessages(
                            action = touchAction,
                            actionIndex = event.actionIndex,
                            pointers = pointers,
                            viewWidth = viewW,
                            viewHeight = viewH,
                            videoWidth = sessionManager.targetWidth,
                            videoHeight = sessionManager.targetHeight
                        )
                        if (messages.isNotEmpty()) {
                            sessionManager.sendControlMessages(messages)
                        }
                        touchAction != TouchEventAction.OTHER
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Stats
        Card(
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
        ) {
            Text(
                text = "${stats.width}x${stats.height} | ${String.format("%.1f", stats.fps)} FPS",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp)
            )
        }

        // Bottom Navigation Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f))
        ) {
            if (showKeyboardBar) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type text to send...") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotEmpty()) {
                                sessionManager.sendControlMessage(ControlMessage.InjectText(textInput))
                                textInput = ""
                            }
                        })
                    )
                    IconButton(onClick = {
                        if (textInput.isNotEmpty()) {
                            sessionManager.sendControlMessage(ControlMessage.InjectText(textInput))
                            textInput = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    sessionManager.sendControlMessage(ControlMessage.InjectKeycode(0, ControlMessage.KEYCODE_BACK))
                    sessionManager.sendControlMessage(ControlMessage.InjectKeycode(1, ControlMessage.KEYCODE_BACK))
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                IconButton(onClick = {
                    sessionManager.sendControlMessage(ControlMessage.InjectKeycode(0, ControlMessage.KEYCODE_HOME))
                    sessionManager.sendControlMessage(ControlMessage.InjectKeycode(1, ControlMessage.KEYCODE_HOME))
                }) {
                    Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White)
                }
                IconButton(onClick = {
                    sessionManager.sendControlMessage(ControlMessage.InjectKeycode(0, ControlMessage.KEYCODE_APP_SWITCH))
                    sessionManager.sendControlMessage(ControlMessage.InjectKeycode(1, ControlMessage.KEYCODE_APP_SWITCH))
                }) {
                    Icon(Icons.Default.Menu, contentDescription = "Recents", tint = Color.White)
                }
                IconButton(onClick = {
                    isScreenOff = !isScreenOff
                    sessionManager.setScreenOffState(isScreenOff)
                }) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Screen Off Toggle",
                        tint = if (isScreenOff) Color.Cyan else Color.White
                    )
                }
                IconButton(onClick = {
                    isAudioActive = !isAudioActive
                    sessionManager.toggleAudioLive(isAudioActive)
                }) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Audio Toggle",
                        tint = if (isAudioActive) Color.Green else Color.White
                    )
                }
                IconButton(onClick = { showKeyboardBar = !showKeyboardBar }) {
                    Icon(Icons.Default.Edit, contentDescription = "Keyboard", tint = Color.White)
                }
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = Color.Red)
                }
            }
        }
    }
}
