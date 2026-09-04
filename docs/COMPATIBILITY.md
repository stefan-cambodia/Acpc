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
| Jet Set Willy+ (jswplus) | crashes after loading on a 6128 | CPC Plus program (ASIC registers, RMR2): boot it on the 6128 Plus model, where it runs (see Cartridges below) |
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
`<name>.cmd`, `<name>.464`.

With 90 s runs, gameplay was reached and rendered correctly in Bomb Jack
(attract mode after the trainer prompts), Commando, Dizzy III, Elite
(commander screen), Fruity Frank, Harrier Attack, Manic Miner, Nemesis,
Prince of Persia, Rick Dangerous II and Robocop. Bubble Bobble and Oh Mummy
reached their option screens; Barbarian and Gryzor were still loading their
second part at 70 s (loading pictures drawn progressively, drive active).

Games verified interactively on a phone (played, not only booted): Live and
Let Die (Domark), Sean McManus collection programs.

## Tapes

`TapeIntegrationTest` (slow) loads every `.cdt` in `~/.acpc/tapes` (Gradle
property `tapeDir`) on a CPC 464: `|TAPE` when AMSDOS is present, `RUN"`, a
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
| System cartridge (Caprice32 `system.cpr`) | "f1 Amstrad BASIC / f2 Burnin' Rubber" menu, BASIC 1.1 after f1 | 6128 Plus firmware |
| Parados 1.2+ (French firmware replacement) | BASIC prompt | 6128 Plus firmware; its French keyboard layout makes the auto-typed commands come out wrong (`RUN2DISC:BQS`) |

With the system cartridge, `CompatibilityRunTest` boots a 6128 Plus for a
disc when a `<name>.plus` file names the cartridge (default `system.cpr` in
`cartDir`); it presses f1 at the boot menu before typing the command. Jet
Set Willy+ then loads and reaches its first room, "The Bathroom".

Two details found while bringing these up:

- The keyboard must stay readable when AY register 7 sets port A as an
  output (Pang leaves the mixer at &FF and polls the joystick line); the AY
  now returns the input pins for register 14 in both directions, as MAME does.
- The pad's button 1 is the CPC's "fire 2" (matrix line 9 bit 4), the button
  the standard joystick and most games use; the on-screen overlay's main
  button and the gamepad's A button send that bit.
