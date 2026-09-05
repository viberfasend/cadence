package de.andi1984.cadence.data.assistant

import de.andi1984.cadence.domain.assistant.AssistantSnapshot
import de.andi1984.cadence.domain.assistant.AssistantToolbox
import de.andi1984.cadence.domain.assistant.ToolOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who said a line in the conversation the screen shows and the next request replays. */
enum class AssistantRole { USER, ASSISTANT }

/** One line of the conversation: a question, or the answer it got. */
data class AssistantMessage(val role: AssistantRole, val text: String)

/** Why a question got no answer, in the shape the screen words (`ui/format/AssistantLabels.kt`). */
enum class AssistantFailure {
    /** No network, or the request never reached the API. */
    OFFLINE,
    /** The API rejected the key — wrong, revoked, or out of credit. */
    UNAUTHORIZED,
    /** The API asked for a pause (429 or 529). */
    RATE_LIMITED,
    /** Anything else the API answered with, or an answer that could not be read. */
    SERVER,
    /** The model declined the question outright (`stop_reason: refusal`). */
    REFUSED,
}

sealed interface AssistantResult {
    data class Answer(val text: String) : AssistantResult

    /** [detail] is for a log, never for the screen: the API's own error text, truncated. */
    data class Failed(val reason: AssistantFailure, val detail: String? = null) : AssistantResult
}

/**
 * The one Claude API call the app makes (ADR 0006): a question about the task list, answered
 * over `POST /v1/messages` with tool use, where every tool is an [AssistantToolbox] read of the
 * snapshot the caller handed in. Nothing about the list is sent up front — the model asks for
 * what it needs and the toolbox answers from the device.
 *
 * Hand-rolled over Ktor rather than the Anthropic Java SDK, for the reason the sync engine is
 * (ADR 0005): the SDK wants Jackson and OkHttp on both targets and an Android runtime under the
 * JVM tests, and the wire is one endpoint with a JSON body. Everything the request needs to be
 * — the model, the version header, the beta flag that turns on server-side refusal fallbacks —
 * sits in the companion, so a change to any of them is one line.
 *
 * The key is the user's own and travels only in the `x-api-key` header of this request; the
 * class never stores it. Where it *is* stored is the settings store, per device, beside the
 * theme (ADR 0006, decision 2).
 */
