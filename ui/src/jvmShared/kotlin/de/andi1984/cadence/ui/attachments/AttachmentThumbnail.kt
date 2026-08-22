package de.andi1984.cadence.ui.attachments

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.ui.components.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.File

/**
 * The 40dp square at the head of an attachment row: the picture itself for an image, the kind's
 * icon for everything else.
 *
 * Hand-rolled rather than Coil (`docs/attachments-and-share.md` section F), and now for a second
 * reason the doc could not have: `:ui` is Compose Multiplatform, and Coil's Android integration
 * would have to be an `expect`/`actual` pair for a job that is one call —
 * `ByteArray.decodeToImageBitmap()` is the multiplatform decoder, and it is already on the
 * classpath because the string resources use the same artifact. No network, no cache
 * invalidation, no placeholder machinery; the only images to decode are files this app wrote to
 * its own private storage.
 *
 * The decode is bounded twice over. [THUMBNAIL_SOURCE_CEILING] is the honest limit of doing it
 * this way: `decodeToImageBitmap` has no `inSampleSize`, so it decodes at full resolution and a
 * 25 MB photo would land in memory whole. Above the ceiling the row shows its icon instead, which
 * is exactly what a non-image row shows and reads as deliberate rather than broken. Below it,
 * [ThumbnailCache] keeps the result so scrolling past the same row does not decode it again.
 */

/** The largest file this will decode. Deliberately well under [de.andi1984.cadence.data.MAX_ATTACHMENT_BYTES]. */
private const val THUMBNAIL_SOURCE_CEILING = 4L * 1024 * 1024

@Composable
fun AttachmentThumbnail(
    attachment: Attachment,
    present: Boolean,
    blobFile: (String) -> File?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val hash = attachment.sha256

    // `produceState` keyed on the hash: cancellation comes free when the row leaves composition,
    // and a re-key (a healed row now pointing at different bytes) reloads without extra state.
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, hash, present) {
        value = if (!present || hash == null || !attachment.isImage) {
            null
        } else {
            withContext(Dispatchers.IO) { ThumbnailCache.load(hash, blobFile) }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Icon(
            imageVector = attachmentIcon(attachment, present),
            contentDescription = null,
            tint = if (present) scheme.onSurfaceVariant else scheme.error,
            modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)),
        )
    }
}

/** What a row without a picture shows: the kind, or that the bytes are gone. */
internal fun attachmentIcon(attachment: Attachment, present: Boolean) = when {
    !present -> AppIcons.Error
    attachment.url != null -> AppIcons.Link
    attachment.isImage -> AppIcons.Image
    else -> AppIcons.File
}

/**
 * Decoded thumbnails, newest-used last, bounded by the bytes they occupy.
 *
 * Keyed by hash alone rather than hash-plus-size: every caller asks for the same 40dp square, and
 * a second size would be a second entry rather than a wrong one. Bounded by *bytes* because entry
 * count says nothing about cost — one 4000×3000 photo is four hundred 100×100 ones.
 *
 * Process-wide and deliberately not tied to any screen: the same file is on the row, on the
 * detail card and on the row again after a back navigation.
 */
private object ThumbnailCache {

    private const val MAX_BYTES = 8L * 1024 * 1024

    private val entries = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L

    fun load(sha256: String, blobFile: (String) -> File?): ImageBitmap? {
        synchronized(this) { entries[sha256] }?.let { return it }

        val file = blobFile(sha256) ?: return null
        if (file.length() > THUMBNAIL_SOURCE_CEILING) return null
        val decoded = try {
            file.readBytes().decodeToImageBitmap()
        } catch (t: Throwable) {
            // Not an image after all, or a truncated one — a mime type is what the sending app
            // claimed, not a promise. The row falls back to its icon.
            return null
        }
        synchronized(this) { put(sha256, decoded) }
        return decoded
    }

    private fun put(sha256: String, bitmap: ImageBitmap) {
        entries[sha256] = bitmap
        bytes += bitmap.sizeInBytes()
        val iterator = entries.entries.iterator()
        while (bytes > MAX_BYTES && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == sha256) break
            bytes -= eldest.value.sizeInBytes()
            iterator.remove()
        }
    }

    private fun ImageBitmap.sizeInBytes(): Long = width.toLong() * height.toLong() * 4L
}
