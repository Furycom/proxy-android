# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased] — v1.1 (planned)

### Planned
- Smoother animations: a higher, steadier frame rate on every screen.
- Major visual polish across the whole interface.
- Session history & statistics: daily/weekly/monthly/yearly totals with charts, streaks, favorite time of day, average duration and temperature — stored on your phone only.

## [1.0] — 2026-11-07

First public release.

### Added
- Automatic Bluetooth Low Energy connection to the Proxy, with memory of the paired device and automatic reconnection.
- Home screen with the four heat modes (color and temperature), battery level and puff counter.
- Start, stop, Boost and power-off controls; live session screen with gauge and temperature.
- Cleaning cycle that stops automatically halfway, and a full-cleaning reminder every 100 puffs.
- Lights: brightness, stealth mode and lantern.
- "My buttons" page mapping each physical gesture to an on-screen button.
- Neon Canvas interface with hexagon textures and animations.
- Foreground service that keeps the connection alive in the background.
- Support for Android 10 and up (min SDK 29, target SDK 34); tested on Android 10 (Huawei P30) and Android 17.

### Privacy
- No `INTERNET` permission, no data collection, app backups disabled.

### Known issues
- The lantern switch always shows "off" in the app, even when the lantern is on. A fix is planned for v1.1.

[Unreleased]: https://github.com/Furycom/proxy-android/compare/v1.0...HEAD
[1.0]: https://github.com/Furycom/proxy-android/releases/tag/v1.0
