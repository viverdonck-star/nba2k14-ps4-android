# Changelog

## 0.2 — 2026-11-25

First public release.

### Game
- NBA 2K14 (PS4, CUSA00104) offline: quick matches, MyCAREER, saves kept across restarts.
- shadPS4 ARM64 engine with FEX, patched for the game: fixed-address executable, NGS2 audio, offline system
  services, RGB565 and macro-tiled textures, MSAA on Adreno drivers, 8-channel to stereo downmix.
- Frames shown on the device's display rhythm: 40 frames per second on the reference device.

### App
- Standalone: no root, no Termux, no X server; the game folder is picked with Android's folder picker.
- Menu with the game's own cover art and music, in French and English: play, settings, saves, controls, device,
  about.
- **DEVICE** screen: processor, graphics chip, memory, page size, screen and planned driver, with a verdict; the
  report is included in the exported logs.
- A Vulkan driver per GPU family: Turnip gen8 (Adreno 8xx), Turnip 7xx, Turnip 26.1 (Adreno 6xx), PanVK (Mali,
  experimental), with a manual choice in Settings.
- Frame rate per device: 40 only with an Adreno 830 or newer and a 120 Hz screen, 30 elsewhere.
- Heat watch from Android's thermal status and headroom: 30 frames per second while too hot, warnings.
- Gamepads (Xbox, PlayStation, 8BitDo, built-in) with button remapping, including gamepads that report Linux's raw
  axes; on-screen PS4 pad with size and opacity settings, clear of camera cutouts.
- Picture format: black bars or full screen.
- Saves export and import as a zip; logs export for bug reports.
- The PS4 audio module is never bundled: the app installs `libSceNgs2.sprx` from the player's game folder.
- Opens on every 64-bit ARM device (unsupported ones get an explanation); native libraries aligned for 16 KB pages.
