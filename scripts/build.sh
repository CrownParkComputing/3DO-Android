#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

resolve_java_home() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        return
    fi

    candidates=(
        "/c/Program Files/Android/Android Studio/jbr"
        "/c/Program Files/Java/latest"
        "/c/Program Files/Java/jdk-25.0.2"
        "/c/Program Files/Java/jdk-21"
        "/c/Program Files/Java/jdk-17"
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
        "/usr/lib/jvm/default-java"
        "/usr/lib/jvm/java-21-openjdk-amd64"
        "/usr/lib/jvm/java-17-openjdk-amd64"
    )

    for candidate in "${candidates[@]}"; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            return
        fi
    done

    echo "ERROR: No valid JDK found. Set JAVA_HOME to a JDK root containing bin/java." >&2
    exit 1
}

resolve_java_home

command_name="${1:-debug}"

case "$command_name" in
    debug)
        tasks=(assembleDebug)
        ;;
    release)
        tasks=(assembleRelease)
        ;;
    clean)
        tasks=(clean)
        ;;
    lint)
        tasks=(lint)
        ;;
    test)
        tasks=(test)
        ;;
    install)
        tasks=(installDebug)
        ;;
    all)
        tasks=(clean assembleDebug lint test)
        ;;
    *)
        echo "Usage: ./build.sh [debug|release|clean|lint|test|install|all]" >&2
        exit 1
        ;;
esac

echo "Using JAVA_HOME=$JAVA_HOME"
./gradlew --no-daemon "${tasks[@]}"