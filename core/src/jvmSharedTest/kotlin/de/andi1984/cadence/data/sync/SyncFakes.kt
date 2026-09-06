package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.util.Base64

/*
 * The fakes the sync tests share — `:ui`'s `Fakes.kt` in shape: plain state, every write visible
 * to the test, nothing stubbed by name. The HTTP fake is Ktor's own [MockEngine]; every response
 * is written by the test that needs it.
 */

/** Far enough that the 30-second refresh margin never triggers in a test. */
internal const val FAR_FUTURE = 4_102_444_800L // 2100-01-01

/** A syntactically valid JWT whose payload carries only `exp` — enough for the client, which
 *  reads the claim without verifying anything. */
internal fun jwt(expEpochSecond: Long): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = encoder.encodeToString("""{"exp":$expEpochSecond}""".toByteArray())
    return "$header.$payload.sig"
}

internal fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

internal fun unexpected(request: HttpRequestData): Nothing =
    throw AssertionError("unexpected request: ${request.method.value} ${request.url}")

/** Records every request and answers with whatever the test's handler says. */
internal class RecordingHttp(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests: MutableList<HttpRequestData> =
        java.util.Collections.synchronizedList(mutableListOf())
    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
}

/** The store in `Fakes.kt`'s shape: plain state, every write visible to the test. */
internal class InMemorySyncStore : SyncStore {

    var stateValue = SyncState()
    var tasks: List<Task> = emptyList()
    var projects: List<Project> = emptyList()
    var sections: List<Section> = emptyList()
    var tags: List<Tag> = emptyList()
    val mergedTasks = mutableListOf<Task>()

    /** The task cursor each [mergeAndAdvance] carried, page by page — null where a page left
     *  it alone. */
    val advancedTaskCursors = mutableListOf<String?>()

    /** Every local sweep, as the horizon it was asked to collect before. */
    val sweeps = mutableListOf<Instant>()

    val tombstonesCollected: Boolean get() = sweeps.isNotEmpty()

    override suspend fun state(): SyncState = stateValue

    override suspend fun setSession(session: String?) {
        stateValue = stateValue.copy(session = session)
    }

    override suspend fun tasksChangedSince(since: Instant): List<Task> =
        tasks.filter { it.updatedAt.isAfter(since) }

    override suspend fun projectsChangedSince(since: Instant): List<Project> =
        projects.filter { it.updatedAt.isAfter(since) }

    override suspend fun sectionsChangedSince(since: Instant): List<Section> =
        sections.filter { it.updatedAt.isAfter(since) }

    override suspend fun tagsChangedSince(since: Instant): List<Tag> =
        tags.filter { it.updatedAt.isAfter(since) }

    override suspend fun mergeAndAdvance(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
        sectionCursor: String?,
        tagCursor: String?,
    ) {
        mergedTasks += tasks
        advancedTaskCursors += taskCursor
        stateValue = stateValue.copy(
            taskCursor = taskCursor ?: stateValue.taskCursor,
            projectCursor = projectCursor ?: stateValue.projectCursor,
            sectionCursor = sectionCursor ?: stateValue.sectionCursor,
            tagCursor = tagCursor ?: stateValue.tagCursor,
        )
    }

    override suspend fun setPushWatermark(at: Instant) {
        stateValue = stateValue.copy(pushWatermark = at)
    }

    override suspend fun setLastSyncedAt(at: Instant) {
        stateValue = stateValue.copy(lastSyncedAt = at)
    }

    override suspend fun collectTombstones(before: Instant, at: Instant) {
        sweeps += before
        stateValue = stateValue.copy(lastSweepAt = at)
    }

    override suspend fun clear() {
        stateValue = SyncState()
    }
}
