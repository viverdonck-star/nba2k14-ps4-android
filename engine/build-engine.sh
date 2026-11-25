#!/usr/bin/env bash
# Builds the engine: the shadPS4 ARM64 fork at its pinned base, with this repository's patches applied.
# Run in an aarch64-cross capable Debian 13 (trixie) environment: the binary must not need a newer glibc than the
# runtime's (2.38 at most). See docs/BUILDING.md.
#
# Environment:
#   ENGINE_DIR   where to clone the fork (default: out/shadps4-arm64)
#   JOBS         parallel build jobs (default: 4; the full build needs about 2 GB of RAM per job)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
dir="${ENGINE_DIR:-$root/out/shadps4-arm64}"
upstream=https://github.com/zenithblue-oss/shadps4-arm64.git
base=be6bc2e9c60799e071dd2fafa6216e8d80ec619c

if [ ! -d "$dir/.git" ]; then
  git clone "$upstream" "$dir"
  git -C "$dir" checkout -q "$base"
  git -C "$dir" submodule update --init --recursive --depth 1
  git -C "$dir" -c user.name="nba2k14-android" -c user.email="nba2k14-android@users.noreply.github.com" \
    am -q --committer-date-is-author-date "$root"/engine/patches/*.patch
fi

cd "$dir"
# The fork's own build script, with the NBA 2K14 compatibility layer switched on.
sed 's|-DENABLE_FEX_GUEST_CPU=ON \\|-DENABLE_FEX_GUEST_CPU=ON -DENABLE_NBA_2K14_COMPATIBILITY=ON \\|' \
  runtime/scripts/build-shadps4-arm64.sh > runtime/scripts/.build-nba2k14.sh
grep -q ENABLE_NBA_2K14_COMPATIBILITY=ON runtime/scripts/.build-nba2k14.sh
CMAKE_BUILD_PARALLEL_LEVEL="${JOBS:-4}" bash runtime/scripts/.build-nba2k14.sh

binary=runtime/build/shadps4-arm64/shadps4
echo "glibc versions needed (2.38 at most):"
aarch64-linux-gnu-readelf -V "$binary" | grep -oE 'GLIBC(XX)?_[0-9.]+' | sort -Vu | tail -2
mkdir -p "$root/out"
cp -f "$binary" "$root/out/shadps4-arm64-fex-NBA"
sha256sum "$root/out/shadps4-arm64-fex-NBA"
