package dev.stefan.acpc.core.fdc

import dev.stefan.acpc.core.disk.DiskImage
import dev.stefan.acpc.core.disk.DskFormat
import dev.stefan.acpc.core.state.StateReader
import dev.stefan.acpc.core.state.StateWriter

/**
 * NEC uPD765A floppy disc controller, as used in the CPC 664 / 6128 and the
 * DDI-1 interface.
 *
 * The controller is driven through two ports: the main status register
 * (&FB7E, read) and the data register (&FB7F). A command goes through the
 * classic three phases: command bytes (CPU → FDC), execution (data transfer
 * in non-DMA mode, one byte per RQM) and result bytes (FDC → CPU).
 *
 * Timing is derived from the machine clock passed to [catchUp]:
 *  - seeks take (16 - SRT) ms per cylinder (SPECIFY step rate),
 *  - a sector becomes available when it passes under the head, using a
 *    300 rpm rotation model, so loading speed is close to a real drive,
 *  - during execution the CPU must fetch each byte within [OVERRUN_TIMEOUT_US]
 *    or the transfer ends with an overrun error, which some copy-protected
 *    loaders rely on to terminate partial sector reads.
 *
 * Sector status bits stored in the disc image (CRC errors, deleted data
 * marks) are reported like the real chip, and weak sectors return their
 * successive copies.
 */
class Upd765 {

    val drives = arrayOf(FloppyDrive(0), FloppyDrive(1))

    var motorOn = false
        private set

    /** When true, rotational and seek delays are removed (fast loading). */
    @Volatile var fastMode = false

    private enum class Phase { COMMAND, EXEC_READ, EXEC_WRITE, EXEC_FORMAT, EXEC_SCAN, RESULT }

    private var phase = Phase.COMMAND
    private var now = 0L

    // Command / result buffers
    private val command = IntArray(9)
    private var commandLength = 0
    private var commandExpected = 0
    private val result = IntArray(7)
    private var resultLength = 0
    private var resultIndex = 0

    // SPECIFY
    private var stepRateMs = 6
    private var headUnloadMs = 240
    private var headLoadMs = 2
    private var nonDma = true

    // Seek state per drive
    private val seekTarget = IntArray(2)
    private val seekEndUs = LongArray(2)
    private val seeking = BooleanArray(2)
    private val seekDone = BooleanArray(2)

    // Execution state
    private var activeDrive = 0
    private var activeHead = 0
    private var buffer = ByteArray(0)
    private var bufferIndex = 0
    private var bufferLength = 0
    private var dataReadyAtUs = 0L
    private var byteDeadlineUs = 0L
    private var lastSt0 = 0
    private var currentSector: DiskImage.Sector? = null
    private var currentTrack: DiskImage.Track? = null
    private var formatSectors = 0
    private var formatBuffer = ByteArray(0)
    private var readTrackRemaining = 0
    private var scanHit = false
    private var scanNotSatisfied = false

    init {
        reset()
    }

    fun reset() {
        motorOn = false
        phase = Phase.COMMAND
        commandLength = 0
        commandExpected = 0
        resultLength = 0
        resultIndex = 0
        stepRateMs = 6
        nonDma = true
        seeking.fill(false)
        seekDone.fill(false)
        buffer = ByteArray(0)
        bufferIndex = 0
        bufferLength = 0
        lastSt0 = 0
        currentSector = null
        currentTrack = null
        for (d in drives) { d.cylinder = 0; d.sectorIndex = 0 }
    }

    // ---- Disc management ---------------------------------------------------

    fun insertDisk(drive: Int, image: ByteArray, name: String) {
        val parsed = DskFormat.read(image, name)
        drives[drive and 1].insert(parsed)
    }

    fun insertDiskImage(drive: Int, image: DiskImage) {
        drives[drive and 1].insert(image)
    }

    fun ejectDisk(drive: Int) = drives[drive and 1].eject()

    fun hasDisk(drive: Int): Boolean = drives[drive and 1].hasDisk

    fun disk(drive: Int): DiskImage? = drives[drive and 1].disk

    fun exportDisk(drive: Int): ByteArray? = drives[drive and 1].disk?.let { DskFormat.write(it) }

    fun diskName(drive: Int): String? = drives[drive and 1].disk?.name

    fun setMotor(on: Boolean) {
        motorOn = on
    }

    // ---- Timing ------------------------------------------------------------

