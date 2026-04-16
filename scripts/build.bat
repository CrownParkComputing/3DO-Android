@echo off
setlocal EnableExtensions

cd /d "%~dp0\.."

call :resolve_java_home
if errorlevel 1 exit /b 1

set "COMMAND=%~1"
if "%COMMAND%"=="" set "COMMAND=debug"

set "TASKS="

if /I "%COMMAND%"=="debug" set "TASKS=assembleDebug"
if /I "%COMMAND%"=="release" set "TASKS=assembleRelease"
if /I "%COMMAND%"=="clean" set "TASKS=clean"
if /I "%COMMAND%"=="lint" set "TASKS=lint"
if /I "%COMMAND%"=="test" set "TASKS=test"
if /I "%COMMAND%"=="install" set "TASKS=installDebug"
if /I "%COMMAND%"=="all" set "TASKS=clean assembleDebug lint test"

if not defined TASKS (
    echo Usage: build.bat [debug^|release^|clean^|lint^|test^|install^|all]
    exit /b 1
)

echo Using JAVA_HOME=%JAVA_HOME%
call gradlew.bat --no-daemon %TASKS%
exit /b %errorlevel%

:resolve_java_home
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" exit /b 0

for %%D in (
    "C:\Program Files\Android\Android Studio\jbr"
    "C:\Program Files\Java\latest"
    "C:\Program Files\Java\jdk-25.0.2"
    "C:\Program Files\Java\jdk-21"
    "C:\Program Files\Java\jdk-17"
) do (
    if exist "%%~D\bin\java.exe" (
        set "JAVA_HOME=%%~D"
        exit /b 0
    )
)

echo ERROR: No valid JDK found. Set JAVA_HOME to a JDK root containing bin\java.exe.
exit /b 1