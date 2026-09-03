# Acpc — Amstrad CPC emulator for Android

Acpc emulates the Amstrad CPC 464, 664 and 6128 on Android phones and
tablets: Z80, gate array, CRTC, PPI, AY-3-8912 sound, µPD765 floppy
controller and `.dsk` disc images. It is written in Kotlin with a pure JVM
`core` module (the emulation, unit-tested) and an `app` module (Android UI).

## Features

- Boots real Amstrad firmware, runs real games from `.dsk` disc images and
  `.sna` snapshots (plain or zipped).
- Touch joystick and fire buttons with movable layouts, on-screen CPC keyboard,
  Android soft keyboard for text entry, Bluetooth/USB keyboards and gamepads.
- Game library: local files, remote servers (any HTTP directory index or an
  archive.org collection, browsable and searchable), local cache, favourites.
- Save states, auto-start of the disc program, adjustable speed, scaling modes,
  scanlines, screen orientation.

## Building

Open the project in Android Studio (AGP 9.3, compileSdk 37) or run:

```
./gradlew :app:assembleDebug
./gradlew :core:test
```

The debug APK is produced in `app/build/outputs/apk/debug/`.

## ROMs

The Amstrad ROMs are not included. Import your own copies from the app
(**ROMs** button or Settings → Amstrad ROMs); they are recognised by content:

| File          | Size  | Purpose                      |
|---------------|-------|------------------------------|
| `cpc464.rom`  | 32 KB | OS + BASIC 1.0 (CPC 464)     |
| `cpc664.rom`  | 32 KB | OS + BASIC 1.1 (CPC 664)     |
| `cpc6128.rom` | 32 KB | OS + BASIC 1.1 (CPC 6128)    |
| `amsdos.rom`  | 16 KB | AMSDOS, required for discs   |

## Remote servers

The **Remote server** button accepts either the direct URL of a `.dsk`,
`.sna` or `.zip` file or the URL of a directory. Directories are listed in the app, with search;
one tap downloads, caches and starts the game. The default entry points to the
[Amstrad CPC game collection by Ghostware](https://archive.org/download/AmstradCPCGameCollectionByGhostware)
on archive.org.

## Layout

- `core/` — emulation (`cpu/z80`, `machine`, `fdc`, `disk`, `keyboard`, `state`) and tests.
- `app/` — Android application (`emulator`, `input`, `video`, `audio`, `storage`, `network`, `ui`).
