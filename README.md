# 4DO Android

[![Release](https://img.shields.io/github/v/release/CrownParkComputing/4DO-Android?label=Version)](https://github.com/CrownParkComputing/4DO-Android/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-green)](https://www.android.com)

Android port of 4DO with a Java UI and native emulator/rendering code — a free, open-source emulator for the [Tapwave Zodiac](https://en.wikipedia.org/wiki/Tapwave_Zodiac) handheld gaming console.

## Downloads

### Latest Release
**[Download 4DO-Emulator-release.aab](https://github.com/CrownParkComputing/4DO-Android/releases/latest/download/4DO-Emulator-release.aab)**

Requires Android 7.0 (API 24) or later.

For previous releases, visit the [Releases page](https://github.com/CrownParkComputing/4DO-Android/releases).

For documentation and more information, visit the **[4DO Android Documentation site](https://crownparkcomputing.github.io/4DO-Android/)**.

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

- **Accurate emulation** of the Tapwave Zodiac hardware
- **On-screen controller** support for touchscreen input
- **External controller** support via Bluetooth gamepads
- **Software and hardware rendering** options (OpenGL/Vulkan)
- **Game library** with box art from IGDB
- **Saves management** and state snapshots

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgements

- Original 4DO emulator by [Lionello "Lion" Vella](https://www.tapatalk.com/groups/tapwave)
- Tapwave Zodiac community
- All contributors