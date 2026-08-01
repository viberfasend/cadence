package de.andi1984.cadence.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Light (direction 1a) ───────────────────────────────────────────────────────
private val LightPrimary = Color(0xFF006A60)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF9FF2E4)
private val LightOnPrimaryContainer = Color(0xFF00201C)
private val LightSecondaryContainer = Color(0xFFCDE8E1)
private val LightOnSecondaryContainer = Color(0xFF00201C)
private val LightBackground = Color(0xFFF4FBF8)
private val LightOnSurface = Color(0xFF171D1B)
private val LightSurfaceVariant = Color(0xFFDAE5E1)
private val LightOnSurfaceVariant = Color(0xFF3F4946)
private val LightOutline = Color(0xFF6F7976)
private val LightOutlineVariant = Color(0xFFBEC9C5)
private val LightError = Color(0xFFBA1A1A)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val LightTertiary = Color(0xFF3E6373)

// ── Dark (direction 1b) ────────────────────────────────────────────────────────
private val DarkPrimary = Color(0xFF83D5C6)
private val DarkOnPrimary = Color(0xFF00382F)
private val DarkPrimaryContainer = Color(0xFF005046)
private val DarkOnPrimaryContainer = Color(0xFF9FF2E4)
private val DarkSecondaryContainer = Color(0xFF334B47)
private val DarkOnSecondaryContainer = Color(0xFFCCE8E2)
private val DarkBackground = Color(0xFF0E1513)
private val DarkSurface = Color(0xFF1B2220)
private val DarkOnSurface = Color(0xFFDDE4E1)
private val DarkSurfaceVariant = Color(0xFF252B29)
private val DarkOnSurfaceVariant = Color(0xFFBEC9C5)
private val DarkOutline = Color(0xFF889390)
private val DarkOutlineVariant = Color(0xFF3F4946)
private val DarkError = Color(0xFFFFB4AB)
private val DarkErrorContainer = Color(0xFF3B0908)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)
private val DarkTertiary = Color(0xFF8DCDF2)

val CadenceLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2E8FF),
    onTertiaryContainer = Color(0xFF001E2E),
    error = LightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightBackground,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F7F4),
    surfaceContainer = Color(0xFFEFF5F2),
    surfaceContainerHigh = Color(0xFFE9EFEC),
    surfaceContainerHighest = Color(0xFFE3E9E6),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    inversePrimary = DarkPrimary,
)

val CadenceDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = Color(0xFF00344C),
    tertiaryContainer = Color(0xFF004C6B),
    onTertiaryContainer = Color(0xFFC2E8FF),
    error = DarkError,
    onError = Color(0xFF690005),
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkBackground,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1B2220),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303735),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = Color(0xFF2B3230),
    inversePrimary = LightPrimary,
)

/**
 * Colours the Material palette has no slot for: the priority spine, the overdue block and
 * the quick-add token highlights.
 */
@Immutable
data class CadenceColors(
    val priorityCritical: Color,
    val priorityHigh: Color,
    val priorityNormal: Color,
    val priorityTrack: Color,
    val overdueBlock: Color,
    val overdueRow: Color,
    val onOverdue: Color,
    val overdueAccent: Color,
    val tokenDate: Color,
    val onTokenDate: Color,
    val tokenPriority: Color,
    val onTokenPriority: Color,
    val tokenProject: Color,
    val onTokenProject: Color,
)

val LightCadenceColors = CadenceColors(
    priorityCritical = Color(0xFFBA1A1A),
    priorityHigh = Color(0xFFA1560A),
    priorityNormal = Color(0xFF6F7976),
    priorityTrack = Color(0xFFE3E0DD),
    overdueBlock = Color(0xFFFFDAD6),
    overdueRow = Color(0xFFFFF5F4),
    onOverdue = Color(0xFF410002),
    overdueAccent = Color(0xFF93000A),
    tokenDate = Color(0xFFC2E8FF),
    onTokenDate = Color(0xFF001E2E),
    tokenPriority = Color(0xFFFFDCC4),
    onTokenPriority = Color(0xFF3A1C00),
    tokenProject = Color(0xFFE5DFF0),
    onTokenProject = Color(0xFF1E1A24),
)

val DarkCadenceColors = CadenceColors(
    priorityCritical = Color(0xFFFFB4AB),
    priorityHigh = Color(0xFFFFB787),
    priorityNormal = Color(0xFFCCE8E2),
    priorityTrack = Color(0xFF2F3634),
    overdueBlock = Color(0xFF3B0908),
    overdueRow = Color(0xFF2A0B0A),
    onOverdue = Color(0xFFFFDAD6),
    overdueAccent = Color(0xFFFFB4AB),
    tokenDate = Color(0xFF004C6B),
    onTokenDate = Color(0xFFC2E8FF),
    tokenPriority = Color(0xFF4A3520),
    onTokenPriority = Color(0xFFFFDCC4),
    tokenProject = Color(0xFF3B3547),
    onTokenProject = Color(0xFFE5DFF0),
)

val LocalCadenceColors = staticCompositionLocalOf { LightCadenceColors }
