@echo off
set SCRIPT_DIR=%~dp0
set GRADLE_HOME=%SCRIPT_DIR%.gradle-dist\gradle-8.10.2

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Missing local Gradle distribution at %GRADLE_HOME%
  exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
