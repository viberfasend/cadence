package de.andi1984.cadence.data

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The sample content from the design, laid out relative to whatever "today" is on first run
 * so a fresh install looks like the mockups instead of an empty list.
 */
object SeedData {

    data class SeedProject(
        val name: String,
        val colorHex: String,
        val parentKey: String? = null,
        val sortOrder: Int = 0,
    )

    fun projects(): List<Pair<String, SeedProject>> = listOf(
        "work" to SeedProject("Work", "#006A60", sortOrder = 0),
        "q3" to SeedProject("Q3 Launch", "#3E6373", parentKey = "work", sortOrder = 0),
        "hiring" to SeedProject("Hiring", "#7D5260", parentKey = "work", sortOrder = 1),
        "home" to SeedProject("Home", "#A1560A", sortOrder = 1),
        "renovation" to SeedProject("Renovation", "#A1560A", parentKey = "home", sortOrder = 0),
        "finance" to SeedProject("Finance", "#6F7976", parentKey = "home", sortOrder = 1),
        "someday" to SeedProject("Someday", "#3E6373", sortOrder = 2),
    )

    /**
     * Sample checklists, keyed by the title of the task they hang under, so a fresh install
     * shows what subtasks look like without needing ids the seed cannot know yet.
     */
    fun subtasks(): Map<String, List<String>> = mapOf(
        "Book the summer holiday" to listOf(
            "Agree on the dates",
            "Compare flights",
            "Book the flights",
            "Find a place to stay",
        ),
        "Sign the lease addendum" to listOf(
            "Read the changed clauses",
            "Scan the signed copy",
        ),
    )

    fun tasks(today: LocalDate, projectIds: Map<String, Long>): List<Task> {
        val now = Instant.now()
        fun project(key: String): Long? = projectIds[key]

        var order = 0
        fun task(
            title: String,
            priority: Priority,
            projectKey: String? = null,
            dueDate: LocalDate? = null,
            dueTime: LocalTime? = null,
            reminderTime: LocalTime? = null,
            notes: String? = null,
            recurrence: RecurrenceRule? = null,
            completedAt: Instant? = null,
        ) = Task(
            title = title,
            notes = notes,
            priority = priority,
            projectId = projectKey?.let { project(it) },
            dueDate = dueDate,
            dueTime = dueTime,
            reminderTime = reminderTime,
            completedAt = completedAt,
            createdAt = now,
            sortOrder = order++,
            recurrence = recurrence,
        )

        return listOf(
            // ── Overdue ────────────────────────────────────────────────────────────
            task(
                title = "Submit tax pre-payment",
                priority = Priority.P1,
                projectKey = "finance",
                dueDate = today.minusDays(4),
                reminderTime = LocalTime.of(9, 0),
                notes = "Use the ELSTER portal. Reference number is on last year's " +
                    "assessment, page 2.",
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.SCHEDULE,
                    interval = 3,
                    unit = RecurrenceUnit.MONTH,
                    monthlyMode = MonthlyMode.LAST_WEEKDAY,
                ),
            ),
            task(
                title = "Reply to Dana about the contract",
                priority = Priority.P2,
                projectKey = "q3",
                dueDate = today.minusDays(1),
            ),
            task(
                title = "Cancel gym membership",
                priority = Priority.P3,
                projectKey = "home",
                dueDate = today.minusDays(9),
            ),

            // ── Due today ──────────────────────────────────────────────────────────
            task(
                title = "Ship release notes v2.4",
                priority = Priority.P1,
                projectKey = "q3",
                dueDate = today,
                dueTime = LocalTime.of(17, 0),
            ),
            task(
                title = "Sign the lease addendum",
                priority = Priority.P1,
                projectKey = "home",
                dueDate = today,
            ),
            task(
                title = "Pay rent",
                priority = Priority.P2,
                projectKey = "home",
                dueDate = today,
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.SCHEDULE,
                    interval = 1,
                    unit = RecurrenceUnit.MONTH,
                    monthlyMode = MonthlyMode.DAY_OF_MONTH,
                    dayOfMonth = 1,
                ),
            ),
            task(
                title = "Prep 1:1 agenda",
                priority = Priority.P2,
                projectKey = "work",
                dueDate = today,
                dueTime = LocalTime.of(16, 0),
            ),
            task(
                title = "Water the plants",
                priority = Priority.P3,
                projectKey = "home",
                dueDate = today,
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.AFTER_COMPLETION,
                    interval = 3,
                    unit = RecurrenceUnit.DAY,
                ),
            ),
            task(
                title = "Stand-up notes",
                priority = Priority.P3,
                projectKey = "work",
                dueDate = today,
                completedAt = now,
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.SCHEDULE,
                    interval = 1,
                    unit = RecurrenceUnit.DAY,
                ),
            ),

            // ── Upcoming ───────────────────────────────────────────────────────────
            task(
                title = "Design review — onboarding",
                priority = Priority.P1,
                projectKey = "work",
                dueDate = today.plusDays(1),
                dueTime = LocalTime.of(10, 30),
            ),
            task(
                title = "Order replacement filter",
                priority = Priority.P3,
                projectKey = "renovation",
                dueDate = today.plusDays(1),
            ),
            task(
                title = "Interview: backend candidate",
                priority = Priority.P2,
                projectKey = "hiring",
                dueDate = today.plusDays(2),
                dueTime = LocalTime.of(14, 0),
            ),
            task(
                title = "Take out recycling",
                priority = Priority.P3,
                projectKey = "home",
                dueDate = today.plusDays(2),
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.SCHEDULE,
                    interval = 2,
                    unit = RecurrenceUnit.WEEK,
                    daysOfWeek = setOf(DayOfWeek.THURSDAY),
                ),
            ),
            task(
                title = "Weekly review",
                priority = Priority.P3,
                projectKey = "work",
                dueDate = today.plusDays(3),
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.SCHEDULE,
                    interval = 1,
                    unit = RecurrenceUnit.WEEK,
                    daysOfWeek = setOf(DayOfWeek.FRIDAY),
                ),
            ),
            task(
                title = "Book the summer holiday",
                priority = Priority.P2,
                projectKey = "home",
                dueDate = today.plusDays(6),
            ),
            task(
                title = "Renew the domain",
                priority = Priority.P3,
                projectKey = "finance",
                dueDate = today.plusDays(9),
                recurrence = RecurrenceRule(
                    mode = RecurrenceMode.SCHEDULE,
                    interval = 1,
                    unit = RecurrenceUnit.YEAR,
                ),
            ),

            // ── Inbox — untriaged ──────────────────────────────────────────────────
            task("Renegotiate the internet contract", Priority.P3),
            task("Look into the noisy radiator", Priority.P3),
            task("Find a dentist that takes new patients", Priority.P3),
            task("Back up the photo library", Priority.P3),
            task("Replace the bike lock", Priority.P3),
            task("Read the pension letter properly", Priority.P3),

            // ── Someday ────────────────────────────────────────────────────────────
            task("Learn to develop film at home", Priority.P4, "someday"),
            task("Rebuild the garden bed", Priority.P4, "someday"),
            task("Write up the Kotlin notes", Priority.P4, "someday"),
        )
    }
}
