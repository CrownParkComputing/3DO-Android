---
title: 4DO Emulator for Android
layout: default
---

<p align="center">
  <img src="screenshots/icon.png" width="120" alt="4DO Android icon" />
</p>

# 4DO Emulator for Android

A free, open-source Android emulator for 3DO software built from the **Opera** codebase (a fork of 4DO), designed for broad compatibility across modern Android phones, tablets, foldables, and handheld devices.

## Download

[![Get it on Google Play](https://img.shields.io/badge/Get_it_on-Google_Play-414141?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.fourdo.android)

Install from **[Google Play](https://play.google.com/store/apps/details?id=com.fourdo.android)**. Android 7.0 (API 24) or later is required.

## What's New in v2.0.6

- **Fixed sideways / mirrored display** on Vulkan devices — games now render upright and correctly oriented.
- **Major performance boost** — the emulator core is fully optimized, and rendering is decoupled from the display refresh for smoother, faster gameplay.
- **New 3DO app icon.**
- **Redesigned controller mapper** — a clean, colour-coded button layout.
- **Removed the manual display-rotate control** — orientation is now always correct automatically.

**[Update on Google Play »](https://play.google.com/store/apps/details?id=com.fourdo.android)**

## Screenshots

| | |
|---|---|
| ![Title screen](screenshots/title.png) | ![Game library](screenshots/library.png) |
| ![Game details](screenshots/game-detail.png) | ![In-game, correct orientation](screenshots/gameplay.png) |

## Features

- **Accurate 4DO-based emulation** with broad Android device support
- **On-screen controller** support for touchscreen input
- **External controller** support via Bluetooth gamepads
- **Software and hardware rendering** options
- **Game library** with box art from IGDB
- **Saves management** and state snapshots

## Installation

1. Install from [Google Play](https://play.google.com/store/apps/details?id=com.fourdo.android).
2. Open the app and follow the on-screen setup prompts.
3. Launch **4DO Emulator** from your app drawer.

## Quick Start

1. Launch the app and complete the initial setup wizard.
2. Place your supported ROM files (`.iso`, `.bin`, or `.img`) in a folder on your device.
3. Use the built-in file browser to locate and load your ROM.
4. Tap the screen to reveal the on-screen controls if needed.

## Building from Source

Please refer to the [README.md](https://github.com/CrownParkComputing/4DO-Android/blob/main/README.md) for build instructions.

## Compatibility

The emulator targets general Android device compatibility and supports a wide range of 3DO and Tapwave-compatible titles. A compatibility list is maintained on the project wiki.

## Contributing

Contributions are welcome! Please see the [CONTRIBUTING.md](https://github.com/CrownParkComputing/4DO-Android/blob/main/CONTRIBUTING.md) for guidelines.

## License

The core emulator logic is based on **Opera** and is licensed under the **LGPL v2.1**. The Android-specific frontend and UI code are provided under the MIT License. See the [LICENSE](https://github.com/CrownParkComputing/4DO-Android/blob/main/LICENSE) for details.

## Acknowledgements

- **FreeDO**: The original 3DO emulator by Alexander64, Maxim Grishin, Andrey Tkachuk, Viktor Sen'ko (johnnydude), and others.
- **4DO**: Built upon FreeDO by **Viktor "johnnydude" Sen'ko**.
- **Opera**: An optimized fork of 4DO maintained by the [Opera-Libretro](https://github.com/libretro/opera-libretro) community.
- **Opera Android**: This project is an Android port/frontend for the Opera core.
- **Tapwave Zodiac** and broader 3DO preservation community.
- All contributors.

## Links

- [GitHub Repository](https://github.com/CrownParkComputing/4DO-Android)
- [Issue Tracker](https://github.com/CrownParkComputing/4DO-Android/issues)
- [Discussions](https://github.com/CrownParkComputing/4DO-Android/discussions)
