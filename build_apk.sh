#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

resolve_java_home() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        return
    fi

    local candidates=(
        "/c/Program Files/Java/latest"
        "/c/Program Files/Java/jdk-25.0.2"
        "/c/Program Files/Android/Android Studio/jbr"
    )

    local candidate
    for candidate in "${candidates[@]}"; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            echo "Using JAVA_HOME=$JAVA_HOME"
            return
        fi
    done

    echo "Build failed: no valid JAVA_HOME found."
    echo "Set JAVA_HOME to a JDK root directory that contains bin/java."
    exit 1
}

resolve_java_home

# Build the Android APK
./gradlew assembleDebug

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
else
    echo "Build failed!"
    exit 1
fi