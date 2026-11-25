# Third-party notices

The app and its engine package include the following components. Each keeps its own licence; the full texts are
in the linked projects and, for the engine, in the fork's `LICENSES` folder.

## Engine (in the APK's engine package)

| Component | Licence | Source |
|---|---|---|
| shadPS4, ARM64 fork, with this project's patches | GPL-2.0-or-later | [zenithblue-oss/shadps4-arm64](https://github.com/zenithblue-oss/shadps4-arm64), [engine/](engine/) |
| FEX (FEXCore), x86-64 to ARM64 translator, built into the engine | MIT | [FEX-Emu/FEX](https://github.com/FEX-Emu/FEX) |
| The engine's own third-party libraries | various (see the fork's `LICENSES` and `externals`) | as above |

## Vulkan drivers

Mesa builds for glibc, as distributed with Bachata S4 0.2.5.

| Driver | Licence | Source |
|---|---|---|
| Turnip gen8, Turnip mojo 26.1 and 25.0 (Adreno) | MIT | [whitebelyash/mesa-unified](https://github.com/whitebelyash/mesa-unified) |
| Turnip vauzi 7xx (Adreno 7xx) | MIT | [Vauzi-17/mesa-tu8](https://github.com/Vauzi-17/mesa-tu8) |
| PanVK V3 (Mali) | MIT | [Mesa](https://gitlab.freedesktop.org/mesa/mesa), build notes in the driver's `SOURCE.json` |

## glibc runtime (engine package, `host/`)

From the Bachata S4 0.2.5 runtime, itself built from Debian packages, and Debian's `libzstd1`.

| Libraries | Licence |
|---|---|
| glibc (`ld-linux-aarch64`, `libc`, `libm`, `libdl`, `libpthread`, `librt`, `libresolv`, `libutil`) | LGPL-2.1-or-later |
| `libstdc++`, `libgcc_s` | GPL-3.0 with the GCC Runtime Library Exception |
| `libX11`, `libX11-xcb`, `libXau`, `libXcursor`, `libXdmcp`, `libXext`, `libXfixes`, `libXi`, `libXrandr`, `libXrender`, `libXss`, `libxcb*`, `libxshmfence`, `libxkbcommon`, `libwayland-client`, `libdrm`, `libexpat`, `libffi` | MIT / X11 |
| `libvulkan` (Vulkan loader) | Apache-2.0 |
| `libSPIRV-Tools` | Apache-2.0 |
| `libsystemd`, `libudev`, `libcap`, `libdbus-1`, `libuuid` | LGPL-2.1-or-later (libcap: BSD-3-Clause or GPL-2.0; libdbus: AFL-2.1 or GPL-2.0-or-later; libuuid: BSD-3-Clause) |
| `libz` | Zlib |
| `libzstd` | BSD-3-Clause |

## App

| Component | Licence | Source |
|---|---|---|
| SDL 3.4.14 | Zlib | [libsdl-org/SDL](https://github.com/libsdl-org/SDL) (submodule) |
| Dear ImGui 1.92.5 | MIT | [ocornut/imgui](https://github.com/ocornut/imgui) (submodule) |
| LibAtrac9 | MIT | [shadps4-emu/ext-LibAtrac9](https://github.com/shadps4-emu/ext-LibAtrac9) (submodule) |
| stb_image 2.29 | public domain / MIT | [nothings/stb](https://github.com/nothings/stb) (`third_party/stb`) |
| Oswald font | SIL Open Font License 1.1 | [Google Fonts](https://fonts.google.com/specimen/Oswald) (`app/fonts/OFL.txt`) |

## Not included

No NBA 2K14 game data and no PlayStation system software is part of this repository or of its releases. The menu
shows the cover art and plays the music of the player's own game folder at run time.
