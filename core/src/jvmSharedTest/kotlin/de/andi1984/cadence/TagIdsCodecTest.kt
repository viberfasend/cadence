package de.andi1984.cadence

import de.andi1984.cadence.data.db.TagIdsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The packed `tagIds` column. Small surface, but every list in the app reads through it. */
class TagIdsCodecTest {

    @Test
    fun `a round trip keeps the order the task listed`() {
        val ids = listOf("019277e0-0000-7000-8000-000000000002", "019277e0-0000-7000-8000-000000000001")
        assertEquals(ids, TagIdsCodec.decode(TagIdsCodec.encode(ids)))
    }

    /** Null rather than `""`, so a task with no tags leaves the column genuinely empty and the
     *  merge has nothing to compare. */
    @Test
    fun `no tags encodes to null`() {
        assertNull(TagIdsCodec.encode(emptyList()))
        assertNull(TagIdsCodec.encode(listOf("", "  ")))
    }

    @Test
    fun `null, blank and whitespace all decode to no tags`() {
        assertEquals(emptyList<String>(), TagIdsCodec.decode(null))
        assertEquals(emptyList<String>(), TagIdsCodec.decode(""))
        assertEquals(emptyList<String>(), TagIdsCodec.decode("   "))
        assertEquals(emptyList<String>(), TagIdsCodec.decode(",,,"))
    }

    /** A malformed column costs a task its labels, never the whole list — the rule
     *  `RecurrenceCodec` already follows. */
    @Test
    fun `stray separators and spacing survive a decode`() {
        assertEquals(listOf("a", "b"), TagIdsCodec.decode(" a , ,b,"))
    }

    /** The same id twice is one label. Reachable through a merge, not through the UI, which is
     *  exactly why the codec and not the caller enforces it. */
    @Test
    fun `duplicates collapse in both directions`() {
        assertEquals("a,b", TagIdsCodec.encode(listOf("a", "b", "a")))
        assertEquals(listOf("a", "b"), TagIdsCodec.decode("a,b,a"))
    }
}