    /** Brings the controller up to date with the machine clock (microseconds). */
    fun catchUp(us: Long) {
        now = us
        for (i in 0..1) {
            if (seeking[i] && now >= seekEndUs[i]) {
                seeking[i] = false
                seekDone[i] = true
                drives[i].cylinder = seekTarget[i]
            }
        }
        when (phase) {
            Phase.EXEC_READ, Phase.EXEC_WRITE, Phase.EXEC_FORMAT, Phase.EXEC_SCAN -> {
                if (now >= dataReadyAtUs && now > byteDeadlineUs) overrun()
            }
            else -> Unit
        }
    }

    private fun isReady(drive: Int): Boolean = motorOn && drives[drive].hasDisk

    private fun rotationalDelayUs(track: DiskImage.Track, sectorIndex: Int): Long {
        if (fastMode) return 0
        // Position of the sector on the track, 32 µs per MFM byte, 6250 bytes per revolution.
        var bytes = 146
        for (i in 0 until sectorIndex) {
            val s = track.sectors[i]
            bytes += 62 + minOf(s.copyLength, 128 shl (s.n and 7)) + track.gap3 + 2
        }
        val sectorStart = ((bytes + 62) * 32L) % REVOLUTION_US
        val position = now % REVOLUTION_US
        return (sectorStart - position + REVOLUTION_US) % REVOLUTION_US
    }

    // ---- Register interface ------------------------------------------------

    /** Main status register (&FB7E). */
    fun readStatus(): Int {
        var msr = 0
        for (i in 0..1) if (seeking[i]) msr = msr or (1 shl i)
        when (phase) {
            Phase.COMMAND -> {
                msr = msr or 0x80
                if (commandLength > 0) msr = msr or 0x10
            }
            Phase.EXEC_READ -> {
                msr = msr or 0x10
                if (now >= dataReadyAtUs) msr = msr or 0x80 or 0x40 or 0x20
            }
            Phase.EXEC_WRITE, Phase.EXEC_FORMAT, Phase.EXEC_SCAN -> {
                msr = msr or 0x10
                if (now >= dataReadyAtUs) msr = msr or 0x80 or 0x20
            }
            Phase.RESULT -> msr = msr or 0x80 or 0x40 or 0x10
        }
        return msr
    }

    /** Data register read (&FB7F). */
    fun readData(): Int {
        return when (phase) {
            Phase.EXEC_READ -> {
                if (now < dataReadyAtUs) return 0xFF
                val v = buffer[bufferIndex].toInt() and 0xFF
                bufferIndex++
                byteDeadlineUs = now + OVERRUN_TIMEOUT_US
                if (bufferIndex >= bufferLength) sectorTransferComplete()
                v
            }
            Phase.RESULT -> {
                val v = result[resultIndex]
                resultIndex++
                if (resultIndex >= resultLength) {
                    phase = Phase.COMMAND
                    commandLength = 0
                }
                v
            }
            else -> 0xFF
        }
    }

    /** Data register write (&FB7F). */
    fun writeData(value: Int) {
        val v = value and 0xFF
        when (phase) {
            Phase.COMMAND -> {
                if (commandLength == 0) {
                    commandExpected = commandLengthFor(v)
                }
                command[commandLength++] = v
                if (commandLength >= commandExpected) executeCommand()
            }
            Phase.EXEC_WRITE, Phase.EXEC_SCAN -> {
                if (now < dataReadyAtUs) return
                buffer[bufferIndex] = v.toByte()
                bufferIndex++
                byteDeadlineUs = now + OVERRUN_TIMEOUT_US
                if (bufferIndex >= bufferLength) sectorTransferComplete()
            }
            Phase.EXEC_FORMAT -> {
                if (now < dataReadyAtUs) return
                formatBuffer[bufferIndex] = v.toByte()
                bufferIndex++
                byteDeadlineUs = now + OVERRUN_TIMEOUT_US
                if (bufferIndex >= bufferLength) formatComplete()
            }
            else -> Unit // writes during a read execution or result phase are ignored
        }
    }

    private fun commandLengthFor(first: Int): Int = when (first and 0x1F) {
        0x03 -> 3  // SPECIFY
        0x04 -> 2  // SENSE DRIVE STATUS
        0x07 -> 2  // RECALIBRATE
        0x08 -> 1  // SENSE INTERRUPT STATUS
        0x0F -> 3  // SEEK
        0x0A -> 2  // READ ID
        0x02, 0x05, 0x06, 0x09, 0x0C, 0x11, 0x19, 0x1D -> 9
        0x0D -> 6  // FORMAT TRACK
        else -> 1  // invalid
    }

