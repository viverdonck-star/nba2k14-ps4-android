# Architecture

The app is a self-contained Android launcher around a PS4 emulator. It needs no root, no Termux and no X server:
the engine runs as a child process of the app and hands its frames, sound and input over local channels.

```
 Android app (com.nba2k14.autonome)                         engine process (glibc, arm64)
 ┌──────────────────────────────────────────────┐          ┌────────────────────────────────┐
 │ Accueil (menu, SDL3 + Dear ImGui)            │          │ shadPS4 + FEX (x86-64 → ARM64) │
 │   game folder picker, settings, DEVICE check │          │   NBA 2K14 (your copy)         │
 │                                              │  spawn   │                                │
 │ JeuActivity (":game" process)                ├─────────►│   Vulkan → Turnip / PanVK      │
 │   SurfaceView  ◄── frames (memfd + socket) ───┼──────────┤   frame export                 │
 │   Manette / Tactile ── pad packets (UDP) ────┼─────────►│   loopback pad bridge          │
 │   heat watch (PowerManager)                  │          │   audio ──► sonrelay ──► AAudio│
 └──────────────────────────────────────────────┘          └────────────────────────────────┘
```

## Processes

- **Menu** (`Accueil`, `liblanceur.so`): an SDL3 activity drawing a Dear ImGui menu. It picks the game folder through
  Android's folder picker (no storage permission), checks the files against the expected sizes, plays the game's
  own menu music (ATRAC9, decoded with LibAtrac9) and holds the settings.
- **Game** (`JeuActivity`, process `:game`): starts the engine, shows its frames, forwards input, watches the heat.
  The process ends with the game; the menu stays.
- **Engine** (`host/shadps4-arm64-fex-NBA`): started through the bundled glibc loader with `posix_spawn`, so that it
  inherits the game files' descriptors. It sees the game folder as links to `/proc/self/fd/<n>`.
- **Sound relay** (`sonrelay`): receives PCM on a unix socket and plays it with AAudio.

## Channels

| Channel | From → to | How |
|---|---|---|
| Frames | engine → `libecran.so` | Vulkan image copied into a shared memory ring (`memfd`), slot numbers on a unix socket; drawn on the `SurfaceView` at the display's refresh, which also paces the engine (`NBA2K14_FLIP_SYNC`). |
| Input | app → engine | 16-byte pad state on UDP `127.0.0.1:27014`: buttons, sticks and triggers, from a gamepad (`Manette`) and the on-screen pad (`Tactile`) combined. |
| Sound | engine → `sonrelay` | unix socket, 8-channel mix down to stereo in the engine. |
| Messages | game process → menu | a file in the app's folder, shown by the menu when it comes back (an Android toast would die with the process). |

## Engine options set by the app

| Variable | Value | Effect |
|---|---|---|
| `NBA2K14_FRAME_SOCKET` | socket path | export frames to the app instead of a window |
| `NBA2K14_FLIP_MS` | 25 or 33 | frame spacing: 40 or 30 frames per second |
| `NBA2K14_FLIP_SYNC` | 1 | new frames wait for a tick from the Android display |
| `NBA2K14_FLIP_INTERVAL` | 1 or 2 | vblank interval |
| `NBA2K14_MSAA_MAX` | 1 or 2 | cap of the host MSAA sample count |
| `NBA2K14_HLE_MEMORY` | 0 | the game's own libc memory routines run translated (faster than host calls here) |
| `VK_ICD_FILENAMES` | `tmp/icd.json` | the Vulkan driver chosen for this device |
| `TU_DEBUG` | sysmem | Turnip draws in system memory (its tile-memory path corrupts this game's picture) |
| `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER` | 1 | lets PanVK run on Mali generations it calls unfinished |

## Device adaptation

- **Driver** (`Pilotes`): the GPU name is read from a throw-away OpenGL context. Adreno 8xx gets Turnip gen8, Adreno
  7xx Turnip 7xx, Adreno 6xx Turnip 26.1, Mali Valhall and later PanVK. Settings can force another one.
- **Frame rate** (`Compatibilite.cadenceConseillee`): 40 only on an Adreno 830 or newer with a 120 Hz screen
  (three refreshes per frame); 30 everywhere else, the only even rate on 60 and 90 Hz screens.
- **Heat** (`JeuActivity.surveillerChaleur`): Android's thermal status and headroom, the signals of the Android
  Dynamic Performance Framework. Above *severe* the display paces the engine at 30 frames per second, with a banner,
  until the device has been cool for a minute; above *critical* the player is told to pause.
- **Screen**: 16:9 picture with black bars or stretched; display cutouts are kept clear of the on-screen pad; the
  in-game menu scrolls on short screens.
- **Input**: known gamepads use Android's mapping; unknown ones that report Linux's raw axes (right stick on RX/RY)
  are detected from their axis ranges.
- **Compatibility report** (`Compatibilite`): processor, GPU, memory, page size, screen and planned driver, shown on
  the DEVICE screen and saved with the exported logs.

## What is not in the app

The app contains no game data and no PlayStation system software. The player provides their own NBA 2K14 (PS4)
folder and, for sound, the `libSceNgs2.sprx` audio module from their own console, which the app copies into the
engine's system modules on first start.
