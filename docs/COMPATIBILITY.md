# Compatibility log

Results of the batch harness (`CompatibilityRunTest`: boot a CPC 6128, insert
the disc, type the AMSDOS auto-start command, run 40 s, dump screenshots).
"State at 40 s" is what the screen showed when the run stopped. A game still
in its loader at 40 s is not a failure: AMSDOS reads one sector per disc
revolution on discs whose sectors are stored in physical order, exactly like
the real machine, so big games need 40 to 90 s to load at real speed (the
"Fast disc drive" setting removes those delays).

Discs come from the archive.org collection reachable from the app, unless
noted. Loads were also checked with an instruction trace where a game looked
stuck (`CpcMachine.instructionHook` lets a test observe every instruction).
Run with:

```
./gradlew :core:test -PslowTests --tests '*CompatibilityRunTest*' \
    -PtestDiskDir=/path/to/discs -PcompatOut=/path/to/output
```

| Title | State at 40 s | Notes |
|-------|---------------|-------|
| Arkanoid II - Revenge of Doh | tape loader prompt | disc is a tape-to-disc transfer that expects tape input |
| Barbarian (Palace) | menu | |
| Bomb Jack | title screen | |
| Bubble Bobble [cr Enrique Soft] | crack intro, waits for a key | |
| Chase H.Q. | loading (screen cleared after loading picture) | starts at ~96 s, 128 KB load |
| Commando | loading (screen cleared after loading picture) | starts at ~44 s |
| Dizzy III - Fantasy World Dizzy | title screen | |
| Elite | "Load New Commander" prompt | |
| Fruity Frank | title screen | |
| Gryzor | title screen, still loading rest | |
| Harrier Attack | high score / skill prompt | |
| Head over Heels [t +4] | trainer prompt | |
| Manic Miner | title screen | |
| Nemesis | player select | |
| Oh Mummy | title screen | |
| Prehistorik 2 | monitor synchronisation screen | |
| Prince of Persia | story screen | |
| Renegade | title screen | |
| Rick Dangerous II | title, then text screen | |
| Robocop | high score table | |
| Sorcery+ | high score table | |
| Boulder Dash (bdash) | "Loading..." | long load |
| Columns CPC | menu | |
| Cyber Power | title, "press space" | |
| Jet Set Willy+ (jswplus) | crashes after loading on a 6128 | CPC Plus program (ASIC registers, RMR2): runs on the 6128 Plus model, which the launcher now picks by itself (see Cartridges below) |
| Robotron 2084 | title screen | |
| Sean McManus collection 2024 | menu | |
| Space: Above and Beyond | title | |
| Zaxon | menu text | |
| DAMS (arkanoi3) | assembler prompt | not a game |

## Past the title screen

The harness sends "nudges" every six seconds while the drive is idle,
cycling through a key list up to three times, so a prompt that appears late
still gets its key. Keys are held for 12 frames (a human tap; the 3-frame
press of the text typer is too short for some games, Manic Miner among
them). Per-disc files: `<name>.nudges` (comma-separated tokens: `SPACE`,
`RETURN`, `ENTER`, `FIRE`, a letter or a digit; `token@25` forces it at that
second, once), `<name>.nonudge`, `<name>.hold` (frames a nudged key is held, default 12), `<name>.play` (after the nudges, wave the
joystick and fire so sprites, scrolling and collisions run), `<name>.secs`,
`<name>.cmd`, `<name>.464`, `<name>.swap` (`second:file.dsk` lines, to put
the second disc of a set in the drive). The drive counts as idle when its
motor is off or when it has not seeked or read for three seconds, since
some games (Prehistorik 2, Super Cauldron) keep the motor running at a
prompt.

With 90 s runs, gameplay was reached and rendered correctly in Bomb Jack
(attract mode after the trainer prompts), Commando, Dizzy III, Elite
(commander screen), Fruity Frank, Harrier Attack, Manic Miner, Nemesis,
Prince of Persia, Rick Dangerous II and Robocop. Bubble Bobble and Oh Mummy
reached their option screens; Barbarian and Gryzor were still loading their
second part at 70 s (loading pictures drawn progressively, drive active).

Games verified interactively on a phone (played, not only booted): Live and
Let Die (Domark), Sean McManus collection programs.

## Wide sweep: 203 discs

A second batch took 208 well-known titles from the archive.org collection
(1984-1999, UK, French, Spanish and German releases, originals, cracks and
trainers; 5 were cartridges) and ran the 203 discs for 90 s, then the
stragglers for 180 s. Contact sheets of the last screenshot of each game
were checked by eye, and every screenshot of a run was compared with the
run before each change, so that a fix could not silently break another
game.

177 reached a title screen, a menu, a trainer prompt or gameplay on their
own at the first pass. What stopped the other 26, and what came of it:

