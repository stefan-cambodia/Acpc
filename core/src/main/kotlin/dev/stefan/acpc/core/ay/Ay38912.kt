package dev.stefan.acpc.core.ay

/**
 * General Instrument AY-3-8912 programmable sound generator.
 *
 * Three square-wave tone generators, one noise generator (17-bit LFSR), one
 * envelope generator and a 16-level logarithmic DAC per channel, plus the
 * I/O port A that the CPC uses to read the keyboard matrix.
 *
 * The chip is clocked at 1 MHz. Tone, noise and envelope counters advance at
 * clock/8 (125 kHz), which is the resolution used by [step]. Samples are
 * produced by box-filtering the 125 kHz output down to [sampleRate].
 *
 * Stereo mixing follows the CPC wiring: channel A left, channel C right,
 * channel B in the middle.
 */
class Ay38912(val sampleRate: Int = 44_100) {

    /** Reads the AY port A input: the keyboard matrix line selected by the PPI. */
    var portAInput: () -> Int = { 0xFF }

    val regs = IntArray(16)
    var selectedRegister = 0
        private set

    // Tone generators
    private val toneCounter = IntArray(3)
    private val toneOutput = IntArray(3)

    // Noise
    private var noiseCounter = 0
    private var noisePrescale = 0
    private var rng = 1
    private var noiseOutput = 0

    // Envelope
    private var envCounter = 0
    private var envStep = 0
    private var envVolume = 0
    private var envHolding = false
    private var envAttack = false
    private var envAlternate = false
    private var envHold = false
    private var envContinue = false

    // Output
    private var pendingUs = 0
    private var sampleAcc = 0
    private var accL = 0
    private var accR = 0
    private var accCount = 0
    private var buffer = ShortArray(sampleRate / 10 * 2)
    private var bufferFrames = 0

    /** Master volume 0..1. */
    @Volatile var volume: Float = 1.0f
    @Volatile var muted: Boolean = false

    init {
        reset()
    }

    fun reset() {
        regs.fill(0)
        regs[7] = 0xFF
        selectedRegister = 0
        toneCounter.fill(0)
        toneOutput.fill(0)
        noiseCounter = 0; noisePrescale = 0; rng = 1; noiseOutput = 0
        envCounter = 0; envStep = 0; envVolume = 0; envHolding = false
        pendingUs = 0; sampleAcc = 0; accL = 0; accR = 0; accCount = 0
        bufferFrames = 0
    }

    // ---- Bus interface (through the PPI) -----------------------------------

    fun selectRegister(value: Int) {
        selectedRegister = value and 0x0F
    }

    fun writeRegister(value: Int) {
        val r = selectedRegister
        val v = value and REG_MASKS[r]
        regs[r] = v
        if (r == 13) {
            // Writing the shape register restarts the envelope.
            envAttack = v and 0x04 != 0
            envAlternate = v and 0x02 != 0
            envHold = v and 0x01 != 0
            envContinue = v and 0x08 != 0
            envCounter = 0
            envStep = 0
            envHolding = false
            envVolume = if (envAttack) 0 else 15
        }
    }

    fun readRegister(): Int {
        val r = selectedRegister
        return when (r) {
            // The keyboard is readable even when register 7 sets port A as an
            // output (Pang leaves the mixer at &FF): the pins follow the inputs.
            14 -> portAInput() and 0xFF
            15 -> if (regs[7] and 0x80 == 0) 0xFF else regs[15]
            else -> regs[r]
        }
    }

    // ---- Sound generation --------------------------------------------------

    /** Advances the chip by [us] microseconds and accumulates output samples. */
    fun advance(us: Int) {
        pendingUs += us
        while (pendingUs >= 8) {
            pendingUs -= 8
            step()
        }
    }