    // ---- Command execution -------------------------------------------------

    private fun executeCommand() {
        val code = command[0] and 0x1F
        when (code) {
            0x03 -> {
                stepRateMs = 16 - ((command[1] ushr 4) and 0x0F)
                headUnloadMs = (command[1] and 0x0F) * 16
                headLoadMs = (command[2] ushr 1) * 2
                nonDma = command[2] and 1 != 0
                finishNoResult()
            }
            0x04 -> senseDriveStatus()
            0x07 -> startSeek(command[1] and 3, 0)
            0x0F -> startSeek(command[1] and 3, command[2])
            0x08 -> senseInterruptStatus()
            0x0A -> readId()
            0x06, 0x0C -> startRead(deleted = code == 0x0C)
            0x02 -> startReadTrack()
            0x05, 0x09 -> startWrite(deleted = code == 0x09)
            0x0D -> startFormat()
            0x11, 0x19, 0x1D -> startScan()
            else -> invalidCommand()
        }
    }

    private fun finishNoResult() {
        phase = Phase.COMMAND
        commandLength = 0
    }

    private fun setResult(vararg values: Int) {
        for (i in values.indices) result[i] = values[i] and 0xFF
        resultLength = values.size
        resultIndex = 0
        phase = Phase.RESULT
        commandLength = 0
    }

    private fun invalidCommand() {
        lastSt0 = 0x80
        setResult(0x80)
    }

    /** ST0 head/unit bits plus not-ready flags for the drive addressed by the command. */
    private fun baseSt0(): Int {
        val unit = command[1] and 7
        val drive = unit and 3
        var st0 = unit
        if (drive > 1 || !isReady(drive)) st0 = st0 or 0x48 // AT + NR
        return st0
    }

    private fun senseDriveStatus() {
        val drive = command[1] and 3
        var st3 = command[1] and 7
        if (drive <= 1) {
            val d = drives[drive]
            if (d.hasDisk && d.sides > 1) st3 = st3 or 0x08 // two sided
            if (d.cylinder == 0) st3 = st3 or 0x10        // track 0
            if (isReady(drive)) st3 = st3 or 0x20           // ready
            if (!d.hasDisk) st3 = st3 or 0x40 or 0x08       // write protected (no disc)
        }
        setResult(st3)
    }

    private fun startSeek(drive: Int, target: Int) {
        if (drive > 1) {
            // Drives 2 and 3 do not exist: complete immediately, not ready.
            finishNoResult()
            return
        }
        val d = drives[drive]
        val clamped = target.coerceIn(0, FloppyDrive.MAX_CYLINDER)
        val steps = Math.abs(clamped - d.cylinder)
        seekTarget[drive] = clamped
        seekEndUs[drive] = if (fastMode) now + 1 else now + steps.toLong() * stepRateMs * 1000L + 200
        seeking[drive] = true
        seekDone[drive] = false
        finishNoResult()
    }

    private fun senseInterruptStatus() {
        for (i in 0..1) {
            if (seekDone[i]) {
                seekDone[i] = false
                drives[i].readyChanged = false
                var st0 = 0x20 or i
                if (!isReady(i)) st0 = st0 or 0x48
                lastSt0 = st0
                setResult(st0, drives[i].cylinder)
                return
            }
        }
        for (i in 0..1) {
            if (drives[i].readyChanged) {
                drives[i].readyChanged = false
                var st0 = 0xC0 or i
                if (!isReady(i)) st0 = st0 or 0x08
                lastSt0 = st0
                setResult(st0, drives[i].cylinder)
                return
            }
        }
        invalidCommand()
    }

    private fun selectTrack(): DiskImage.Track? {
        activeDrive = command[1] and 3
        activeHead = (command[1] ushr 2) and 1
        if (activeDrive > 1) return null
        val d = drives[activeDrive]
        return d.track(activeHead)
    }

