package de.andi1984.cadence.domain.assistant

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The toolbox against a small, hand-built list: what each tool answers, and the one rule that
 * matters most — a "when did I last" question is answered from the completed rows, newest
 * first, which the ordinary lists deliberately hide.
 */
class AssistantToolboxTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val now: Instant = today.atTime(14, 0).atZone(zone).toInstant()

    private val home = Project(id = "p-home", name = "Home")
    private val chores = Tag(id = "t-chores", name = "chores")

    private fun at(date: LocalDate, hour: Int = 18): Instant = date.atTime(hour, 0).atZone(zone).toInstant()

    private val kitchenOld = Task(
        id = "k1", title = "Clean the kitchen", projectId = home.id, tagIds = listOf(chores.id),
        dueDate = today.minusDays(14), completedAt = at(today.minusDays(14)),
        recurrence = RecurrenceRule(interval = 1, unit = RecurrenceUnit.WEEK),
    )
    private val kitchenRecent = kitchenOld.copy(
        id = "k2", spawnedFromId = "k1",
        dueDate = today.minusDays(7), completedAt = at(today.minusDays(4), hour = 9),
    )
    private val kitchenOpen = kitchenOld.copy(
        id = "k3", spawnedFromId = "k2", dueDate = today.plusDays(3), completedAt = null,
    )
    private val taxes = Task(id = "x1", title = "File taxes", notes = "Kitchen receipts too", dueDate = today.minusDays(1))
    private val call = Task(id = "c1", title = "Call the dentist", dueDate = today)
    private val someday = Task(id = "s1", title = "Learn the accordion")

    private val toolbox = AssistantToolbox(
        snapshot = AssistantSnapshot(
            tasks = listOf(kitchenOld, kitchenRecent, kitchenOpen, taxes, call, someday),
            projects = listOf(home),
            tags = listOf(chores),
        ),
        now = now,
        zone = zone,
    )

    private fun run(tool: String, input: JsonObject = JsonObject(emptyMap())): JsonObject {
        val outcome = toolbox.execute(tool, input)
        assertTrue("expected Ok, got $outcome", outcome is ToolOutcome.Ok)
        return Json.parseToJsonElement((outcome as ToolOutcome.Ok).json).jsonObject
    }

    private fun JsonObject.taskIds(): List<String> =
        this["tasks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.contentOrNull!! }

    @Test
    fun `searching done tasks answers when something was last done, newest first`() {
        val result = run(
            AssistantToolbox.SEARCH_TASKS,
            buildJsonObject { put("query", "kitchen"); put("status", "done") },
        )

        assertEquals(listOf("k2", "k1"), result.taskIds())
        val newest = result["tasks"]!!.jsonArray[0].jsonObject
        assertEquals("2026-09-01T09:00", newest["completed_at"]!!.jsonPrimitive.contentOrNull)
        assertEquals(4, newest["completed_days_ago"]!!.jsonPrimitive.int)
        assertEquals("every week", newest["repeats"]!!.jsonPrimitive.contentOrNull)
        assertEquals("Home", newest["project"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `search reads notes, projects and tags too and ranks a title hit first`() {
        val result = run(AssistantToolbox.SEARCH_TASKS, buildJsonObject { put("query", "kitchen") })

        val ids = result.taskIds()
        assertEquals(4, ids.size)
        assertTrue("the notes-only match comes last: $ids", ids.last() == "x1")

        val byTag = run(AssistantToolbox.SEARCH_TASKS, buildJsonObject { put("query", "chores"); put("status", "open") })
        assertEquals(listOf("k3"), byTag.taskIds())
    }

    @Test
    fun `an empty query and an unknown scope are errors the model can read`() {
        assertTrue(toolbox.execute(AssistantToolbox.SEARCH_TASKS, buildJsonObject { put("query", " ") }) is ToolOutcome.Error)
        assertTrue(toolbox.execute(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "yesterday") }) is ToolOutcome.Error)
        assertTrue(toolbox.execute("open_the_pod_bay_doors", JsonObject(emptyMap())) is ToolOutcome.Error)
    }

    @Test
    fun `list scopes split the open tasks by date`() {
        assertEquals(listOf("x1"), run(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "overdue") }).taskIds())
        // Today includes what is overdue: that is what the Today screen shows, too.
        assertEquals(listOf("x1", "c1"), run(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "today") }).taskIds())
        assertEquals(listOf("c1", "k3"), run(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "next_7_days") }).taskIds())
        assertEquals(listOf("s1"), run(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "no_due_date") }).taskIds())
        assertEquals(4, run(AssistantToolbox.LIST_TASKS).taskIds().size)
    }

    @Test
    fun `completed scope looks back a window of days and can be narrowed to a project`() {
        val lastWeek = run(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "completed"); put("days", 7) })
        assertEquals(listOf("k2"), lastWeek.taskIds())

        val month = run(AssistantToolbox.LIST_TASKS, buildJsonObject { put("scope", "completed"); put("project", "home") })
        assertEquals(listOf("k2", "k1"), month.taskIds())
    }

    @Test
    fun `get_task walks the whole recurrence chain from any row`() {
        val result = run(AssistantToolbox.GET_TASK, buildJsonObject { put("id", "k3") })

        assertEquals("k3", result["task"]!!.jsonObject["id"]!!.jsonPrimitive.contentOrNull)
        assertEquals(2, result["completed_occurrences"]!!.jsonPrimitive.int)
        val history = result["history"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.contentOrNull }
        assertEquals(listOf("k2", "k1"), history)
    }

    @Test
    fun `projects and tags come with their counts`() {
        val raw = (toolbox.execute(AssistantToolbox.LIST_PROJECTS, JsonObject(emptyMap())) as ToolOutcome.Ok).json
        val list = Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }
        val homeRow = list.first { it["id"]!!.jsonPrimitive.contentOrNull == "p-home" }
        assertEquals(1, homeRow["open_tasks"]!!.jsonPrimitive.int)
        assertEquals(2, homeRow["done_tasks"]!!.jsonPrimitive.int)
        val inbox = list.first { it["id"]!!.jsonPrimitive.contentOrNull == "inbox" }
        assertEquals(3, inbox["open_tasks"]!!.jsonPrimitive.int)

        val tags = (toolbox.execute(AssistantToolbox.LIST_TAGS, JsonObject(emptyMap())) as ToolOutcome.Ok).json
        val chores = Json.parseToJsonElement(tags).jsonArray.first().jsonObject
        assertEquals("chores", chores["name"]!!.jsonPrimitive.contentOrNull)
        assertEquals(1, chores["open_tasks"]!!.jsonPrimitive.int)
    }

    @Test
    fun `every declared tool is one the toolbox executes`() {
        AssistantToolbox.TOOLS.forEach { tool ->
            val input = buildJsonObject {
                put("query", "x")
                put("id", "k1")
            }
            assertTrue(tool.name, toolbox.execute(tool.name, input) is ToolOutcome.Ok)
        }
    }
}
