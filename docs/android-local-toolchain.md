# Daily Android Local Toolchain

## Purpose

This document defines the local Windows setup for Android CI-parity checks in the Daily workspace.

Goal:

- catch Android/Kotlin regressions before pushing to GitHub
- reproduce the same debug build target that runs in `.github/workflows/ci.yml`
- keep repo-local helper files out of version control

Important:

- GitHub Actions does not depend on this local SDK
- local Android setup is only for faster pre-push verification on this workstation
- release builds still require the real signing secrets and the real `google-services.json` from GitHub secrets

## Repo Requirements

Current Daily Android requirements:

- JDK `17`
- Gradle `8.2.1`
- `compileSdk = 34`
- `targetSdk = 34`
- SDK packages:
  - `platform-tools`
  - `platforms;android-34`
  - `build-tools;34.0.0`

## Windows Standard Path

Use the per-user SDK path:

- `%LOCALAPPDATA%\\Android\\Sdk`

Current tested layout on this machine:

- `cmdline-tools\\latest`
- `platform-tools`
- `platforms\\android-34`
- `build-tools\\34.0.0`

## One-Time Machine Setup

1. Download the official Android command-line tools for Windows.
   Current tested artifact: `commandlinetools-win-14742923_latest.zip`
2. Extract the archive so that `sdkmanager.bat` ends up here:
   - `%LOCALAPPDATA%\\Android\\Sdk\\cmdline-tools\\latest\\bin\\sdkmanager.bat`
3. Set these user environment variables:
   - `ANDROID_HOME=%LOCALAPPDATA%\\Android\\Sdk`
   - `ANDROID_SDK_ROOT=%LOCALAPPDATA%\\Android\\Sdk`
4. Add these paths to the user `Path`:
   - `%LOCALAPPDATA%\\Android\\Sdk\\platform-tools`
   - `%LOCALAPPDATA%\\Android\\Sdk\\cmdline-tools\\latest\\bin`
5. Open a new terminal after changing user environment variables.

## One-Time SDK Package Install

Run:

```powershell
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## Repo-Local Helper Files

These files are intentionally ignored by Git and can be created locally:

- `android/local.properties`
- `android/app/google-services.json`

`android/local.properties` should point to the local SDK:

```properties
sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

For local debug verification without production Firebase secrets, a placeholder `android/app/google-services.json` is sufficient. GitHub CI already generates the same kind of placeholder for the debug build.

## Verification Commands

Check the toolchain:

```powershell
java -version
gradle --version
adb version
```

Run the same Android target as CI:

```powershell
gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain
```

Expected local output:

- APK under `android/app/build/outputs/apk/debug/app-debug.apk`

## Current Verified Host State

Verified on `2026-04-17` on this workstation:

- Java: `Temurin 17.0.16`
- Gradle: `8.2.1`
- Android command-line tools: `Pkg.Revision=20.0`
- Platform tools / `adb`: `37.0.0`
- Local CI-parity build:
  - `gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain`
  - result: success

## Operator Rule

If a change touches `android/` or changes shared app-facing Kotlin models/UI state, run the local debug build before pushing to `main` when this workstation is available.