    private fun readId() {
        val st0 = baseSt0()
        if (st0 and 0x08 != 0) {
            lastSt0 = st0
            setResult(st0, 0, 0, command[1] and 0, 0, 0, 0)
            return
        }
        val track = selectTrack()
        val d = drives[activeDrive]
        if (track == null || track.sectors.isEmpty()) {
            lastSt0 = st0 or 0x40
            setResult(st0 or 0x40, 0x01, 0, d.cylinder, activeHead, 0, 0)
            return
        }
        if (d.sectorIndex >= track.sectors.size) d.sectorIndex = 0
        val s = track.sectors[d.sectorIndex]
        d.sectorIndex = (d.sectorIndex + 1) % track.sectors.size
        lastSt0 = st0
        // The result becomes available when the ID passes under the head.
        dataReadyAtUs = now + rotationalDelayUs(track, d.sectorIndex)
        setResult(st0, 0, 0, s.c, s.h, s.r, s.n)
    }

    /** Looks for the sector matching the command CHRN, starting at the drive's rotation index. */
    private fun findSector(track: DiskImage.Track, d: FloppyDrive): DiskImage.Sector? {
        val c = command[2]; val h = command[3]; val r = command[4]; val n = command[5]
        result[2] = 0 // ST2 scratch
        var st2 = 0
        var idx = if (d.sectorIndex < track.sectors.size) d.sectorIndex else 0
        var found: DiskImage.Sector? = null
        var checked = 0
        while (checked < track.sectors.size) {
            val s = track.sectors[idx]
            if (s.c == c && s.h == h && s.r == r && s.n == n) {
                found = s
                d.sectorIndex = idx
                break
            }
            if (s.c == 0xFF) st2 = st2 or 0x02 else if (s.c != c) st2 = st2 or 0x10
            idx = (idx + 1) % track.sectors.size
            checked++
        }
        scratchSt2 = if (found != null) 0 else st2
        return found
    }

    private var scratchSt2 = 0

    private fun startRead(deleted: Boolean) {
        val st0 = baseSt0()
        if (st0 and 0x08 != 0) {
            resultWithChrn(st0, 0, 0)
            return
        }
        val track = selectTrack()
        if (track == null || track.sectors.isEmpty()) {
            resultWithChrn(st0 or 0x40, 0x01, 0) // missing address mark
            return
        }
        currentTrack = track
        continueRead(deleted)
    }

    /** Sets up the transfer of the sector designated by the command R. */
    private fun continueRead(deleted: Boolean) {
        val track = currentTrack ?: return
        val d = drives[activeDrive]
        val sector = findSector(track, d)
        if (sector == null) {
            resultWithChrn(baseSt0() or 0x40, 0x04, scratchSt2) // no data
            return
        }
        val skip = command[0] and 0x20 != 0
        val isDeleted = sector.st2 and 0x40 != 0
        if (skip && isDeleted != deleted) {
            // Skip this sector and move to the next one.
            d.sectorIndex = (d.sectorIndex + 1) % track.sectors.size
            if (command[4] == command[6]) {
                resultWithChrn(baseSt0() or 0x40, 0x80, 0)
                return
            }
            command[4]++
            continueRead(deleted)
            return
        }
        currentSector = sector
        val data = sector.readCopy()
        val requested = if (command[5] == 0) command[8].coerceAtLeast(1) else 128 shl (command[5] and 7)
        buffer = data
        bufferLength = minOf(requested, data.size)
        bufferIndex = 0
        dataReadyAtUs = now + rotationalDelayUs(track, d.sectorIndex)
        byteDeadlineUs = dataReadyAtUs + INITIAL_TIMEOUT_US
        phase = Phase.EXEC_READ
    }

    private fun startReadTrack() {
        val st0 = baseSt0()
        if (st0 and 0x08 != 0) {
            resultWithChrn(st0, 0, 0)
            return
        }
        val track = selectTrack()
        if (track == null || track.sectors.isEmpty()) {
            resultWithChrn(st0 or 0x40, 0x01, 0)
            return
        }
        currentTrack = track
        val d = drives[activeDrive]
        d.sectorIndex = 0
        readTrackRemaining = command[6].coerceAtLeast(1)
        continueReadTrack()
    }

    private fun continueReadTrack() {
        val track = currentTrack ?: return
        val d = drives[activeDrive]
        if (d.sectorIndex >= track.sectors.size) d.sectorIndex = 0
        val sector = track.sectors[d.sectorIndex]
        currentSector = sector
        val data = sector.readCopy()
        val requested = 128 shl (command[5] and 7)
        buffer = data
        bufferLength = minOf(requested, data.size)
        bufferIndex = 0
        dataReadyAtUs = now + rotationalDelayUs(track, d.sectorIndex)
        byteDeadlineUs = dataReadyAtUs + INITIAL_TIMEOUT_US
        phase = Phase.EXEC_READ
    }

