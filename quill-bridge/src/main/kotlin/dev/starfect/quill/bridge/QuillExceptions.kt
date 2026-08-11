package dev.starfect.quill.bridge

/** Raised when the native library cannot be found, opened, or does not match this bridge. */
public class QuillNativeLibraryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when an engine call fails.
 *
 * [status] is the raw code from `ffi/error.rs`; [detail] is the engine's own description, retrieved
 * from its thread-local error slot when one was recorded.
 */
public class QuillEngineException(
    public val status: Int,
    public val operation: String,
    public val detail: String?,
) : RuntimeException(
    buildString {
        append(operation)
        append(" failed: ")
        append(QuillStatus.describe(status))
        if (!detail.isNullOrBlank()) {
            append(" (")
            append(detail)
            append(')')
        }
    }
)
