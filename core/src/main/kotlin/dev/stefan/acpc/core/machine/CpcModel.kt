package dev.stefan.acpc.core.machine

/**
 * The Amstrad CPC models supported by the emulator.
 *
 * The three classic models share the same chipset (Z80A @ 4 MHz, Gate Array,
 * 6845 CRTC, 8255 PPI, AY-3-8912). They differ by:
 *  - the amount of RAM (64 KB or 128 KB),
 *  - the firmware/BASIC ROM revision,
 *  - the presence of a built-in disc controller (664 / 6128).
 *
 * The Plus range (6128 Plus, GX4000 console) replaces the Gate Array and
 * the CRTC by the ASIC, which adds hardware sprites, a 4096-colour palette,
 * soft scrolling, split screens, a raster interrupt and DMA sound. Their
 * firmware comes from a cartridge instead of ROM chips: the system
 * cartridge of the 6128 Plus, or the game cartridge on a GX4000.
 */
enum class CpcModel(
    /** Human readable name. */
    val displayName: String,
    /** Total RAM in bytes. */
    val ramSize: Int,
    /** True when the machine has a built-in FDC (uPD765) and AMSDOS ROM. */
    val hasBuiltInDisc: Boolean,
    /** Conventional name of the firmware file: the 32 KB OS+BASIC ROM, or the system cartridge on a Plus. */
    val systemRomFile: String,
    /** Value of the "manufacturer" bits read back on PPI port B (bits 1-3). */
    val manufacturerId: Int,
    /** True for the Plus range (ASIC, cartridge firmware). */
    val isPlus: Boolean = false,
) {
    CPC464("Amstrad CPC 464", 64 * 1024, false, "cpc464.rom", 7),
    CPC664("Amstrad CPC 664", 64 * 1024, true, "cpc664.rom", 7),
    CPC6128("Amstrad CPC 6128", 128 * 1024, true, "cpc6128.rom", 7),
    CPC6128PLUS("Amstrad CPC 6128 Plus", 128 * 1024, true, "system.cpr", 7, isPlus = true),
    GX4000("Amstrad GX4000", 64 * 1024, false, "", 7, isPlus = true);
}
