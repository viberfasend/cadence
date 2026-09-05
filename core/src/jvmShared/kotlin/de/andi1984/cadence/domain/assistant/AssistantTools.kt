package de.andi1984.cadence.domain.assistant

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the assistant may look at: the lists the ViewModel already holds, frozen at the moment a
 * question is asked. Nothing here reaches storage — the model reads a snapshot, never the
 * database, and never writes (ADR 0006).
 */
data class AssistantSnapshot(
    val tasks: List<Task>,
    val projects: List<Project>,
    val tags: List<Tag>,
    val sections: List<Section> = emptyList(),
)

/** One tool the model may call, in the shape the Messages API declares it: a name, prose the
 *  model reads to decide when to use it, and a JSON schema for its input. */
data class AssistantTool(val name: String, val description: String, val inputSchema: JsonObject)

/** What a tool call came back with — JSON text for the model, or a message it should read as
 *  an error and recover from (a bad id, an unknown scope). */
sealed interface ToolOutcome {
    data class Ok(val json: String) : ToolOutcome
    data class Error(val message: String) : ToolOutcome
}

/**
 * The assistant's read-only window onto the task list (ADR 0006, decision 3).
 *
 * Deliberately *tools* rather than "paste every task into the prompt": a list with a few years
 * of completed recurring occurrences runs to thousands of rows, and the questions people ask —
 * "when did I last clean the kitchen", "what is due this week" — each touch a handful. The model
 * asks for what it needs and gets JSON back; everything else stays on the device.
 *
 * Pure Kotlin on purpose, so it is testable on the JVM the way `QuickAddParser` is: dates are
 * resolved against [now] in [zone], never against the wall clock, and every answer is derived
 * from [snapshot] alone.
 */