    private fun startWrite(deleted: Boolean) {
        val st0 = baseSt0()
        if (st0 and 0x08 != 0) {
            resultWithChrn(st0, 0, 0)
            return
        }
        val track = selectTrack()
        if (track == null || track.sectors.isEmpty()) {
            resultWithChrn(st0 or 0x40, 0x01, 0)
            return
        }
        currentTrack = track
        continueWrite(deleted)
    }

    private fun continueWrite(deleted: Boolean) {
        val track = currentTrack ?: return
        val d = drives[activeDrive]
        val sector = findSector(track, d)
        if (sector == null) {
            resultWithChrn(baseSt0() or 0x40, 0x04, scratchSt2)
            return
        }
        currentSector = sector
        val requested = if (command[5] == 0) command[8].coerceAtLeast(1) else 128 shl (command[5] and 7)
        bufferLength = minOf(requested, sector.copyLength.coerceAtLeast(1))
        buffer = ByteArray(bufferLength)
        bufferIndex = 0
        dataReadyAtUs = now + rotationalDelayUs(track, d.sectorIndex)
        byteDeadlineUs = dataReadyAtUs + INITIAL_TIMEOUT_US
        phase = Phase.EXEC_WRITE
        pendingDeletedWrite = deleted
    }

    private var pendingDeletedWrite = false

    private fun startFormat() {
        val st0 = baseSt0()
        if (st0 and 0x08 != 0) {
            resultWithChrn(st0, 0, 0)
            return
        }
        selectTrack()
        formatSectors = command[3]
        bufferLength = formatSectors * 4
        formatBuffer = ByteArray(bufferLength)
        bufferIndex = 0
        dataReadyAtUs = now
        byteDeadlineUs = now + INITIAL_TIMEOUT_US
        phase = if (bufferLength == 0) Phase.COMMAND else Phase.EXEC_FORMAT
        if (bufferLength == 0) formatComplete()
    }

    private fun formatComplete() {
        val d = drives[activeDrive]
        val disk = d.disk
        val n = command[2] and 7
        val gap3 = command[4]
        val filler = command[5]
        val sectors = ArrayList<DiskImage.Sector>(formatSectors)
        for (i in 0 until formatSectors) {
            val o = i * 4
            sectors += DiskImage.Sector(
                formatBuffer[o].toInt() and 0xFF, formatBuffer[o + 1].toInt() and 0xFF,
                formatBuffer[o + 2].toInt() and 0xFF, formatBuffer[o + 3].toInt() and 0xFF,
                0, 0, ByteArray(128 shl n) { filler.toByte() },
            )
        }
        val side = if (disk != null && disk.sides > 1) activeHead else 0
        if (disk != null && d.cylinder < disk.trackCount) {
            disk.setTrack(side, d.cylinder, DiskImage.Track(d.cylinder, side, n, gap3, filler, sectors))
        }
        d.sectorIndex = 0
        // Result: CHRN of the "next" sector.
        val st0 = baseSt0()
        lastSt0 = st0
        setResult(st0, 0, 0, d.cylinder, activeHead, formatSectors + 1, n)
    }

    private fun startScan() {
        val st0 = baseSt0()
        if (st0 and 0x08 != 0) {
            resultWithChrn(st0, 0, 0)
            return
        }
        val track = selectTrack()
        if (track == null || track.sectors.isEmpty()) {
            resultWithChrn(st0 or 0x40, 0x01, 0)
            return
        }
        currentTrack = track
        val d = drives[activeDrive]
        val sector = findSector(track, d)
        if (sector == null) {
            resultWithChrn(st0 or 0x40, 0x04, scratchSt2)
            return
        }
        currentSector = sector
        bufferLength = minOf(128 shl (command[5] and 7), sector.copyLength.coerceAtLeast(1))
        buffer = ByteArray(bufferLength)
        bufferIndex = 0
        scanHit = false
        scanNotSatisfied = false
        dataReadyAtUs = now + rotationalDelayUs(track, d.sectorIndex)
        byteDeadlineUs = dataReadyAtUs + INITIAL_TIMEOUT_US
        phase = Phase.EXEC_SCAN
    }

