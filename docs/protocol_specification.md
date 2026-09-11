# Pioneer AppRadio RE: Protocol Specification

## 1. Introduction

This specification details the wire-level communication protocol between an Android phone and a Pioneer AppRadio-compatible car head unit (AppRadio Mode 2 / AAM2 / WebLink) over Android Open Accessory (AOA 2.0).

The target head unit architecture is asymmetrical **Dual-SoC**:
- **Application Processor (Panasonic GerdaC ARMv7 Linux)**: Runs AOA host stack, Abalta WebLink client (`weblink_manager`), and GStreamer hardware H.264 video decoder (`omx_h264dec` $\rightarrow$ `omx_videosink`).
- **Real-Time MCU (Renesas uITRON 4.0 RTOS)**: Runs physical video routing, hardware display plane multiplexing, and the Pioneer SAC (Smartphone to Accessory Communication) authentication state machine.
- **Inter-SoC Mailbox**: Linux and uITRON communicate via `/dev/isc` (`iscdrv.ko` / `libisc.so`).

---

## 2. Multiplexed Transport Architecture: Abalta MTP (Multi-Point Transport)

All USB AOA 2.0 streaming is multiplexed into two distinct virtual channels using Abalta's **Multi-Point Transport (MTP)** protocol (originally implemented in `libMCS_MTP.so` and `com.abaltatech.mcs.mtp`):
- **Port 12346 (`0x303A`)**: WebLink Command & H.264 Video Stream (`FillRectangle`, `VideoConfig`, `SetCurrentApp`).
- **Port 12347 (`0x303B`)**: Pioneer SAC & PFormat Control Channel (`AuthBegin`, `AuthResponse`, `SmartPhoneStatus`, specs).

### 2.1 MTP Packet Framing Layout
```
[0x1E] [Size: 2B BE] [Flags: 2B BE] [SrcAddress: 7B] [DstAddress: 7B] [Payload: N bytes] [0x03]
```
- **Start Delimiter**: `0x1E` (1 byte)
- **Total Packet Size**: 2 bytes Big-Endian (includes start delimiter, size, flags, addresses, payload, and end delimiter)
- **Frame Options / Flags**: 2 bytes Big-Endian bitmask:
  - Bit 15: `isLast` (1 = last packet of sequence / close signal, 0 = active / continuing)
  - Bits 11–14: `protocol` (`0x00` = TCP)
  - Bit 10: `isCompressed` (0 = uncompressed)
  - Bit 9: `hasChecksum` (0 = no checksum)
  - Bits 0–3: `messageType` (`0x00` = Normal Data, `0x01` = Connection Request/SYN, `0x02` = Reset)
- **TCPIPAddress Layout** (7 bytes each for `srcAddress` and `dstAddress`):
  - Byte 0: Address Type (`0x01` = IPv4)
  - Bytes 1–4: IP Address (e.g. `127.0.0.1` $\rightarrow$ `0x7F 0x00 0x00 0x01`)
  - Bytes 5–6: Port Number **Big-Endian** (Port 12346 = `0x30 0x3A`, Port 12347 = `0x30 0x3B`)
- **Payload Data**: $0$ to $16,284$ bytes.
- **End Delimiter**: `0x03` (1 byte).

### 2.2 Initial Connection Handshake (SYN / ACK)
1. When the stereo opens a virtual channel, it transmits a 0-byte MTP packet (**SYN**):
   - 20 bytes total:
     `1E 00 14 00 00 01 7F 00 00 01 30 3B 01 7F 00 00 01 30 3B 03`
2. The phone immediately acknowledges by echoing back a 20-byte MTP packet (**ACK**):
   - Flags must have `isLast = false` (`0x0000`). Setting `isLast = true` causes `libMCS_MTP.so` on the stereo to trigger `SendCloseMtpMessage` and tear down the socket.