| Title | Symptom | Cause | Outcome |
|-------|---------|-------|---------|
| Barbarian II, Bomb Jack II, Super Wonderboy, Vindicators, Nigel Mansell's Grand Prix | back to BASIC (or a reset) right after `RUN"` | auto-start picked a file by name: a binary whose entry address is 0 (`LOADER.BIN`), while the loader was `DISC.BIN` (a protected BASIC program) | auto-start reads the AMSDOS headers; all five now reach their menus or gameplay |
| North & South | `RUN"s6<<<<<<.<<<` "Bad command" | CP/M game on a custom format: the "directory" was track data | no AMSDOS sectors where the directory should be: boots with `|CPM` |
| Turrican | two copies of the HUD over garbage | the game lowers R4 below the row counter; the CRTC ended the frame instead of letting the counter wrap at 127, which put the game's two-frame split out of phase | CRTC counters compare for equality and wrap; title and gameplay correct |
| Thunder Blade | HUD missing in play | two CRTC frames per picture, each with its own VSYNC; the monitor model obeyed both | monitor vertical hold (a VSYNC is obeyed after 262 lines, free run after 340): HUD below the play area |
| Out Run | race screen stays black | the stage table is read from disc 2 after the menu; the disc 2 image has an empty track size table (CPDRead 3.24) and loaded as blank | tolerant DSK reader; with disc 2 inserted before choosing "1", the race runs |
| Chip's Challenge, Puffy's Saga | "START TAPE", black screen | wait for their second disc ("rewind to the start of side 2") | work with disc 2 in the drive; the in-game **Change disc…** offers and downloads it |
| Prehistorik 2, Super Cauldron | monitor synchronisation screen | harness: no nudge while the motor ran | gameplay once nudged |
| Zap't'Balls | "Bad command" | harness: types junk into the level file name prompt | runs; the prompt wants RETURN |
| Double Dragon II, Ghouls'n Ghosts, Myth, Trantor, Zynaps, Chase H.Q., Galactic Games | black or static screen at 90 s | long decrunching or loading | menus or gameplay by 120-180 s |
| Mercs | screen of garbage at 80-110 s | shows memory while it decrunches | reaches its control menu |
| Hero Quest - Return of the Witchlord | "Cannot find .EMS file" | an expansion that needs the Hero Quest disc | not a fault |
| Ghostbusters II, Shadow of the Beast | "Turn disk over", "Insert disc side 2" | second disc needed | not a fault |
| Fire and Forget | black | the image is flagged `[b]` (bad dump) | not a fault |

## Modern productions and the CRTC

Twenty 2011-2020 productions archived on archive.org (Mojon Twins and
usebox games, R-Type 128K, Sonic GX, Batman Group's Pinball Dreams and the
Batman Forever megademo) test the video hardware harder than 1980s games.
Nearly all ran at once. Pinball Dreams did not: its table and score panel
are two CRTC frames per picture whose R4, R7 and R9 are rewritten while the
beam runs, and the row counter overflowed to 127 where the real chip resets
it, so VSYNC came every 243 and 974 lines and the picture broke up. The
CRTC now latches its "last line" and "last row" comparisons the way the
HD6845S and UM6845R do (see ARCHITECTURE.md). Pinball Dreams plays with a
stable table, a Batman Forever part that showed noise now draws its red sky
and perspective grid, and over the 423 discs and 38 cartridges of the
sweeps only four pictures changed: the Fast Food Dizzy crack intro and the
Mercs title lost their garbage, the other two are animation timing.

| Production | State |
|------------|-------|
| Cheril of the Bosque, Nanako, Uwol 2, Cheman, Phantomas 2.0, Profanation 2, Operation Alexandra, Golden Tail, Magica, Space Pest Control, The Dawn of Kernel (disc and GX4000 cartridge), The Return of Traxtor, R-Type 128K | title or gameplay |
| Pinball Dreams | table and score panel, scrolling (after the CRTC change) |
| Batman Forever | runs through its parts from the startup screen (press a key) to the end scroller |
| Battro | an intro that returns to BASIC when a key is pressed, as written |
| Sonic GX (GX4000) | title with parallax, then a crash at 17 s: a RET pops a table pointer left on the stack by its interrupt-driven main loop. Probably interrupt timing (the ASIC raster interrupt fires at the end of HSYNC here); open |
| The Sword of Ianna (512 KB ROM) | black: a ROM for a ROM board, not a cartridge |

## Second sweep: 220 more discs

A random sample of 220 other titles from the same collection (every genre:
text adventures, sports, puzzles, budget games, French and Spanish
releases, a few 1990s homebrews) went through the same 90 s run. The
first pass reached a title, a menu, a prompt or gameplay for 207 of them.
The others:

