@echo off
REM Version bumping script for 4DO Android
REM Usage: bump_version.bat [major|minor|patch]
REM Default: patch

setlocal enabledelayedexpansion

REM Colors for output (Windows doesn't support ANSI colors easily, so we'll use simple text)
echo [INFO] Version bumping script for 4DO Android

REM Check if we're in a git repository
git rev-parse --git-dir >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Not in a git repository
    exit /b 1
)

REM Check if there are untracked changes
git diff-index --quiet HEAD -- >nul 2>&1
if not errorlevel 1 (
    echo [WARNING] You have untracked changes. Please commit them first.
    git status
    exit /b 1
)

REM Get current version from build.gradle
for /f "tokens=2 delims=""" %%i in ('findstr "versionName" app\build.gradle') do set "current_version=%%i"
for /f "tokens=2" %%i in ('findstr "versionCode" app\build.gradle') do set "current_version_code=%%i"

if "%current_version%"=="" (
    echo [ERROR] Could not extract current version from app/build.gradle
    exit /b 1
)

if "%current_version_code%"=="" (
    echo [ERROR] Could not extract current version code from app/build.gradle
    exit /b 1
)

echo [INFO] Current version: %current_version% (code: %current_version_code%)

REM Determine bump type
set "bump_type=%~1"
if "%bump_type%"=="" set "bump_type=patch"

REM Parse version into parts
for /f "tokens=1,2,3 delims=." %%a in ("%current_version%") do (
    set /a major=%%a
    set /a minor=%%b
    set /a patch=%%c
)

REM Calculate new version
if "%bump_type%"=="major" (
    set /a major+=1
    set minor=0
    set patch=0
) else if "%bump_type%"=="minor" (
    set /a minor+=1
    set patch=0
) else if "%bump_type%"=="patch" (
    set /a patch+=1
) else (
    echo [ERROR] Invalid version type: %bump_type%
    echo [ERROR] Usage: %0 [major|minor|patch]
    exit /b 1
)

set "new_version=%major%.%minor%.%patch%"
set /a new_version_code=current_version_code+1

echo [INFO] New version: %new_version% (code: %new_version_code%)

REM Confirm before proceeding
echo.
echo [WARNING] This will:
echo   - Update versionName from %current_version% to %new_version%
echo   - Update versionCode from %current_version_code% to %new_version_code%
echo   - Create a new commit
echo   - Create a new tag: v%new_version%
echo.
set /p "confirm=Do you want to proceed? (y/N): "

if /i not "%confirm%"=="y" (
    echo [INFO] Version bump cancelled
    exit /b 0
)

REM Create backup
copy app\build.gradle app\build.gradle.backup >nul

REM Update versionName in build.gradle
powershell -Command "(Get-Content app\build.gradle) -replace 'versionName \".*\"', 'versionName \"%new_version%\"' | Set-Content app\build.gradle"

REM Update versionCode in build.gradle
powershell -Command "(Get-Content app\build.gradle) -replace 'versionCode [0-9]*', 'versionCode %new_version_code%' | Set-Content app\build.gradle"

echo [INFO] Updated app/build.gradle with version %new_version% (code: %new_version_code%)

REM Commit changes
git add app\build.gradle
git commit -m "Bump version to %new_version% (code: %new_version_code%)"

REM Create tag
git tag "v%new_version%"

echo [INFO] Committed changes and created tag v%new_version%
echo [INFO] Version bump completed successfully!
echo [INFO] New version: %new_version%
echo [INFO] New version code: %new_version_code%
echo [INFO] Tag created: v%new_version%

REM Cleanup backup
if exist app\build.gradle.backup del app\build.gradle.backup

endlocal