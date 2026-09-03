package dev.stefan.acpc.core.api

/** Base class of user-facing errors raised by the core (e.g. invalid disk image). */
open class EmulatorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The disk image could not be parsed. */
class InvalidDiskImageException(message: String, cause: Throwable? = null) : EmulatorException(message, cause)

/** A save state could not be read (corrupted or incompatible version). */
class InvalidStateException(message: String, cause: Throwable? = null) : EmulatorException(message, cause)
