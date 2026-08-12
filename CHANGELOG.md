# Changelog

All notable changes to this Android port are documented here.

## Unreleased

### Fixed

- Top screen no longer gets a grey wash when the dual-screen companion
  appears. `Presentation` inherits Dialog dimming (`FLAG_DIM_BEHIND`);
  that dim is now disabled via theme + window flags so it cannot veil
  the primary Activity.
- Panel (top) dual-screen CSS drops the MSPA grey page chrome behind
  comic panels and applies a slight zoom-out so larger Flash stages fit
  more comfortably.

## 2.5.7-android-ds1

### Added

- Dual-screen support for devices that expose a secondary presentation
  display (AYN Thor, AYANEO Pocket DS, and similar): story panels/Flash
  on the top screen, narrative text and page nav on the bottom, kept in
  sync through a JS bridge.
- Release signing path fixes in `build.cmd` for producing signed APKs.
