package de.andi1984.cadence

import de.andi1984.cadence.data.db.RecurrenceCodec
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

/** The packed `v1;key=value` column is a storage detail, unlike the backup format. */
class RecurrenceCodecTest {

    @Test
    fun `recurrence rules survive a round trip through the database column`() {
        val rule = RecurrenceRule(
            mode = RecurrenceMode.SCHEDULE,
            interval = 3,
            unit = RecurrenceUnit.MONTH,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            monthlyMode = MonthlyMode.NTH_WEEKDAY,
            dayOfMonth = 15,
            nthWeek = 2,
            nthDayOfWeek = DayOfWeek.TUESDAY,
            keepMissed = false,
        )
        assertEquals(rule, RecurrenceCodec.decode(RecurrenceCodec.encode(rule)))
        assertNull(RecurrenceCodec.encode(null))
        assertNull(RecurrenceCodec.decode(null))
        assertNull(RecurrenceCodec.decode("garbage"))
    }

    @Test
    fun `a completion-anchored rule with no days of week round-trips without a dows segment`() {
        val rule = RecurrenceRule(
            mode = RecurrenceMode.AFTER_COMPLETION,
            interval = 3,
            unit = RecurrenceUnit.DAY,
            keepMissed = true,
        )
        val encoded = RecurrenceCodec.encode(rule)

        assertFalse(encoded!!.contains("dows="))
        assertEquals(rule, RecurrenceCodec.decode(encoded))
    }

    @Test
    fun `an unrecognised unit falls back to weekly rather than failing the whole rule`() {
        val decoded = RecurrenceCodec.decode("v1;mode=SCHEDULE;interval=1;unit=FORTNIGHT;monthly=DAY_OF_MONTH")

        assertEquals(RecurrenceUnit.WEEK, decoded?.unit)
    }

    @Test
    fun `a column written before keepMissed existed catches its series up`() {
        // Rules stored before the flag was added carry no `keepMissed=` segment. They used to
        // decode to true, which is why every one of them only ever stepped a single interval on
        // however far overdue it was; the missing segment now means the model's own default.
        val decoded = RecurrenceCodec.decode("v1;mode=SCHEDULE;interval=1;unit=DAY;monthly=DAY_OF_MONTH")

        assertFalse(decoded!!.keepMissed)
    }

    @Test
    fun `an unrecognised mode decodes the whole rule to null`() {
        assertNull(RecurrenceCodec.decode("v1;mode=YEARLY_ISH;interval=1;unit=WEEK"))
    }

    @Test
    fun `a missing mode decodes the whole rule to null`() {
        assertNull(RecurrenceCodec.decode("v1;interval=1;unit=WEEK"))
    }

    @Test
    fun `a malformed keepMissed value falls back to false, the model's default`() {
        val decoded = RecurrenceCodec.decode("v1;mode=SCHEDULE;interval=1;unit=WEEK;keepMissed=maybe")

        assertEquals(false, decoded?.keepMissed)
    }

    @Test
    fun `a version other than v1 decodes to null`() {
        assertNull(RecurrenceCodec.decode("v2;mode=SCHEDULE;interval=1;unit=WEEK"))
    }

    @Test
    fun `a segment with no equals sign is skipped rather than corrupting the rest`() {
        val decoded = RecurrenceCodec.decode("v1;mode=SCHEDULE;garbage-segment;interval=2;unit=DAY")

        assertEquals(2, decoded?.interval)
        assertEquals(RecurrenceUnit.DAY, decoded?.unit)
    }

    @Test
    fun `interval below 1 is coerced up to 1`() {
        val decoded = RecurrenceCodec.decode("v1;mode=SCHEDULE;interval=0;unit=WEEK")

        assertEquals(1, decoded?.interval)
    }
}
