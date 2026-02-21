#!/bin/bash

# Script to create new signing keys for 4DO Android
# Usage: ./scripts/create_signing_keys.sh

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to generate new keystore
generate_keystore() {
    local keystore_file=$1
    local alias=$2
    local store_password=$3
    local key_password=$4
    local dname=$5
    
    print_status "Generating new keystore: $keystore_file"
    
    keytool -genkeypair \
        -v \
        -keystore "$keystore_file" \
        -alias "$alias" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storetype PKCS12 \
        -storepass "$store_password" \
        -keypass "$key_password" \
        -dname "$dname"
    
    if [ $? -eq 0 ]; then
        print_status "Keystore created successfully: $keystore_file"
        print_status "Alias: $alias"
        print_status "Store password: $store_password"
        print_status "Key password: $key_password"
    else
        print_error "Failed to create keystore"
        exit 1
    fi
}

# Function to backup old keystore
backup_old_keystore() {
    local old_keystore=$1
    if [ -f "$old_keystore" ]; then
        local backup_name="${old_keystore}.backup.$(date +%Y%m%d_%H%M%S)"
        print_warning "Backing up old keystore to: $backup_name"
        cp "$old_keystore" "$backup_name"
    fi
}

# Function to update build.gradle
update_build_gradle() {
    local keystore_file=$1
    local alias=$2
    local store_password=$3
    local key_password=$4
    
    print_status "Updating app/build.gradle with new signing configuration"
    
    # Create backup
    cp app/build.gradle app/build.gradle.backup
    
    # Update signing config
    sed -i "s|storeFile file('.*')|storeFile file('$keystore_file')|" app/build.gradle
    sed -i "s|storePassword '.*'|storePassword '$store_password'|" app/build.gradle
    sed -i "s|keyAlias '.*'|keyAlias '$alias'|" app/build.gradle
    sed -i "s|keyPassword '.*'|keyPassword '$key_password'|" app/build.gradle
    
    print_status "Build.gradle updated successfully"
}

# Main execution
main() {
    print_status "4DO Android Signing Key Generator"
    echo
    
    # Configuration
    NEW_KEYSTORE_FILE="4do-release-key-new.keystore"
    KEY_ALIAS="4do-key"
    STORE_PASSWORD="android"
    KEY_PASSWORD="android"
    DNAME="CN=4DO Android, OU=Development, O=4DO, L=Unknown, ST=Unknown, C=US"
    
    # Check if keytool is available
    if ! command -v keytool &> /dev/null; then
        print_error "keytool not found. Please install Java JDK."
        exit 1
    fi
    
    # Backup old keystore if it exists
    if [ -f "4do-release-key.keystore" ]; then
        backup_old_keystore "4do-release-key.keystore"
    fi
    
    # Generate new keystore
    generate_keystore "$NEW_KEYSTORE_FILE" "$KEY_ALIAS" "$STORE_PASSWORD" "$KEY_PASSWORD" "$DNAME"
    
    # Update build.gradle
    update_build_gradle "../$NEW_KEYSTORE_FILE" "$KEY_ALIAS" "$STORE_PASSWORD" "$KEY_PASSWORD"
    
    # Rename to standard name
    if [ -f "$NEW_KEYSTORE_FILE" ]; then
        print_status "Renaming keystore to standard name: 4do-release-key.keystore"
        mv "$NEW_KEYSTORE_FILE" "4do-release-key.keystore"
    fi
    
    echo
    print_status "New signing keys created successfully!"
    print_status "Keystore file: 4do-release-key.keystore"
    print_status "Alias: $KEY_ALIAS"
    print_status "Store password: $STORE_PASSWORD"
    print_status "Key password: $KEY_PASSWORD"
    print_status "These values will be used for GitHub Actions secrets:"
    print_status "  KEYSTORE_PASSWORD: $STORE_PASSWORD"
    print_status "  KEY_PASSWORD: $KEY_PASSWORD"
    
    echo
    print_warning "IMPORTANT: Commit the new keystore file to your repository"
    print_warning "IMPORTANT: Add the passwords as GitHub secrets for CI/CD to work"
}

# Run main function
main "$@"