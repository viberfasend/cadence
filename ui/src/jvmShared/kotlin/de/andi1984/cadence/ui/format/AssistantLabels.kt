package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import de.andi1984.cadence.data.assistant.AssistantFailure
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.assistant_failed_offline
import de.andi1984.cadence.ui.resources.assistant_failed_rate_limited
import de.andi1984.cadence.ui.resources.assistant_failed_refused
import de.andi1984.cadence.ui.resources.assistant_failed_server
import de.andi1984.cadence.ui.resources.assistant_failed_unauthorized
import org.jetbrains.compose.resources.stringResource

/** Why a question got no answer, in words — `syncFailureText`'s shape for Ask Cadence. */
@Composable
fun assistantFailureText(reason: AssistantFailure): String = when (reason) {
    AssistantFailure.OFFLINE -> stringResource(Res.string.assistant_failed_offline)
    AssistantFailure.UNAUTHORIZED -> stringResource(Res.string.assistant_failed_unauthorized)
    AssistantFailure.RATE_LIMITED -> stringResource(Res.string.assistant_failed_rate_limited)
    AssistantFailure.SERVER -> stringResource(Res.string.assistant_failed_server)
    AssistantFailure.REFUSED -> stringResource(Res.string.assistant_failed_refused)
}