### 2.3 Payload Fragmentation Boundary
The stereo's MTP socket buffer in `libMCS_MTP.so` allocates an internal maximum packet buffer of **16,384 bytes**. With 20 bytes reserved for MTP header and delimiter bytes:
$$\text{Max Payload Per MTP Packet} = 16,384 - 20 = 16,364\text{ bytes}$$
AppRadio RE uses an operational safe threshold of **16,284 bytes**. Payloads exceeding this limit (e.g., large H.264 IDR keyframes) are fragmented across multiple MTP packets with `isLast = false` on intermediate packets and `isLast = true` on the terminal fragment.

### 2.4 Physical USB Write Chunking & ZLP Avoidance
In `UsbAccessoryLayer.writeDataInternal()`:
- USB writes are split into chunks of **5,000 bytes max**.
- **Zero-Length Packet (ZLP) Guard**: If a chunk size is an exact multiple of the USB packet size (512 bytes), it is reduced by 257 bytes:
  $$\text{if } (\text{chunkSize} \bmod 512 == 0) \implies \text{chunkSize} = \text{chunkSize} - 257$$
  This eliminates USB controller packet stalling and prevents buffer hangs on automotive host controllers.

---

## 3. PFormat Stream Framing Layer (Port 12347)

Because Port 12347 carries SAC control messages, Pioneer wraps SAC payloads in **PFormat** framing (originally in `libPFormat.so`):

### 3.1 Frame Wire Layout
```
[ 0x9F 0x02 ] [ Escaped Command ID ] [ Escaped Payload (N bytes) ] [ Escaped XOR Checksum ] [ 0x9F 0x03 ]
```

### 3.2 Delimiters & Byte-Stuffing
- **STX (Start Delimiter)**: `0x9F 0x02` (2 bytes)
- **ETX (End Delimiter)**: `0x9F 0x03` (2 bytes)
- **Byte Stuffing (Escaping)**:
  Any occurrence of byte `0x9F` inside the data (including Command ID, Payload, and Checksum) **must be escaped** by doubling it:
  $$0x9F \longrightarrow 0x9F\ 0x9F$$

### 3.3 Rolling XOR Checksum
The checksum is an 8-bit value computed by XORing the Command ID and every **unescaped** byte of the payload:
$$\text{Checksum} = \text{CommandID} \oplus \text{Payload}[0] \oplus \text{Payload}[1] \oplus \dots \oplus \text{Payload}[N-1]$$
- If the calculated checksum equals `0x9F`, it is written to the wire as `0x9F 0x9F`.

---

## 4. SAC (Smartphone to Accessory Communication) Layer (Port 12347)

All multibyte integers (Shorts, Ints) are transmitted in **Big-Endian (Network Byte Order)**.

### 4.1 Ground-Truth SAC Opcodes
| Direction | Opcode | Name | Description |
|---|---|---|---|
| **Phone $\rightarrow$ Stereo** | `0x00` | `ID_S2A_AUTH` | AuthBegin, AuthEnd, StartAppAcc, StartAccessoryInfo, EndAccessoryInfo, EndAppAcc |
| **Stereo $\rightarrow$ Phone** | `0x01` | `ID_A2S_AUTH` | AuthResponse, StartAppAccReply, StartAccessoryInfoReply, EndAccessoryInfoReply, EndAppAccReply |
| **Phone $\rightarrow$ Stereo** | `0x06` | `ID_S2A_PROC_SPEC` | Request Display Specs (`0x00`), Request Product Specs (`0x01`) |
| **Stereo $\rightarrow$ Phone** | `0x07` | `ID_A2S_PROC_SPEC` | Product Spec Info (`0x01`), Display Spec Info (`0x00`) |
| **Phone $\rightarrow$ Stereo** | `0x10` | `ID_S2A_VEDIO_OUTPUT_REPLY` | Phone confirms video output ready |
| **Stereo $\rightarrow$ Phone** | `0x11` | `ID_A2S_VEDIO_OUTPUT` | Stereo requests video stream output |
| **Phone $\rightarrow$ Stereo** | `0x60` | `ID_S2A_ACCESSORY_STATUS` | Query Parking Brake, HDMI connection (`[0x20, 0xFF]`) |
| **Stereo $\rightarrow$ Phone** | `0x61` | `ID_A2S_ACCESSORY_STATUS` | Status reply (subtype `0x20`) |
| **Phone $\rightarrow$ Stereo** | `0x70` | `ID_S2A_APPNINFO_RELY` | App name and package metadata replies |
| **Stereo $\rightarrow$ Phone** | `0x71` | `ID_A2S_APPNINFO` | Head unit requests app names / metadata |
| **Stereo $\rightarrow$ Phone** | `0x62` | `ID_A2S_REQUEST_PHONE_STATUS` | Stereo queries phone battery, call, and VR state (AppRadio Mode+) |
| **Phone $\rightarrow$ Stereo** | `0x63` | `ID_S2A_SMARTPHONE_STATUS` | Phone status reply & 5s periodic heartbeat (Opcode 99, subtype `0x20`) |

