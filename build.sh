#!/usr/bin/env bash
# Builds the Android app (APK). See docs/BUILDING.md for the full procedure and requirements.
#
# Environment:
#   ANDROID_SDK_ROOT    Android SDK with build-tools;35.0.0 and platforms;android-35 (required)
#   ANDROID_NDK         NDK r27c (default: $ANDROID_SDK_ROOT/ndk/27.2.12479018)
#   RUNTIME_ZIP         engine package made by runtime/make-runtime.sh (default: out/runtime.zip)
#   RELEASE=1           release build (not debuggable); otherwise a debuggable test build
#   KEYSTORE, KEY_ALIAS, KEYSTORE_PASS_FILE
#                       signing key; without them a local debug key is created in out/
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
out="$root/out"
sdk="${ANDROID_SDK_ROOT:?set ANDROID_SDK_ROOT to your Android SDK}"
ndk="${ANDROID_NDK:-$sdk/ndk/27.2.12479018}"
tools="$sdk/build-tools/35.0.0"
jar="$sdk/platforms/android-35/android.jar"
runtime="${RUNTIME_ZIP:-$out/runtime.zip}"
third="$root/third_party"

for need in "$ndk/build/cmake/android.toolchain.cmake" "$tools/zipalign" "$jar" "$runtime" \
            "$third/SDL/CMakeLists.txt" "$third/imgui/imgui.h" "$third/LibAtrac9/C/src"; do
  [ -e "$need" ] || { echo "missing: $need (see docs/BUILDING.md)" >&2; exit 1; }
done
mkdir -p "$out"

# 1. SDL3, static, for any 64-bit ARM processor (plain ARMv8.0: no LSE atomics), so that the app opens on every
#    arm64 device and can tell the player when theirs can't run the game.
if [ ! -f "$out/sdl/install/lib/libSDL3.a" ]; then
  cmake -S "$third/SDL" -B "$out/sdl/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-march=armv8-a -mno-outline-atomics" \
    -DSDL_SHARED=OFF -DSDL_STATIC=ON -DSDL_TEST_LIBRARY=OFF \
    -DCMAKE_INSTALL_PREFIX="$out/sdl/install" > "$out/sdl-cmake.log"
  cmake --build "$out/sdl/build" -j "$(nproc)" > "$out/sdl-build.log"
  cmake --install "$out/sdl/build" > /dev/null
fi

# 2. Native libraries.
cmake -S "$root/app/src/main/cpp" -B "$out/native" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DTHIRD_PARTY="$third" -DSDL_INSTALL="$out/sdl/install" > "$out/native-cmake.log"
cmake --build "$out/native" -j "$(nproc)" > "$out/native-build.log"
rm -rf "$out/pack" && mkdir -p "$out/pack/lib/arm64-v8a" "$out/pack/assets/polices"
cp "$out/native/liblanceur.so" "$out/native/libecran.so" "$out/pack/lib/arm64-v8a/"
cp "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
  "$out/pack/lib/arm64-v8a/"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$out/pack/lib/arm64-v8a/"*.so

# 3. Java: SDL's activity and the app, then dex.
rm -rf "$out/classes" "$out/dex" && mkdir -p "$out/classes" "$out/dex"
javac -nowarn -Xlint:none -source 8 -target 8 -bootclasspath "$jar" -d "$out/classes" \
  $(find "$third/SDL/android-project/app/src/main/java" "$root/app/src/main/com" -name '*.java') 2>&1 \
  | grep -v '^Note\|^warning\|warnings$' || true
java -cp "$tools/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$jar" --output "$out/dex" \
  $(find "$out/classes" -name '*.class')

# 4. Manifest (binary XML), compiled icon resources, engine package and fonts.
mkdir -p "$out/man"
if [ "${RELEASE:-0}" = 1 ]; then
  grep -v 'android:debuggable' "$root/app/src/main/AndroidManifest.xml" > "$out/AndroidManifest.src.xml"
  name=nba2k14-ps4-android.apk
else
  cp "$root/app/src/main/AndroidManifest.xml" "$out/AndroidManifest.src.xml"
  name=nba2k14-ps4-android-debug.apk
fi
python3 "$root/tools/axml.py" "$out/AndroidManifest.src.xml" "$out/man/AndroidManifest.xml"
cp "$runtime" "$out/pack/assets/runtime.zip"
cp "$root/app/fonts/"* "$out/pack/assets/polices/"

apk="$out/unsigned.apk"
rm -f "$apk"
(cd "$out/man" && zip -q -X "$apk" AndroidManifest.xml)
(cd "$root/app/res-compiled" && zip -q -X -0 "$apk" resources.arsc && zip -q -X -r "$apk" res)
(cd "$out/dex" && zip -q "$apk" classes.dex)
(cd "$out/pack" && zip -q -0 "$apk" assets/runtime.zip && zip -q -r "$apk" lib assets/polices)
"$tools/zipalign" -f -p 4 "$apk" "$out/aligned.apk"

# 5. Signature.
if [ -z "${KEYSTORE:-}" ]; then
  KEYSTORE="$out/debug.keystore" KEY_ALIAS=debug KEYSTORE_PASS_FILE="$out/debug.pass"
  if [ ! -f "$KEYSTORE" ]; then
    echo android > "$KEYSTORE_PASS_FILE"
    keytool -genkeypair -keystore "$KEYSTORE" -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android -dname "CN=Android Debug" > /dev/null 2>&1
  fi
fi
"$tools/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KEY_ALIAS:-key}" --ks-pass "file:$KEYSTORE_PASS_FILE" \
  --min-sdk-version 26 --out "$out/$name" "$out/aligned.apk"
"$tools/apksigner" verify "$out/$name"
ls -l "$out/$name"
