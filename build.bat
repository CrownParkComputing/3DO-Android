@echo off
setlocal
call "%~dp0scripts\build.bat" %*
exit /b %errorlevel%
