# Scrcpy Protocol Specification (v5.0.1)

This document describes the binary wire protocol used between `pocket-scrcpy` and the upstream `scrcpy-server` (version `5.0.1`, verified against `Genymobile/scrcpy` `v5.0.1`).

## 1. Connection Lifecycle & Tunneling

Upstream scrcpy supports two connection modes:
- **Forward tunnel (`tunnel_forward=true`)**: The server creates a UNIX abstract local socket named `scrcpy_<scid>` (e.g. `scrcpy_12345678`). The client issues an ADB `open("localabstract:scrcpy_<scid>")` stream for each enabled channel.
- **Reverse tunnel (`tunnel_forward=false`)**: The client listens on a local socket and configures `adb reverse` so the server connects back.

In `pocket-scrcpy`, we use **Forward Tunneling** (`tunnel_forward=true`), directly leveraging ADB `localabstract:` streams over TCP or USB host connections.

### Server Options (`v5.0.1`)
`scrcpy-server` (`com.genymobile.scrcpy.Server 5.0.1`) accepts `key=value` options including:
- `scid=<8 hex digits>`, `log_level=info`, `tunnel_forward=true`, `cleanup=true`
- `video=true`, `video_codec=h264`, `video_bit_rate=<bps>`, `max_fps=<fps>`
- `audio=true|false`, `audio_codec=raw` (48 kHz 16-bit LE stereo PCM)
- `control=true`, `stay_awake=true`, `power_off_on_close=true`
- `send_dummy_byte=true`, `send_device_meta=true`, `send_frame_meta=true`, `send_stream_meta=true` (replaces the older `send_codec_meta` option name, which is rejected as unknown in `v5.0.1`)

### Handshake Sequence (`tunnel_forward=true`)
1. **Server Launch**:
   ```bash
   CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 [options...]
   ```
2. **Open Enabled Sockets in Order** (`DesktopConnection.open`):
   The server accepts sockets sequentially before sending device metadata or starting encoders. The client MUST open all enabled channels before blocking on device metadata or stream headers:
   1. **Video socket** (if `video=true`): open `localabstract:scrcpy_<scid>`. If `send_dummy_byte=true`, the server immediately writes 1 dummy byte (`0x00`) on the first opened socket so the client can detect that the server is listening.
   2. **Audio socket** (if `audio=true`): open `localabstract:scrcpy_<scid>`.
   3. **Control socket** (if `control=true`): open `localabstract:scrcpy_<scid>`.
3. **Device Metadata** (on the first enabled socket if `send_device_meta=true`):
   - **64 bytes**: UTF-8 device name, zero-padded to 64 bytes.
4. **Video Stream Header** (on the video socket if `send_stream_meta=true`):
   - **4 bytes** (big-endian `int32`): Video codec ID or disable code:
     - `0x68323634` (`"h264"`): H.264 / AVC
     - `0x68323635` (`"h265"`): H.265 / HEVC
     - `0x00617631` (`"\0av1"`): AV1
     - `0x00767038` (`"\0vp8"`): VP8
     - `0x00767039` (`"\0vp9"`): VP9
     - `0x00000000`: Stream explicitly disabled
     - `0x00000001`: Target configuration error
   - **12 bytes**: Initial **Session Packet** (see Section 2) carrying the initial encoded video `width` and `height`.
5. **Audio Stream Header** (on the audio socket if `audio=true` and `send_stream_meta=true`):
   - **4 bytes** (big-endian `int32`): Audio codec ID or disable code:
     - `0x6f707573` (`"opus"`): Opus
     - `0x00616163` (`"\0aac"`): AAC
     - `0x666c6163` (`"flac"`): FLAC
     - `0x00726177` (`"\0raw"`): Raw PCM (48 kHz, 16-bit signed LE, stereo)
     - `0x00000000`: Stream explicitly disabled (e.g. Android < 11 or capture denied)
     - `0x00000001`: Audio configuration error
   - Audio does not use session packets; media packets follow immediately after the 4-byte codec ID.

---

## 2. Video & Audio Framing (`Streamer.java` / `demuxer.c`)

Every packet begins with a **12-byte header** (`SC_PACKET_HEADER_SIZE = 12`). Bit 7 of byte 0 (the MSB of the 12-byte header) distinguishes session packets from media packets:

### Session Packet (MSB = 1, video stream only, 12 bytes total — NO payload)
Emitted once after the 4-byte video codec ID and whenever the encoder restarts due to rotation or resize:
- **Bytes 0..3** (`uint32` BE): Flags (`0x80000000` | `(client_resized ? 1 : 0)`)
- **Bytes 4..7** (`uint32` BE): Video `width` in pixels
- **Bytes 8..11** (`uint32` BE): Video `height` in pixels

