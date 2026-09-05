package de.andi1984.cadence.ui

import de.andi1984.cadence.data.assistant.AssistantFailure
import de.andi1984.cadence.data.assistant.AssistantMessage
import de.andi1984.cadence.data.assistant.AssistantRole
import de.andi1984.cadence.data.assistant.ClaudeAssistant
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.assistant.AssistantUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask Cadence from the ViewModel's side (ADR 0006): what a question does to the transcript,
 * that no request leaves without a key, and that clearing forgets everything. Same arrangement
 * as the other two ViewModel tests — a real repository over fakes — with the API answered by
 * Ktor's fake engine.
 */
class CadenceViewModelAssistantTest {

    private val taskStore = FakeTaskStore()
    private val projectStore = FakeProjectStore(taskStore)
    private val sectionStore = FakeSectionStore()
    private val tagStore = FakeTagStore()
    private val attachmentStore = FakeAttachmentStore()
    private val repository =
        repositoryOver(taskStore, projectStore, sectionStore, tagStore, attachmentStore)
    private val settings = FakeSettingsStore()

    private val requests = mutableListOf<HttpRequestData>()
    private var answer: String = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Four days ago."}]}"""
    private var status: HttpStatusCode = HttpStatusCode.OK

    /**
     * The fake engine on the test's own scheduler: `ask` is fire-and-forget on the ViewModel's
     * scope, so the test can only wait for the answer through `advanceUntilIdle`, and an engine
     * that hopped to a real thread to answer would slip past it.
     */
    private fun TestScope.http(): HttpClient = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    requests += request
                    respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        ),
    )

    private fun TestScope.viewModel(): CadenceViewModel {
        val viewModel = CadenceViewModel(
            repository = repository,
            settingsStore = settings,
            reminderScheduler = RecordingReminderScheduler(),
            backupGateway = FakeBackupGateway(),
            attachmentOpener = RecordingAttachmentOpener(),
            syncEngine = CadenceSyncEngine(FakeSyncStore(), backgroundScope),
            assistant = ClaudeAssistant(httpClient = http()),
            scope = backgroundScope,
        )
        backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        return viewModel
    }

    @Test
    fun `without a key no request is made and the screen is told why`() = runTest {
        val viewModel = viewModel()

        viewModel.ask("When did I last clean the kitchen?")
        advanceUntilIdle()

        assertEquals(0, requests.size)
        assertEquals(AssistantFailure.UNAUTHORIZED, viewModel.assistantState.value.failure)
        assertTrue(viewModel.assistantState.value.messages.isEmpty())
    }

    @Test
    fun `a question lands in the transcript at once and its answer follows`() = runTest {
        taskStore.seed(listOf(Task(id = "k1", title = "Clean the kitchen")))
        settings.setClaudeApiKey("sk-test")
        val viewModel = viewModel()

        viewModel.ask("  When did I last clean the kitchen?  ")
        assertTrue(viewModel.assistantState.value.busy)
        assertEquals(
            listOf(AssistantMessage(AssistantRole.USER, "When did I last clean the kitchen?")),
            viewModel.assistantState.value.messages,
        )

        advanceUntilIdle()

        val state = viewModel.assistantState.value
        assertFalse(state.busy)
        assertNull(state.failure)
        assertEquals(
            listOf(
                AssistantMessage(AssistantRole.USER, "When did I last clean the kitchen?"),
                AssistantMessage(AssistantRole.ASSISTANT, "Four days ago."),
            ),
            state.messages,
        )
        assertEquals(1, requests.size)
        assertEquals("sk-test", requests.single().headers["x-api-key"])
        val body = (requests.single().body as TextContent).text
        assertTrue(body.contains("When did I last clean the kitchen?"))
    }

    @Test
    fun `a failed round keeps the question, drops busy and names the reason`() = runTest {
        settings.setClaudeApiKey("sk-test")
        status = HttpStatusCode.Unauthorized
        answer = """{"type":"error"}"""
        val viewModel = viewModel()

        viewModel.ask("Anything due?")
        advanceUntilIdle()

        val state = viewModel.assistantState.value
        assertFalse(state.busy)
        assertEquals(AssistantFailure.UNAUTHORIZED, state.failure)
        assertEquals(1, state.messages.size)
    }

    @Test
    fun `clearing forgets the transcript, and a blank key is stored as no key`() = runTest {
        settings.setClaudeApiKey("sk-test")
        val viewModel = viewModel()
        viewModel.ask("Anything due?")
        advanceUntilIdle()
        assertEquals(2, viewModel.assistantState.value.messages.size)

        viewModel.clearConversation()
        assertEquals(AssistantUiState(), viewModel.assistantState.value)

        viewModel.setClaudeApiKey("   ")
        assertNull(settings.state.value.claudeApiKey)
        viewModel.setClaudeApiKey(" sk-new ")
        assertEquals("sk-new", settings.state.value.claudeApiKey)
    }
}