---

### 4.2 SAC Command Payloads Detail

#### 1. Authentication Handshake
- **AuthBegin (Phone $\rightarrow$ Stereo)**:
  - Opcode: `0x00` (`ID_S2A_AUTH`)
  - Subtype: `0x00` (`AUTH_BEGIN`)
  - Payload: `[ 0x00 ]` (1 byte)
  - PFormat Frame: `9F 02 00 00 00 9F 03` (7 bytes)
  - Wire size in MTP: 27 bytes total.
  - **No cryptography, certificate, challenge, or nonce is used.**
  - **Retry Policy**: `AUTH_INTERVAL = 3000ms`, max 3 attempts (`attempt <= 3`).

- **AuthResponse (Stereo $\rightarrow$ Phone)**:
  - Opcode: `0x01` (`ID_A2S_AUTH`)
  - Subtype: `0x00` (`AUTH_RESPONSE`)
  - Payload (5 bytes):
    - Byte 0: `accessoryType`:
      - `0x02` = AppRadio
      - `0x03` = MediaDEH
      - `0x08` = Linkwith AAM2 (Pioneer AVH-Z series)
    - Bytes 1–2: `mMachineMajorVer` (Short Big-Endian, e.g. `0x0003` = v3)
    - Bytes 3–4: `mMachineMinorVer` (Short Big-Endian, e.g. `0x0000` = v0)
  - PFormat Frame: `9F 02 01 00 08 00 03 00 00 0A 9F 03` (12 bytes)
  - Wire size in MTP: 32 bytes total.

- **AuthEnd (Phone $\rightarrow$ Stereo)**:
  - Opcode: `0x00` (`ID_S2A_AUTH`)
  - Subtype: `0x01` (`AUTH_END`)
  - Payload (5 bytes):
    - Byte 0: `status` (`0x01` = AUTH_SUCCESS)
    - Bytes 1–2: `majorVersion` (Short Big-Endian: $\min(3, \text{mMachineMajorVer})$)
    - Bytes 3–4: `minorVersion` (Short Big-Endian: `0x0001`)
  - PFormat Frame: `9F 02 00 01 01 00 03 00 01 02 9F 03` (12 bytes)
  - Wire size in MTP: 32 bytes total.

#### 2. App & Accessory Session Lifecycle
- **StartAppAcc (TX)**: Opcode `0x00`, Payload: `[ 0x10 ]`
- **StartAppAccReply (RX)**: Opcode `0x01`, Payload: `[ 0x10, status=1 ]`
- **StartAccessoryInfo (TX)**: Opcode `0x00`, Payload: `[ 0x14 ]`
- **StartAccessoryInfoReply (RX)**: Opcode `0x01`, Payload: `[ 0x14, status=1 ]`
- **EndAccessoryInfo (TX)**: Opcode `0x00`, Payload: `[ 0x15 ]`
- **EndAccessoryInfoReply (RX)**: Opcode `0x01`, Payload: `[ 0x15, status=1 ]`
- **EndAppAcc (TX)**: Opcode `0x00`, Payload: `[ 0x11 ]`
- **EndAppAccReply (RX)**: Opcode `0x01`, Payload: `[ 0x11, status=1 ]`

