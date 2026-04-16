# 4DO Android

Android port of 4DO with a Java UI and native emulator/rendering code.

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