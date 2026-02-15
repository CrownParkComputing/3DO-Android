# 4DO Android Port

This is an Android port of the 4DO 3DO emulator using SDL2 for rendering and a Java GUI overlay.

## Building

1. Install Android Studio and the Android SDK
2. Install CMake and the NDK
3. Open the project in Android Studio
4. Build and run the project

## Project Structure

- `app/src/main/java/com/fourdo/android/` - Java source code
- `app/src/main/cpp/` - Native C++ source code
- `app/src/main/res/` - Android resources
- `app/CMakeLists.txt` - CMake build configuration

## Native Code

The native code uses SDL2 for rendering and handles the 3DO emulation core.

## Java GUI

The Java GUI provides a simple interface for launching the emulator and managing settings.