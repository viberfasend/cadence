package de.andi1984.cadence.ui.assistant

import de.andi1984.cadence.data.assistant.AssistantFailure
import de.andi1984.cadence.data.assistant.AssistantMessage

/**
 * Ask Cadence's conversation, as the screen draws it (ADR 0006).
 *
 * Held beside `CadenceUiState` rather than inside it: a transcript is a screen's own scratch,
 * not a record — it is not stored, not synced, and gone when the conversation is cleared — and
 * folding it into the five-flow `combine` would cost a pairing for something no other screen
 * reads.
 */
data class AssistantUiState(
    /** Oldest first; the question just asked is the last one while [busy]. */
    val messages: List<AssistantMessage> = emptyList(),
    /** A question is out and nothing has come back yet. */
    val busy: Boolean = false,
    /** Why the last question got no answer, cleared by the next one. */
    val failure: AssistantFailure? = null,
)
