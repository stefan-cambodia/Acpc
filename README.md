# Acpc — Amstrad CPC emulator for Android

Acpc emulates the Amstrad CPC 464, 664 and 6128, the CPC 6128 Plus and the
GX4000 console on Android phones and tablets: Z80, gate array, CRTC, PPI,
AY-3-8912 sound, µPD765 floppy controller, `.dsk` disc images, `.cdt` tapes,
`.cpr` cartridges and the Plus ASIC (hardware sprites, 4096 colours, soft
scrolling, split screen, raster interrupt, DMA sound). It is written in Kotlin with a pure JVM
`core` module (the emulation, unit-tested) and an `app` module (Android UI).

## Features

- Boots real Amstrad firmware, runs real games from `.dsk` disc images,
  `.cdt` tapes, `.cpr` cartridges (or raw `.bin` dumps) and `.sna` snapshots
  (plain or zipped). Tape loading can run at full emulation speed while the
  motor turns.
- Starts games by itself: the disc catalogue and the AMSDOS file headers tell
  which file is the loader (`RUN"…`, or `|CPM` for CP/M games); a tape gets
  `RUN"` on a machine without disc ROM, as on a 464 of the day.
- CPC Plus and GX4000: a game cartridge boots a GX4000 (no ROM needed), the
  6128 Plus boots its system cartridge and runs discs and tapes with the ASIC
  available to programs. A disc or tape written for the Plus (Jet Set Willy+,
  Fluff) is recognised and started on a 6128 Plus.
- Multi-disc games and multi-side tapes: the in-game **Change disc…** /
  **Change tape…** offers the other discs or sides of the set, from the
  library or downloaded from the server the game came from.
- Discs written by games (saved games, high scores) are kept.
- Touch joystick and fire buttons with movable layouts, on-screen CPC keyboard,
  Android soft keyboard for text entry, Bluetooth/USB keyboards and gamepads.
- Game library: local files, remote servers (any HTTP directory index or an
  archive.org collection, browsable and searchable), local cache, favourites.
- Save states, auto-start of the disc program, adjustable speed, scaling modes,
  scanlines, screen orientation.

## Documentation

- [docs/MANUAL.md](docs/MANUAL.md): the user manual, with screenshots: ROM
  import, the library, remote servers, playing, save states, tapes,
  cartridges and every setting.
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md): what was tested and how.
  About 700 titles went through the automated harnesses: 453 disc images
  (well-known 1984-1999 games, then random picks of the whole collection),
  185 tapes, 38 cartridge images (all 26 GX4000 games, homebrew, Plus
  firmware) and 20 productions from 2011-2020 (Pinball Dreams, Batman
  Forever, R-Type 128K...), with every failure traced to its cause.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the emulator is built,
  chip by chip, and the test and diagnostic tools.

## Building

Open the project in Android Studio (AGP 9.3, compileSdk 37) or run:

```
./gradlew :app:assembleDebug
./gradlew :core:test
```

The debug APK is produced in `app/build/outputs/apk/debug/`. The unit tests
run in seconds and need nothing outside the repository. The integration
harnesses (real firmware, game batches) need your ROMs and games and only
run with `-PslowTests`; see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

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

- `core/` — emulation (`cpu/z80`, `machine`, `memory`, `gatearray`, `crtc`, `asic`, `cartridge`, `ppi`, `ay`, `fdc`, `disk`, `tape`, `snapshot`, `keyboard`, `joystick`, `state`) and tests.
- `app/` — Android application (`emulator`, `input`, `video`, `audio`, `storage`, `network`, `ui`).
