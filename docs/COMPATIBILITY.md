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
| Jet Set Willy+ (jswplus) | crashes after loading | CPC Plus program: writes the ASIC registers (&6800-&6805) and uses RMR2 (gate array values with bit 5 set); the Plus is not emulated |
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
ends or the motor stays off for 12 s, and saves screenshots to
`compatOut/tapes`. Results with the "Amstrad CPC CDT Collection" on
archive.org (UK originals):

| Tape | Result |
|------|--------|
| Arkanoid, Barbarian (both sides), Batman, Bubble Bobble (both sides), Chuckie Egg, Commando, Cybernoid, Dizzy Dice, Elite, Ghosts 'n Goblins, Harrier Attack, Head over Heels, Jet Set Willy, Nemesis, Oh Mummy (two releases), Renegade, Rick Dangerous, Robocop, Roland on the Ropes, Sorcery, Target Renegade side A, Ninja Grannies | loads to the title or menu; Speedlock-style loaders with border stripes included |
| Target Renegade side B | "Read error b": a level-data tape, not meant to be started with `RUN"` |
| Manic Miner (MAD re-release), Gryzor, Fruity Frank, Le Monde | first part loads, then the motor stays off: to be checked (waiting for a key, or a loader detail) |
