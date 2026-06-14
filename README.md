# 3DO Opera

[![Get it on Google Play](https://img.shields.io/badge/Google_Play-Download-414141?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.fourdo.android)
[![License: LGPL v2.1](https://img.shields.io/badge/License-LGPL_v2.1-blue.svg)](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html)
[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-green)](https://www.android)

<img src="docs/screenshots/icon.png" align="right" width="96" alt="3DO Opera icon" />

Android port of the **Opera** emulator codebase with a Java UI and native emulator/rendering code — a free, open-source emulator for 3DO software with broad Android compatibility across phones, tablets, foldables, and handheld devices.

## Download

**[Get it on Google Play](https://play.google.com/store/apps/details?id=com.fourdo.android)** — requires Android 7.0 (API 24) or later.

For documentation and more information, visit the **[3DO Opera Documentation site](https://crownparkcomputing.github.io/3DO-Android/)**.

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
| ![Title screen](docs/screenshots/title.png) | ![Game library](docs/screenshots/library.png) |
| ![Game details](docs/screenshots/game-detail.png) | ![In-game (correct orientation)](docs/screenshots/gameplay.png) |

## Features

- **Accurate Opera-based emulation** with broad Android device support
- **On-screen controller** support for touchscreen input
- **External controller** support via Bluetooth gamepads
- **Software and hardware rendering** options (OpenGL/Vulkan)
- **Game library** with box art from IGDB
- **Saves management** and state snapshots

## License

**3DO Opera** is built from the **Opera** emulator codebase. The native emulator core used by this Android project is derived from Opera upstream sources only; this application is not derived from the former 4DO Android frontend/application. The name was changed to **3DO Opera** to reflect the Opera basis of the emulator core and to avoid presenting the app as a continuation of 4DO.

The Opera-derived native emulator core is licensed under **GNU LGPL v2.1**. The complete corresponding source for the native core and this project's local modifications is available in this repository.

The Android application code, launcher UI, renderer integration, game library, IGDB integration, bezel handling, controller mapping, artwork, and project-specific frontend code are licensed under the **MIT License**, unless a file explicitly states otherwise.

Commercial distribution is permitted under the applicable LGPL v2.1 and MIT terms, including paid distribution or sale on Google Play, provided the license obligations are satisfied. This project does not rely on any non-commercial emulator license grant for the Android app distributed as **3DO Opera**.

See the [LICENSE](LICENSE) file for the full license notice.

## Acknowledgements

- **Opera / libretro-opera**: The upstream emulator codebase used for the native core.
- **Opera Android**: This project is an Android port/frontend for the Opera emulator core.
- **Tapwave Zodiac** and broader 3DO preservation community.
- All contributors.
