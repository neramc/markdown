package dev.starfect.quill.io

/**
 * What a file looked like on disk the last time Quill and the disk agreed.
 *
 * Held per open document so a save can tell "nobody has touched this since I read it" from "this
 * has changed underneath me". Without it, saving is an unconditional overwrite, and a `git checkout`
 * or a formatter run while a document sits open costs somebody their work with no message at all.
 */
public data class FileStamp(
    public val modifiedMillis: Long,
    public val sizeBytes: Long,
)
