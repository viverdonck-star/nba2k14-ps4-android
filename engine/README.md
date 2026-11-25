# Engine

The game runs on **shadPS4**, a PlayStation 4 emulator, in its ARM64 fork with an embedded
[FEX](https://github.com/FEX-Emu/FEX) x86-64 translator:

- upstream: [zenithblue-oss/shadps4-arm64](https://github.com/zenithblue-oss/shadps4-arm64)
- base commit: `be6bc2e9c60799e071dd2fafa6216e8d80ec619c`
- licence: GPL-2.0-or-later

This folder holds everything that differs from that base, as 40 patches in `git am` format, and the script that
builds the engine binary from them (`build-engine.sh`). The binary shipped in the app's release is built from
exactly this source.

## Patches

| Patches | Purpose |
|---|---|
| 0001 – 0008 | NBA 2K14 compatibility: fixed-address executable mapping, NGS2 audio fallback, offline system services, RGB565 and macro-tiled textures, MyCAREER save owner and first-match guard. Enabled with the `ENABLE_NBA_2K14_COMPATIBILITY` build option. |
| 0009 – 0021 | Rendering fixes (tiled texture synchronisation, mip and slice pitch validation, uniform alignment) and opt-in diagnostics used while porting. |
| 0022 – 0040 | Android integration: MSAA on Adreno drivers without multisampled storage images, loopback pad input, 8-channel to stereo downmix, real guest timestamp rate, frame pacing and export of frames to an Android surface. |

Most patches only change behaviour when an environment variable or build option asks for it, so the engine stays
usable for other games. The options the app sets are listed in [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Building

```bash
engine/build-engine.sh
```

It clones the fork at the base commit with its submodules, applies the patches and runs the fork's own build
script with the NBA 2K14 compatibility layer on. The result is `out/shadps4-arm64-fex-NBA`. Requirements and
timings are in [docs/BUILDING.md](../docs/BUILDING.md).
