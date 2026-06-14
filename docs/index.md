---
title: 3DO Opera
layout: default
---

<p align="center">
  <img src="screenshots/icon.png" width="120" alt="3DO Opera icon" />
</p>

# 3DO Opera

A free, open-source Android emulator for 3DO software built from the **Opera** codebase, designed for broad compatibility across modern Android phones, tablets, foldables, and handheld devices.

## Download

[![Get it on Google Play](https://img.shields.io/badge/Get_it_on-Google_Play-414141?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.fourdo.android)

Install from **[Google Play](https://play.google.com/store/apps/details?id=com.fourdo.android)**. Android 7.0 (API 24) or later is required.

## What's New in v2.0.7

- **Fixed sideways / mirrored display** on Vulkan devices — games now render upright and correctly oriented.
- **Major performance boost** — the emulator core is fully optimized, and rendering is decoupled from the display refresh for smoother, faster gameplay.
- **New 3DO app icon.**
- **Ymir-style launcher and library** with IGDB metadata and per-game bezel support.
- **Translucent 3DO virtual pad** with grey on-screen controls for touch devices.
- **Redesigned controller mapper** — a clean, colour-coded button layout.
- **Removed the manual display-rotate control** — orientation is now always correct automatically.

**[Update on Google Play »](https://play.google.com/store/apps/details?id=com.fourdo.android)**

## Screenshots

| | |
|---|---|
| ![Title screen](screenshots/title.png) | ![Game library](screenshots/library.png) |
| ![Game details](screenshots/game-detail.png) | ![In-game, correct orientation](screenshots/gameplay.png) |

## Features

- **Accurate Opera-based emulation** with broad Android device support
- **On-screen controller** support for touchscreen input
- **External controller** support via Bluetooth gamepads
- **Software and hardware rendering** options
- **Game library** with box art from IGDB
- **Saves management** and state snapshots

## Installation

1. Install from [Google Play](https://play.google.com/store/apps/details?id=com.fourdo.android).
2. Open the app and follow the on-screen setup prompts.
3. Launch **3DO Opera** from your app drawer.

## Quick Start

1. Launch the app and complete the initial setup wizard.
2. Place your supported ROM files (`.iso`, `.bin`, or `.img`) in a folder on your device.
3. Use the built-in file browser to locate and load your ROM.
4. Tap the screen to reveal the on-screen controls if needed.

## Compatibility

The emulator targets general Android device compatibility and supports a wide range of 3DO and Tapwave-compatible titles. A compatibility list is maintained on the project wiki.

## Contributing

Contributions are welcome! Please see the [CONTRIBUTING.md](https://github.com/CrownParkComputing/3DO-Android/blob/main/CONTRIBUTING.md) for guidelines.

## License

**3DO Opera** is built from the **Opera** emulator codebase. The native emulator core used by this Android project is derived from Opera upstream sources only; this application is not derived from the former 4DO Android frontend/application. The name was changed to **3DO Opera** to reflect the Opera basis of the emulator core and to avoid presenting the app as a continuation of 4DO.

The Opera-derived native emulator core is licensed under **GNU LGPL v2.1**. The complete corresponding source for the native core and this project's local modifications is available in this repository.

The Android application code, launcher UI, renderer integration, game library, IGDB integration, bezel handling, controller mapping, artwork, and project-specific frontend code are licensed under the **MIT License**, unless a file explicitly states otherwise.

Commercial distribution is permitted under the applicable LGPL v2.1 and MIT terms, including paid distribution or sale on Google Play, provided the license obligations are satisfied. This project does not rely on any non-commercial emulator license grant for the Android app distributed as **3DO Opera**.

See the [LICENSE](https://github.com/CrownParkComputing/3DO-Android/blob/main/LICENSE) file for the full license notice.

## Acknowledgements

- **Opera / libretro-opera**: The upstream emulator codebase used for the native core.
- **Opera Android**: This project is an Android port/frontend for the Opera emulator core.
- **Tapwave Zodiac** and broader 3DO preservation community.
- All contributors.

## Links

- [GitHub Repository](https://github.com/CrownParkComputing/3DO-Android)
- [Issue Tracker](https://github.com/CrownParkComputing/3DO-Android/issues)
- [Discussions](https://github.com/CrownParkComputing/3DO-Android/discussions)
