@echo off
rem Starts sdrtrunk with PlutoSDR support from the source code on Windows.
rem
rem The build needs a JDK 27 that includes JavaFX, such as the Bellsoft Liberica JDK 27 "Full" package.  This uses
rem the JDK in the SDRTRUNK_JDK environment variable, or %USERPROFILE%\.jdks\jdk-27-full when it exists.
rem Otherwise Gradle looks for an installed JDK 27.  The PlutoSDR tuner also needs libiio 0.25 (see README.md).
setlocal
cd /d "%~dp0"
if not defined SDRTRUNK_JDK if exist "%USERPROFILE%\.jdks\jdk-27-full\bin\java.exe" set "SDRTRUNK_JDK=%USERPROFILE%\.jdks\jdk-27-full"
if defined SDRTRUNK_JDK (
    call "%~dp0gradlew.bat" run "-Porg.gradle.java.installations.paths=%SDRTRUNK_JDK%"
) else (
    call "%~dp0gradlew.bat" run
)
rem Keep the window open after an error so that it can be read
if errorlevel 1 pause
endlocal
