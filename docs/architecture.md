# Pioneer AppRadio RE: System Architecture

## 1. Overview & Vision

**Pioneer AppRadio RE** is a modern, open-source Android implementation of Pioneer's proprietary car stereo accessory protocol (AAM2 / AppRadio Mode 2 / WebLink). 

The primary goals of this project are:
1. **Reverse Engineer & Modernize**: Completely replace legacy 32-bit closed-source native libraries (`libPFormat.so`, `libWebLinkServerLib.so`, `libMCS_MTP.so`) with clean, fast, memory-safe pure Kotlin implementations compatible with modern 64-bit Android 14/15 devices.
2. **Real-time Protocol Inspection**: Provide an in-app live sniffer that displays raw and parsed USB communication between phone and car stereo across both MTP virtual ports (12346 Video and 12347 Control), enabling on-hardware debugging and log export via Android ShareSheet.
3. **Display Mirroring via Hardware MediaCodec**: Stream a smooth 30 FPS hardware-encoded H.264 video feed (`800x480`) to Pioneer head units (e.g. SPH-DA120, AVH-Z2090BT, Carrozzeria) with touch coordinate feedback and steering wheel remote control integration.

---

## 2. High-Level Architecture Diagram

```mermaid
graph TD
    subgraph Pioneer Stereo Hardware
        HU["Pioneer Head Unit<br/>(Dual-SoC: Linux CPU 0 + uITRON CPU 1)"]
    end

    subgraph Android OS Layer
        AOA["Android Open Accessory (AOA 2.0)<br/>UsbManager & ParcelFileDescriptor"]
        MC["Hardware MediaCodec<br/>(video/avc Surface Encoder)"]
    end

    subgraph Core Transport & Multiplexer
        UAM["UsbAccessoryManager & UsbDataSource<br/>(5000B Chunking + 512B ZLP Avoidance)"]
        MTP["MTPCodec<br/>(0x1E ... 0x03 Multiplexer)"]
    end

    subgraph Virtual Channels
        P12346["Port 12346: Video Channel<br/>(WebLink Commands & H.264 Video)"]
        P12347["Port 12347: Control Channel<br/>(PFormat & SAC Commands)"]
    end

    subgraph Core Protocol Stack
        WL["WebLinkCodec<br/>('WL' 8B Header, Little-Endian)"]
        PF["PFormatCodec<br/>(STX 0x9F 0x02 / ETX 0x9F 0x03 Framing)"]
        SAC["SACCodec<br/>(Big-Endian Pioneer Command Engine)"]
    end

    subgraph Hardware Video Pipeline
        TPR["TestPatternRenderer<br/>(EGL 1.4 + GLES20 Surface Canvas)"]
        H264["H264VideoEncoder<br/>(Surface -> SPS/PPS/IDR NALUs)"]
        VSM["VideoStreamingManager<br/>(Non-blocking Conflated Channel)"]
    end

    subgraph Orchestration & Diagnostics
        HSM["HandshakeStateMachine<br/>(Dual-Port Handshake, 5s Heartbeat)"]
        LR["LogRepository<br/>(Circular Buffer & FileProvider Export)"]
    end

    subgraph MVI Presentation Layer
        VM["LiveLogViewModel<br/>(StateFlow, Channel Events, Filters)"]
        UI["LiveLogScreen<br/>(Jetpack Compose M3 UI)"]
        Share["Android ShareSheet<br/>(.txt Export via FileProvider)"]
    end

    HU <==> |USB Cable (AOA 2.0)| AOA
    AOA <==> |Raw Byte Stream| UAM
    UAM <==> MTP
    MTP <==> P12346
    MTP <==> P12347
    P12346 <==> WL
    P12347 <==> PF
    PF <==> SAC
    SAC <==> HSM
    WL <==> HSM

    TPR --> |EGL Surface Frames| MC
    MC --> H264
    H264 --> VSM
    VSM --> |FillRectangle Packets| P12346

    UAM -.-> |Raw Packet Logs| LR
    MTP -.-> |MTP Packet Logs| LR
    SAC -.-> |SAC Packet Logs| LR
    WL -.-> |WebLink Logs| LR
    HSM -.-> |Status & Specs| VM
    LR -.-> |StateFlow Logs| VM
    VM <==> UI
    UI -.-> |Export Trigger| Share
```

