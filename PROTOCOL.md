# Scrcpy Protocol Specification (v5.0.1)

This document describes the binary wire protocol used between the client (`scrcpy-android`) and the upstream `scrcpy-server` (version `5.0.1`).

## 1. Connection Lifecycle & Tunneling

Upstream scrcpy supports two connection modes:
- **Forward tunnel (`tunnel_forward=true`)**: The server creates a UNIX abstract local socket named `scrcpy_<scid>` (e.g. `scrcpy_12345678`). The client issues an ADB `open("localabstract:scrcpy_<scid>")` stream for each channel.
- **Reverse tunnel (`tunnel_forward=false`)**: The client runs a local TCP server on port `P` and sets up `adb reverse localabstract:scrcpy_<scid> tcp:P`. The server connects back to the socket.

In `scrcpy-android`, we use **Forward Tunneling** (`tunnel_forward=true`), which directly leverages native ADB socket forward streams over TCP or USB host connections without needing root or reverse port permissions on Android.

### Handshake Sequence
1. **Server Launch**:
   ```bash
   CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 [options...]
   ```
2. **Video Socket**:
   - Client opens stream `localabstract:scrcpy_<scid>`.
   - **Dummy byte**: If `send_dummy_byte=true`, the server sends a single `0x00` byte to signal that the connection is active.
   - **Device Meta**: 64 bytes UTF-8 string (zero-padded) containing device model name.
   - **Codec Header**: 4-byte integer (big-endian) representing codec FourCC:
     - H.264: `0x68323634` (`h264`)
     - H.265: `0x68323635` (`h265`)
     - AV1: `0x61763031` (`av01`)
3. **Control Socket**:
   - Client opens second stream `localabstract:scrcpy_<scid>`.
   - Bidirectional stream for control messages (Client -> Server) and device notifications (Server -> Client).

---

## 2. Video Framing & Stream Packets

Each media packet sent over the video stream is framed as:
- **PTS and Flags** (8 bytes, `uint64_t` big-endian):
  - Bit 63 (`1L << 63`): `PACKET_FLAG_SESSION` (session metadata / resize event)
  - Bit 62 (`1L << 62`): `PACKET_FLAG_CONFIG` (codec configuration / SPS/PPS)
  - Bit 61 (`1L << 61`): `PACKET_FLAG_KEY_FRAME` (I-frame)
  - Bits 0..60: Presentation timestamp in microseconds (PTS)
- **Packet Size** (4 bytes, `uint32_t` big-endian): Length of the raw NAL units following immediately.
- **Payload** (`size` bytes): Raw H.264/H.265 annex-B bitstream data.

---

## 3. Control Message Layouts (Client -> Server)

Messages sent from the client to the server on the control channel begin with a 1-byte message type.

### TYPE 0: `INJECT_KEYCODE` (14 bytes)
- `type` (1 byte): `0`
- `action` (1 byte): `0` (ACTION_DOWN), `1` (ACTION_UP)
- `keycode` (4 bytes, BE): Android `KeyEvent.KEYCODE_*`
- `repeat` (4 bytes, BE): Repeat count
- `meta_state` (4 bytes, BE): `KeyEvent.META_*` bitmask

### TYPE 1: `INJECT_TEXT` (Variable length)
- `type` (1 byte): `1`
- `length` (4 bytes, BE): UTF-8 byte length
- `text` (`length` bytes): Raw UTF-8 bytes

### TYPE 2: `INJECT_TOUCH_EVENT` (32 bytes)
- `type` (1 byte): `2`
- `action` (1 byte): `0` (ACTION_DOWN), `1` (ACTION_UP), `2` (ACTION_MOVE)
- `pointer_id` (8 bytes, BE): Unique touch pointer ID
- `position.x` (4 bytes, BE): X coordinate
- `position.y` (4 bytes, BE): Y coordinate
- `screen_width` (2 bytes, BE): Target display width
- `screen_height` (2 bytes, BE): Target display height
- `pressure` (2 bytes, BE): Fixed-point 16-bit unsigned (float 0.0 .. 1.0 mapped to `0x0000` .. `0xFFFF`)
- `action_button` (4 bytes, BE): Button mask
- `buttons` (4 bytes, BE): State of mouse/stylus buttons

### TYPE 3: `INJECT_SCROLL_EVENT` (21 bytes)
- `type` (1 byte): `3`
- `position.x` (4 bytes, BE): X coordinate
- `position.y` (4 bytes, BE): Y coordinate
- `screen_width` (2 bytes, BE): Screen width
- `screen_height` (2 bytes, BE): Screen height
- `hscroll` (2 bytes, BE): Fixed-point 16-bit signed (-16 to +16)
- `vscroll` (2 bytes, BE): Fixed-point 16-bit signed (-16 to +16)
- `buttons` (4 bytes, BE): Button state

### TYPE 4: `BACK_OR_SCREEN_ON` (2 bytes)
- `type` (1 byte): `4`
- `action` (1 byte): `0` (ACTION_DOWN), `1` (ACTION_UP)

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
