# 4DO Android

[![Get it on Google Play](https://img.shields.io/badge/Google_Play-Download-414141?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.fourdo.android)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-green)](https://www.android.com)

<img src="docs/screenshots/icon.png" align="right" width="96" alt="4DO Android icon" />

Android port of 4DO with a Java UI and native emulator/rendering code — a free, open-source emulator for 3DO software with broad Android compatibility across phones, tablets, foldables, and handheld devices.

## Download

**[Get it on Google Play](https://play.google.com/store/apps/details?id=com.fourdo.android)** — requires Android 7.0 (API 24) or later.

For documentation and more information, visit the **[4DO Android Documentation site](https://crownparkcomputing.github.io/4DO-Android/)**.

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
| ![Title screen](docs/screenshots/title.png) | ![Game library](docs/screenshots/library.png) |
| ![Game details](docs/screenshots/game-detail.png) | ![In-game (correct orientation)](docs/screenshots/gameplay.png) |

## Requirements

- Android SDK
- Android NDK
- CMake
- A working JDK for Gradle

`JAVA_HOME` should point to a real JDK root containing `bin/java`. If it is unset or invalid, the local build scripts try common locations such as Android Studio JBR.

## Local Build Commands

Windows PowerShell or Command Prompt:

```bat
build.bat
build.bat clean
build.bat debug
build.bat release
build.bat lint
build.bat test
build.bat install
```

Git Bash:

```bash
./build.sh
./build.sh clean
./build.sh debug
./build.sh release
./build.sh lint
./build.sh test
./build.sh install
```

Both entry points delegate to the maintained scripts under `scripts/` and run the Gradle wrapper with `--no-daemon`.

## CI

GitHub Actions build configuration lives in `.github/workflows/android-build.yml`.

## Project Structure

- `app/src/main/java/com/fourdo/android/` - Java source
- `app/cpp/` - Native code
- `app/src/main/res/` - Android resources
- `scripts/` - Local helper scripts

## Features

- **Accurate 4DO-based emulation** with broad Android device support
- **On-screen controller** support for touchscreen input
- **External controller** support via Bluetooth gamepads
- **Software and hardware rendering** options (OpenGL/Vulkan)
- **Game library** with box art from IGDB
- **Saves management** and state snapshots

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgements

- Original 4DO emulator by [Lionello "Lion" Vella](https://www.tapatalk.com/groups/tapwave)
- [Opera](https://github.com/libretro/opera-libretro) contributors and community
- Tapwave Zodiac and broader 3DO preservation community
- All contributors
