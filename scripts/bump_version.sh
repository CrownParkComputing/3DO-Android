#!/bin/bash

# Version bumping script for 4DO Android
# Usage: ./scripts/bump_version.sh [major|minor|patch]
# Default: patch

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

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

# Function to get current version
get_current_version() {
    grep "versionName" app/build.gradle | grep -o '"[^"]*"' | tr -d '"'
}

# Function to get current version code
get_current_version_code() {
    grep "versionCode" app/build.gradle | grep -o '[0-9]*'
}

# Function to increment version
bump_version() {
    local version=$1
    local type=$2
    
    # Split version into parts
    IFS='.' read -ra VERSION_PARTS <<< "$version"
    local major=${VERSION_PARTS[0]}
    local minor=${VERSION_PARTS[1]}
    local patch=${VERSION_PARTS[2]}
    
    case $type in
        "major")
            major=$((major + 1))
            minor=0
            patch=0
            ;;
        "minor")
            minor=$((minor + 1))
            patch=0
            ;;
        "patch")
            patch=$((patch + 1))
            ;;
        *)
            print_error "Invalid version type: $type"
            print_error "Usage: $0 [major|minor|patch]"
            exit 1
            ;;
    esac
    
    echo "${major}.${minor}.${patch}"
}

# Function to update build.gradle
update_build_gradle() {
    local new_version=$1
    local new_version_code=$2
    
    # Create backup
    cp app/build.gradle app/build.gradle.backup
    
    # Update versionName
    sed -i "s/versionName \".*\"/versionName \"$new_version\"/" app/build.gradle
    
    # Update versionCode
    sed -i "s/versionCode [0-9]*/versionCode $new_version_code/" app/build.gradle
    
    print_status "Updated app/build.gradle with version $new_version (code: $new_version_code)"
}

# Function to commit changes
commit_changes() {
    local new_version=$1
    local new_version_code=$2
    
    git add app/build.gradle
    git commit -m "Bump version to $new_version (code: $new_version_code)"
    
    # Create tag
    git tag "v$new_version"
    
    print_status "Committed changes and created tag v$new_version"
}

# Function to cleanup
cleanup() {
    if [ -f app/build.gradle.backup ]; then
        rm app/build.gradle.backup
    fi
}

# Trap cleanup on exit
trap cleanup EXIT

# Check if we're in a git repository
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    print_error "Not in a git repository"
    exit 1
fi

# Check if there are untracked changes
if ! git diff-index --quiet HEAD --; then
    print_warning "You have untracked changes. Please commit them first."
    git status
    exit 1
fi

# Get current version
current_version=$(get_current_version)
current_version_code=$(get_current_version_code)

if [ -z "$current_version" ] || [ -z "$current_version_code" ]; then
    print_error "Could not extract current version from app/build.gradle"
    exit 1
fi

print_status "Current version: $current_version (code: $current_version_code)"

# Determine bump type
bump_type=${1:-patch}

# Calculate new version
new_version=$(bump_version "$current_version" "$bump_type")
new_version_code=$((current_version_code + 1))

print_status "New version: $new_version (code: $new_version_code)"

# Confirm before proceeding
echo
print_warning "This will:"
print_warning "  - Update versionName from $current_version to $new_version"
print_warning "  - Update versionCode from $current_version_code to $new_version_code"
print_warning "  - Create a new commit"
print_warning "  - Create a new tag: v$new_version"
echo
read -p "Do you want to proceed? (y/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    print_status "Version bump cancelled"
    exit 0
fi

# Update build.gradle
update_build_gradle "$new_version" "$new_version_code"

# Commit changes
commit_changes "$new_version" "$new_version_code"

print_status "Version bump completed successfully!"
print_status "New version: $new_version"
print_status "New version code: $new_version_code"
print_status "Tag created: v$new_version"