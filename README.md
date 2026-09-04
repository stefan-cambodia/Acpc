# Acpc — Amstrad CPC emulator for Android

Acpc emulates the Amstrad CPC 464, 664 and 6128, the CPC 6128 Plus and the
GX4000 console on Android phones and tablets: Z80, gate array, CRTC, PPI,
AY-3-8912 sound, µPD765 floppy controller, `.dsk` disc images, `.cdt` tapes,
`.cpr` cartridges and the Plus ASIC (hardware sprites, 4096 colours, soft
scrolling, split screen, raster interrupt, DMA sound). It is written in Kotlin with a pure JVM
`core` module (the emulation, unit-tested) and an `app` module (Android UI).

## Features

- Boots real Amstrad firmware, runs real games from `.dsk` disc images,
  `.cdt` tapes, `.cpr` cartridges and `.sna` snapshots (plain or zipped).
  Tape loading can run at full emulation speed while the motor turns.
- CPC Plus and GX4000: a game cartridge boots a GX4000 (no ROM needed), the
  6128 Plus boots its system cartridge and runs discs and tapes with the ASIC
  available to programs (Jet Set Willy+ and the like).
- Discs written by games (saved games, high scores) are kept.
- Touch joystick and fire buttons with movable layouts, on-screen CPC keyboard,
  Android soft keyboard for text entry, Bluetooth/USB keyboards and gamepads.
- Game library: local files, remote servers (any HTTP directory index or an
  archive.org collection, browsable and searchable), local cache, favourites.
- Save states, auto-start of the disc program, adjustable speed, scaling modes,
  scanlines, screen orientation.

## Manual

[docs/MANUAL.md](docs/MANUAL.md) is the user manual, with screenshots: ROM
import, the library, remote servers, playing, save states, tapes, cartridges
and every setting.

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
| `system.cpr`  | cartridge | 6128 Plus system cartridge (OS, BASIC, AMSDOS, Burnin' Rubber); only for the 6128 Plus model, GX4000 games boot from their own cartridge |

## Remote servers

The **Remote server** button accepts either the direct URL of a `.dsk`,
`.cdt`, `.cpr`, `.sna` or `.zip` file or the URL of a directory. Directories are listed in the app, with search;
one tap downloads, caches and starts the game. The default entry points to the
[Amstrad CPC game collection by Ghostware](https://archive.org/download/AmstradCPCGameCollectionByGhostware)
on archive.org.

## Layout

- `core/` — emulation (`cpu/z80`, `machine`, `gatearray`, `crtc`, `asic`, `cartridge`, `fdc`, `disk`, `tape`, `keyboard`, `state`) and tests.
- `app/` — Android application (`emulator`, `input`, `video`, `audio`, `storage`, `network`, `ui`).
