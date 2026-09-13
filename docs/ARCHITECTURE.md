# Acpc architecture

Acpc is split in two Gradle modules with a strict dependency direction:
`app` depends on `core`, `core` depends on nothing but the Kotlin standard
library. Everything that emulates hardware lives in `core` and runs on a plain
JVM, which is what makes the test suite fast and the Android layer thin.

```
app  (Android: UI, audio output, storage, network)
 └── core  (pure Kotlin/JVM: Z80, memory, Gate Array, CRTC, Plus ASIC, PPI, AY,
           FDC, DSK, CDT tapes, cartridges, snapshots, save states)
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
PPI, `&FB7E/F` FDC) and runs the emulation frame by frame. Two debugging
hooks, null in normal operation, let tools observe it: `instructionHook`
(before every instruction) and `ioWriteHook` (every OUT).

Clock model: the Z80 counts T-states at 4 MHz; the Gate Array stretches every
memory and I/O access to a 1 µs boundary, so instruction lengths are multiples
of 4 T-states ("NOPs"). The video pipeline (CRTC + Gate Array) is advanced one
microsecond at a time before every instruction and before every I/O access,
so register writes land on the exact microsecond the real hardware would see
them. The AY and the FDC are caught up lazily on access and at frame end.
Constants are in `core/timing/CpcTiming`.

`CpcModel` (464 / 664 / 6128 / 6128 Plus / GX4000) carries the RAM size, the
firmware file, the manufacturer id read through the PPI and whether the
machine is a Plus. `CrtcType` selects the register masks and read-back rules
of the emulated CRTC variant.

Plus machines take a `Cartridge` instead of a `RomSet`: the system cartridge
of the 6128 Plus or a GX4000 game. The `Asic` object holds the Plus-only
state; the Gate Array, the memory and the machine consult it when present, so
a classic CPC pays nothing for it.

### Chips

| Package      | Class           | Notes |
|--------------|-----------------|-------|
| `cpu/z80`    | `Z80`           | Full instruction set including undocumented opcodes and flags (X/Y, MEMPTR), IM 0/1/2, R register, HALT, with CPC wait-state timing. Talks to the machine through the `Z80Bus` interface. Validated with per-instruction tests and the zexdoc/zexall exercisers. |
| `memory`     | `CpcMemory`     | 64 KB base RAM plus 64 KB expansion on the 6128, lower ROM, upper ROMs (BASIC 0, AMSDOS 7), Gate Array RMR ROM enables, the eight 6128 RAM configurations (bits 3-5, the bank of a memory expansion, only count when more than 128 KB is fitted, as with the 6128's own decoder). On a Plus the ROMs are cartridge pages (RMR2 chooses the lower one and where it sits; ROM 7 = page 3, ROMs 128-159 = pages 0-31, others = page 1), RAM shows at &C000 until the first write to the ROM select port, and the ASIC I/O page replaces block 1 when mapped. |
| `gatearray`  | `GateArray`     | Pen and border colours (`CpcPalette`), modes 0/1/2 latched at HSYNC, pixel generation from CRTC addresses, 300 Hz interrupt generator (52-line counter, VSYNC reset, bit 5 clear on acknowledge), monitor model that turns sync signals into a stable raster (vertical hold: a VSYNC is obeyed after 262 lines of sweep, the picture flies back by itself after 340). With an ASIC: 12-bit palette, soft scroll (horizontal delay, vertical raster offset, border extension), split screen (`Crtc.splitAt`), programmable raster interrupt replacing the 52-line one, DMA step at every HSYNC end, hardware sprites drawn over each display line, ASIC vector on interrupt acknowledge. |
| `asic`       | `Asic`, `PlusProgram` | `PlusProgram` recognises a disc or tape written for the Plus by the unlock sequence it carries. Plus ASIC: 17-byte unlock sequence on the CRTC select port, RMR2 (cartridge page and position of the lower ROM, I/O page at &4000), the I/O page (sprite pixels and attributes, 32-entry palette, PRI, SPLT, SSCR, IVR, analogue inputs, three DMA channels and DCSR), DMA instruction set (LOAD, PAUSE with prescaler, REPEAT/LOOP, INT, STOP), interrupt priorities and vectors (raster 6, DMA 0/1/2 = 4/2/0). |
| `cartridge`  | `Cartridge`     | `.cpr` RIFF container (`AMS!`, `cbNN` chunks) or raw dump (a `.bin` of 64 to 512 KB is recognised as one): up to 32 pages of 16 KB, page numbers wrapping on the cartridge size, system cartridge detection. |
| `crtc`       | `Crtc`          | 6845 counters, display enable, HSYNC / VSYNC generation with programmable widths, vertical total adjust, MA/RA generation, type-dependent behaviour. The "last line of the row" and "last row of the frame" comparisons are latched when a counter reaches its register or when the register is written, as on the real chips, so split-screen "ruptures" that rewrite R4, R6, R7 and R9 mid-frame behave like the hardware (counter overflow at 127, row counter reset on a changed R4, rows counted during the vertical adjust). |
| `ppi`        | `Ppi8255`       | Ports A (AY data), B (VSYNC, manufacturer id, 50 Hz, cassette in), C (keyboard line, AY control, cassette motor), modes and bit set/reset. |
| `ay`         | `Ay38912`       | Three tone channels, noise, envelope, logarithmic DAC, 125 kHz stepping box-filtered to the output rate, stereo A-left / C-right, port A wired to the keyboard matrix. |
| `keyboard`   | `KeyboardMatrix`, `KeyTyper`, `KeyQueue` | 10 × 8 matrix scanned through PPI port C and read through AY port A; `KeyTyper` injects text over several frames for auto-start; `KeyQueue` paces front-end key events in frames (minimum hold and release) so 50 Hz scanners never miss one. |
| `joystick`   | `JoystickState` | Joystick 0 on matrix line 9, joystick 1 on line 6, so games see them exactly as on a real machine. |
| `fdc`        | `Upd765`, `FloppyDrive` | uPD765A command/execution/result phases, rotation timing, overrun handling for copy-protected loaders, deleted data and CRC status from the image. Heads step one cylinder per step-rate period of the controller's clock, so a SEEK repeated while the head travels carries on from where it is. `accessCount` (seeks and transfers) and `traceListener` (commands and results) serve the test tools. |
| `disk`       | `DiskImage`, `AmsdosCatalog` | Standard and extended DSK parsing and export (an extended image whose track size table is empty is read from its track headers), AMSDOS catalogue listing with each file's header (type, load and entry address), format detection. Auto-start ranks the files by what they are (BASIC, binary with an entry address, headerless BASIC listing) before their names (`DISC`, `LOADER`, the game's name or initials); a system disc without an AMSDOS directory boots with `|CPM`. |
| `state`      | `StateCodec`    | Tagged binary save-state format (tag, length, payload), deflate-compressed, tolerant of unknown sections. |
| `tape`       | `CdtFormat`, `Tape` | CDT/TZX tape images turned into an edge timeline in CPC cycles (blocks 10-15, 20-28, 2A-35, loops), with 3 s of silent leader in front of an image whose signal starts at once; `Tape` plays it against the CPU clock while the PPI motor relay is on and feeds PPI port B bit 7, so both the firmware loader and custom loaders work. |
| `snapshot`   | `SnaFormat`     | The standard CPC `.sna` snapshot format, versions 1 to 3 (including v3 compressed `MEM` chunks): loads into a reset machine through the chips' normal write paths, and writes version 2 snapshots. |

### Tests — `core/src/test`

Unit tests run in seconds with nothing outside the repository: the Z80
(instruction semantics and CPC timing), memory configurations, the CRTC
counters and the monitor model (`VideoTimingTest`), the ASIC, cartridges,
DSK parsing and auto-start, CDT tapes, snapshots, the keyboard matrix and
key queue.

Integration tests boot the real firmware, read the screen back as text
(`ScreenReader`), load discs, check sound output, and run the compatibility
harnesses over folders of games. They need ROMs and games from outside the
repository; their locations are Gradle properties (`-PromDir`,
`-PtestDiskDir`, `-PtapeDir`, `-PcartDir`, `-PcompatOut`) and they only run
with `-PslowTests`:

- `CompatibilityRunTest`: boots every disc, types the auto-start command,
  sends key "nudges" when the drive is idle, can swap discs, and saves a
  screenshot every 10 s.
- `TapeIntegrationTest`: loads every tape through the firmware at full speed
  (`-PtapeModel` chooses the machine).
- `CartridgeIntegrationTest`: boots every cartridge on a GX4000 (a system
  cartridge on a 6128 Plus) and reports ASIC use.
- `PrintCatalogsTest`: prints each disc's catalogue, file headers and
  auto-start choice.

Diagnostic tools, driven by environment variables and doing nothing without
them: `TapeTraceTest` follows a tape loader bit by bit; `DiscTraceTest`
boots a disc or a cartridge, types keys, and records what led to an event:
the last instructions and control transfers (with ROM pages) before a chosen
address, every CRTC and Gate Array write row by row, VSYNC spacing, disc
controller commands, changes to a watched memory range, RAM at the stop.
[COMPATIBILITY.md](COMPATIBILITY.md) shows them in use.

## app

| Package     | Role |
|-------------|------|
| `emulator`  | `EmulatorSession` owns a running machine: the emulation thread, pacing (audio-clock paced when sound is on, wall-clock otherwise, with a speed factor), auto-start scheduling and statistics. `EmulatorHolder` keeps it alive across activities. `GameLauncher` builds a session for a library entry and chooses the machine: the per-game model, the model a snapshot records, a GX4000 or 6128 Plus for a cartridge, a 6128 Plus for a Plus program, otherwise the default; a tape boots without the disc ROM. |
| `video`     | `CpcSurfaceView` presents frames with the chosen scaling (fit, integer, stretch, pixel perfect), optional scanlines and filtering. |
| `audio`     | `AndroidAudioSink` streams the AY output to a blocking `AudioTrack`; the blocking write paces the emulation thread on the audio clock, which keeps sound and video in sync without drift. |
| `input`     | `JoystickOverlayView` (8-way stick, fire buttons, extra keys, drag-to-move edit mode, `OverlayLayout` profiles), `VirtualKeyboardView` (full 6128 layout, multi-touch, sticky SHIFT/CTRL), `KeyMapper` (Android key events to CPC keys, character-aware for symbol keys), `GamepadMapper`. Typed keys from all three sources go through the core's `KeyQueue`, which applies them at frame boundaries with minimum hold and release times, so the timing of touch taps and key events never depends on how the emulation thread batches its frames. |
| `storage`   | `GameLibrary` (index of imported discs, tapes, cartridges and snapshots, cache of downloads, save-state files, ZIP extraction), `RomStore` (ROMs recognised by content hash), `DiscSet` (the other discs or sides of a set, from collection names such as "(Disk 1 of 2)", "(Side A)", "Side_A", and their URLs next to the downloaded file). |
| `network`   | `HttpDownloader` (bounded, cancellable, redirect-following download), `RemoteCatalog` (directory listing from an HTTP index or the archive.org metadata API, cached on disk). |
| `settings`  | `AppSettings`: typed access to the preferences. |
| `ui`        | `LibraryActivity` (games, search, favourites, remote dialog), `RemoteBrowserActivity` (searchable server listing), `EmulatorActivity` (display, overlays, quick bar, in-game menu with disc and tape changes, physical input), `SettingsActivity`, `KeyMappingActivity`, `RomSetupActivity`. |

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
is model-specific; the Plus models show how a whole extra chip plugs in
through one optional object.
