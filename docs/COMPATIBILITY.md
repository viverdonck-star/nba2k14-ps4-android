# Device compatibility

The app's **DEVICE** screen checks the device it runs on and says whether the game should run, may run, or can't.

## Requirements

| | Needed | Why |
|---|---|---|
| Android | 8.0 (API 26) or newer | |
| Processor | 64-bit ARM (arm64-v8a) | the engine and its x86-64 translator are arm64 programs |
| Graphics | Qualcomm Adreno 6xx, 7xx, 8xx; or ARM Mali Valhall or newer (experimental) | the engine is a glibc program and can't load Android's own Vulkan drivers; it brings open-source Mesa drivers, which exist for these GPUs only |
| Memory | 12 GB recommended, 8 GB at least | the PS4 had 8 GB; with less the system closes the game |
| Storage | about 45 GB | the game folder (about 40 GB) and the engine (about 250 MB once installed) |

## Graphics chips

| Chip | Examples | Driver | Expectation |
|---|---|---|---|
| Adreno 830 and newer | Snapdragon 8 Elite (AYN Odin 3, Galaxy S25) | Turnip gen8 | **tested**: 40 frames per second, full matches |
| Adreno 735 – 750, 8xx below 830 | Snapdragon 8 Gen 2 / 8 Gen 3, 8s Gen 3 | Turnip 7xx | should run; 30 frames per second advised |
| Adreno 640 – 732 | Snapdragon 855 – 8 Gen 1, 7-series | Turnip 7xx or 26.1 | likely too slow for a fluid match |
| Adreno below 640 | older Snapdragon | — | not supported |
| Mali Valhall and newer (G57, G68, G77, G78, G310 and up, Immortalis) | MediaTek Dimensity, Google Tensor G1 – G4 | PanVK (experimental) | not tested with this game; PanVK has run other PS4 games on a Mali-G615 |
| Older Mali (Bifrost, Midgard), PowerVR, Samsung Xclipse | | — | no driver; the game can't start |

If the game does not start or the picture is damaged, **Settings → Graphics driver** offers the other drivers.

## Screens

- 40 frames per second needs a 120 Hz screen to be even (three refreshes per frame). On 60 and 90 Hz screens the
  app uses 30, the only even rate there.
- The picture is 16:9: black bars on longer screens, or stretched (**Settings → Picture format**).
- The on-screen pad keeps clear of camera cutouts and can be resized and made fainter or bolder.

## Controls

- Gamepads: Xbox, PlayStation (DualShock 4, DualSense), 8BitDo and other Android gamepads, Bluetooth or USB. The
  on-screen pad hides itself while one is connected.
- On-screen pad: left stick (floating), D-pad, Cross / Circle / Square / Triangle, right stick, L1 / L2 / R1 / R2,
  touchpad, Options, and **≡** for the app's menu. Several fingers at once.

## Reporting

**SAVES → Export the log** makes a zip of the game's and the menu's logs, with the DEVICE report and the driver used.
