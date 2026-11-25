#!/usr/bin/env bash
# Assembles the engine package the app installs on first start (runtime.zip, an APK asset):
#
#   host/              glibc runtime: the loader and libraries, from Bachata S4 0.2.5's runtime, plus libzstd
#   host/shadps4-arm64-fex-NBA   the engine (engine/build-engine.sh)
#   turnip/            Mesa Turnip gen8, the default Vulkan driver (Adreno 8xx)
#   pilotes/           the other Vulkan drivers: Turnip 7xx, Turnip 26.1 and 25.0, PanVK (Mali)
#   sonrelay           the audio relay (tools/sonrelay), built with the NDK
#   home/.local/share/shadPS4/   default engine settings (runtime/defaults)
#
# No game file and no console system file is ever part of it.
#
# Environment:
#   BACHATA_APK   Bachata S4 0.2.5 APK (bachatas4-0.2.5-release.apk), source of the runtime libraries and drivers
#   ENGINE        the engine binary built by engine/build-engine.sh
#   LIBZSTD       an aarch64 glibc libzstd.so.1 (default: Debian's, from the libzstd1:arm64 package)
#   ANDROID_NDK   NDK r27c, for the audio relay
#   OUT           output file (default: out/runtime.zip)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
apk="${BACHATA_APK:?set BACHATA_APK to bachatas4-0.2.5-release.apk}"
engine="${ENGINE:?set ENGINE to the engine binary}"
zstd="${LIBZSTD:-/usr/lib/aarch64-linux-gnu/libzstd.so.1}"
ndk="${ANDROID_NDK:?set ANDROID_NDK}"
output="${OUT:-$root/out/runtime.zip}"
work="$root/out/runtime-work"

rm -rf "$work" && mkdir -p "$work/src" "$work/rt"
unzip -q "$apk" assets/runtime/runtime.zip 'assets/drivers/*' -d "$work/src"
unzip -q "$work/src/assets/runtime/runtime.zip" 'host/*' -d "$work/src/runtime"

# glibc runtime, without Bachata's own engine and test tools.
mkdir -p "$work/rt/host"
find "$work/src/runtime/host" -maxdepth 1 -type f ! -name 'fexcore-*' ! -name 'shadps4-arm64-fex*' \
  -exec cp -p {} "$work/rt/host/" \;
cp -L "$zstd" "$work/rt/host/libzstd.so.1"
cp "$engine" "$work/rt/host/shadps4-arm64-fex-NBA"

# Vulkan drivers (Mesa, MIT).
unzip_driver() { mkdir -p "$2" && unzip -q -o "$work/src/assets/drivers/$1.zip" -d "$2"; }
unzip_driver turnip-gen8-EMULATOR "$work/rt/turnip"
rm -f "$work/rt/turnip/meta.json"
cp "$root/runtime/NOTICE-Mesa.txt" "$work/rt/turnip/NOTICE.txt"
unzip_driver turnip-vauzi-7xx-EMULATOR "$work/rt/pilotes/turnip-7xx"
unzip_driver turnip-26.1.0-EMULATOR "$work/rt/pilotes/turnip-26.1"
unzip_driver turnip-mojo-25.0-EMULATOR "$work/rt/pilotes/turnip-25.0"
unzip_driver Bachata-PanVK-V3-glibc "$work/rt/pilotes/panvk"
cp "$root/runtime/NOTICE-Mesa.txt" "$work/rt/pilotes/NOTICE-Mesa-MIT.txt"

# Audio relay (an Android program: AAudio).
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" -O2 \
  -o "$work/rt/sonrelay" "$root/tools/sonrelay/sonrelay.c" -ldl

# Default engine settings; the player's saves are created on the device.
mkdir -p "$work/rt/home/.local/share/shadPS4/home"
cp "$root/runtime/defaults/config.json" "$root/runtime/defaults/users.json" "$work/rt/home/.local/share/shadPS4/"

mkdir -p "$(dirname "$output")" && rm -f "$output"
(cd "$work/rt" && zip -q -r -9 "$output" .)
ls -l "$output"
