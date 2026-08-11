package dev.starfect.quill.bridge

import dev.starfect.quill.bridge.internal.QuillBindings
import dev.starfect.quill.bridge.wire.decodeText
import java.lang.foreign.MemorySegment

/** Status codes mirroring `ffi/error.rs`. */
internal object QuillStatus {
    const val OK: Int = 0
    const val NULL_POINTER: Int = -1
    const val INVALID_UTF8: Int = -2
    const val OUT_OF_RANGE: Int = -3
    const val INVALID_ARGUMENT: Int = -4
    const val PANIC: Int = -5

    fun describe(status: Int): String = when (status) {
        OK -> "ok"
        NULL_POINTER -> "null pointer"
        INVALID_UTF8 -> "invalid UTF-8"
        OUT_OF_RANGE -> "offset out of range"
        INVALID_ARGUMENT -> "invalid argument"
        PANIC -> "the engine panicked"
        else -> "unknown status $status"
    }
}

/** Retrieves and clears the calling thread's last engine error, if one was recorded. */
internal fun lastEngineError(): String? {
    val payload = QuillBindings.lastError() ?: return null
    return runCatching { decodeText(MemorySegment.ofArray(payload)) }.getOrNull()
}

internal fun checkStatus(status: Int, operation: String) {
    if (status != QuillStatus.OK) {
        throw QuillEngineException(status, operation, lastEngineError())
    }
}

/** Unwraps an out-parameter result, converting a failure status into an exception. */
internal fun QuillBindings.Payload.require(operation: String): MemorySegment {
    checkStatus(status(), operation)
    return MemorySegment.ofArray(bytes())
}
