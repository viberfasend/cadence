package de.andi1984.cadence.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.data.assistant.AssistantMessage
import de.andi1984.cadence.data.assistant.AssistantRole
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.format.assistantFailureText
import de.andi1984.cadence.ui.platform.VoiceInput
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Ask Cadence (ADR 0006): a question in your own words, typed or spoken, answered by Claude
 * from the task list on this device.
 *
 * Stateless like every other screen — the transcript lives in `CadenceViewModel.assistantState`
 * — and shared by both shells: Android hands in its speech recogniser as [voiceInput], the
 * desktop hands in `NoVoiceInput` and the microphone is simply not drawn. Without an API key the
 * screen explains what it needs and points at Settings rather than failing on the first question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    state: CadenceUiState,
    assistant: AssistantUiState,
    voiceInput: VoiceInput,
    onBack: () -> Unit,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
) {
    val hasKey = !state.settings.claudeApiKey.isNullOrBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
            Text(
                text = stringResource(Res.string.assistant_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (assistant.messages.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(AppIcons.Delete, contentDescription = stringResource(Res.string.assistant_clear))
                }
            }
        }

        if (!hasKey) {
            NoKeyState(onSettings = onSettings)
            return@Column
        }

        Transcript(
            assistant = assistant,
            onAsk = onAsk,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Composer(
            busy = assistant.busy,
            voiceInput = voiceInput,
            onAsk = onAsk,
        )
    }
}

@Composable
private fun NoKeyState(onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = stringResource(Res.string.assistant_no_key_title),
            supporting = stringResource(Res.string.assistant_no_key_supporting),
        )
        Button(onClick = onSettings) {
            Text(stringResource(Res.string.assistant_open_settings))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transcript(
    assistant: AssistantUiState,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (assistant.messages.isEmpty() && !assistant.busy) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                title = stringResource(Res.string.assistant_empty_title),
                supporting = stringResource(Res.string.assistant_empty_supporting),
            )
            // Three questions the feature exists for, as chips: a tap asks them outright, and
            // they show the shape of question that works better than any placeholder could.
            val examples = listOf(
                stringResource(Res.string.assistant_example_last_done),
                stringResource(Res.string.assistant_example_this_week),
                stringResource(Res.string.assistant_example_project_open),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                examples.forEach { example ->
                    CadenceChip(label = example, selected = false, onClick = { onAsk(example) })
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    // A new line — asked or answered — and the thinking row both scroll into view; the reader
    // is always looking at the end of a conversation.
    val rowCount = assistant.messages.size + (if (assistant.busy) 1 else 0) + (if (assistant.failure != null) 1 else 0)
    LaunchedEffect(rowCount) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(assistant.messages) { _, message -> MessageBubble(message) }
        if (assistant.busy) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = stringResource(Res.string.assistant_thinking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        assistant.failure?.let { failure ->
            item {
                Text(
                    text = assistantFailureText(failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AssistantMessage) {
    val fromUser = message.role == AssistantRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (fromUser) 16.dp else 4.dp,
                bottomEnd = if (fromUser) 4.dp else 16.dp,
            ),
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            // An answer is something to copy — a date, a task name — so it is selectable; the
            // question is the user's own words and needs no such thing.
            if (fromUser) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    busy: Boolean,
    voiceInput: VoiceInput,
    onAsk: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun send() {
        val question = draft.trim()
        if (question.isEmpty() || busy) return
        onAsk(question)
        draft = ""
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(Res.string.assistant_placeholder)) },
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    // Enter sends and Shift+Enter breaks the line — on a hardware keyboard the
                    // field is a chat box, not a notes field.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
            )
            if (voiceInput.isAvailable) {
                IconButton(
                    onClick = {
                        // What was heard is asked at once rather than put in the field: a spoken
                        // question is a question, and reading it back first would make the
                        // microphone slower than typing.
                        voiceInput.listen { heard -> heard?.takeIf { it.isNotBlank() }?.let(onAsk) }
                    },
                    enabled = !busy,
                ) {
                    Icon(AppIcons.Mic, contentDescription = stringResource(Res.string.assistant_speak))
                }
            }
            IconButton(onClick = { send() }, enabled = draft.isNotBlank() && !busy) {
                Icon(AppIcons.Send, contentDescription = stringResource(Res.string.assistant_send))
            }
        }
        Text(
            text = stringResource(Res.string.assistant_footnote),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}
