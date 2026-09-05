package de.andi1984.cadence.data.assistant

import de.andi1984.cadence.domain.assistant.AssistantSnapshot
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * The client against Ktor's [MockEngine], the way `CadenceSyncEngineTest` does it: every
 * response is written by the test, so what is pinned is the wire — the headers the API insists
 * on, the tool loop, and which failure each kind of answer maps to.
 */
class ClaudeAssistantTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val clock: Clock = Clock.fixed(today.atTime(14, 0).atZone(zone).toInstant(), zone)

    private val kitchen = Task(
        id = "k1", title = "Clean the kitchen",
        completedAt = today.minusDays(4).atTime(9, 0).atZone(zone).toInstant(),
    )
    private val snapshot = AssistantSnapshot(tasks = listOf(kitchen), projects = emptyList(), tags = emptyList())

    private fun assistant(http: RecordingHttp) =
        ClaudeAssistant(httpClient = HttpClient(http.engine), clock = clock)

    private fun HttpRequestData.json(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun `a question runs the tool loop and comes back as the final text`() = runTest {
        var round = 0
        val http = RecordingHttp {
            when (++round) {
                1 -> respondJson(
                    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                        "stop_reason":"tool_use",
                        "content":[
                          {"type":"thinking","thinking":"","signature":"sig"},
                          {"type":"text","text":"Let me look."},
                          {"type":"tool_use","id":"toolu_1","name":"search_tasks",
                           "input":{"query":"kitchen","status":"done"}}
                        ]}""",
                )
                else -> respondJson(
                    """{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5",
                        "stop_reason":"end_turn",
                        "content":[{"type":"text","text":"Tuesday, 1 September — four days ago."}]}""",
                )
            }
        }

        val result = assistant(http).ask("sk-test", emptyList(), "When did I last clean the kitchen?", snapshot)

        assertEquals(AssistantResult.Answer("Tuesday, 1 September — four days ago."), result)
        assertEquals(2, http.requests.size)

        val first = http.requests[0]
        assertEquals("sk-test", first.headers["x-api-key"])
        assertEquals(ClaudeAssistant.API_VERSION, first.headers["anthropic-version"])
        assertEquals(ClaudeAssistant.BETA_FLAGS, first.headers["anthropic-beta"])
        assertEquals("/v1/messages", first.url.encodedPath)
        val firstBody = first.json()
        assertEquals(ClaudeAssistant.DEFAULT_MODEL, firstBody["model"]!!.jsonPrimitive.contentOrNull)
        assertEquals("default", firstBody["fallbacks"]!!.jsonPrimitive.contentOrNull)
        assertEquals(5, firstBody["tools"]!!.jsonArray.size)
        val system = firstBody["system"]!!.jsonArray
        assertEquals(ClaudeAssistant.SYSTEM_PROMPT, system[0].jsonObject["text"]!!.jsonPrimitive.contentOrNull)
        assertTrue(system[1].jsonObject["text"]!!.jsonPrimitive.contentOrNull!!.contains("Saturday, 2026-09-05"))

        // Round two replays the assistant turn unchanged — thinking block included — and
        // answers the tool call with what the toolbox found.
        val second = http.requests[1].json()["messages"]!!.jsonArray
        assertEquals(3, second.size)
        val echoed = second[1].jsonObject
        assertEquals("assistant", echoed["role"]!!.jsonPrimitive.contentOrNull)
        assertEquals("thinking", echoed["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.contentOrNull)
        val toolResult = second[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.contentOrNull)
        assertEquals("toolu_1", toolResult["tool_use_id"]!!.jsonPrimitive.contentOrNull)
        assertTrue(toolResult["content"]!!.jsonPrimitive.contentOrNull!!.contains("2026-09-01T09:00"))
    }

    @Test
    fun `earlier turns are replayed as text before the new question`() = runTest {
        val http = RecordingHttp {
            respondJson("""{"stop_reason":"end_turn","content":[{"type":"text","text":"Yes."}]}""")
        }
        val history = listOf(
            AssistantMessage(AssistantRole.USER, "What is due today?"),
            AssistantMessage(AssistantRole.ASSISTANT, "Nothing."),
        )

        assistant(http).ask("sk-test", history, "Really?", snapshot)

        val messages = http.requests.single().json()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.contentOrNull })
        assertEquals("Really?", messages[2]["content"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `a bad tool input is answered as an error result, not a crash`() = runTest {
        var round = 0
        val http = RecordingHttp {
            when (++round) {
                1 -> respondJson(
                    """{"stop_reason":"tool_use","content":[
                        {"type":"tool_use","id":"toolu_1","name":"get_task","input":{"id":"nope"}}]}""",
                )
                else -> respondJson("""{"stop_reason":"end_turn","content":[{"type":"text","text":"No such task."}]}""")
            }
        }

        val result = assistant(http).ask("sk-test", emptyList(), "?", snapshot)

        assertEquals(AssistantResult.Answer("No such task."), result)
        val toolResult = http.requests[1].json()["messages"]!!.jsonArray[2]
            .jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("true", toolResult["is_error"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `a refusal, a rejected key, a rate limit and no network each map to their failure`() = runTest {
        val refused = RecordingHttp { respondJson("""{"stop_reason":"refusal","content":[]}""") }
        assertEquals(
            AssistantFailure.REFUSED,
            (assistant(refused).ask("k", emptyList(), "?", snapshot) as AssistantResult.Failed).reason,
        )

        val unauthorized = RecordingHttp {
            respond("""{"type":"error","error":{"type":"authentication_error"}}""", HttpStatusCode.Unauthorized)
        }
        assertEquals(
            AssistantFailure.UNAUTHORIZED,
            (assistant(unauthorized).ask("k", emptyList(), "?", snapshot) as AssistantResult.Failed).reason,
        )

        val limited = RecordingHttp { respond("", HttpStatusCode.TooManyRequests) }
        assertEquals(
            AssistantFailure.RATE_LIMITED,
            (assistant(limited).ask("k", emptyList(), "?", snapshot) as AssistantResult.Failed).reason,
        )

        val offline = RecordingHttp { throw IOException("no route") }
        assertEquals(
            AssistantFailure.OFFLINE,
            (assistant(offline).ask("k", emptyList(), "?", snapshot) as AssistantResult.Failed).reason,
        )

        val garbage = RecordingHttp { respond("<html>", HttpStatusCode.OK) }
        assertEquals(
            AssistantFailure.SERVER,
            (assistant(garbage).ask("k", emptyList(), "?", snapshot) as AssistantResult.Failed).reason,
        )
    }

    @Test
    fun `a model that never stops calling tools is cut off rather than looped forever`() = runTest {
        val http = RecordingHttp {
            respondJson(
                """{"stop_reason":"tool_use","content":[
                    {"type":"tool_use","id":"toolu_x","name":"list_projects","input":{}}]}""",
            )
        }

        val result = assistant(http).ask("k", emptyList(), "?", snapshot)

        assertTrue(result is AssistantResult.Failed)
        assertEquals(AssistantFailure.SERVER, (result as AssistantResult.Failed).reason)
        assertTrue(http.requests.size in 2..20)
    }
}

private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData = respond(
    content = body,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

/** Records every request and answers with whatever the test's handler says. */
private class RecordingHttp(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests: MutableList<HttpRequestData> = java.util.Collections.synchronizedList(mutableListOf())
    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
}
