package dev.stefan.acpc.core.fdc

import dev.stefan.acpc.core.disk.DiskImage

/**
 * A 3" floppy drive: head position, inserted disc and a rotation model used
 * to derive realistic access delays.
 */
class FloppyDrive(val index: Int) {
    var disk: DiskImage? = null
        private set

    /** Physical cylinder the head is on. */
    var cylinder = 0

    /** Set when the disc was inserted or removed since the last SENSE INTERRUPT STATUS. */
    var readyChanged = false

    /** Index used for READ ID / sector search: models the rotation position. */
    var sectorIndex = 0

    val hasDisk: Boolean get() = disk != null

    val sides: Int get() = disk?.sides ?: 1

    fun insert(image: DiskImage) {
        disk = image
        readyChanged = true
        sectorIndex = 0
    }

    fun eject() {
        if (disk != null) readyChanged = true
        disk = null
    }

    fun track(side: Int): DiskImage.Track? {
        val d = disk ?: return null
        val s = if (d.sides > 1) side else 0
        return d.track(s, cylinder)
    }

    companion object {
        /** Drives cannot step beyond this cylinder (the mechanical stop). */
        const val MAX_CYLINDER = 84
    }
}
