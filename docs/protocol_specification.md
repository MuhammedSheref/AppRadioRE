# Pioneer AppRadio RE: Protocol Specification

## 1. Introduction

This specification details the wire-level communication protocol between an Android phone and a Pioneer AppRadio-compatible head unit (AAM2 / AppRadio Mode 2) over Android Open Accessory (AOA).

---

## 2. PFormat Stream Framing Layer

Because USB streams are continuous byte streams without packet boundary markers, Pioneer uses **PFormat** framing (originally implemented in `libPFormat.so`).

### 2.1 Frame Wire Layout
```
[ 0x9F 0x02 ] [ Escaped Command ID ] [ Escaped Payload (N bytes) ] [ Escaped XOR Checksum ] [ 0x9F 0x03 ]
```

### 2.2 Delimiters
- **STX (Start Delimiter)**: `0x9F 0x02` (2 bytes)
- **ETX (End Delimiter)**: `0x9F 0x03` (2 bytes)

### 2.3 Byte Stuffing (Escaping)
Any occurrence of the escape byte `0x9F` inside the data (including the Command ID, Payload bytes, and XOR Checksum) **must be escaped** by doubling it:
$$\text{Data byte } 0x9F \longrightarrow 0x9F\ 0x9F$$

### 2.4 Rolling XOR Checksum
The checksum is an 8-bit value computed by XORing the Command ID and every unescaped byte of the payload:
$$\text{Checksum} = \text{CommandID} \oplus \text{Payload}[0] \oplus \text{Payload}[1] \oplus \dots \oplus \text{Payload}[N-1]$$
- If the calculated checksum equals `0x9F`, it is written to the wire as `0x9F 0x9F`.

---

## 3. SAC (Smartphone to Accessory Communication) Layer

The SAC layer defines application-level control messages. All multibyte integers (Shorts, Ints) are transmitted in **Big-Endian (Network Byte Order)**.

### 3.1 Primary SAC Opcodes
| Direction | Opcode | Name | Description |
|---|---|---|---|
| **Phone $\rightarrow$ Stereo** | `0x00` | `OP_S2A_AUTH` | Authentication, Start/End App Acc, Start/End Acc Info |
| **Stereo $\rightarrow$ Phone** | `0x00` | `OP_A2S_AUTH` | Auth Response, Reply confirmations |
| **Phone $\rightarrow$ Stereo** | `0x01` | `OP_S2A_PROC_SPEC` | Request Display Specs, Request Product Specs |
| **Stereo $\rightarrow$ Phone** | `0x01` | `OP_A2S_PROC_SPEC` | Product Spec Info, Display Spec Info |
| **Phone $\rightarrow$ Stereo** | `0x20` | `OP_S2A_ACCESSORY_STATUS` | Query Parking Brake, HDMI connection, VR status |
| **Stereo $\rightarrow$ Phone** | `0x02` | `OP_A2S_PACKAGEINFO` | Status flags reply (subtype 32) |
| **Stereo $\rightarrow$ Phone** | `0x06` | `OP_A2S_VEDIO_OUTPUT` | Head unit requests video stream startup |
| **Phone $\rightarrow$ Stereo** | `0x06` | `OP_S2A_VEDIO_OUTPUT_REPLY` | Phone replies with `[0x06, 0x01]` to acknowledge |
| **Phone $\rightarrow$ Stereo** | `0x02` | `OP_S2A_APPNINFO_RELY` | App name and package metadata replies |
| **Stereo $\rightarrow$ Phone** | `0x04` | `OP_A2S_APPIMAGE_TRANSFER_REQUEST` | Head unit requests app icons for menu display |
| **Phone $\rightarrow$ Stereo** | `0x04` | `OP_S2A_APPIMAGE_TRANSFER` | Phone streams icon bitmap chunks (512B max) |
| **Stereo $\rightarrow$ Phone** | `0x05` | `OP_A2S_APPS` | User tapped app icon on head unit screen |
| **Stereo $\rightarrow$ Phone** | `0x62` | `OP_A2S_REQUEST_PHONE_STATUS` | Stereo queries phone battery, call, and VR state (AppRadio Mode+) |
| **Phone $\rightarrow$ Stereo** | `0x63` | `OP_S2A_SMARTPHONE_STATUS` | Phone status reply (subtype `0x20` + status flags) |

---

### 3.2 Command Payloads Detail

#### 1. Authentication (`Opcode 0x00`)
- **AuthBegin (TX)**:
  `Payload: [ 0x00 ]`
- **AuthResponse (RX)**:
  `Payload: [ 0x00, Result (1B), MajorVersion (2B Big-Endian), MinorVersion (2B Big-Endian) ]`
  - `Result`: `0x00` = Success
  - Standard Version: `3.1` (`0x00 0x03, 0x00 0x01`)
- **AuthEnd (TX)**:
  `Payload: [ 0x01, SuccessFlag (1B), MajorVersion (2B), MinorVersion (2B) ]`
  - Example: `[ 0x01, 0x01, 0x00, 0x03, 0x00, 0x01 ]`

#### 2. App & Accessory Info Session
- **StartAppAcc (TX)**: `[ 0x10 ]` (16 decimal)
- **StartAppAccReply (RX)**: `[ 0x10, Status (1B) ]`
- **StartAccessoryInfo (TX)**: `[ 0x14 ]` (20 decimal)
- **StartAccessoryInfoReply (RX)**: `[ 0x14, Status (1B) ]`
- **EndAccessoryInfo (TX)**: `[ 0x15 ]` (21 decimal)
- **EndAccessoryInfoReply (RX)**: `[ 0x15, Status (1B) ]`

