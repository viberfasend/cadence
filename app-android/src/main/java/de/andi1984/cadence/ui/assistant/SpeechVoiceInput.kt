package de.andi1984.cadence.ui.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import de.andi1984.cadence.ui.format.currentLocale
import de.andi1984.cadence.ui.platform.VoiceInput

/**
 * Android's [VoiceInput]: the system speech recogniser behind [RecognizerIntent]
 * (ADR 0006, decision 5).
 *
 * The *intent*, not the `SpeechRecognizer` service, on purpose. The intent shows the system
 * listening dialog, holds `RECORD_AUDIO` itself and hands back text through an activity result,
 * so the app declares no microphone permission and asks the user for nothing — the recogniser
 * is what they already talk to. The service would give a custom listening UI and cost a
 * permission prompt, a manifest entry and an audio-focus dance for a button pressed once a day.
 *
 * A composable, like the SAF pickers, because an activity-result launcher can only be
 * registered in a composition; the object it returns is stable across recompositions.
 */
@Composable
fun rememberSpeechVoiceInput(): VoiceInput {
    val context = LocalContext.current
    val locale = currentLocale()
    val input = remember { SpeechVoiceInput() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val heard = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        input.deliver(heard)
    }
    input.isAvailable = remember(context) { canRecognise(context) }
    input.launch = { intent -> launcher.launch(intent) }
    input.languageTag = locale.toLanguageTag()
    return input
}

private class SpeechVoiceInput : VoiceInput {

    override var isAvailable: Boolean = false
    var launch: (Intent) -> Unit = {}
    var languageTag: String = ""

    /** The one caller waiting for a result; the recogniser is modal, so never more. */
    private var pending: ((String?) -> Unit)? = null

    override fun listen(onResult: (String?) -> Unit) {
        pending = onResult
        try {
            launch(recogniseIntent(languageTag))
        } catch (e: ActivityNotFoundException) {
            // `isAvailable` said yes and the package has gone since — a rare race, answered
            // like a cancel rather than crashed on.
            deliver(null)
        }
    }

    fun deliver(heard: String?) {
        val waiting = pending
        pending = null
        waiting?.invoke(heard)
    }
}

/** Whether anything on this device answers the recogniser intent — needs the `<queries>` entry
 *  in the manifest to see past package visibility on Android 11+. */
private fun canRecognise(context: Context): Boolean =
    context.packageManager
        .queryIntentActivities(recogniseIntent(null), PackageManager.MATCH_DEFAULT_ONLY)
        .isNotEmpty()

private fun recogniseIntent(languageTag: String?): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // The app language, not the system one: a German install on an English phone hears
        // German, the same rule every formatter follows through `currentLocale()`.
        if (!languageTag.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