| Title | Symptom | Cause | Outcome |
|-------|---------|-------|---------|
| The Krypton Factor | CP/M boot hangs | the boot loader repeats SEEK and SENSE INTERRUPT STATUS until the seek is reported done; each SEEK restarted the seek delay | heads step on the controller's own step-rate clock; boots to the contestant screen |
| International 3D Tennis | black after the title on a 6128 | the 128K loader writes RAM configurations with the expansion bank bits set; they picked a nonexistent bank | the 6128 ignores those bits as the real decoder does; menu with the 128K season mode |
| Winter Games | "File type error" | auto-start ran `RECORDS.BIN`, a header of type &F1 | headers with a non-zero version nibble are not programs; `DISK.BIN` runs, ski jump reached |
| Fer & Flamme | "Improper argument in 20" | auto-start ran `DEF1.BAS`, a sub-program | a loader named after the title's initials (`F&F.BAS`) is preferred; title menu reached |
| Fluff | black screen, crash | a CPC Plus game (unlocks the ASIC) | runs on the 6128 Plus; the launcher now picks it for discs carrying the ASIC unlock sequence |
| Arctic Fox | "Syntax error in 60141" | the first bytes of `ARTICFOX.BAS` are corrupt in this dump | not a fault |
| Passagers du Vent 2, Exit, Ice Guardian, Histoire d'Or, Dick Tracy, Maffia, Zelda | "insert side B", long loads | second disc or long loading | not a fault |

## Tapes

`TapeIntegrationTest` (slow) loads every `.cdt` in `~/.acpc/tapes` (Gradle
property `tapeDir`) on a CPC 464 without disc ROM (Gradle property
`tapeModel`, or `<name>.6128`; `<name>.ddi` adds AMSDOS): `RUN"`, a
key for "Press PLAY then any key", then runs at full speed until the tape
ends or the motor stays off for 30 s, and saves screenshots to
`compatOut/tapes`. The idle limit has to be that long: BASIC loaders draw
their loading picture with the motor off for up to 20 s before they load the
next file (Fruity Frank, Manic Miner, Le Monde). Results with the "Amstrad
CPC CDT Collection" on archive.org (UK originals):

| Tape | Result |
|------|--------|
| Arkanoid, Barbarian (both sides), Batman, Bubble Bobble (both sides), Chuckie Egg, Commando, Cybernoid, Dizzy Dice, Elite, Ghosts 'n Goblins, Harrier Attack, Head over Heels, Jet Set Willy, Nemesis, Oh Mummy (two releases), Renegade, Rick Dangerous, Robocop, Roland on the Ropes, Sorcery, Target Renegade side A, Ninja Grannies | loads to the title or menu (Renegade into the game); Speedlock loaders with border stripes included. Multi-load tapes (Arkanoid, Renegade, Robocop, Sorcery, Target Renegade) stop at their menu with the tape half read, as they should |
| Manic Miner (MAD re-release), Fruity Frank, Le Monde | title, speed menu, intro: the BASIC loader draws for 13 to 20 s with the motor off, then loads the rest |
| Target Renegade side B | "Read error b": a level-data tape, not meant to be started with `RUN"` |
| Gryzor | the Speedlock loader wipes memory at the end of its second block: defective image, see below |

### A random 157 tapes

157 first sides picked at random from the whole CDT collection (4675
archives: games, compilations, educational software, 2018-2023 CPCRetroDev
entries) loaded on a 464. All but seven reached a title, a menu or a prompt;
six of them exposed two faults:

| Tape | Symptom | Cause | Outcome |
|------|---------|-------|---------|
| Darkula 64, Stryfe, The Flintstones | nothing loads, "Found STRYFE block 2", "Read error b" | the image starts its first pilot tone within 0.4 s; the firmware lets the motor come up to speed for a while before it listens, and missed most or all of a short 2000-baud leader | images whose signal starts in the first 3 s get 3 s of leader tape in front, as a real cassette has; all three load |
| Punchy (two releases), Football Manager 3 | "Memory full in 10", back to BASIC | the test machine had AMSDOS, whose buffers lower the memory ceiling | tapes run without the disc ROM, in the app as in the harness; Punchy asks for the monitor type, Football Manager 3 reaches its menu |
| Nibbler (Mosaik) | "Syntax error in 1" | `ON BREAK CONT` is BASIC 1.1: a 6128 program | runs on the 6128 |

The same 157 tapes on a 6128 without disc ROM (the app's default model
for a tape) load as on the 464; compilations and 128K versions (Shadow
Warriors 128K, Operation Thunderbolt) read further.

### Gryzor: a mistimed image

