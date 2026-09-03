# Acpc architecture

Acpc is split in two Gradle modules with a strict dependency direction:
`app` depends on `core`, `core` depends on nothing but the Kotlin standard
library. Everything that emulates hardware lives in `core` and runs on a plain
JVM, which is what makes the test suite fast and the Android layer thin.

```
app  (Android: UI, audio output, storage, network)
 └── core  (pure Kotlin/JVM: Z80, memory, Gate Array, CRTC, PPI, AY, FDC, DSK)
```

## core

### Public API — `core/api`

`Emulator` is the only entry point a front-end needs. It knows nothing about
Android: video comes out as an ARGB `VideoFrame`, audio goes to an
`AudioSink`, input comes in as CPC matrix keys (`CpcKey`) and joystick
buttons, discs and states are byte arrays. `CpcEmulator` implements it around
a `CpcMachine` and serialises the control methods (disk insertion, reset,
save/load state) against `runFrame()`, so they can be called from any thread.
Input methods are lock-free.

### Machine — `core/machine`

`CpcMachine` wires the chips together, decodes the I/O address space
(`&7Fxx` Gate Array, `&BCxx`-`&BFxx` CRTC, `&DFxx` ROM select, `&F4xx`-`&F7xx`
PPI, `&FB7E/F` FDC) and runs the emulation frame by frame.

Clock model: the Z80 counts T-states at 4 MHz; the Gate Array stretches every
memory and I/O access to a 1 µs boundary, so instruction lengths are multiples
of 4 T-states ("NOPs"). The video pipeline (CRTC + Gate Array) is advanced one
microsecond at a time before every instruction and before every I/O access,
so register writes land on the exact microsecond the real hardware would see
them. The AY and the FDC are caught up lazily on access and at frame end.
Constants are in `core/timing/CpcTiming`.

`CpcModel` (464 / 664 / 6128) carries the RAM size, the ROM set, the
manufacturer id read through the PPI and the default CRTC. `CrtcType` selects
the register masks and read-back rules of the emulated CRTC variant.

### Chips

| Package      | Class           | Notes |
|--------------|-----------------|-------|
| `cpu/z80`    | `Z80`           | Full instruction set including undocumented opcodes and flags (X/Y, MEMPTR), IM 0/1/2, R register, HALT, with CPC wait-state timing. Talks to the machine through the `Z80Bus` interface. Validated with per-instruction tests and the zexdoc/zexall exercisers. |
| `memory`     | `CpcMemory`     | 64 KB base RAM plus 64 KB expansion on the 6128, lower ROM, upper ROMs (BASIC 0, AMSDOS 7), Gate Array RMR ROM enables, the eight 6128 RAM configurations and bank selection. |
| `gatearray`  | `GateArray`     | Pen and border colours (`CpcPalette`), modes 0/1/2 latched at HSYNC, pixel generation from CRTC addresses, 300 Hz interrupt generator (52-line counter, VSYNC reset, bit 5 clear on acknowledge), monitor model that turns sync signals into a stable raster. |
| `crtc`       | `Crtc`          | 6845 counters, display enable, HSYNC / VSYNC generation with programmable widths, vertical total adjust, MA/RA generation, type-dependent behaviour. |
| `ppi`        | `Ppi8255`       | Ports A (AY data), B (VSYNC, manufacturer id, 50 Hz, cassette in), C (keyboard line, AY control, cassette motor), modes and bit set/reset. |
| `ay`         | `Ay38912`       | Three tone channels, noise, envelope, logarithmic DAC, 125 kHz stepping box-filtered to the output rate, stereo A-left / C-right, port A wired to the keyboard matrix. |
| `keyboard`   | `KeyboardMatrix`, `KeyTyper` | 10 × 8 matrix scanned through PPI port C and read through AY port A; `KeyTyper` injects text over several frames for auto-start. |
| `joystick`   | `JoystickState` | Joystick 0 on matrix line 9, joystick 1 on line 6, so games see them exactly as on a real machine. |
| `fdc`        | `Upd765`, `FloppyDrive` | uPD765A command/execution/result phases, seek and rotation timing, overrun handling for copy-protected loaders, deleted data and CRC status from the image. |
| `disk`       | `DiskImage`, `AmsdosCatalog` | Standard and extended DSK parsing and export, AMSDOS catalogue listing, format detection and auto-start command selection. |
| `state`      | `StateCodec`    | Tagged binary save-state format (tag, length, payload), deflate-compressed, tolerant of unknown sections. |

### Tests — `core/src/test`

Unit tests for the Z80 (instruction semantics and CPC timing), memory
configurations, keyboard matrix and DSK parsing run in seconds. Integration
tests boot the real firmware, read the screen back as text (`ScreenReader`),
load discs, check sound output and run a compatibility batch over a folder of
games. Those need ROMs and disc images from outside the repository; their
locations are Gradle properties (`-PromDir`, `-PtestDiskDir`, ...) and the
long ones only run with `-PslowTests`.

## app

| Package     | Role |
|-------------|------|
| `emulator`  | `EmulatorSession` owns a running machine: the emulation thread, pacing (audio-clock paced when sound is on, wall-clock otherwise, with a speed factor), auto-start scheduling and statistics. `EmulatorHolder` keeps it alive across activities. `GameLauncher` builds a session for a library entry. |
| `video`     | `CpcSurfaceView` presents frames with the chosen scaling (fit, integer, stretch, pixel perfect), optional scanlines and filtering. |
| `audio`     | `AndroidAudioSink` streams the AY output to a blocking `AudioTrack`; the blocking write paces the emulation thread on the audio clock, which keeps sound and video in sync without drift. |
| `input`     | `JoystickOverlayView` (8-way stick, fire buttons, extra keys, drag-to-move edit mode, `OverlayLayout` profiles), `VirtualKeyboardView` (full 6128 layout, multi-touch, sticky SHIFT/CTRL), `KeyMapper` and `PhysicalKeyQueue` (Android key events to CPC keys, character-aware for symbol keys), `GamepadMapper`. |
| `storage`   | `GameLibrary` (index of imported discs, cache of downloads, save-state files, ZIP extraction), `RomStore` (ROMs recognised by content hash). |
| `network`   | `HttpDownloader` (bounded, cancellable, redirect-following download), `RemoteCatalog` (directory listing from an HTTP index or the archive.org metadata API, cached on disk). |
| `settings`  | `AppSettings`: typed access to the preferences. |
| `ui`        | `LibraryActivity` (games, search, favourites, remote dialog), `RemoteBrowserActivity` (searchable server listing), `EmulatorActivity` (display, overlays, quick bar, in-game menu, physical input), `SettingsActivity`, `KeyMappingActivity`, `RomSetupActivity`. |

### Threads

- **Emulation thread** (`EmulatorSession.runLoop`): calls `Emulator.runFrame()`,
  hands the frame to the surface view and the samples to the audio sink.
  Pausing parks the thread on a condition; the activity pauses it whenever it
  shows a dialog or goes to the background, and writes an autosave state.
- **Main thread**: UI, input. Key and joystick changes go straight into the
  keyboard matrix (lock-free); disc, reset and state operations take the
  emulator lock between two frames.
- **IO** (coroutines / worker threads): downloads, listing fetches, imports.

### Adding a CPC variant

Add a `CpcModel` entry (RAM size, ROM set, manufacturer id), teach `RomStore`
the ROM hashes, and if the video chip differs add a `CrtcType`. Nothing else
is model-specific.