---

## 3. Layer Breakdown

### 3.1 USB & Transport Layer (`core.usb`)
- **`UsbDataSource`**:
  - Implements 5,000-byte chunking matching Pioneer's `UsbAccessoryLayer.writeDataInternal()`.
  - Implements `% 512 == 0 -> -257` ZLP stall prevention.
  - Implements lock acquisition timeout protection (`withTimeoutOrNull(2500L)`) around `writeMutex.withLock` to guarantee that streaming video bursts cannot starve control packets (`AuthBegin`, `AuthEnd`, heartbeats).
- **`MTPCodec`**:
  - Multiplexes Port 12346 (Video/WebLink) and Port 12347 (Control/SAC).
  - Handles 20-byte SYN/ACK connection packets.
  - Enforces safe 16,284-byte payload fragmentation to prevent MTP buffer overruns.

### 3.2 Handshake Orchestration (`core.usb.HandshakeStateMachine`)
- **WebLink Channel Synchronization (Port 12346)**:
  - Responds to `SetCurrentApp` with `"aam2serverapp://"`.
  - Echoes `SyncSessionTime` using monotonic system uptime (`SystemClock.uptimeMillis()`).
  - Confirms `VideoConfig` (`800x480 @ 30fps`).
- **SAC Authentication Sequence (Port 12347)**:
  - 1000ms delay after Port 12347 ACK before transmitting `AuthBegin`.
  - Maximum 3 retries (`attempt <= 3`) with 3000ms intervals matching `AccessoryAuthor`.
  - Validates `AuthResponse` accessory types (`0x02`, `0x03`, `0x08`).
  - Sends `AuthEnd` with `majorVersion = min(3, mMachineMajorVer)` and `minorVersion = 1`.
  - Dispatches full post-auth exchange (`StartAppAcc` $\rightarrow$ `StartAccessoryInfo` $\rightarrow$ `RequestSpec` $\rightarrow$ `DisplaySpec` $\rightarrow$ `AccessoryStatus` $\rightarrow$ `EndAccessoryInfo` $\rightarrow$ `EndAppAcc`).
  - Sends periodic `SmartPhoneStatus` (Opcode 99) heartbeat every 5000ms while authenticated to satisfy the stereo's 15-second inactivity watchdog.

### 3.3 Hardware Video Pipeline (`core.video`)
- **`TestPatternRenderer`**:
  - EGL 1.4 hardware renderer drawing a 30 FPS automotive test canvas with millisecond clock, frame counter, corner crosshairs, and animated orb onto the `MediaCodec` input surface.
  - Bulletproof texture upload with `GL_UNPACK_ALIGNMENT = 4` and automatic fallback from `texSubImage2D` to `texImage2D`.
  - Direct error callbacks to `logRepository`.
- **`H264VideoEncoder`**:
  - Direct hardware encoding via `MediaCodec` (`video/avc`) at `800x480 @ 30fps`.
  - CSD parameter extraction (`00 00 00 01 67` SPS, `00 00 00 01 68` PPS) prepended to keyframes.
  - Isolated callback dispatch protecting the drain loop.
- **`VideoStreamingManager`**:
  - Non-blocking `Channel<EncodedFrame>(capacity = Channel.CONFLATED)` pipeline and dedicated sender coroutine.
  - Wraps frames into WebLink `FillRectangleCommand` (ID 1).
  - Emits 5-second periodic video heartbeat logging (`"Video Stream Heartbeat: X fps | Y total frames | Z KB"`).

### 3.4 Presentation Layer (`feature.livelog`)
- **Architecture**: Strict MVI (Model-View-Intent).
- **State**: Single immutable data class (`LiveLogState`) driven by `StateFlow`.
- **Action**: Sealed interface representing all user intents (`LiveLogAction`).
- **Event**: Sealed interface representing one-time side effects (`LiveLogEvent`), observed lifecycle-safely with `ObserveAsEvents`.
- **Root/Screen Split**: `LiveLogRoot` handles Koin ViewModel injection and event listening, while `LiveLogScreen` is a pure composable receiving only `State` and `onAction`.
