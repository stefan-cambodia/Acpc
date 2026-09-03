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

The harness also "nudges" a game whose picture has been static for three
seconds while the drive is idle (Space, Fire, Return, "1", Fire, Space in
turn; `<name>.nonudge` disables it). With 70 s runs this reached actual
gameplay, rendered correctly, in Dizzy III, Harrier Attack, Nemesis, Prince
of Persia and Robocop; Bubble Bobble, Fruity Frank, Bomb Jack and Rick
Dangerous II reached their option menus; Commando reached its credits
screen; Barbarian and Gryzor were still loading their second part at 70 s
(loading pictures drawn progressively, drive active).

Games verified interactively on a phone (played, not only booted): Live and
Let Die (Domark), Sean McManus collection programs.