    /** One 125 kHz step. */
    private fun step() {
        // Tones
        for (ch in 0 until 3) {
            val period = regs[ch * 2] or (regs[ch * 2 + 1] shl 8)
            toneCounter[ch]++
            if (toneCounter[ch] >= period) {
                toneCounter[ch] = 0
                toneOutput[ch] = toneOutput[ch] xor 1
            }
        }
        // Noise
        noiseCounter++
        if (noiseCounter >= (regs[6] and 0x1F)) {
            noiseCounter = 0
            noisePrescale = noisePrescale xor 1
            if (noisePrescale == 0) {
                // 17-bit LFSR, taps at bits 0 and 3 (per MAME / datasheet analysis).
                val bit = (rng xor (rng ushr 3)) and 1
                rng = (rng ushr 1) or (bit shl 16)
                noiseOutput = rng and 1
            }
        }
        // Envelope
        if (!envHolding) {
            envCounter++
            val period = (regs[11] or (regs[12] shl 8)) * 2
            if (envCounter >= period) {
                envCounter = 0
                stepEnvelope()
            }
        }
        // Mixing
        val mixer = regs[7]
        var l = 0
        var r = 0
        for (ch in 0 until 3) {
            val toneEnabled = mixer and (1 shl ch) == 0
            val noiseEnabled = mixer and (8 shl ch) == 0
            val active = (!toneEnabled || toneOutput[ch] != 0) && (!noiseEnabled || noiseOutput != 0)
            if (active) {
                val volReg = regs[8 + ch]
                val level = VOLUME[if (volReg and 0x10 != 0) envVolume else volReg and 0x0F]
                when (ch) {
                    0 -> l += level
                    1 -> { l += level ushr 1; r += level ushr 1 }
                    else -> r += level
                }
            }
        }
        accL += l
        accR += r
        accCount++
        sampleAcc += sampleRate
        if (sampleAcc >= STEP_RATE) {
            sampleAcc -= STEP_RATE
            emit(accL / accCount, accR / accCount)
            accL = 0; accR = 0; accCount = 0
        }
    }

    private fun stepEnvelope() {
        // Envelope state machine, 16 steps per ramp.
        if (envStep < 15) {
            envStep++
        } else {
            if (!envContinue) {
                envHolding = true
                envVolume = 0
                return
            }
            if (envHold) {
                envHolding = true
                envVolume = if (envAttack xor envAlternate) 15 else 0
                return
            }
            if (envAlternate) envAttack = !envAttack
            envStep = 0
        }
        envVolume = if (envAttack) envStep else 15 - envStep
    }

    private fun emit(l: Int, r: Int) {
        if (bufferFrames * 2 + 2 > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        val gain = if (muted) 0f else volume
        buffer[bufferFrames * 2] = (l * gain).toInt().coerceIn(-32768, 32767).toShort()
        buffer[bufferFrames * 2 + 1] = (r * gain).toInt().coerceIn(-32768, 32767).toShort()
        bufferFrames++
    }

    /** Number of stereo frames waiting in the internal buffer. */
    val pendingFrames: Int get() = bufferFrames

    /** Hands the buffered samples to [consumer] and empties the buffer. */
    fun drain(consumer: (ShortArray, Int) -> Unit) {
        if (bufferFrames > 0) consumer(buffer, bufferFrames)
        bufferFrames = 0
    }

    fun discardSamples() {
        bufferFrames = 0
    }

    // ---- State -------------------------------------------------------------

    fun exportState(): IntArray = intArrayOf(
        selectedRegister, toneCounter[0], toneCounter[1], toneCounter[2],
        toneOutput[0], toneOutput[1], toneOutput[2],
        noiseCounter, noisePrescale, rng, noiseOutput,
        envCounter, envStep, envVolume, if (envHolding) 1 else 0, if (envAttack) 1 else 0,
        if (envAlternate) 1 else 0, if (envHold) 1 else 0, if (envContinue) 1 else 0, pendingUs,
    ) + regs

    fun importState(s: IntArray) {
        require(s.size >= 20 + 16) { "Invalid AY state" }
        selectedRegister = s[0]
        toneCounter[0] = s[1]; toneCounter[1] = s[2]; toneCounter[2] = s[3]
        toneOutput[0] = s[4]; toneOutput[1] = s[5]; toneOutput[2] = s[6]
        noiseCounter = s[7]; noisePrescale = s[8]; rng = s[9]; noiseOutput = s[10]
        envCounter = s[11]; envStep = s[12]; envVolume = s[13]; envHolding = s[14] != 0
        envAttack = s[15] != 0; envAlternate = s[16] != 0; envHold = s[17] != 0; envContinue = s[18] != 0
        pendingUs = s[19]
        System.arraycopy(s, 20, regs, 0, 16)
        sampleAcc = 0; accL = 0; accR = 0; accCount = 0; bufferFrames = 0
    }

    companion object {
        /** Counter step rate: 1 MHz / 8. */
        const val STEP_RATE = 125_000

        private val REG_MASKS = intArrayOf(
            0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F, 0xFF,
            0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F, 0xFF, 0xFF,
        )

        /**
         * Logarithmic DAC levels of the AY-3-8910/8912 (from the datasheet
         * measurements used by MAME), scaled so that three channels at full
         * volume stay within 16-bit range.
         */
        private val VOLUME: IntArray = doubleArrayOf(
            0.0, 0.0137, 0.0205, 0.0291, 0.0423, 0.0618, 0.0847, 0.1369,
            0.1691, 0.2647, 0.3527, 0.4499, 0.5701, 0.6873, 0.8482, 1.0,
        ).map { (it * 10_000).toInt() }.toIntArray()
    }
}
