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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SessionManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var adbClient: AdbClient? = null
    private var controlStream: AdbStream? = null
    private var controlOutboundChannel: Channel<ControlMessage>? = null
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
                val scidHex = String.format(Locale.US, "%08x", scid)

                val options = buildList {
                    add("scid=$scidHex")
                    add("log_level=info")
                    add("video=true")
                    add("video_codec=h264")
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

                // Upstream DesktopConnection.open() accepts sockets in strict order:
                // video (sends dummy byte if enabled), audio (if enabled), then control.
                // Device meta and stream headers are only written AFTER all enabled sockets connect.
                _state.value = SessionState.Connecting("Connecting video stream...")
                val abstractName = "localabstract:scrcpy_$scidHex"
                val vStream = client.open(abstractName)
                videoStream = vStream
                StreamPacket.readDummyByte(vStream.inputStream)

                val aStream = if (lastEnableAudio) {
                    _state.value = SessionState.Connecting("Connecting audio stream...")
                    client.open(abstractName).also { audioStream = it }
                } else {
                    null
                }

                _state.value = SessionState.Connecting("Connecting control stream...")
                val cStream = client.open(abstractName)
                controlStream = cStream

                _state.value = SessionState.Connecting("Reading stream metadata...")
                val deviceName = StreamPacket.readDeviceMeta(vStream.inputStream)
                val videoCodecId = StreamPacket.readCodecId(vStream.inputStream)
                val videoMimeType = when (videoCodecId) {
                    StreamPacket.STREAM_DISABLED_EXPLICIT ->
                        throw IOException("Video stream explicitly disabled by target device")
                    StreamPacket.STREAM_DISABLED_CONFIG_ERROR ->
                        throw IOException("Video encoder configuration error on target device")
                    StreamPacket.CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
                    StreamPacket.CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                    StreamPacket.CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
                    StreamPacket.CODEC_VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
                    StreamPacket.CODEC_VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
                    else -> throw IOException(
                        "Unsupported video codec ID: 0x${String.format(Locale.US, "%08x", videoCodecId)}"
                    )
                }

                val initialSession = StreamPacket.readPacket(vStream.inputStream)
                if (initialSession !is StreamPacket.Session) {
                    throw IOException("Expected initial video session packet after codec header")
                }
                targetWidth = initialSession.width
                targetHeight = initialSession.height
                _stats.value = SessionStats(targetWidth, targetHeight, 0f, 0L)

                _state.value = SessionState.Connected(deviceName)

                // Start control message reader and serialized outbound writer
                val txChannel = Channel<ControlMessage>(Channel.UNLIMITED)
                controlOutboundChannel = txChannel
                launch { handleControlOutbound(cStream, txChannel) }
                launch { handleControlReplies(cStream.inputStream) }

                // Start audio stream reader if enabled
                if (aStream != null) {
                    launch { startAndHandleAudioStream(aStream) }
                }

                // Start video decoder
                lastSurface?.let { surface ->
                    val codec = initMediaCodec(surface, targetWidth, targetHeight, videoMimeType)
                    launch { handleVideoStream(vStream.inputStream, surface, videoMimeType, codec) }
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

    private fun initMediaCodec(
        surface: Surface,
        width: Int,
        height: Int,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    ): MediaCodec {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
        } catch (_: Exception) {}

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(format, surface, null, 0)
        codec.start()
        videoDecoder = codec
        return codec
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

    private fun drainVideoOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo): Int {
        var rendered = 0
        while (isRunning.get()) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true)
                rendered++
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else {
                break
            }
        }
        return rendered
    }

    private fun handleVideoStream(
        input: InputStream,
        surface: Surface,
        mimeType: String,
        initialCodec: MediaCodec
    ) {
        var codec = initialCodec
        val bufferInfo = MediaCodec.BufferInfo()
        var frameCount = 0
        var lastTime = System.currentTimeMillis()

        try {
            while (isRunning.get()) {
                when (val packet = StreamPacket.readPacket(input)) {
                    is StreamPacket.Session -> {
                        if (packet.width != targetWidth || packet.height != targetHeight) {
                            targetWidth = packet.width
                            targetHeight = packet.height
                            _stats.value = _stats.value.copy(width = targetWidth, height = targetHeight)
                            codec = initMediaCodec(surface, targetWidth, targetHeight, mimeType)
                        }
                    }
                    is StreamPacket.Media -> {
                        var inIndex = -1
                        while (isRunning.get()) {
                            frameCount += drainVideoOutput(codec, bufferInfo)
                            inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) break
                        }
                        if (!isRunning.get() || inIndex < 0) break

                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            inBuffer.put(packet.data)
                        }
                        val flags = when {
                            packet.isConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                            packet.isKeyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                            else -> 0
                        }
                        codec.queueInputBuffer(inIndex, 0, packet.data.size, packet.pts, flags)
                        frameCount += drainVideoOutput(codec, bufferInfo)

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 1000) {
                            val fps = frameCount * 1000f / (now - lastTime)
                            _stats.value = SessionStats(targetWidth, targetHeight, fps, 0L)
                            frameCount = 0
                            lastTime = now
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                handleDisconnectOrReconnect()
            }
        }
    }

    private fun startAndHandleAudioStream(aStream: AdbStream) {
        try {
            val audioHeader = AudioStreamHeader.read(aStream.inputStream)
            if (audioHeader.isDisabled) {
                _userMessage.value = if (audioHeader.isConfigError) {
                    "Target audio configuration error. Continuing video only."
                } else {
                    "Audio capture unsupported or disabled on device (requires Android 11+). Continuing video only."
                }
                try {
                    aStream.close()
                } catch (_: Exception) {}
                return
            }
            if (!isRunning.get()) return
            initAudioTrack()
            handleAudioStream(aStream.inputStream)
        } catch (_: Exception) {}
    }

    private fun handleAudioStream(input: InputStream) {
        try {
            while (isRunning.get()) {
                when (val packet = StreamPacket.readPacket(input)) {
                    is StreamPacket.Media -> {
                        // Write raw 48kHz stereo 16-bit PCM buffer directly into AudioTrack
                        audioTrack?.write(packet.data, 0, packet.data.size)
                    }
                    is StreamPacket.Session -> Unit
                }
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

    private suspend fun handleControlOutbound(stream: AdbStream, channel: Channel<ControlMessage>) {
        try {
            for (msg in channel) {
                val bytes = msg.toByteArray()
                stream.outputStream.write(bytes)
                stream.outputStream.flush()
            }
        } catch (_: Exception) {}
    }

    fun sendControlMessage(msg: ControlMessage) {
        controlOutboundChannel?.trySend(msg)
    }

    fun sendControlMessages(messages: List<ControlMessage>) {
        val channel = controlOutboundChannel ?: return
        for (msg in messages) {
            channel.trySend(msg)
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
            controlOutboundChannel?.close()
        } catch (_: Exception) {}
        controlOutboundChannel = null

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