    /** Called when the last byte of a sector was transferred. */
    private fun sectorTransferComplete() {
        val track = currentTrack ?: return
        val d = drives[activeDrive]
        val sector = currentSector ?: return
        when (phase) {
            Phase.EXEC_READ -> {
                d.sectorIndex = (d.sectorIndex + 1) % track.sectors.size
                if ((command[0] and 0x1F) == 0x02) {
                    readTrackRemaining--
                    if (readTrackRemaining > 0) {
                        command[4]++
                        continueReadTrack()
                    } else {
                        endOfCylinder(sector)
                    }
                    return
                }
                var st1 = sector.st1 and 0x7F
                var st2 = sector.st2 and 0x7F
                val deletedCommand = (command[0] and 0x1F) == 0x0C
                val isDeleted = sector.st2 and 0x40 != 0
                if (isDeleted != deletedCommand) st2 = st2 or 0x40 else st2 = st2 and 0x40.inv()
                if (st1 and 0x31 != 0 || st2 and 0x21 != 0) {
                    // Data error, missing address mark or bad cylinder: abnormal termination.
                    if (st1 and 0x20 != 0 || st2 and 0x20 != 0) st2 = st2 and 0xBF
                    resultWithChrn(baseSt0() or 0x40, st1, st2)
                } else if (st2 and 0x40 != 0) {
                    // Control mark: the sector type did not match, terminate without error.
                    resultWithChrn(baseSt0(), st1, st2)
                } else if (command[4] != command[6]) {
                    command[4]++
                    continueRead(deletedCommand)
                } else {
                    endOfCylinder(sector)
                }
            }
            Phase.EXEC_WRITE -> {
                sector.write(buffer.copyOf(bufferLength).let { written ->
                    // Keep the sector size: pad with the old content when fewer bytes were written.
                    if (written.size >= sector.copyLength) written else sector.data.copyOf(sector.copyLength).also {
                        System.arraycopy(written, 0, it, 0, written.size)
                    }
                })
                if (pendingDeletedWrite) sector.st2 = sector.st2 or 0x40
                drives[activeDrive].disk?.modified = true
                d.sectorIndex = (d.sectorIndex + 1) % track.sectors.size
                if (command[4] != command[6]) {
                    command[4]++
                    continueWrite(pendingDeletedWrite)
                } else {
                    endOfCylinder(sector)
                }
            }
            Phase.EXEC_SCAN -> {
                val data = sector.readCopy()
                var equal = true
                var less = false
                var greater = false
                for (i in 0 until bufferLength) {
                    val a = data[i].toInt() and 0xFF
                    val b = buffer[i].toInt() and 0xFF
                    if (a != b) { equal = false; if (a < b) less = true else greater = true }
                }
                val code = command[0] and 0x1F
                val hit = when (code) {
                    0x11 -> equal
                    0x19 -> equal || less
                    else -> equal || greater
                }
                d.sectorIndex = (d.sectorIndex + 1) % track.sectors.size
                var st2 = 0
                if (hit) st2 = st2 or 0x08 else st2 = st2 or 0x04
                resultWithChrn(baseSt0(), 0, st2)
            }
            else -> Unit
        }
    }

    /** Normal end of a multi-sector command: EN in ST1, AT in ST0, C/R advanced. */
    private fun endOfCylinder(sector: DiskImage.Sector) {
        val st0 = baseSt0() or 0x40
        val st1 = (sector.st1 and 0x7F) or 0x80
        val st2 = sector.st2 and 0x3F
        var c = command[2]
        var h = command[3]
        var r = command[4] + 1
        if (command[0] and 0x80 != 0 && h == 0) {
            h = 1
            r = 1
        } else if (r > command[6]) {
            c++
            r = 1
        }
        lastSt0 = st0
        setResult(st0, st1, st2, c, h, r, command[5])
    }

    private fun resultWithChrn(st0: Int, st1: Int, st2: Int) {
        lastSt0 = st0
        setResult(st0, st1, st2, command[2], command[3], command[4], command[5])
    }

    private fun overrun() {
        resultWithChrn(baseSt0() or 0x40, 0x10, 0)
    }

    // ---- Diagnostics -------------------------------------------------------

    fun describe(): String {
        val d = drives[0]
        return "phase=$phase drive=$activeDrive cyl=${d.cylinder}/${drives[1].cylinder} motor=$motorOn cmd=${"%02X".format(command[0])} idx=$bufferIndex/$bufferLength"
    }

    // ---- State -------------------------------------------------------------

