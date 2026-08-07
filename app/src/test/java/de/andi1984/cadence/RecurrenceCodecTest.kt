package de.andi1984.cadence

import de.andi1984.cadence.data.db.RecurrenceCodec
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

/**
 * The packed `v1;key=value` column is a storage detail of `:app`, unlike the backup format —
 * which is why this left `QuickAddParserTest` when the parser moved into `:core`.
 */
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
}
