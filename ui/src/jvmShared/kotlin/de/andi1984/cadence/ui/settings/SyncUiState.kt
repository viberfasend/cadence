package de.andi1984.cadence.ui.settings

import de.andi1984.cadence.data.sync.SyncStatus

/** Why a sign-in did not go through, at the granularity a message can usefully distinguish. */
enum class SignInError { WRONG_CREDENTIALS, OFFLINE, SERVER }

/**
 * Sync as the Settings screen needs it: what the engine says about itself, plus the part of a
 * sign-in attempt only the ViewModel knows — that one is in flight, and how the last one failed.
 *
 * The server's own error text is deliberately not carried through. Every user-visible string in
 * this app comes from `composeResources` (see CLAUDE.md), and a message written in the server's
 * language is exactly the kind of prose that would quietly appear in English in a German install.
 */
data class SyncUiState(
    val status: SyncStatus = SyncStatus.SignedOut,
    val signingIn: Boolean = false,
    val error: SignInError? = null,
)
