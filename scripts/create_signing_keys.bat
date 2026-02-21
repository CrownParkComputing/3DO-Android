@echo off
REM Script to create new signing keys for 4DO Android
REM Usage: create_signing_keys.bat

setlocal enabledelayedexpansion

REM Colors for output (Windows doesn't support ANSI colors easily, so we'll use simple text)
echo [INFO] 4DO Android Signing Key Generator
echo.

REM Configuration
set "NEW_KEYSTORE_FILE=4do-release-key-new.keystore"
set "KEY_ALIAS=4do-key"
set "STORE_PASSWORD=android"
set "KEY_PASSWORD=android"
set "DNAME=CN=4DO Android, OU=Development, O=4DO, L=Unknown, ST=Unknown, C=US"

REM Check if keytool is available
where keytool >nul 2>&1
if errorlevel 1 (
    echo [ERROR] keytool not found. Please install Java JDK.
    exit /b 1
)

REM Backup old keystore if it exists
if exist "4do-release-key.keystore" (
    echo [WARNING] Backing up old keystore
    for /f "tokens=*" %%a in ('wmic OS Get localdatetime ^| find "."') do set "dt=%%a"
    set "backup_name=4do-release-key.keystore.backup.%dt:~0,4%%dt:~4,2%%dt:~6,2%_%dt:~8,2%%dt:~10,2%%dt:~12,2%"
    copy "4do-release-key.keystore" "%backup_name%" >nul
    echo [INFO] Old keystore backed up to: %backup_name%
)

REM Generate new keystore
echo [INFO] Generating new keystore: %NEW_KEYSTORE_FILE%
keytool -genkeypair ^
    -v ^
    -keystore "%NEW_KEYSTORE_FILE%" ^
    -alias "%KEY_ALIAS%" ^
    -keyalg RSA ^
    -keysize 2048 ^
    -validity 10000 ^
    -storetype PKCS12 ^
    -storepass "%STORE_PASSWORD%" ^
    -keypass "%KEY_PASSWORD%" ^
    -dname "%DNAME%"

if errorlevel 1 (
    echo [ERROR] Failed to create keystore
    exit /b 1
)

echo [INFO] Keystore created successfully: %NEW_KEYSTORE_FILE%
echo [INFO] Alias: %KEY_ALIAS%
echo [INFO] Store password: %STORE_PASSWORD%
echo [INFO] Key password: %KEY_PASSWORD%

REM Update build.gradle
echo [INFO] Updating app/build.gradle with new signing configuration

REM Create backup
copy app\build.gradle app\build.gradle.backup >nul

REM Update signing config using PowerShell
powershell -Command ^
    "$content = Get-Content app\build.gradle -Raw; ^
    $content = $content -replace 'storeFile file\(''[^'']*''\)', 'storeFile file(''../%NEW_KEYSTORE_FILE%'')'; ^
    $content = $content -replace 'storePassword ''[^'']*''', 'storePassword ''%STORE_PASSWORD%'''; ^
    $content = $content -replace 'keyAlias ''[^'']*''', 'keyAlias ''%KEY_ALIAS%'''; ^
    $content = $content -replace 'keyPassword ''[^'']*''', 'keyPassword ''%KEY_PASSWORD%'''; ^
    Set-Content app\build.gradle $content"

if errorlevel 1 (
    echo [ERROR] Failed to update build.gradle
    exit /b 1
)

echo [INFO] Build.gradle updated successfully

REM Rename to standard name
if exist "%NEW_KEYSTORE_FILE%" (
    echo [INFO] Renaming keystore to standard name: 4do-release-key.keystore
    move "%NEW_KEYSTORE_FILE%" "4do-release-key.keystore" >nul
)

echo.
echo [INFO] New signing keys created successfully!
echo [INFO] Keystore file: 4do-release-key.keystore
echo [INFO] Alias: %KEY_ALIAS%
echo [INFO] Store password: %STORE_PASSWORD%
echo [INFO] Key password: %KEY_PASSWORD%
echo [INFO] These values will be used for GitHub Actions secrets:
echo [INFO]   KEYSTORE_PASSWORD: %STORE_PASSWORD%
echo [INFO]   KEY_PASSWORD: %KEY_PASSWORD%
echo.
echo [WARNING] IMPORTANT: Commit the new keystore file to your repository
echo [WARNING] IMPORTANT: Add the passwords as GitHub secrets for CI/CD to work

endlocal