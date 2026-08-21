package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.andi1984.cadence.R

/**
 * A one-tap "add a task" button for the home screen: it opens the app straight into the same
 * [de.andi1984.cadence.ui.quickadd.QuickAddSheet] the in-app FAB opens, with the same parser
 * behind it, so "pay rent tomorrow 9am p1" works from the home screen exactly as it does inside
 * the app.
 *
 * The sheet is deliberately *not* reimplemented in Glance. A widget cannot host a text field —
 * RemoteViews has no editable input — so a "type it in the widget" design would mean a second,
 * worse composer with none of the quick-add grammar behind it. Opening the real one is both the
 * only workable design and the better one.
 *
 * [SizeMode.Responsive] with two sizes rather than [SizeMode.Exact]: this widget has exactly two
 * useful layouts — icon alone when it is square, icon and label when it is wide — so a fixed pair
 * of breakpoints says what it means, and Glance can pick between them without a round trip.
 */
class CadenceQuickAddWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(COMPACT, WIDE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme(colors = CadenceWidgetColors) {
                QuickAddContent(context)
            }
        }
    }

    private companion object {
        val COMPACT = DpSize(56.dp, 56.dp)
        val WIDE = DpSize(140.dp, 56.dp)
    }
}

@Composable
private fun QuickAddContent(context: Context) {
    val label = context.getString(R.string.widget_add_task)
    // Wide enough for the label to be worth drawing — below this it would ellipsize to nothing
    // useful and the icon says the same thing on its own.
    val showLabel = LocalSize.current.width >= 120.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(24.dp)
            .clickable(actionStartActivity(WidgetIntents.openQuickAdd(context)))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                // The label repeats it when it is drawn, so the icon only has to announce
                // itself when it is standing alone.
                contentDescription = if (showLabel) null else label,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                modifier = GlanceModifier.size(24.dp),
            )
            if (showLabel) {
                Text(
                    text = label,
                    maxLines = 1,
                    modifier = GlanceModifier.padding(start = 8.dp),
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}