`TapeTraceTest` (a diagnostic, driven by environment variables, see its
KDoc) showed where Gryzor fails. The Ocean Speedlock loader reads a bit by
counting iterations of a 15 µs polling loop between two edges and compares
the count with a fixed threshold (`LD A,&C0 / CP H`, counter started at
&A1: a "1" needs more than 31 iterations). Bubble Bobble and Robocop, which
load, use the very same loader with data pulses of 753/1508 TZX T-states:
a "1" then counts about 46 iterations and a "0" about 18, the threshold
sits in the middle. The Gryzor image carries the same loader but its data
blocks are written with the Spectrum Speedlock values, 565/1130 (4/3
shorter), while its pilot, sync and separator pulses are the same canonical
values as in the other two tapes. A "1" then counts 32 to 34 iterations,
one above the threshold; the first bit after the 208th sub-block separator,
where the loader has more work between edges, counts exactly 31 and reads
as 0. The loader's XOR checksum of the block fails and it wipes memory,
which is the black screen. The image's own XOR over every block is zero,
so the bytes are right and only the timing is wrong; a real machine would
fail on such a signal too. Nothing to fix in the emulator: Z80 timings
(`Z80CpcTimingTest`) and the edge stream are correct.

## Cartridges (CPC Plus, GX4000)

`CartridgeIntegrationTest` (slow) boots every `.cpr` in `~/.acpc/carts` (Gradle
property `cartDir`): a game cartridge on a GX4000, a system cartridge on a
6128 Plus. It presses the pad's button 1 (the CPC's fire 2, matrix line 9
bit 4) every 6 s, runs `cartSeconds` (default 40) and saves screenshots and
a report (ASIC unlocked, sprites, DMA, interrupt state) to `compatOut/carts`.
Run with:

```
./gradlew :core:test -PslowTests --tests '*CartridgeIntegrationTest*' \
    -PcartDir=/path/to/carts -PcompatOut=/path/to/output -PcartSeconds=40
```

Results with the cartridges from the archive.org GX4000 collections:

| Cartridge | State at 40 s | ASIC use |
|-----------|---------------|----------|
| Pang | in the game (Mt Fuji stage) | sprites, PRI, split, IM 2 vectors |
| Robocop 2 | in the game | sprites, PRI, RMR2 paging |
| Navy Seals | in the game | sprites, PRI, DMA music, 4096-colour title and score table |
| Batman the Movie | in the game | 12-bit palette |
| Klax | in the game | 12-bit palette |
| Plotting | in the game (two-player screen) | sprites, PRI |
| Burnin' Rubber (GX4000 cartridge) | in the game (race start) | sprites, DMA music, RMR2 paging |
| Switchblade | animated title, then sprites | sprites, PRI |
| Relentless, zblast SD (homebrew) | menus | none: plain CPC programs on a cartridge |
| Barbarian II, Copter 271, Crazy Cars 2, Dick Tracy, The Enforcer, Fire and Forget 2, Mystical, Operation Thunderbolt, Panza Kick Boxing, Pro Tennis Tour, Skeet Shoot, Super Pinball Magic, Tennis Cup 2, Tintin on the Moon, Wild Streets, World of Sports | in the game or at its menu (raw `.bin` dumps of the full 26-cartridge GX4000 set) | sprites, PRI and split in most, DMA sound in Copter 271 |
| No Exit | title with its mirrored logo, then the fight | calls a subroutine before setting its stack pointer: needs RAM at &C000 until the first ROM select (see below) |
| System cartridge (Caprice32 `system.cpr`) | "f1 Amstrad BASIC / f2 Burnin' Rubber" menu, BASIC 1.1 after f1 | 6128 Plus firmware |
| Parados 1.2+ (French firmware replacement) | BASIC prompt | 6128 Plus firmware; its French keyboard layout makes the auto-typed commands come out wrong (`RUN2DISC:BQS`) |

With the system cartridge, `CompatibilityRunTest` boots a 6128 Plus for a
disc when a `<name>.plus` file names the cartridge (default `system.cpr` in
`cartDir`); it presses f1 at the boot menu before typing the command. Jet
Set Willy+ then loads and reaches its first room, "The Bathroom".

Details found while bringing these up:

- A Plus shows RAM at &C000 from power-on until the first write to the ROM
  select port. No Exit's first CALL runs with the stack pointer still at
  &FFFF and returns through RAM there (the cartridge is known not to start
  on a Plus with a ROM board, whose ROM would answer); World of Sports
  selects a ROM before jumping into it, so both run.

- The keyboard must stay readable when AY register 7 sets port A as an
  output (Pang leaves the mixer at &FF and polls the joystick line); the AY
  now returns the input pins for register 14 in both directions, as MAME does.
- The pad's button 1 is the CPC's "fire 2" (matrix line 9 bit 4), the button
  the standard joystick and most games use; the on-screen overlay's main
  button and the gamepad's A button send that bit.