class ClaudeAssistant(
    httpClient: HttpClient? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    /** Injected so a test can pin "today"; the toolbox resolves every relative date against it. */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    private val http: HttpClient = httpClient ?: defaultHttpClient()

    /**
     * Answers [question] against [snapshot], continuing [history] — the text turns shown on
     * screen, oldest first. Only text is replayed: the tool calls behind an earlier answer are
     * not carried forward, so a follow-up question re-reads the (possibly changed) list rather
     * than an answer's stale evidence.
     */
    suspend fun ask(
        apiKey: String,
        history: List<AssistantMessage>,
        question: String,
        snapshot: AssistantSnapshot,
    ): AssistantResult {
        val now = clock.instant()
        val zone = clock.zone
        val toolbox = AssistantToolbox(snapshot, now, zone)

        val messages = mutableListOf<JsonObject>()
        history.takeLast(MAX_HISTORY_TURNS)
            .filter { it.text.isNotBlank() }
            .forEach { messages += textMessage(it.role.wire, it.text) }
        messages += textMessage("user", question)

        repeat(MAX_TOOL_ROUNDS) {
            val response = try {
                send(apiKey, messages, now, zone)
            } catch (e: IOException) {
                return AssistantResult.Failed(AssistantFailure.OFFLINE, e.message)
            } catch (e: AssistantHttpException) {
                return AssistantResult.Failed(e.toFailure(), e.body)
            } catch (e: AssistantResponseException) {
                return AssistantResult.Failed(AssistantFailure.SERVER, e.message)
            }

            val stopReason = response["stop_reason"]?.jsonPrimitive?.contentOrNull
            if (stopReason == "refusal") return AssistantResult.Failed(AssistantFailure.REFUSED)

            val content = response["content"]?.jsonArray ?: JsonArray(emptyList())
            val toolUses = content.mapNotNull { it as? JsonObject }.filter { it.type == "tool_use" }
            if (stopReason != "tool_use" || toolUses.isEmpty()) {
                val text = content.mapNotNull { it as? JsonObject }
                    .filter { it.type == "text" }
                    .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n")
                    .trim()
                return if (text.isEmpty()) {
                    AssistantResult.Failed(AssistantFailure.SERVER, "empty answer ($stopReason)")
                } else {
                    AssistantResult.Answer(text)
                }
            }

            // The assistant turn goes back exactly as it came — thinking blocks included, which
            // the API requires on a continued turn — and every tool result rides in *one* user
            // message, since splitting them teaches the model to stop calling tools in parallel.
            messages += buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(echoable(content)))
            }
            messages += buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    toolUses.forEach { use -> add(toolResult(use, toolbox)) }
                }
            }
        }
        return AssistantResult.Failed(AssistantFailure.SERVER, "no answer after $MAX_TOOL_ROUNDS tool rounds")
    }

    private suspend fun send(
        apiKey: String,
        messages: List<JsonObject>,
        now: Instant,
        zone: ZoneId,
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            // Server-side refusal fallback: a safety classifier that declines re-runs the same
            // request on another model inside this call, instead of handing back a refusal for
            // a question about the laundry. Needs BETA_FLAGS on the request.
            put("fallbacks", "default")
            putJsonObject("output_config") { put("effort", EFFORT) }
            putJsonArray("system") {
                // The stable half first, with the cache breakpoint on it; the date after it, so
                // a new day changes the suffix and not the cached prefix.
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", SYSTEM_PROMPT)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", contextLine(now, zone))
                    },
                )
            }
            putJsonArray("tools") {
                AssistantToolbox.TOOLS.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.inputSchema)
                        },
                    )
                }
            }
            putJsonArray("messages") { messages.forEach { add(it) } }
        }

        val response = http.post("$baseUrl/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
            header("anthropic-beta", BETA_FLAGS)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw AssistantHttpException(response.status.value, text.take(BODY_LIMIT))
        }
        return runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw AssistantResponseException("unreadable answer: ${text.take(BODY_LIMIT)}") }
    }

    /** Runs one `tool_use` block through the toolbox and shapes what came back as its
     *  `tool_result`, flagged `is_error` when the toolbox refused the input. */
    private fun toolResult(use: JsonObject, toolbox: AssistantToolbox): JsonObject {
        val id = use["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = use["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val input = use["input"] as? JsonObject ?: JsonObject(emptyMap())
        val outcome = try {
            toolbox.execute(name, input)
        } catch (e: RuntimeException) {
            ToolOutcome.Error("tool failed: ${e.message}")
        }
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", id)
            when (outcome) {
                is ToolOutcome.Ok -> put("content", outcome.json)
                is ToolOutcome.Error -> {
                    put("content", outcome.message)
                    put("is_error", true)
                }
            }
        }
    }

    /**
     * The blocks an assistant turn may be echoed with.
     *
     * A server-side fallback marks the switch with a `fallback` block; the model that continued
     * never saw what the declined model put before it, so those blocks — a `thinking`, a
     * `tool_use` — must not be replayed as if it had. Text before the marker is kept (it is
     * what the fallback model was given as its continuation), the marker itself is dropped.
     */
    private fun echoable(content: JsonArray): List<JsonElement> {
        val lastFallback = content.indexOfLast { (it as? JsonObject)?.type == "fallback" }
        return content.filterIndexed { index, block ->
            val type = (block as? JsonObject)?.type
            when {
                type == "fallback" -> false
                index < lastFallback -> type == "text"
                else -> true
            }
        }
    }

    private fun contextLine(now: Instant, zone: ZoneId): String {
        val local = now.atZone(zone)
        return "Today is ${local.format(CONTEXT_DATE)}, the time is ${local.format(CONTEXT_TIME)}, " +
            "time zone ${zone.id}."
    }

    private fun textMessage(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private val JsonObject.type: String?
        get() = this["type"]?.jsonPrimitive?.contentOrNull

    private val AssistantRole.wire: String
        get() = when (this) {
            AssistantRole.USER -> "user"
            AssistantRole.ASSISTANT -> "assistant"
        }

    private fun AssistantHttpException.toFailure(): AssistantFailure = when (status) {
        401, 403 -> AssistantFailure.UNAUTHORIZED
        429, 529 -> AssistantFailure.RATE_LIMITED
        else -> AssistantFailure.SERVER
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"

        /** The model every question goes to. Opus-tier on purpose — an answer that names the
         *  wrong date is worse than no answer, and a chat this short costs cents either way. */
        const val DEFAULT_MODEL = "claude-opus-5"

        const val API_VERSION = "2023-06-01"

        /** Turns on `fallbacks: "default"` (see [send]); the two go together or not at all. */
        const val BETA_FLAGS = "server-side-fallback-2026-07-01"

        /** Chat about chores, not a proof: enough thinking to read a tool result correctly,
         *  not enough to bill for a paragraph nobody reads. */
        private const val EFFORT = "medium"

        private const val MAX_TOKENS = 4096

        /** Search, then maybe a `get_task`, then an answer — anything beyond a handful of rounds
         *  is the model looping, and the user is waiting. */
        private const val MAX_TOOL_ROUNDS = 8

        /** How much of the conversation is replayed. Twenty lines is ten exchanges, more than
         *  a question about the laundry needs, and it bounds what a long session costs. */
        private const val MAX_HISTORY_TURNS = 20

        private const val BODY_LIMIT = 500

        private val CONTEXT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd", Locale.ENGLISH)
        private val CONTEXT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /**
         * Frozen text: it is the cached prefix, so a byte changed here is a cache miss for every
         * question until it is warm again. The date goes in a block of its own, after it.
         */
        const val SYSTEM_PROMPT: String =
            "You are the assistant inside Cadence, a personal to-do app. You answer questions " +
                "about the user's own task list: what is due, what was done and when, what is " +
                "still open in a project or under a tag, how often something recurs. The tools " +
                "read the list stored on this device; they are the only source of truth.\n\n" +
                "Look before you answer: search or list first, then answer from what came " +
                "back. Never guess a date or a task. Completed tasks stay in the list with a " +
                "completed_at timestamp, and a recurring task is a chain of rows, one per " +
                "occurrence, so \"when did I last …\" is a search with status \"done\" and " +
                "the newest completed_at; \"how often\" is get_task on any row of the chain.\n\n" +
                "Answer in the language the user wrote in, briefly and concretely. Name the " +
                "task and give the date as a weekday and date plus how long ago or how far away " +
                "it is. Prefer a sentence or two over a list; use a short list only when the " +
                "answer is several tasks. Never show ids. If nothing matches, say so plainly and " +
                "suggest a different keyword.\n\n" +
                "You cannot change anything. If asked to add, complete, move or delete a task, " +
                "say that the app itself does that and answer whatever question remains."

        /** Generous on purpose: a question that makes the model search three times and then
         *  think is well over the ten seconds a sync round is allowed. */
        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 180_000
            }
        }
    }
}

/** A non-2xx answer from the API, with as much of its body as is worth keeping. */
internal class AssistantHttpException(val status: Int, val body: String) :
    Exception("Claude API answered $status: $body")

/** A 2xx answer that was not the JSON the API documents. */
internal class AssistantResponseException(message: String) : Exception(message)
