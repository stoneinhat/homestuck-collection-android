# Changelog

All notable changes to this Android port are documented here.

## Unreleased

### Fixed

- Top screen no longer gets a grey wash when the dual-screen companion
  appears. `Presentation` inherits Dialog dimming (`FLAG_DIM_BEHIND`);
  that dim is now disabled via theme + window flags so it cannot veil
  the primary Activity. The companion is also non-cancelable and
  `FLAG_NOT_TOUCH_MODAL`, so a tap on the top screen is not treated as
  an "outside" dialog touch (the "greys out like it's selected" report).
- Top-screen comic panels are contained in the 1920×1080 viewport
  instead of fit-to-width. The collection's `width=650` viewport plus
  WebView `loadWithOverviewMode` was scaling panels to full width and
  cropping them vertically; dual-screen mode now uses device-width and
  scales each panel/Flash stage to fit.

## 2.5.7-android-ds1

### Added

- Dual-screen support for devices that expose a secondary presentation
  display (AYN Thor, AYANEO Pocket DS, and similar): story panels/Flash
  on the top screen, narrative text and page nav on the bottom, kept in
  sync through a JS bridge.
- Release signing path fixes in `build.cmd` for producing signed APKs.