class AssistantToolbox(
    private val snapshot: AssistantSnapshot,
    private val now: Instant,
    private val zone: ZoneId,
) {

    private val today: LocalDate = now.atZone(zone).toLocalDate()
    private val projectById: Map<String, Project> = snapshot.projects.associateBy { it.id }
    private val sectionById: Map<String, Section> = snapshot.sections.associateBy { it.id }
    private val tagById: Map<String, Tag> = snapshot.tags.associateBy { it.id }
    private val taskById: Map<String, Task> = snapshot.tasks.associateBy { it.id }
    private val subtasksByParent: Map<String, List<Task>> =
        snapshot.tasks.filter { it.parentId != null }.groupBy { it.parentId!! }
    /** Successor per replaced occurrence — how a recurrence chain is walked forwards. */
    private val successorOf: Map<String, Task> =
        snapshot.tasks.filter { it.spawnedFromId != null }.associateBy { it.spawnedFromId!! }

    fun execute(name: String, input: JsonObject): ToolOutcome = when (name) {
        SEARCH_TASKS -> searchTasks(input)
        LIST_TASKS -> listTasks(input)
        GET_TASK -> getTask(input)
        LIST_PROJECTS -> ToolOutcome.Ok(projectsJson().toString())
        LIST_TAGS -> ToolOutcome.Ok(tagsJson().toString())
        else -> ToolOutcome.Error("Unknown tool: $name")
    }

    // ── search_tasks ───────────────────────────────────────────────────────────────

    private fun searchTasks(input: JsonObject): ToolOutcome {
        val query = input.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolOutcome.Error("query must not be empty")
        val status = input.string("status") ?: "any"
        if (status !in STATUSES) return ToolOutcome.Error("status must be one of $STATUSES")
        val limit = input.int("limit").clampedLimit()

        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        val scored = snapshot.tasks
            .filter { matchesStatus(it, status) }
            .mapNotNull { task ->
                val haystack = haystackOf(task)
                val score = tokens.count { it in haystack } +
                    (if (query.lowercase() in task.title.lowercase()) tokens.size else 0)
                if (score == 0) null else task to score
            }
            .sortedWith(compareByDescending<Pair<Task, Int>> { it.second }.thenByDescending { moment(it.first) })
        return ToolOutcome.Ok(
            buildJsonObject {
                put("total_matches", scored.size)
                putJsonArray("tasks") { scored.take(limit).forEach { add(taskJson(it.first)) } }
            }.toString(),
        )
    }

    /** Everything a search may hit on, lowercased once per task: the title, the notes, the
     *  project path, the tag names and — for a step — its parent's title. */
    private fun haystackOf(task: Task): String = buildString {
        append(task.title.lowercase()).append(' ')
        task.notes?.let { append(it.lowercase()).append(' ') }
        projectPathOf(task)?.let { append(it.lowercase()).append(' ') }
        task.tagIds.mapNotNull { tagById[it] }.forEach { append(it.name.lowercase()).append(' ') }
        task.parentId?.let { taskById[it] }?.let { append(it.title.lowercase()) }
    }

    /** The instant a row is "about", for ordering: when it was finished, else when it is due,
     *  else when it was created. Newest first is what every "when did I last" question wants. */
    private fun moment(task: Task): Instant =
        task.completedAt
            ?: task.dueDate?.atStartOfDay(zone)?.toInstant()
            ?: task.createdAt

    // ── list_tasks ─────────────────────────────────────────────────────────────────

    private fun listTasks(input: JsonObject): ToolOutcome {
        val scope = input.string("scope") ?: "open"
        if (scope !in SCOPES) return ToolOutcome.Error("scope must be one of $SCOPES")
        val limit = input.int("limit").clampedLimit()
        val days = (input.int("days") ?: 30).coerceIn(1, 3650)
        val projectFilter = input.string("project")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val tagFilter = input.string("tag")?.trim()?.removePrefix("@")?.lowercase()?.takeIf { it.isNotEmpty() }

        val since = today.minusDays(days.toLong()).atStartOfDay(zone).toInstant()
        val inScope: (Task) -> Boolean = when (scope) {
            "overdue" -> { t -> t.isOverdue(today) }
            "today" -> { t -> !t.isDone && (t.isDueOn(today) || t.isOverdue(today)) }
            "tomorrow" -> { t -> !t.isDone && t.isDueOn(today.plusDays(1)) }
            "next_7_days" -> { t ->
                val due = t.dueDate
                !t.isDone && due != null && !due.isBefore(today) && due.isBefore(today.plusDays(7))
            }
            "no_due_date" -> { t -> !t.isDone && t.dueDate == null }
            "completed" -> { t -> t.completedAt?.let { !it.isBefore(since) } == true }
            else -> { t -> !t.isDone }
        }
        val matching = snapshot.tasks
            .filter(inScope)
            .filter { projectFilter == null || projectPathOf(it)?.lowercase()?.contains(projectFilter) == true }
            .filter { tagFilter == null || tagNamesOf(it).any { name -> name.lowercase().contains(tagFilter) } }
            .sortedWith(
                if (scope == "completed") {
                    compareByDescending<Task> { it.completedAt }
                } else {
                    compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate }.thenBy { it.priority.level }
                },
            )
        return ToolOutcome.Ok(
            buildJsonObject {
                put("scope", scope)
                put("total", matching.size)
                putJsonArray("tasks") { matching.take(limit).forEach { add(taskJson(it)) } }
            }.toString(),
        )
    }

    // ── get_task ───────────────────────────────────────────────────────────────────

    private fun getTask(input: JsonObject): ToolOutcome {
        val id = input.string("id") ?: return ToolOutcome.Error("id is required")
        val task = taskById[id] ?: return ToolOutcome.Error("No task with id $id")
        val chain = chainOf(task)
        return ToolOutcome.Ok(
            buildJsonObject {
                put("task", taskJson(task, notesLimit = NOTES_FULL))
                putJsonArray("subtasks") {
                    subtasksByParent[task.id].orEmpty().sortedBy { it.sortOrder }.forEach { add(taskJson(it)) }
                }
                // Every finished occurrence of the same recurring task, newest first — the
                // answer to "how often" and "when before that" without a second search.
                val completed = chain.filter { it.isDone }.sortedByDescending { it.completedAt }
                put("completed_occurrences", completed.size)
                putJsonArray("history") {
                    completed.take(MAX_HISTORY).forEach { occurrence ->
                        add(
                            buildJsonObject {
                                put("id", occurrence.id)
                                putCompletedAt(occurrence)
                                occurrence.dueDate?.let { put("was_due", it.toString()) }
                            },
                        )
                    }
                }
            }.toString(),
        )
    }

    /** The whole recurrence chain [task] sits in: back to the first occurrence via
     *  `spawnedFromId`, then forward through every successor. A one-off is a chain of one. */
    private fun chainOf(task: Task): List<Task> {
        var root = task
        val seen = mutableSetOf(task.id)
        while (true) {
            val previous = root.spawnedFromId?.let { taskById[it] } ?: break
            if (!seen.add(previous.id)) break
            root = previous
        }
        val chain = mutableListOf(root)
        val visited = mutableSetOf(root.id)
        var current = root
        while (true) {
            val next = successorOf[current.id] ?: break
            if (!visited.add(next.id)) break
            chain += next
            current = next
        }
        return chain
    }

    // ── list_projects / list_tags ──────────────────────────────────────────────────

    private fun projectsJson(): JsonArray = buildJsonArray {
        snapshot.projects.sortedBy { it.sortOrder }.forEach { project ->
            val tasks = snapshot.tasks.filter { it.projectId == project.id && it.parentId == null }
            add(
                buildJsonObject {
                    put("id", project.id)
                    put("name", projectPath(project, snapshot.projects) ?: project.name)
                    put("open_tasks", tasks.count { !it.isDone })
                    put("done_tasks", tasks.count { it.isDone })
                },
            )
        }
        val inbox = snapshot.tasks.filter { it.projectId == null && it.parentId == null }
        add(
            buildJsonObject {
                put("id", "inbox")
                put("name", "Inbox")
                put("open_tasks", inbox.count { !it.isDone })
                put("done_tasks", inbox.count { it.isDone })
            },
        )
    }

    private fun tagsJson(): JsonArray = buildJsonArray {
        snapshot.tags.sortedBy { it.sortOrder }.forEach { tag ->
            add(
                buildJsonObject {
                    put("id", tag.id)
                    put("name", tag.name)
                    put("open_tasks", snapshot.tasks.count { tag.id in it.tagIds && !it.isDone })
                },
            )
        }
    }

    // ── The task record ────────────────────────────────────────────────────────────

    private fun taskJson(task: Task, notesLimit: Int = NOTES_PREVIEW): JsonObject = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        put("status", if (task.isDone) "done" else "open")
        put("priority", task.priority.shortLabel)
        put("project", projectPathOf(task) ?: "Inbox")
        task.sectionId?.let { sectionById[it] }?.let { put("section", it.name) }
        val tags = tagNamesOf(task)
        if (tags.isNotEmpty()) putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        task.dueDate?.let { due ->
            put("due_date", due.toString())
            put("due_in_days", ChronoUnit.DAYS.between(today, due))
        }
        task.dueTime?.let { put("due_time", it.toString()) }
        if (task.isDone) putCompletedAt(task)
        put("created_at", task.createdAt.atZone(zone).toLocalDate().toString())
        task.recurrence?.let { put("repeats", it.describe()) }
        task.parentId?.let { taskById[it] }?.let { put("parent_title", it.title) }
        subtasksByParent[task.id]?.let { steps ->
            put("subtasks_total", steps.size)
            put("subtasks_done", steps.count { it.isDone })
        }
        task.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            put("notes", if (notes.length > notesLimit) notes.take(notesLimit) + "…" else notes)
        }
    }

    private fun JsonObjectBuilder.putCompletedAt(task: Task) {
        val at = task.completedAt ?: return
        val local = at.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        put("completed_at", local.toString())
        put("completed_days_ago", ChronoUnit.DAYS.between(local.toLocalDate(), today))
    }

    private fun projectPathOf(task: Task): String? =
        projectPath(task.projectId?.let { projectById[it] }, snapshot.projects)

    private fun tagNamesOf(task: Task): List<String> = task.tagIds.mapNotNull { tagById[it]?.name }

    private fun matchesStatus(task: Task, status: String): Boolean = when (status) {
        "open" -> !task.isDone
        "done" -> task.isDone
        else -> true
    }

    private fun RecurrenceRule.describe(): String {
        val unitWord = unit.name.lowercase() + if (interval == 1) "" else "s"
        val base = when (mode) {
            RecurrenceMode.SCHEDULE -> if (interval == 1) "every $unitWord" else "every $interval $unitWord"
            RecurrenceMode.AFTER_COMPLETION -> "$interval $unitWord after completion"
        }
        val days = daysOfWeek.sorted().joinToString(",") { it.name.lowercase() }
        return if (days.isEmpty()) base else "$base on $days"
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun Int?.clampedLimit(): Int = (this ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    companion object {
        const val SEARCH_TASKS = "search_tasks"
        const val LIST_TASKS = "list_tasks"
        const val GET_TASK = "get_task"
        const val LIST_PROJECTS = "list_projects"
        const val LIST_TAGS = "list_tags"

        private val STATUSES = listOf("any", "open", "done")
        private val SCOPES = listOf(
            "open", "overdue", "today", "tomorrow", "next_7_days", "no_due_date", "completed",
        )
        private val WHITESPACE = Regex("\\s+")
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 50
        private const val MAX_HISTORY = 50
        private const val NOTES_PREVIEW = 200
        private const val NOTES_FULL = 4000

        /**
         * The tool table the request carries. Frozen, and declared in one fixed order, because
         * the tools render ahead of the system prompt and the prompt cache is a prefix match: a
         * table that changed shape between two questions would throw the cache away each time.
         */
        val TOOLS: List<AssistantTool> = listOf(
            AssistantTool(
                name = SEARCH_TASKS,
                description = "Search the user's tasks by keywords. Matches the title, notes, " +
                    "project name, tag names and a step's parent title. Completed tasks are " +
                    "included, so this is how to find out when something was last done: search " +
                    "with status \"done\" and read completed_at of the newest match. Use one or " +
                    "two short keywords (a noun, not a whole sentence); results come newest first.",
                inputSchema = schema(
                    required = listOf("query"),
                    properties = {
                        putJsonObject("query") {
                            put("type", "string")
                            put("description", "Keywords to look for, e.g. \"kitchen\" or \"tax\".")
                        }
                        putJsonObject("status") {
                            put("type", "string")
                            putJsonArray("enum") { STATUSES.forEach { add(JsonPrimitive(it)) } }
                            put("description", "Restrict to open or done tasks. Default: any.")
                        }
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "How many matches to return, 1-$MAX_LIMIT. Default $DEFAULT_LIMIT.")
                        }
                    },
                ),
            ),
            AssistantTool(
                name = LIST_TASKS,
                description = "List tasks by scope: what is overdue, due today (overdue included), " +
                    "due tomorrow, due in the next 7 days, open with no due date, all open tasks, " +
                    "or completed within the last N days. Optionally narrow to one project or one tag.",
                inputSchema = schema(
                    required = emptyList(),
                    properties = {
                        putJsonObject("scope") {
                            put("type", "string")
                            putJsonArray("enum") { SCOPES.forEach { add(JsonPrimitive(it)) } }
                            put("description", "Which tasks to list. Default: open.")
                        }
                        putJsonObject("project") {
                            put("type", "string")
                            put("description", "Only tasks whose project name contains this text.")
                        }
                        putJsonObject("tag") {
                            put("type", "string")
                            put("description", "Only tasks wearing a tag whose name contains this text.")
                        }
                        putJsonObject("days") {
                            put("type", "integer")
                            put("description", "For scope \"completed\": how many days back to look. Default 30.")
                        }
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "How many tasks to return, 1-$MAX_LIMIT. Default $DEFAULT_LIMIT.")
                        }
                    },
                ),
            ),
            AssistantTool(
                name = GET_TASK,
                description = "Everything about one task by id: full notes, its steps, and for a " +
                    "recurring task every completed occurrence with its date, newest first. Use it " +
                    "for \"how often\" or \"when before that\" questions after a search found the task.",
                inputSchema = schema(
                    required = listOf("id"),
                    properties = {
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "A task id from an earlier result.")
                        }
                    },
                ),
            ),
            AssistantTool(
                name = LIST_PROJECTS,
                description = "The user's projects with open and done task counts, plus the Inbox.",
                inputSchema = schema(required = emptyList(), properties = {}),
            ),
            AssistantTool(
                name = LIST_TAGS,
                description = "The user's tags with the number of open tasks wearing each.",
                inputSchema = schema(required = emptyList(), properties = {}),
            ),
        )

        private fun schema(
            required: List<String>,
            properties: JsonObjectBuilder.() -> Unit,
        ): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", properties)
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }
    }
}
