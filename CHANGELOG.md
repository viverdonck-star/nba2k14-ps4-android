# Changelog

## 0.14 — 2026-11-25

Built for running on many Android devices, not only the reference one.

### Added
- **DEVICE** screen: processor, graphics chip, memory, memory page size, screen and planned driver, with a verdict
  (should run / may run / can't run). The report is included in the exported logs.
- Vulkan driver per GPU family: Turnip gen8 (Adreno 8xx), Turnip 7xx, Turnip 26.1 (Adreno 6xx), PanVK (Mali,
  experimental), with a manual choice in Settings.
- On-screen PS4 pad for devices without a gamepad: floating left stick, D-pad, face buttons, right stick,
  shoulders, touchpad, Options and the app menu; size and opacity settings; hidden while a gamepad is connected.
- Heat watch from Android's thermal status and headroom: 30 frames per second while too hot, warnings.
- Picture format setting: black bars or full screen.
- Support for gamepads that report Linux's raw axes (right stick on RX/RY).
- The game's error messages ("couldn't start", "stopped by itself") are shown on the menu.

### Changed
- Default frame rate follows the device: 40 only with an Adreno 830 or newer and a 120 Hz screen, 30 elsewhere.
- The app opens on every 64-bit ARM processor (SDL3 built for ARMv8.0), so unsupported devices get an explanation
  instead of a crash.
- Native libraries aligned for 16 KB memory pages.
- The in-game menu scrolls on short screens and keeps clear of camera cutouts.
- The PS4 audio module is no longer bundled: the app installs `libSceNgs2.sprx` from the player's game folder.

### Removed
- Two unused trial Vulkan drivers (30 MB).

## 0.13 — 2026-11-15
- Menu music plays the game's songs in random order.

## 0.9 — 2026-11-02
- French and English menu.

## 0.8 — 2026-11-02
- New menu (SDL3 and Dear ImGui) with the game's own art and music; game folder picker instead of a storage
  permission; the game in a process of its own.

## 0.5 – 0.7 — 2026-11-01
- Standalone app: no Termux or X server. Frames shown on the device's display rhythm (40 frames per second).
- Full matches, saves kept across restarts, physical gamepad, side menu.
