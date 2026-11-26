package org.kavach.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.kavach.R

/** Inter (SIL Open Font License), a close open-source neighbour of system UI typefaces. */
@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

val AtlasTypography: Typography = Typography().run {
    fun TextStyle.inter() = copy(fontFamily = Inter)
    copy(
        displayLarge = displayLarge.inter(), displayMedium = displayMedium.inter(), displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.inter(), headlineMedium = headlineMedium.inter(), headlineSmall = headlineSmall.inter(),
        titleLarge = titleLarge.inter(), titleMedium = titleMedium.inter(), titleSmall = titleSmall.inter(),
        // Tighter tracking than Material's default body text.
        bodyLarge = bodyLarge.inter().copy(letterSpacing = bodyLarge.letterSpacing * 0.2f),
        bodyMedium = bodyMedium.inter().copy(letterSpacing = bodyMedium.letterSpacing * 0.2f),
        bodySmall = bodySmall.inter().copy(letterSpacing = bodySmall.letterSpacing * 0.2f),
        labelLarge = labelLarge.inter(), labelMedium = labelMedium.inter(), labelSmall = labelSmall.inter(),
    )
}
