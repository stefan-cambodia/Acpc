package dev.stefan.acpc.core.machine

/**
 * CRTC variants found in real CPCs. They behave slightly differently
 * (register masks, read-back behaviour, VSYNC width, R8 handling...).
 * Type 0 (Hitachi HD6845S) is the most common and the reference for games.
 */
enum class CrtcType(val displayName: String) {
    TYPE0_HD6845S("Type 0 — Hitachi HD6845S"),
    TYPE1_UM6845R("Type 1 — UMC UM6845R"),
}
