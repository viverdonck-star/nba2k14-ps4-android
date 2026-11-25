# Building

The release APK is built in three steps: the engine, the engine package (`runtime.zip`), then the app. All three
run on Linux (Debian 13 or WSL 2 with Debian 13 on Windows).

## Requirements

| Tool | Version | Used for |
|---|---|---|
| Debian | 13 (trixie), x86-64 | glibc 2.41 / GCC 14: the engine must not need more than the runtime's glibc 2.38 |
| Android SDK | build-tools 35.0.0, platform android-35 | `android.jar`, `d8`, `zipalign`, `apksigner` |
| Android NDK | r27c | native libraries, SDL3, audio relay |
| JDK | 17 or newer | `javac`, `keytool` |
| CMake, Ninja | 3.25 or newer | native builds |
| Python | 3.9 or newer | binary manifest (`tools/axml.py`) |
| zip, unzip | | packaging |
| aarch64 cross toolchain | Debian's `crossbuild-essential-arm64`, clang 21 | the engine (see the fork's `runtime/scripts`) |

Clone with the submodules:

```bash
git clone --recursive https://github.com/<owner>/nba2k14-ps4-android.git
cd nba2k14-ps4-android
```

## 1. Engine

```bash
JOBS=4 engine/build-engine.sh
```

Clones the shadPS4 ARM64 fork at the pinned commit, applies `engine/patches` and builds
`out/shadps4-arm64-fex-NBA`. Allow about 1 hour and 8 GB of RAM with 4 jobs.

## 2. Engine package

The glibc runtime libraries and the Vulkan drivers come from the Bachata S4 0.2.5 APK
(`bachatas4-0.2.5-release.apk`, published by its authors); `libzstd` from Debian's `libzstd1:arm64` package.

```bash
sudo dpkg --add-architecture arm64 && sudo apt update && sudo apt install libzstd1:arm64
BACHATA_APK=/path/to/bachatas4-0.2.5-release.apk \
ENGINE=out/shadps4-arm64-fex-NBA \
ANDROID_NDK=$HOME/android-ndk-r27c \
  runtime/make-runtime.sh
```

Result: `out/runtime.zip` (about 70 MB). It never contains game files or console system files.

## 3. App

```bash
export ANDROID_SDK_ROOT=$HOME/android-sdk ANDROID_NDK=$HOME/android-ndk-r27c
./build.sh                       # debuggable test build, signed with a local debug key
RELEASE=1 KEYSTORE=release.keystore KEY_ALIAS=release KEYSTORE_PASS_FILE=release.pass ./build.sh
```

Result: `out/nba2k14-ps4-android.apk` (release) or `out/nba2k14-ps4-android-debug.apk`. An update installs over
an earlier version only when both are signed with the same key.

## Test builds

A debuggable build reads two files from the app's folder (`Android/data/com.nba2k14.autonome/files`), which a
release build ignores:

- `gpu-essai.txt`: a GPU name (for example `Mali-G715 MC11`) that stands for the real one, to check the DEVICE
  report and the driver choice for chips there is no device for;
- `sans-moteur`: opens the game screen without starting the engine, to try the on-screen pad, the in-game menu and
  the heat banner where the engine can't run (an Android emulator).

See [TESTING.md](TESTING.md).
