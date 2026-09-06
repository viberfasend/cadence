package de.andi1984.cadence.domain

/**
 * A single unreadable value must not fail the whole record — that field simply goes empty.
 *
 * Shared by the two decoders that read published shapes, `BackupCodec` (the file) and
 * `RemoteRecords` (the wire): a blank string reads as absent, and a string the parser refuses
 * reads as absent too rather than throwing out the task it came with.
 */
fun <T> String?.parseOrNull(parse: (String) -> T): T? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() }
