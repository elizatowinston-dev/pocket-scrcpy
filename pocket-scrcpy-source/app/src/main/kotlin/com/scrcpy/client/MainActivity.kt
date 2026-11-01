package com.scrcpy.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import com.scrcpy.adb.AdbPairingClient
import com.scrcpy.client.usb.AdbUsbTransport
import com.scrcpy.protocol.ControlMessage
import com.scrcpy.protocol.TouchMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var sessionManager: SessionManager

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

        setContent {
            ScrcpyApp(sessionManager, onUsbTcpipFallback = { runUsbTcpipFallback() })
        }
    }

    private fun runUsbTcpipFallback(): String {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        val targetUsb = deviceList.values.firstOrNull() ?: return "No USB device detected. Connect Xperia with USB-C cable."

        return try {
            val connection = usbManager.openDevice(targetUsb) ?: return "Cannot open USB device. Grant USB permissions."
            val transport = AdbUsbTransport(usbManager, targetUsb, connection)
            val keyPair = AdbCrypto.generateKeyPair()
            val client = AdbClient(transport.inputStream, transport.outputStream, keyPair)
            client.connect()
            // Execute adb tcpip 5555
            val stream = client.open("tcpip:5555")
            val resp = ByteArray(64)
            stream.inputStream.read(resp)
            transport.close()
            "Success! Xperia is now listening on port 5555. Disconnect USB and connect over Wi-Fi."
        } catch (e: Exception) {
            "USB TCP/IP failed: ${e.message}"
        }
    }

    override fun onDestroy() {
        sessionManager.disconnect()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrcpyApp(sessionManager: SessionManager, onUsbTcpipFallback: () -> String) {
    val state by sessionManager.state.collectAsState()
    val stats by sessionManager.stats.collectAsState()
    val userMsg by sessionManager.userMessage.collectAsState()

    var host by remember { mutableStateOf("192.168.43.1") }
    var port by remember { mutableStateOf("5555") }
    var pairingPort by remember { mutableStateOf("37000") }
    var pairingCode by remember { mutableStateOf("") }
    var showPairDialog by remember { mutableStateOf(false) }
    var showUsbDialog by remember { mutableStateOf(false) }
    var usbStatus by remember { mutableStateOf<String?>(null) }
    var pairingStatus by remember { mutableStateOf<String?>(null) }

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

                        if (userMsg != null) {
                            Text(
                                userMsg!!,
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

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showPairDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Wireless Pair")
                            }

                            OutlinedButton(
                                onClick = {
                                    showUsbDialog = true
                                    usbStatus = onUsbTcpipFallback()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("USB OTG Setup")
                            }
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

                    if (showPairDialog) {
                        AlertDialog(
                            onDismissRequest = { showPairDialog = false },
                            title = { Text("Wireless Debugging Pairing") },
                            text = {
                                Column {
                                    Text("Enter pairing code from Xperia Wireless Debugging settings:")
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = pairingPort,
                                        onValueChange = { pairingPort = it },
                                        label = { Text("Pairing Port") }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = pairingCode,
                                        onValueChange = { pairingCode = it },
                                        label = { Text("6-digit Code") }
                                    )
                                    if (pairingStatus != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(pairingStatus!!, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    coroutineScope.launch {
                                        pairingStatus = "Pairing with SPAKE2 / Conscrypt TLS..."
                                        try {
                                            val client = AdbPairingClient(
                                                host,
                                                pairingPort.toIntOrNull() ?: 37000,
                                                pairingCode
                                            )
                                            val res = withContext(Dispatchers.IO) { client.pair() }
                                            pairingStatus = if (res) "Paired successfully!" else "Pairing failed"
                                        } catch (e: Exception) {
                                            pairingStatus = "Error: ${e.message}"
                                        }
                                    }
                                }) {
                                    Text("Start Pairing")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPairDialog = false }) {
                                    Text("Close")
                                }
                            }
                        )
                    }

                    if (showUsbDialog) {
                        AlertDialog(
                            onDismissRequest = { showUsbDialog = false },
                            title = { Text("USB OTG `adb tcpip 5555`") },
                            text = { Text(usbStatus ?: "Running USB OTG fallback...") },
                            confirmButton = {
                                Button(onClick = { showUsbDialog = false }) { Text("OK") }
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
                        val actionMasked = event.actionMasked
                        val pointerIndex = event.actionIndex
                        val pointerId = event.getPointerId(pointerIndex).toLong()

                        val mapped = TouchMapper.mapCoordinates(
                            touchX = event.getX(pointerIndex),
                            touchY = event.getY(pointerIndex),
                            viewWidth = viewW,
                            viewHeight = viewH,
                            videoWidth = sessionManager.targetWidth,
                            videoHeight = sessionManager.targetHeight
                        ) ?: return@setOnTouchListener true

                        val scrcpyAction = when (actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_POINTER_DOWN -> ControlMessage.ACTION_DOWN
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_POINTER_UP -> ControlMessage.ACTION_UP
                            android.view.MotionEvent.ACTION_MOVE -> ControlMessage.ACTION_MOVE
                            else -> ControlMessage.ACTION_CANCEL
                        }

                        sessionManager.sendControlMessage(
                            ControlMessage.InjectTouchEvent(
                                action = scrcpyAction,
                                pointerId = pointerId,
                                x = mapped.x.toInt(),
                                y = mapped.y.toInt(),
                                screenWidth = sessionManager.targetWidth,
                                screenHeight = sessionManager.targetHeight,
                                pressure = event.getPressure(pointerIndex)
                            )
                        )
                        true
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