    fun exportState(w: StateWriter) {
        val ints = IntArray(64)
        ints[0] = if (motorOn) 1 else 0
        ints[1] = phase.ordinal
        ints[2] = commandLength; ints[3] = commandExpected
        ints[4] = resultLength; ints[5] = resultIndex
        ints[6] = stepRateMs; ints[7] = if (nonDma) 1 else 0
        ints[8] = activeDrive; ints[9] = activeHead
        ints[10] = bufferIndex; ints[11] = bufferLength
        ints[12] = lastSt0; ints[13] = formatSectors; ints[14] = readTrackRemaining
        ints[15] = if (pendingDeletedWrite) 1 else 0
        for (i in 0..1) {
            ints[16 + i] = seekTarget[i]
            ints[18 + i] = if (seeking[i]) 1 else 0
            ints[20 + i] = if (seekDone[i]) 1 else 0
            ints[22 + i] = drives[i].cylinder
            ints[24 + i] = drives[i].sectorIndex
            ints[26 + i] = if (drives[i].readyChanged) 1 else 0
        }
        for (i in 0 until 9) ints[28 + i] = command[i]
        for (i in 0 until 7) ints[37 + i] = result[i]
        ints[44] = currentSectorIndex()
        w.ints("FDC ", ints)
        w.longs("FDCT", longArrayOf(now, dataReadyAtUs, byteDeadlineUs, seekEndUs[0], seekEndUs[1]))
        w.bytes("FDCB", buffer.copyOf(bufferLength.coerceAtLeast(0)))
        w.bytes("FDCF", formatBuffer)
        for (i in 0..1) {
            val disk = drives[i].disk ?: continue
            w.string("DKN$i", disk.name)
            w.bytes("DSK$i", DskFormat.write(disk))
        }
    }

    private fun currentSectorIndex(): Int {
        val t = currentTrack ?: return -1
        val s = currentSector ?: return -1
        return t.sectors.indexOf(s)
    }

    fun importState(r: StateReader) {
        if (!r.has("FDC ")) {
            reset()
            return
        }
        for (i in 0..1) {
            if (r.has("DSK$i")) {
                val image = DskFormat.read(r.bytes("DSK$i"), r.string("DKN$i"))
                drives[i].insert(image)
                drives[i].readyChanged = false
            } else {
                drives[i].eject()
                drives[i].readyChanged = false
            }
        }
        val ints = r.ints("FDC ")
        motorOn = ints[0] != 0
        phase = Phase.entries[ints[1]]
        commandLength = ints[2]; commandExpected = ints[3]
        resultLength = ints[4]; resultIndex = ints[5]
        stepRateMs = ints[6]; nonDma = ints[7] != 0
        activeDrive = ints[8]; activeHead = ints[9]
        bufferIndex = ints[10]; bufferLength = ints[11]
        lastSt0 = ints[12]; formatSectors = ints[13]; readTrackRemaining = ints[14]
        pendingDeletedWrite = ints[15] != 0
        for (i in 0..1) {
            seekTarget[i] = ints[16 + i]
            seeking[i] = ints[18 + i] != 0
            seekDone[i] = ints[20 + i] != 0
            drives[i].cylinder = ints[22 + i]
            drives[i].sectorIndex = ints[24 + i]
            drives[i].readyChanged = ints[26 + i] != 0
        }
        for (i in 0 until 9) command[i] = ints[28 + i]
        for (i in 0 until 7) result[i] = ints[37 + i]
        val longs = r.longs("FDCT")
        now = longs[0]; dataReadyAtUs = longs[1]; byteDeadlineUs = longs[2]
        seekEndUs[0] = longs[3]; seekEndUs[1] = longs[4]
        val buf = r.bytes("FDCB")
        buffer = if (buf.size >= bufferLength) buf else buf.copyOf(bufferLength)
        formatBuffer = r.bytes("FDCF")
        currentTrack = if (activeDrive <= 1) drives[activeDrive].track(activeHead) else null
        val sectorIndex = ints[44]
        currentSector = currentTrack?.sectors?.getOrNull(sectorIndex)
    }

    companion object {
        /** One revolution at 300 rpm. */
        const val REVOLUTION_US = 200_000L

        /** Time the CPU has to fetch each data byte before an overrun is reported. */
        const val OVERRUN_TIMEOUT_US = 128L

        /** Tolerance before the first byte of a transfer. */
        const val INITIAL_TIMEOUT_US = 128L * 80
    }
}
