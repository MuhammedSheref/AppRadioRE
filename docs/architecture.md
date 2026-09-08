# Pioneer AppRadio RE: System Architecture

## 1. Overview & Vision

**Pioneer AppRadio RE** is a modern, open-source Android implementation of Pioneer's proprietary car stereo accessory protocol (AAM2 / AppRadio Mode 2). 

The primary goals of this project are:
1. **Reverse Engineer & Modernize**: Completely replace legacy 32-bit closed-source native libraries (`libPFormat.so` and `libWebLinkServerLib.so`) with clean, fast, memory-safe pure Kotlin implementations compatible with modern 64-bit Android 14/15 devices.
2. **Real-time Protocol Inspection**: Provide an in-app live sniffer that displays raw and parsed USB communication between phone and car stereo, enabling on-hardware debugging and log export via Android ShareSheet.
3. **Display Mirroring via Android VirtualDisplay**: Stream the phone screen or virtual secondary display to Pioneer head units (e.g. SPH-DA120, SPH-DA210, AVH series) with full two-way touch input and steering wheel remote control integration.

---

## 2. High-Level Architecture Diagram

```mermaid
graph TD
    subgraph Pioneer Stereo Hardware
        HU["Pioneer Head Unit<br/>(SPH-DA120 / AVH)"]
    end

    subgraph Android OS Layer
        AOA["Android Open Accessory (AOA)<br/>UsbManager & ParcelFileDescriptor"]
    end

    subgraph Core Protocol Stack
        PF["PFormatCodec<br/>(STX 0x9F 0x02 / ETX 0x9F 0x03 Framing)"]
        SAC["SACCodec<br/>(Big-Endian Pioneer Command Engine)"]
        WL["WebLinkCodec<br/>(Little-Endian 'WL' Header Engine)"]
    end

    subgraph Core Hardware & Orchestration
        UAM["UsbAccessoryManager<br/>(AOA Discovery & I/O Coroutines)"]
        HSM["HandshakeStateMachine<br/>(6-Step RunableRetry Handshake)"]
        LR["LogRepository<br/>(Circular Buffer & FileProvider Export)"]
    end

    subgraph MVI Presentation & UI Layer
        VM["LiveLogViewModel<br/>(StateFlow, Channel Events, Filters)"]
        UI["LiveLogScreen<br/>(Jetpack Compose M3 UI)"]
        Share["Android ShareSheet<br/>(.txt Export via FileProvider)"]
    end

    HU <==> |USB Cable (AOA Mode)| AOA
    AOA <==> |Raw Byte Stream| UAM
    UAM <==> PF
    PF <==> SAC
    UAM <==> WL
    SAC <==> HSM
    UAM -.-> |Packet Logs| LR
    SAC -.-> |Packet Logs| LR
    WL -.-> |Packet Logs| LR
    HSM -.-> |Status & Specs| VM
    LR -.-> |StateFlow Logs| VM
    VM <==> UI
    UI -.-> |Export Trigger| Share
```

---

## 3. Layer Breakdown

### 3.1 USB & Transport Layer (`core.usb`)
- **`UsbAccessoryManager`**:
  - Manages Android's `UsbManager` and `UsbAccessory` APIs.
  - Dynamically registers for `ACTION_USB_ACCESSORY_ATTACHED` and `ACTION_USB_ACCESSORY_DETACHED`.
  - Handles USB accessory permission requests (`PendingIntent`) with `RECEIVER_NOT_EXPORTED` on Android 14+.
  - Manages the non-blocking background I/O coroutines (`Dispatchers.IO`) reading from `FileInputStream` and writing to `FileOutputStream`.
  - Exposes reactive `connectionState: StateFlow<UsbConnectionState>` and `incomingBytes: SharedFlow<ByteArray>`.

- **`HandshakeStateMachine`**:
  - Encapsulates the complete 6-step state machine reverse-engineered from Pioneer's legacy `RunableRetry.java`.
  - Progresses through:
    1. Auth Begin $\rightarrow$ Auth Response $\rightarrow$ Auth End.
    2. Kind 4 (Start App Accessory).
    3. Kind 5 (Start Accessory Info).
    4. Kind 2 (Product Specs: Model ID, Multi-touch pointer count, GPS, Remote Control).
    5. Kind 1 (Display Specs: Resolution e.g. 800x480).
    6. Kind 3 (Accessory Status: Parking Brake, HDMI connection).
    7. Kind 6 (End Accessory Info).
    8. Standby: Video Output Request/Reply $\rightarrow$ 5s Status Query Heartbeats.
  - Features an offline **Simulation Engine** allowing developers to simulate the entire handshake and touch events without car hardware.

### 3.2 Protocol Codec Layer (`core.protocol`)
Pure Kotlin implementation adhering to strict endianness and framing standards:
- **`PFormatCodec`**: Demarcates stream boundaries using `0x9F 0x02` (STX) and `0x9F 0x03` (ETX), handles byte-stuffing (`0x9F` escaped as `0x9F 0x9F`), and computes rolling XOR checksums.
- **`SACCodec` / `SACCommand`**: Formats all Pioneer Smartphone-to-Accessory (S2A) and Accessory-to-Smartphone (A2S) commands using **Big-Endian (Network Byte Order)**.
- **`WebLinkCodec` / `WebLinkCommand`**: Formats and parses WebLink video, session time sync, and touch coordinates using **Little-Endian** byte order prefixed by 8-byte `'WL'` (`0x57 0x4C`) headers.

### 3.3 Logging Subsystem (`core.logging`)
- **`LogEntry`**: Immutable domain model capturing timestamp, direction (`◄ RX`, `► TX`, `● SYS`), protocol tag, summary, decoded fields, and raw hex string.
- **`LogRepository`**: Thread-safe circular buffer (2,000 capacity) providing real-time `StateFlow<List<LogEntry>>`.
- **`FileProvider` Export**: Formats logs into an ASCII report and provides a secure `content://` URI for instant sharing via Android's ShareSheet.

### 3.4 Presentation Layer (`feature.livelog`)
- **Architecture**: Strict MVI (Model-View-Intent).
- **State**: Single immutable data class (`LiveLogState`) driven by `StateFlow`.
- **Action**: Sealed interface representing all user intents (`LiveLogAction`).
- **Event**: Sealed interface representing one-time side effects (`LiveLogEvent`), observed lifecycle-safely with `ObserveAsEvents`.
- **Root/Screen Split**: `LiveLogRoot` handles Koin ViewModel injection and event listening, while `LiveLogScreen` is a pure composable receiving only `State` and `onAction`.

### 3.5 Dependency Injection (`di`)
- Configured with **Koin 4.0.2**.
- All dependencies are singletons or scoped ViewModels provided via clean, explicit factory declarations in `AppModule.kt`.
- Root application class `AppRadioApp` initializes Koin on app launch.

---

## 4. Endianness & Data Flow Standards

| Protocol Layer | Byte Order | Delimiters / Headers | Payload Format |
|---|---|---|---|
| **PFormat Frame** | Big-Endian | Start: `0x9F 0x02`<br/>End: `0x9F 0x03` | Escaped Command ID (1B) + Escaped Body + Escaped XOR Checksum (1B) |
| **SAC Commands** | Big-Endian (MSB first) | None (inside PFormat) | 1B Subtype + Big-Endian Fields (Shorts/Ints) |
| **WebLink Packets** | Little-Endian (LSB first) | 8-byte Header (`'W'`, `'L'`, 2B Command ID, 4B Length) | Little-Endian Fields (Ints, Longs, Floats) |