### Media Packet (MSB = 0, 12-byte header + `packet_size` payload bytes)
- **Bytes 0..7** (`uint64` BE): PTS and flags:
  - Bit 63: `0` (media packet flag)
  - Bit 62 (`1L << 62`): `PACKET_FLAG_CONFIG` (codec configuration / SPS+PPS / CSD; PTS field is unused)
  - Bit 61 (`1L << 61`): `PACKET_FLAG_KEY_FRAME` (key frame / IDR)
  - Bits 0..60: Presentation timestamp in microseconds (`pts`)
- **Bytes 8..11** (`uint32` BE): `packet_size` (must be `> 0`)
- **Payload** (`packet_size` bytes): Raw encoded frame bytes (Annex-B NAL units for H.264/H.265, or raw PCM samples / encoded audio frame).

---

## 3. Control Message Layouts (Client -> Server)

Messages sent from the client to the server on the control channel begin with a 1-byte message type.

### TYPE 0: `INJECT_KEYCODE` (14 bytes)
- `type` (1 byte): `0`
- `action` (1 byte): `0` (`ACTION_DOWN`), `1` (`ACTION_UP`)
- `keycode` (4 bytes, BE): Android `KeyEvent.KEYCODE_*`
- `repeat` (4 bytes, BE): Repeat count
- `meta_state` (4 bytes, BE): `KeyEvent.META_*` bitmask

### TYPE 1: `INJECT_TEXT` (Variable length)
- `type` (1 byte): `1`
- `length` (4 bytes, BE): UTF-8 byte length
- `text` (`length` bytes): Raw UTF-8 bytes

### TYPE 2: `INJECT_TOUCH_EVENT` (32 bytes)
- `type` (1 byte): `2`
- `action` (1 byte): `0` (`ACTION_DOWN`), `1` (`ACTION_UP`), `2` (`ACTION_MOVE`), `3` (`ACTION_CANCEL`)
- `pointer_id` (8 bytes, BE): Unique touch pointer ID
- `position.x` (4 bytes, BE): X coordinate
- `position.y` (4 bytes, BE): Y coordinate
- `screen_width` (2 bytes, BE): Target display width
- `screen_height` (2 bytes, BE): Target display height
- `pressure` (2 bytes, BE): Fixed-point 16-bit unsigned (float `0.0 .. 1.0` mapped to `0x0000 .. 0xFFFF`)
- `action_button` (4 bytes, BE): Button mask
- `buttons` (4 bytes, BE): State of mouse/stylus buttons

### TYPE 3: `INJECT_SCROLL_EVENT` (21 bytes)
- `type` (1 byte): `3`
- `position.x` (4 bytes, BE): X coordinate
- `position.y` (4 bytes, BE): Y coordinate
- `screen_width` (2 bytes, BE): Screen width
- `screen_height` (2 bytes, BE): Screen height
- `hscroll` (2 bytes, BE): Fixed-point 16-bit signed (`-16` to `+16`)
- `vscroll` (2 bytes, BE): Fixed-point 16-bit signed (`-16` to `+16`)
- `buttons` (4 bytes, BE): Button state

### TYPE 4: `BACK_OR_SCREEN_ON` (2 bytes)
- `type` (1 byte): `4`
- `action` (1 byte): `0` (`ACTION_DOWN`), `1` (`ACTION_UP`)

### TYPE 9: `SET_CLIPBOARD` (Variable length)
- `type` (1 byte): `9`
- `sequence` (8 bytes, BE): Clipboard sequence ID
- `paste` (1 byte): Boolean (`1` to paste immediately, `0` otherwise)
- `length` (4 bytes, BE): UTF-8 text length
- `text` (`length` bytes): String content

### TYPE 10: `SET_DISPLAY_POWER` (2 bytes)
- `type` (1 byte): `10`
- `on` (1 byte): `1` for power on, `0` for power off

---

## 4. Device Message Layouts (Server -> Client)

### TYPE 0: `CLIPBOARD`
- `type` (1 byte): `0`
- `length` (4 bytes, BE): Text length
- `text` (`length` bytes): Clipboard content

### TYPE 1: `ACK_CLIPBOARD`
- `type` (1 byte): `1`
- `sequence` (8 bytes, BE): Acknowledged sequence number

### TYPE 2: `UHID_OUTPUT`
- `type` (1 byte): `2`
- `id` (2 bytes, BE): UHID device ID
- `size` (2 bytes, BE): Payload byte length
- `data` (`size` bytes): HID output report bytes