#### 3. Specifications (`Opcode 0x01`)
- **RequestProductSpec (TX)**: `[ 0x01 ]`
- **ProductSpecReply (RX)**:
  ```
  Byte 0: 0x01 (Subtype)
  Byte 1-2: Model ID (Short, Big-Endian) e.g. 0x0112 (SPH-DA120)
  Byte 3: Pointer Count (1B) -> Multi-touch capability (e.g. 2 points)
  Byte 4: GPS Available (1B) -> 1 = Yes, 0 = No
  Byte 5: Remote Control (1B) -> 1 = Yes
  Byte 6: CAN Bus Connected (1B) -> 1 = Yes
  Byte 7: Calibration Flags (1B)
  ```
- **RequestDisplaySpec (TX)**: `[ 0x00 ]`
- **DisplaySpecReply (RX)**:
  ```
  Byte 0: 0x00 (Subtype)
  Byte 1-4: Padding / Reserved (4B)
  Byte 5-6: Width (Short, Big-Endian) e.g. 800 (0x03 0x20)
  Byte 7-8: Height (Short, Big-Endian) e.g. 480 (0x01 0xE0)
  ```

#### 4. Accessory Status (`Opcode 0x20` / `0x02`)
- **RequestAccessoryStatus (TX)**: `[ 0x20, 0xFF ]`
- **AccessoryStatusReply (RX)**:
  ```
  Byte 0: 0x20 (Subtype: 32)
  Byte 1: Bitmask Flags:
          Bit 0 (0x01): Parking Brake Status (1 = ON, 0 = OFF)
          Bit 1 (0x02): HDMI Connection Status (1 = Connected)
          Bit 2 (0x04): VR (Voice Recognition) Active
  ```

#### 5. Video Output Ready Handshake
- **VideoOutputRequest (RX)**: Opcode `0x06`, Empty Payload
- **VideoOutputReply (TX)**: Opcode `0x06`, Payload: `[ 0x06, 0x01 ]`

#### 6. Smartphone Status Handshake (AppRadio Mode+ / Opcode 0x62 & 0x63)
- **RequestPhoneStatus (RX)**: Opcode `0x62`, Payload: `[ 0x20 ]` (Subtype 32)
- **SmartPhoneStatus (TX)**: Opcode `0x63`, Payload:
  ```
  Byte 0: 0x20 (Subtype: 32)
  Byte 1: Status byte (e.g. 0x00 = Normal, battery OK, no active call)
  ```
  *Note*: If the phone fails to respond to Opcode `0x62` with Opcode `0x63`, the head unit firmware (such as on the Pioneer AVH-Z2090BT) will remain frozen on its "Loading..." screen.

---

## 3.3 MTP Transport & Control Channel Synchronization
- **Port 12347 (Control Channel)**: The head unit initiates connection by transmitting an MTP SYN packet (empty payload) targeting Port 12347.
- **Connection ACK (`isLast = false`)**: The phone must acknowledge this SYN with a 0-byte MTP packet with `isLast = false`. If `isLast` is set to `true`, the stereo interprets it as `SendCloseMtpMessage` and tears down the socket.
- **1000ms Delay Before AuthBegin**: Following Pioneer's `ExtBaseService.handleConnecting()`, the phone must wait 1000ms after acknowledging Port 12347 before transmitting `AuthBegin` to allow the stereo's internal daemon to enter its listener loop.

---

## 4. WebLink Protocol Layer

WebLink commands govern video transmission and interactive touch coordinate feedback.

### 4.1 Header Format (8 Bytes Little-Endian)
```
[ Byte 0 ] 'W' (0x57)
[ Byte 1 ] 'L' (0x4C)
[ Byte 2-3 ] Command ID (Short, Little-Endian)
[ Byte 4-7 ] Payload Length (Int, Little-Endian)
[ Byte 8.. ] Payload Data
```

### 4.2 WebLink Command IDs
| Command ID | Hex | Name | Payload |
|---|---|---|---|
| 0 | `0x0000` | `ID_SYNC_SESSION_TIME` | Client Time (8B Long LE), Server Time (8B Long LE) |
| 1 | `0x0001` | `ID_SET_FPS` | FPS (4B Int LE) e.g. 30 |
| 2 | `0x0002` | `ID_VIDEO_CONFIG` | Width (4B), Height (4B), Encoding Type (4B) |
| 4 | `0x0004` | `ID_SET_CURRENT_APP` | UTF-8 URI String (e.g. `weblink://com.ameer.appradiore`) |
| 5 | `0x0005` | `ID_FILL_RECTANGLE` | Width (4B), Height (4B), Encoding (4B), AppID (4B), Frame Bytes |
| 9 | `0x0009` | `ID_TOUCH_COMMAND` | Event Type (4B), Pointer Count (4B), Touch Points (20B each) |
| 11 | `0x000B` | `ID_BROWSER_COMMAND` | Action (4B) -> 0 = Back Key |

### 4.3 Touch Coordinates Format (`ID_TOUCH_COMMAND`)
Each touch point entry is 20 bytes Little-Endian:
```
Int pointerId (4B)
Int x (4B) -> Stereo screen X coordinate
Int y (4B) -> Stereo screen Y coordinate
Int state (4B) -> 1=Pressed, 2=Moved, 4=Stationary, 8=Released
Float pressure (4B) -> 0.0f - 1.0f
```
