package com.ffvpn.client.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.rtl

/**
 * The chosen page-wide theme, as one piece of state: the settings sheet writes
 * it, and every screen inside [VinTheme] — home, tools, settings, the sign-in
 * pages — recomposes in the new colors at once. Loaded from [Prefs.themeColor]
 * once at startup (see App.onCreate).
 */
object ThemeState {
    var name by mutableStateOf("")
}

/**
 * Lotus: jade over deep indigo, with orchid and gold as the two quieter voices.
 * The palette is dark by design, because the orb and its halo are the screen.
 */
private val LotusNight = darkColorScheme(
    primary = Color(0xFF6FE7C4),
    onPrimary = Color(0xFF03251C),
    primaryContainer = Color(0xFF0E3B31),
    onPrimaryContainer = Color(0xFFB8FFE9),
    secondary = Color(0xFFC7A8FF),
    onSecondary = Color(0xFF1B0B33),
    secondaryContainer = Color(0xFF2A1B4A),
    onSecondaryContainer = Color(0xFFEDE1FF),
    tertiary = Color(0xFFFFCB8B),
    onTertiary = Color(0xFF2B1800),
    background = Color(0xFF06080E),
    onBackground = Color(0xFFEDF1F7),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFEDF1F7),
    surfaceVariant = Color(0xFF171D28),
    onSurfaceVariant = Color(0xFF9AA6B9),
    outline = Color(0xFF25303F),
    error = Color(0xFFFF7A8A),
    onError = Color(0xFF2A0509)
)

/**
 * One accent family over the same night chrome: the accents change with the
 * theme, the surfaces, outlines and errors stay put, so a palette swap reads
 * as a color change and not as a different app.
 */
private fun family(
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, onSecondary: Color, secondaryContainer: Color, onSecondaryContainer: Color,
    tertiary: Color,
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = Color(0xFF2B1800),
    background = Color(0xFF06080E),
    onBackground = Color(0xFFEDF1F7),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFEDF1F7),
    surfaceVariant = Color(0xFF171D28),
    onSurfaceVariant = Color(0xFF9AA6B9),
    outline = Color(0xFF25303F),
    error = Color(0xFFFF7A8A),
    onError = Color(0xFF2A0509)
)

/** The scheme a theme name stands for; "" and unknown names are the default. */
private fun schemeFor(name: String): ColorScheme = when (name) {
    "pink" -> family(
        Color(0xFFFF7EB6), Color(0xFF3B0026), Color(0xFF4E1338), Color(0xFFFFD9EC),
        Color(0xFFFFB3D9), Color(0xFF3B0026), Color(0xFF4A1B3A), Color(0xFFFFE0EF),
        Color(0xFFFFD6A5)
    )

    "green" -> family(
        Color(0xFF6FE7A0), Color(0xFF02321D), Color(0xFF0E4430), Color(0xFFB8FFE0),
        Color(0xFFA8F0C3), Color(0xFF02321D), Color(0xFF174A34), Color(0xFFD9FFEC),
        Color(0xFFFFC97A)
    )

    "orange" -> family(
        Color(0xFFFFA552), Color(0xFF3A1500), Color(0xFF4A2408), Color(0xFFFFE3C4),
        Color(0xFFFFD9A0), Color(0xFF3A1500), Color(0xFF4A3418), Color(0xFFFFEEDC),
        Color(0xFFFFE14A)
    )

    "ruby" -> family(
        Color(0xFFFF5C7A), Color(0xFF3B0010), Color(0xFF4E1024), Color(0xFFFFD9E0),
        Color(0xFFFFA8B8), Color(0xFF3B0010), Color(0xFF4A1B26), Color(0xFFFFE0E6),
        Color(0xFFFFD6A5)
    )

    "purple" -> family(
        Color(0xFFB48CFF), Color(0xFF1B0B33), Color(0xFF2E1B52), Color(0xFFEDE1FF),
        Color(0xFFD9C2FF), Color(0xFF1B0B33), Color(0xFF3A2A5A), Color(0xFFFFE9DC),
        Color(0xFFFFB86B)
    )

    else -> LotusNight
}

/** The accent each theme swatches in the settings picker. */
object ThemeSwatches {
    val default = Color(0xFF6FE7C4)
    val pink = Color(0xFFFF7EB6)
    val green = Color(0xFF6FE7A0)
    val orange = Color(0xFFFFA552)
    val ruby = Color(0xFFFF5C7A)
    val purple = Color(0xFFB48CFF)
}

/** Shared gradients so every screen sits on the same sky — per theme. */
object Lotus {
    val background: Brush
        get() = Brush.verticalGradient(
            when (ThemeState.name) {
                "pink" -> listOf(Color(0xFF140A16), Color(0xFF08060A), Color(0xFF160A1E))
                "green" -> listOf(Color(0xFF0A1A12), Color(0xFF060E08), Color(0xFF0C1810))
                "orange" -> listOf(Color(0xFF1A1208), Color(0xFF0E0A06), Color(0xFF18100A))
                "ruby" -> listOf(Color(0xFF1A0812), Color(0xFF0E060A), Color(0xFF180A14))
                "purple" -> listOf(Color(0xFF120A1E), Color(0xFF0A0714), Color(0xFF160C22))
                else -> listOf(Color(0xFF0A1220), Color(0xFF06080E), Color(0xFF0C0A18))
            }
        )

    val idle: Color
        get() = when (ThemeState.name) {
            "pink" -> Color(0xFFB990A6)
            "green" -> Color(0xFF93B89A)
            "orange" -> Color(0xFFC2A28F)
            "ruby" -> Color(0xFFC0909B)
            "purple" -> Color(0xFFA99BC6)
            else -> Color(0xFF8A93A6)
        }

    val halo: Brush
        get() = Brush.radialGradient(
            listOf(schemeFor(ThemeState.name).primary.copy(alpha = 0.10f), Color.Transparent)
        )
}

@Composable
fun VinTheme(content: @Composable () -> Unit) {
    // A trailing "..." is a bidirectional neutral: it takes the paragraph's
    // direction, so an LTR paragraph puts it at the head of a Persian sentence
    // instead of its tail. The direction is pinned here rather than left to the
    // Activity configuration, which the text does not always inherit.
    val direction = if (Prefs.language.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(colorScheme = schemeFor(ThemeState.name)) {
            ProvideAppTextToolbar(content)
        }
    }
}
