package de.andi1984.cadence.assistant

/**
 * The Assistant voice-capture entry point (#46).
 *
 * There is no built-in intent (BII) for "create a task" or "create a note" in Google's App
 * Actions catalog — the closest fit is `actions.intent.CREATE_ITEM_LIST`
 * (`res/xml/shortcuts.xml`), whose `itemList.itemListElement.name` parameter is the first item a
 * spoken "create a list with …" names. Primico repurposes that one parameter as the quick-add
 * text rather than modelling a list: "Hey Google, create a list in Primico with buy milk" opens
 * the quick-add sheet with "buy milk" already parsed, same as if it had been typed.
 *
 * [EXTRA_ITEM_TEXT] is the `android:key` the capability's `<parameter>` targets in
 * `shortcuts.xml`, so the two have to change together.
 */
object AppActionsIntents {
    const val EXTRA_ITEM_TEXT = "de.andi1984.cadence.assistant.ITEM_TEXT"
}
