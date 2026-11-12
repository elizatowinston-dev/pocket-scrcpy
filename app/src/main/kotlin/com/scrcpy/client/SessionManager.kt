package com.scrcpy.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.scrcpy.adb.AdbClient
import com.scrcpy.adb.AdbCrypto
import com.scrcpy.adb.AdbStream
import com.scrcpy.protocol.AudioStreamHeader
import com.scrcpy.protocol.ControlMessage
import com.scrcpy.protocol.DeviceMessage
import com.scrcpy.protocol.StreamPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class SessionManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var adbClient: AdbClient? = null
    private var controlStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var audioStream: AdbStream? = null

    private var videoDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val isRunning = AtomicBoolean(false)
    private var connectionJob: Job? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats(1080, 2400, 0f, 0L))
    val stats = _stats.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage = _userMessage.asStateFlow()

    var targetWidth = 1080
        private set
    var targetHeight = 2400
        private set

    // Auto-reconnect configuration
    var autoReconnectEnabled = true
    private var lastHost: String = ""
    private var lastPort: Int = 5555
    private var lastSurface: Surface? = null
    private var lastEnableAudio = false
    private var lastTurnScreenOff = false
    private var lastStayAwake = true
    private var lastBitRate = 8_000_000
    private var lastMaxFps = 60
    private var reconnectAttempts = 0

    fun connect(
        host: String,
        port: Int = 5555,
        videoSurface: Surface,
        enableAudio: Boolean = false,
        turnScreenOff: Boolean = false,
        stayAwake: Boolean = true,
        bitRate: Int = 8_000_000,
        maxFps: Int = 60
    ) {
        lastHost = host
        lastPort = port
        lastSurface = videoSurface
        lastEnableAudio = enableAudio
        lastTurnScreenOff = turnScreenOff
        lastStayAwake = stayAwake
        lastBitRate = bitRate
        lastMaxFps = maxFps
        reconnectAttempts = 0

        startConnectionLoop()
    }

    private fun startConnectionLoop() {
        if (isRunning.getAndSet(true)) return

        connectionJob = scope.launch {
            try {
                _state.value = SessionState.Connecting("Connecting to ADB at $lastHost:$lastPort...")

                val keyPair = AdbCrypto.loadOrGenerateKeyPair(File(context.noBackupFilesDir, "adb"))
                val adbSocket = Socket(lastHost, lastPort)
                val client = AdbClient(adbSocket, keyPair)
                client.connect()
                adbClient = client
                reconnectAttempts = 0

                _state.value = SessionState.Connecting("Pushing scrcpy-server...")
                context.assets.open("scrcpy-server.jar").use { jarInput ->
                    client.openSync().use { sync ->
                        sync.sendFile(jarInput, "/data/local/tmp/scrcpy-server.jar")
                    }
                }

                // Random 31-bit SCID as required by scrcpy
                val scid = SecureRandom().nextInt(0x7FFFFFFF)
                val scidHex = String.format("%08x", scid)

                val options = buildList {
                    add("scid=$scidHex")
                    add("log_level=info")
                    add("video=true")
                    add("audio=$lastEnableAudio")
                    if (lastEnableAudio) {
                        add("audio_codec=raw") // 48kHz 16-bit stereo PCM
                    }
                    add("video_bit_rate=$lastBitRate")
                    add("max_fps=$lastMaxFps")
                    add("tunnel_forward=true")
                    add("control=true")
                    add("cleanup=true")
                    if (lastStayAwake) add("stay_awake=true")
                    if (lastTurnScreenOff) add("power_off_on_close=true")
                    add("send_dummy_byte=true")
                    add("send_device_meta=true")
                    add("send_frame_meta=true")
                    add("send_stream_meta=true")
                }.joinToString(" ")

                val serverCmd = "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 $options"

                _state.value = SessionState.Connecting("Starting server on device...")
                client.shell(serverCmd)

                delay(600)

                _state.value = SessionState.Connecting("Connecting video stream...")
                val abstractName = "localabstract:scrcpy_$scidHex"
                val vStream = client.open(abstractName)
                videoStream = vStream

                // Upstream scrcpy handshake:
                // 1. Dummy byte if send_dummy_byte=true
                vStream.inputStream.read()
                // 2. 64-byte device name
                val deviceName = StreamPacket.readDeviceMeta(vStream.inputStream)
                // 3. 4-byte codec id
                StreamPacket.readCodecId(vStream.inputStream)

                // Socket accept order: video, then audio (if enabled), then control
                if (lastEnableAudio) {
                    _state.value = SessionState.Connecting("Connecting audio stream...")
                    val aStream = client.open(abstractName)
                    audioStream = aStream
                    val audioHeader = AudioStreamHeader.read(aStream.inputStream)
                    if (audioHeader.isDisabled) {
                        if (audioHeader.isConfigError) {
                            _userMessage.value = "Target audio configuration error. Continuing video only."
                        } else {
                            _userMessage.value = "Audio capture unsupported or disabled on device (requires Android 11+). Continuing video only."
                        }
                    } else {
                        initAudioTrack()
                        launch { handleAudioStream(aStream.inputStream) }
                    }
                }

                _state.value = SessionState.Connecting("Connecting control stream...")
                val cStream = client.open(abstractName)
                controlStream = cStream

                _state.value = SessionState.Connected(deviceName)

                // Start control message reader
                launch { handleControlReplies(cStream.inputStream) }

                // Start video decoder
                lastSurface?.let { surface ->
                    initMediaCodec(surface, targetWidth, targetHeight)
                    launch { handleVideoStream(vStream.inputStream) }
                }

                if (lastTurnScreenOff) {
                    sendControlMessage(ControlMessage.SetDisplayPower(false))
                }

            } catch (e: Exception) {
                _state.value = SessionState.Error(e.message ?: "Connection failed")
                handleDisconnectOrReconnect()
            }
        }
    }

    private fun initMediaCodec(surface: Surface, width: Int, height: Int) {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
        } catch (_: Exception) {}

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, surface, null, 0)
        codec.start()
        videoDecoder = codec
    }

    private fun initAudioTrack() {
        val sampleRate = 48000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build().apply { play() }
    }

    private fun handleVideoStream(input: InputStream) {
        val codec = videoDecoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var frameCount = 0
        var lastTime = System.currentTimeMillis()

        try {
            while (isRunning.get()) {
                val packet = StreamPacket.readPacket(input)

                // Update resolution on session packet
                if (packet.pts and StreamPacket.PACKET_FLAG_SESSION != 0L) {
                    val buf = ByteBuffer.wrap(packet.data)
                    if (packet.data.size >= 8) {
                        targetWidth = buf.getInt()
                        targetHeight = buf.getInt()
                    }
                    continue
                }

                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    inBuffer?.clear()
                    inBuffer?.put(packet.data)
                    val flags = if (packet.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    codec.queueInputBuffer(inIndex, 0, packet.data.size, packet.pts, flags)
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true)
                    frameCount++
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }

                val now = System.currentTimeMillis()
                if (now - lastTime >= 1000) {
                    val fps = frameCount * 1000f / (now - lastTime)
                    _stats.value = SessionStats(targetWidth, targetHeight, fps, 0L)
                    frameCount = 0
                    lastTime = now
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                handleDisconnectOrReconnect()
            }
        }
    }

    private fun handleAudioStream(input: InputStream) {
        try {
            while (isRunning.get()) {
                val packet = StreamPacket.readPacket(input)
                // Write raw 48kHz stereo 16-bit PCM buffer directly into AudioTrack
                audioTrack?.write(packet.data, 0, packet.data.size)
            }
        } catch (_: Exception) {}
    }

    private fun handleControlReplies(input: InputStream) {
        try {
            while (isRunning.get()) {
                val msg = DeviceMessage.parse(input)
                when (msg) {
                    is DeviceMessage.Clipboard -> {
                        scope.launch(Dispatchers.Main) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("scrcpy", msg.text))
                        }
                    }
                    is DeviceMessage.AckClipboard -> {}
                    is DeviceMessage.UhidOutput -> {}
                }
            }
        } catch (_: Exception) {}
    }

    fun sendControlMessage(msg: ControlMessage) {
        val stream = controlStream ?: return
        scope.launch {
            try {
                val bytes = msg.toByteArray()
                stream.outputStream.write(bytes)
                stream.outputStream.flush()
            } catch (_: Exception) {}
        }
    }

    fun toggleAudioLive(enable: Boolean) {
        lastEnableAudio = enable
        _userMessage.value = if (enable) "Enabling audio (restarting session)..." else "Disabling audio (restarting session)..."
        disconnect(reconnect = true)
    }

    fun setScreenOffState(off: Boolean) {
        sendControlMessage(ControlMessage.SetDisplayPower(!off))
    }

    fun wakeDevice() {
        sendControlMessage(ControlMessage.SetDisplayPower(true))
        sendControlMessage(ControlMessage.InjectKeycode(0, ControlMessage.KEYCODE_POWER))
        sendControlMessage(ControlMessage.InjectKeycode(1, ControlMessage.KEYCODE_POWER))
    }

    private fun handleDisconnectOrReconnect() {
        disconnect(reconnect = autoReconnectEnabled)
    }

    fun disconnect(reconnect: Boolean = false) {
        isRunning.set(false)
        connectionJob?.cancel()

        try {
            videoDecoder?.stop()
            videoDecoder?.release()
        } catch (_: Exception) {}
        videoDecoder = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            controlStream?.close()
            videoStream?.close()
            audioStream?.close()
            adbClient?.close()
        } catch (_: Exception) {}
        controlStream = null
        videoStream = null
        audioStream = null
        adbClient = null

        if (reconnect && reconnectAttempts < 5) {
            reconnectAttempts++
            val backoff = (reconnectAttempts * 1000L).coerceAtMost(5000L)
            scope.launch {
                _state.value = SessionState.Connecting("Connection lost. Reconnecting in ${backoff / 1000}s (attempt $reconnectAttempts)...")
                delay(backoff)
                startConnectionLoop()
            }
        } else {
            _state.value = SessionState.Idle
        }
    }
}

sealed class SessionState {
    object Idle : SessionState()
    data class Connecting(val step: String) : SessionState()
    data class Connected(val deviceName: String) : SessionState()
    data class Error(val error: String) : SessionState()
}

data class SessionStats(
    val width: Int,
    val height: Int,
    val fps: Float,
    val latencyMs: Long
)
