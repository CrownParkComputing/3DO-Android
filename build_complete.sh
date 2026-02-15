#!/bin/bash

# Complete build script for 4DO Android application
set -e  # Exit on any error

# Colors for output
RED='[0;31m'
GREEN='[0;32m'
YELLOW='[1;33m'
NC='[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Android SDK is available
if [ -z "$ANDROID_HOME" ]; then
    print_error "ANDROID_HOME environment variable is not set"
    print_error "Please set ANDROID_HOME to your Android SDK path"
    exit 1
fi

# Check if required tools are available
check_tool() {
    if ! command -v $1 &> /dev/null; then
        print_error "Required tool '$1' is not installed or not in PATH"
        exit 1
    fi
}

check_tool "gradle"
check_tool "adb"

# Clean previous builds
print_status "Cleaning previous build artifacts..."
cd "$(dirname "$0")/4DO-Android" && ./gradlew clean

# Build debug APK
print_status "Building debug APK..."
cd "$(dirname "$0")/4DO-Android" && ./gradlew assembleDebug

# Check if build was successful
if [ $? -eq 0 ]; then
    print_status "Build successful!"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    print_status "APK location: $APK_PATH"
    
    # Get APK size
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    print_status "APK size: $APK_SIZE"
    
    # Install APK to connected device
    if adb devices | grep -q "device\$"; then
        print_status "Installing APK to connected device..."
        adb install -r "$APK_PATH"
        
        if [ $? -eq 0 ]; then
            print_status "Installation successful!"
            print_status "Launching application..."
            adb shell am start -n com.fourdo.android/.MainActivity
        else
            print_warning "Installation failed, but APK was built successfully"
        fi
    else
        print_warning "No device connected, skipping installation"
        print_status "You can install manually using:"
        print_status "adb install $APK_PATH"
    fi
else
    print_error "Build failed!"
    exit 1
fi

# Generate build report
print_status "Generating build report..."
BUILD_REPORT="build_report.txt"
{
    echo "4DO Android Build Report"
    echo "========================="
    echo "Build Date: $(date)"
    echo "Build Status: SUCCESS"
    echo "APK Path: $APK_PATH"
    echo "APK Size: $APK_SIZE"
    echo ""
    echo "Environment:"
    echo "Android SDK: $ANDROID_HOME"
    echo "Gradle Version: $(gradle --version | grep 'Gradle' | cut -d' ' -f2)"
    echo "Java Version: $(java -version 2>&1 | head -n 1)"
} > "$BUILD_REPORT"

print_status "Build report saved to: $BUILD_REPORT"
print_status "Build completed successfully!"