# AppRadio RE (Reverse Engineered)

[![Build Android APK](https://github.com/MuhammedSheref/AppRadioRE/actions/workflows/build-apk.yml/badge.svg)](https://github.com/MuhammedSheref/AppRadioRE/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-green.svg?style=flat&logo=android)](https://www.android.com)
[![Compose](https://img.shields.io/badge/UI-Jetpack_Compose_Material3-blue.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2F%20MVI-orange.svg?style=flat)](#architecture)
[![Unit Tests](https://img.shields.io/badge/Tests-46%20Passing-brightgreen.svg?style=flat)](#testing)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg?style=flat)](LICENSE)

A modern, high-performance Android mirroring engine and live protocol diagnostic tool for **Pioneer AppRadio Mode 2 (AAM2 / WebLink)** car stereos (including the Pioneer SPH-DA120, AVH-X8*** series, and Carrozzeria units).

---

## 🚗 Background & Motivation

In 2014, Pioneer introduced **AppRadio Mode 2 (AAM2)**, a protocol utilizing USB Android Open Accessory (AOA) to stream video and relay touchscreen digitizer events between an Android smartphone and a vehicle dashboard head unit.

The original Pioneer companion application was abandoned years ago:
- Spanned **4 separate background processes** with complex AIDL inter-process communication.
- Relied on deprecated 32-bit native libraries (`libPFormat.so`, `libWebLinkServerLib.so`) incompatible with modern 64-bit Android 14+ devices.
- Depended on prohibited system permissions (`INJECT_EVENTS`) and internal view-hierarchy scraping (`WLMirrorLayer`), which break under modern Android security policies.
- Failed to run on modern Android versions, rendering expensive car stereos incapable of smartphone mirroring.

**AppRadio RE** is a clean-room reverse-engineering and ground-up modernization of the entire protocol stack in pure **Kotlin**, **Jetpack Compose**, and hardware-accelerated **Android MediaCodec APIs**.

---

## ✨ Features

- **⚡ Instant Handshake Negotiation**:
  - Connects to the Pioneer stereo in ~300ms over physical USB AOA.
  - Automatically acknowledges Video Channel (Port 12346) and Control Channel (Port 12347).
  - Synchronizes session timestamps and confirms video parameters (`800x480 @ 240 DPI, H.264 @ 8 Mbps`).
  - Automatically sends `SetCurrentAppCommand("wlhome_1.0://")` to trigger the stereo's mirroring mode.

- **🎥 Hardware H.264 Video Pipeline**:
  - Direct hardware encoding via `MediaCodec` (`video/avc`) at `800x480 @ 30 FPS` and `8 Mbps`.
  - Zero-copy surface rendering loop using `COLOR_FormatSurface`.
  - Built-in 30 FPS automotive test pattern renderer with real-time millisecond clock (`HH:mm:ss.SSS`), monospaced frame counter, corner calibration crosshairs, and animated bouncing orb for visual confirmation of smooth 30 FPS streaming.
  - Automatic SPS/PPS parameter set extraction and keyframe insertion.
  - Packaging of H.264 NAL units into WebLink `FillRectangleCommand` (ID 1) packets routed through MTP Video Channel (Port 12346).

- **🔍 Live Protocol Diagnostic Inspector**:
  - Real-time, zero-blind-spot packet sniffer capturing every raw byte transmitted over USB.
  - Color-coded protocol categorization: `SAC`, `MTP`, `WebLink`, `PFormat`, `System`.
  - Filter chips and instant search bar (filter by protocol or search payload text and hex dumps).
  - Packet inspector modal showing detailed field breakdowns and formatted hex dumps.
  - One-tap log exporter for sharing protocol traces via email, messaging apps, or cloud storage.

- **🛡️ 100% Pure Kotlin Architecture**:
  - Zero legacy `.so` native binary dependencies — fully compatible with modern 16KB-page-size Android 15 devices.
  - Non-blocking asynchronous I/O powered by Kotlin Coroutines and StateFlow.
  - Layered Dependency Injection with Koin.
  - Strict unidirectional data flow (MVI) presentation layer.

---

## 📐 Protocol Stack Architecture

The Pioneer AAM2 protocol is a multiplexed layered transport:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Application Layer                               │
│           (Test Pattern Renderer / Virtual Display / Touch Relay)           │
├──────────────────────────────────────┬──────────────────────────────────────┤
│          WebLink Protocol            │        Pioneer SAC Protocol          │
│  (FillRectangle, VideoConfig, Touch) │  (Auth, SpecInfo, AccessoryStatus)   │
├──────────────────────────────────────┼──────────────────────────────────────┤
│       WebLink Command Framing        │            PFormat Framing           │
│   ('WL' Header 0x57 0x4C, LE Order)  │     (0x9F 0x02 ... 0x9F 0x03, XOR)   │
├──────────────────────────────────────┴──────────────────────────────────────┤
│                    MTP (Multiplexing Transport Protocol)                    │
│             (Framing: 0x1E ... 0x03, Big-Endian Byte Order)                 │
│         Port 12346: Video Stream   │   Port 12347: Control & Input          │
├─────────────────────────────────────────────────────────────────────────────┤
│                         USB Android Open Accessory                          │
│        (Pioneer jp.pioneer.ce.aam2.linkwith, 16KB Read/Write Streams)       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Critical Endianness Rules

| Protocol Layer | Framing Delimiters | Byte Order | Implementation |
|---|---|---|---|
| **MTP Layer** | Begin: `0x1E`, End: `0x03` | **Big-Endian** (Network) | `com.ameer.appradiore.core.protocol.mtp.MTPCodec` |
| **WebLink Layer** | Magic: `0x57 0x4C` (`'W' 'L'`) | **Little-Endian** | `com.ameer.appradiore.core.protocol.weblink.WebLinkCodec` |
| **PFormat Layer** | STX: `0x9F 0x02`, ETX: `0x9F 0x03` | Byte-stuffed + XOR | `com.ameer.appradiore.core.protocol.pformat.PFormatCodec` |
| **SAC Commands** | Opcode-dependent | **Big-Endian** | `com.ameer.appradiore.core.protocol.sac.SACCodec` |

---

## 🛠️ Project Structure

```
AppRadioRE/
├── app/
│   ├── src/main/java/com/ameer/appradiore/
│   │   ├── core/
│   │   │   ├── error/            # Type-safe Result<D, E> and DataError domain models
│   │   │   ├── logging/          # Ring-buffer log repository and file exporter
│   │   │   ├── presentation/     # UiText formatting and Compose event observers
│   │   │   ├── protocol/
│   │   │   │   ├── mtp/          # Abalta MTP packet framing & channel multiplexing
│   │   │   │   ├── pformat/      # Pure Kotlin PFormat byte-stuffer & XOR checksum
│   │   │   │   ├── sac/          # Pioneer SAC commands, opcodes, and subtypes
│   │   │   │   └── weblink/      # WebLink commands (FillRectangle, VideoConfig, Touch)
│   │   │   ├── usb/              # UsbAccessoryManager, HandshakeStateMachine, UsbDataSource
│   │   │   └── video/            # H264VideoEncoder, TestPatternRenderer, VideoStreamingManager
│   │   ├── di/                   # Layered Koin DI modules (Logging, USB, Video, Feature)
│   │   ├── feature/livelog/      # Jetpack Compose UI (Cards, ControlBar, Inspection Dialog)
│   │   └── ui/theme/             # Automotive dark tech theme
│   └── src/test/java/            # Comprehensive unit test suite (46 tests)
├── agend.md                      # Detailed technical specification and reverse-engineering notes
└── README.md
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2+) or newer
- JDK 17 or higher
- Android SDK 34
- Android device running Android 8.0 (API 26) or newer with USB OTG / accessory support

### Building the Project
Clone the repository and build via Gradle:

```bash
git clone https://github.com/MuhammedSheref/AppRadioRE.git
cd AppRadioRE

# Run all unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 In-Car Hardware Testing

1. **Install the APK** on your Android smartphone.
2. **Connect the phone** to your Pioneer car stereo via a USB cable.
3. **Automatic Connection**:
   - The head unit detects the AOA accessory (`jp.pioneer.ce.aam2.linkwith`).
   - Grant USB accessory permission when prompted.
   - The handshake will complete in ~300ms, displaying:
     `CONNECTED_READY: 800x480 @ 240 DPI, Touch: 2 pts, Brake: ON`.
   - The car stereo will switch or highlight its Mirroring / AppRadio Mode screen.
4. **Stream Test Pattern**:
   - Tap **STREAM VIDEO** on the phone.
   - The stereo screen will render the real-time 30 FPS animated automotive dashboard (with ticking clock, frame counter, and animated graphics).
   - Tap **STOP** to halt the video pipeline cleanly.

---

## 🧪 Testing

All unit tests run locally without requiring a connected car stereo:

```bash
./gradlew testDebugUnitTest
```

### Test Coverage Highlights:
- **`WebLinkCodecTest`**: Validates Little-Endian round-trips for `SetFps`, length-prefixed `SetCurrentApp`, `VideoConfig`, `DisplayMetrics`, and `FillRectangle`.
- **`MTPCodecTest`**: Verifies MTP packet framing (`0x1E ... 0x03`), IPv4 address parsing, port multiplexing, and connection ACKs.
- **`HandshakeStateMachineTest`**: Simulates the full Pioneer authentication sequence and verifies automatic screen unlocking.
- **`VideoStreamingManagerTest`**: Verifies encoder lifecycle, H.264 frame packaging into `FillRectangleCommand`, and MTP Port 12346 wire wrapping.
- **`LiveLogViewModelTest`**: Tests MVI state updates, search queries, protocol filters, and video stream toggling via Turbine.

---

## 🗺️ Project Roadmap

- [x] **Phase 1: Transport & Diagnostic Foundation**
  - [x] Abalta MTP framing codec (`0x1E ... 0x03`)
  - [x] WebLink codec & Little-Endian command engine
  - [x] Handshake state machine with 300ms physical hardware unlock
  - [x] Real-time Compose protocol inspector and log exporter
- [x] **Phase 2: Video Pipeline & Test Pattern**
  - [x] Automated head unit mirror trigger (`SetCurrentApp`)
  - [x] Android `MediaCodec` H.264 hardware encoder (`800x480 @ 30 FPS, 8 Mbps`)
  - [x] Zero-copy 30 FPS animated automotive test pattern renderer
  - [x] Video packaging & USB streaming over MTP Port 12346
- [ ] **Phase 3: Virtual Display Mirroring**
  - [ ] Android `MediaProjection` integration
  - [ ] Create `VirtualDisplay` matching stereo dimensions (800x480 @ 240 DPI)
  - [ ] Stream arbitrary apps and full phone UI to head unit
- [ ] **Phase 4: Two-Way Touch Digitizer Relay**
  - [ ] Parse WebLink `TouchCommand` (ID 72) coordinate packets
  - [ ] Map stereo touch points into VirtualDisplay motion events
  - [ ] Support multi-touch gestures (pinch-to-zoom, fling, multi-finger taps)
- [ ] **Phase 5: Audio & Vehicle Integration**
  - [ ] Bluetooth audio routing / USB audio stream
  - [ ] AVRCP steering wheel controls (Track Up/Down, Play/Pause)
  - [ ] GPS data receiver relay from vehicle antenna

---

## 📄 Documentation

For the in-depth byte-by-byte protocol specification, decompiled class references, and reverse-engineering findings, see [agend.md](agend.md).

---

## ⚖️ Disclaimer & License

This project is an independent clean-room reverse-engineering project developed strictly for interoperability, research, and preservation of functional automotive hardware. Pioneer, AppRadio, Linkwith, and WebLink are registered trademarks of their respective owners. This project is not affiliated with, endorsed by, or associated with Pioneer Corporation or Abalta Technologies.

Distributed under the [MIT License](LICENSE).
