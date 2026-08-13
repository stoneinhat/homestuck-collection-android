# homestuck-collection-android

An Android port of The Unofficial Homestuck Collection. Fully offline.
The reader runs in a WebView against a localhost server bundled into the
app, flashes play through Ruffle, and there's an on-screen joystick for
the walkaround games.

This repo doesn't contain the collection itself. The web code belongs to
its authors and the comic belongs to Hussie, so what's here is just the
Android shell, some new files, and a patch against the upstream web fork.
The setup script assembles the rest.

## Setup

Run `setup.cmd` (Windows) or `./setup.sh`. It clones
jennymaeleidig/unofficial-homestuck-collection-web into `base/`, checks
out commit `1cf4339` (the patch is against that commit, later ones may
not work), applies `patches/port.patch`, and copies `overlay/` in.

You also need Asset Pack V2 (~4.3 GB). The collection's website is gone,
but https://stash.giovanh.com/unofficial-homestuck-collection/ lists
mirrors.

## Building

Requires Node 18, yarn, JDK 17, Android SDK 34, and Gradle 8.7.

Run `build.cmd` or `./build.sh`. It builds the web bundle in `base/`,
stages it into the Android project along with the imods, and runs
Gradle.

Release builds need a signing key in `keys/` (gitignored):

```
mkdir keys
keytool -genkeypair -v -keystore keys/release.keystore -storetype PKCS12 \
  -alias tuhc -keyalg RSA -keysize 2048 -validity 10000
```

Put the store password in `keys/keystore_password.txt`. Keep the key;
updates must be signed with the same one or users have to reinstall and
lose their progress.

Heads up if you touch the gradle config: `android/app/build.gradle`
overrides aapt's `ignoreAssetsPattern`. The default pattern skips asset
directories starting with `_`, which is every imod, including the one
containing Ruffle. Removing that block breaks all flash content with no
build error.

## Installing

Copy the asset pack to the phone (or just the zip, the app can extract
it), install the APK, open it, grant file access, and point it at the
pack. The app writes a `.nomedia` into the pack folder so the comic's
images don't show up in your gallery.

A translucent handle on the right edge toggles the touch controls. On
flash pages they appear automatically. The joystick maps to arrow keys
and the two buttons are space and enter. Opacity, size, and position are
under Settings > Touch Controls.

If a flash page shows a red error box instead of content, it names the
file that failed to load and your WebView version. Try updating Android
System WebView before filing an issue.

## Prebuilt APK

Latest dual-screen build (AYN Thor / AYANEO Pocket DS):
https://github.com/stoneinhat/homestuck-collection-android/releases/latest

Download `app-release.apk` from that page. You still need Asset Pack V2;
it is not included. Dual-screen source lives on the `dual-screen` branch.

## License

Original files (the shell, overlay, and scripts) are MIT. The patch
modifies upstream code and stays under upstream's terms.