#### 3. Specifications Exchange
- **RequestProductSpec (TX)**: Opcode `0x06`, Payload: `[ 0x01 ]`
- **ProductSpecReply (RX)**: Opcode `0x07`, Subtype `0x01`:
  - Bytes 1–2: Model ID (Short Big-Endian) e.g. `0x0112`
  - Byte 3: Multi-touch pointer count (e.g. 2 points)
  - Byte 4: GPS Available (`1` = Yes, `0` = No)
  - Byte 5: Remote Control Supported (`1` = Yes)
  - Byte 6: CAN Bus Connected (`1` = Yes)
- **RequestDisplaySpec (TX)**: Opcode `0x06`, Payload: `[ 0x00 ]`
- **DisplaySpecReply (RX)**: Opcode `0x07`, Subtype `0x00` (13 bytes total payload):
  - Byte 0: Subtype `0x00` (`DISPLAY_INFO`)
  - Bytes 1–2: **Pixel Width** (Short Big-Endian: `800` $\rightarrow$ `0x03 0x20`)
  - Bytes 3–4: **Pixel Height** (Short Big-Endian: `480` $\rightarrow$ `0x01 0xE0`)
  - Bytes 5–6: **Physical Width** in tenths of a millimeter (Short Big-Endian: `1550` $\rightarrow$ `0x06 0x0E` = 155.0 mm for standard 7" double-DIN screen)
  - Bytes 7–8: **Physical Height** in tenths of a millimeter (Short Big-Endian: `870` $\rightarrow$ `0x03 0x66` = 87.0 mm)
  - Bytes 9–12: Pad / Reserved (`0xFF 0xFF 0xFF 0xFF`)
  - *Critical Note*: Bytes 1–4 are pixel resolution ($800 \times 480$). Mistaking bytes 5–8 ($1550 \times 870$) for pixel dimensions will cause the phone's video encoder to emit an unsupported resolution that chokes the head unit's hardware H.264 decoder and yields a black screen.

#### 4. Accessory Status (`Opcode 0x60` / `0x61`)
- **RequestAccessoryStatus (TX)**: Opcode `0x60`, Payload: `[ 0x20, 0xFF ]`
- **AccessoryStatusReply (RX)**: Opcode `0x61`, Subtype `0x20`, Bitmask:
  - Bit 0 (`0x01`): Parking Brake (`1` = Engaged / Safe, `0` = Released)
  - Bit 1 (`0x02`): HDMI Connection Active
  - Bit 2 (`0x04`): Voice Recognition Active

#### 5. Smartphone Status & 5-Second Inactivity Watchdog Heartbeat
- **Stereo Inactivity Watchdog**: `mAOADataTimeOut = 15,000ms`. If no data arrives on Port 12347 within 15 seconds, the stereo tears down the connection.
- **Heartbeat Packet**: `SmartPhoneStatus` sent every **5,000ms**:
  - Opcode: `99` (`0x63`, `ID_S2A_SMARTPHONE_STATUS`)
  - Subtype: `32` (`0x20`)
  - Payload (7 bytes): `[ 0x20, flags, 0x00, 0x00, token(2B), 0x00 ]`

---

## 5. WebLink Protocol Layer (Port 12346)

All WebLink messages utilize an 8-byte Little-Endian header.

### 5.1 Header Format (8 Bytes Little-Endian)
```
[ Byte 0 ]   'W' (0x57)
[ Byte 1 ]   'L' (0x4C)
[ Bytes 2-3] Command ID (Short, Little-Endian)
[ Bytes 4-7] Payload Size (Int, Little-Endian)
[ Bytes 8..] Payload Data
```

### 5.2 Verified Ground-Truth WebLink Command IDs
| Command ID | Hex | Name | Payload Specification |
|---|---|---|---|
| **1** | `0x0001` | `FillRectangleCommand` | Width (4B LE), Height (4B LE), FrameEncoding (`0x02` H.264), AppID (`0x00`), raw Annex B NAL units (`00 00 00 01`) |
| **2** | `0x0002` | `MouseCommand` | Mouse coordinates and button states |
| **9** | `0x0009` | `TouchCommand` | Action (4B), PointerCount (4B), Touch points array (20B per point) |
| **10** | `0x000A` | `KeyboardCommand` | Keycode (4B), Action (4B), Unicode character (4B) |
| **11** | `0x000B` | `BrowserCommand` | Action (4B): `0` = Back Key, `1` = Home Key |
| **32** | `0x0020` | `VideoConfigCommand` | SourceWidth (4B), SourceHeight (4B), ClientWidth (4B), ClientHeight (4B), FrameEncoding (`0x02`), EncoderParams string |
| **66** | `0x0042` | `SetCurrentAppCommand` | AppID length (4B LE), AppID string, AppParams length (4B LE), AppParams string |
| **73** | `0x0049` | `SyncSessionTimeCommand` | Client monotonic uptime (8B Int64 LE), Server monotonic uptime (8B Int64 LE) |
| **75** | `0x004B` | `ClientFeaturesCommand` | String: `"xdpi=240\|ydpi=240"` |

---

### 5.3 Video Negotiation & Transmission Wire Details

#### 1. SetCurrentApp (Command ID 66 / 0x0042)
Immediately after Port 12346 connection ACK, the phone transmits:
- AppID length: `16` (`0x10 0x00 0x00 0x00`)
- AppID string: `"aam2serverapp://"`
- AppParams length: `0` (`0x00 0x00 0x00 0x00`)
- Wire bytes (32 bytes total):
  `57 4C 42 00 18 00 00 00 10 00 00 00 61 61 6D 32 73 65 72 76 65 72 61 70 70 3A 2F 2F 00 00 00 00`

#### 2. Clock Synchronization (Command ID 73 / 0x0049)
The stereo initiates with `clientTime` (8B Int64 LE) and `serverTime = 0`.
The phone echoes with:
- `clientTime`: Unchanged client timestamp from stereo.
- `serverTime`: Phone monotonic uptime via `SystemClock.uptimeMillis()` (8B Int64 LE).
- *Critical Note*: Wall-clock epoch time (`System.currentTimeMillis()`) must NOT be used because clock skew causes WebLink frame drop logic to trigger.

#### 3. VideoConfig Negotiation (Command ID 32 / 0x0020)
- **Stereo Offer**: Width `800`, Height `480`, Encoding `2` (H.264), `EncoderParams`:
  `"2:maxKeyFrameInterval=60,bitrate=8388608,fps=30"`
- **Phone Confirmation**: Echoes parameters with agreed bitrate:
  `"maxKeyFrameInterval=60,bitrate=2097152"`

#### 4. Video Streaming: FillRectangle (Command ID 1 / 0x0001)
- Header (16 bytes):
  - Width: `800` (`0x20 0x03 0x00 0x00`)
  - Height: `480` (`0xE0 0x01 0x00 0x00`)
  - Encoding: `2` (`0x02 0x00 0x00 0x00` = H.264)
  - AppID: `0` (`0x00 0x00 0x00 0x00`)
- Payload: Raw H.264 Annex B bitstream:
  - Keyframe: `00 00 00 01 67` (SPS) + `00 00 00 01 68` (PPS) + `00 00 00 01 65` (IDR slice).
  - Delta Frame: `00 00 00 01 41` or `61` (Non-IDR P-slice).

---

## 6. The GStreamer Preroll Interlock & Deadlock Root Cause

### 6.1 The Dual-SoC Dependency
1. On the stereo, Linux CPU 0 constructs the video decoding pipeline:
   `appsrc (weblinksrc) -> queue -> h264parse -> omx_h264dec -> omx_videosink`
2. `omx_videosink` derives from `GstBaseSink`, which sets `gst_base_sink_needs_preroll = TRUE`.
3. GStreamer blocks in `GST_STATE_PAUSED` until the first H.264 buffer arrives at `appsrc`.
4. While in `GST_STATE_PAUSED`, Linux has not signaled `wlcReqDecode(1)` across `/dev/isc` to uITRON CPU 1.
5. In AAM2 mode (`accessoryType = 8`), uITRON's authentication state machine **withholds `AuthResponse`** until `wlcReqDecode(1)` is active.
6. **The Deadlock**: If the phone sends only 1 video frame and pauses, or halts video transmission while waiting for `AuthResponse`, GStreamer stalls, `wlcReqDecode(1)` drops, and uITRON never sends `AuthResponse`.
7. **The Solution**: The phone must run continuous, non-blocking 30 FPS video streaming over Port 12346. Satisfying GStreamer preroll causes uITRON to release `AuthResponse` within 11 milliseconds.

---

## 7. Empirical Hardware Findings: Cold Boot vs. Hotplug Reconnection

Physical vehicle testing on Pioneer AVH head units (AVH-Z series) revealed a fundamental disparity between cold booting and hotplugging:

### 7.1 The Cold Boot Advantage (100% Auth Success)
- When the car and head unit are completely powered OFF (ignition OFF / stereo dark):
  1. Phone is plugged into the USB port.
  2. Ignition is switched ON, initiating a cold boot of Panasonic Gerda Linux and Renesas uITRON.
  3. All IPC mailboxes, USB drivers, and MTP daemons start with zero accumulated state.
  4. Both Port 12346 and Port 12347 sockets are clean, listening, and receive their ACKs immediately.
  5. Authentication succeeds 100% of the time on the very first `AuthBegin` attempt.

### 7.2 The Hotplug / "Stereo Already ON" Disconnect Behavior
- When the car/stereo is ALREADY running, and the USB cable is unplugged and replugged:
  1. **Half-Open MTP Socket State**: Because abrupt cable removal prevents Android from sending a graceful teardown packet (`isLast = true`), the head unit's MTP daemon (`u2nl` / `weblink_manager`) leaves the virtual sockets on Port 12346 and Port 12347 in a half-open state until a 15–30 second inactivity watchdog triggers.
  2. **Head Unit UI State Transition**: On cable disconnect, the Pioneer head unit automatically exits AppRadio mode and reverts to the Tuner, Home menu, or last AV source. The AAM2 background service is placed into a dormant state, ignoring incoming `AuthBegin` control frames.
  3. **Verification with Official Pioneer AppRadio APK**: Testing the official, original Pioneer AppRadio Mode application reveals the **identical failure mode**: hotplugging while the car is on fails authentication repeatedly until either the socket watchdog expires or repeated replug attempts reset the interface.

### 7.3 Implemented Mitigations & User Workflow
- **Replay Buffering (`replay = 16`)**: In `UsbDataSourceImpl`, initial USB chunks are buffered so early Port 12346/12347 SYNs sent before coroutine collection starts are never dropped.
- **Proactive Dual-Port Connection ACKs**: Immediately upon USB connection, the app proactively transmits connection ACKs to both Port 12346 and Port 12347.
- **Persistent Channel Pings**: Auth retry count is extended to 6 attempts, with Port 12347 connection pings sent prior to each `AuthBegin` retry.
- **Head Unit Touchscreen Activation**: If hotplugged while the head unit is running, tapping the **"Apps"** or **"AppRadio"** source icon on the Pioneer touchscreen awakens the head unit's AAM2 service and immediately unblocks authentication.
