package cn.heyread.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.LocalTextStyle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.heyread.app.ui.motion.LocalMotionEnabled

internal val accentOptions = listOf("classic", "blue", "green", "purple", "wallpaper")

internal fun isValidAccent(value: String) = value in accentOptions

internal val classicLight =
    lightColorScheme(
        primary = Color(0xFF151719),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE179),
        onPrimaryContainer = Color(0xFF231B00),
        secondary = Color(0xFFF8D348),
        onSecondary = Color(0xFF151719),
        secondaryContainer = Color(0xFFF8D348),
        onSecondaryContainer = Color(0xFF151719),
        tertiary = Color(0xFF45664C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC6ECCC),
        onTertiaryContainer = Color(0xFF01210D),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1D1B16),
        surface = Color(0xFFF7F8FA),
        onSurface = Color(0xFF1D1B16),
        surfaceVariant = Color(0xFFEAE2CF),
        onSurfaceVariant = Color(0xFF4B4639),
        outline = Color(0xFF7D7767),
        outlineVariant = Color(0xFFCEC6B4),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF33302A),
        inverseOnSurface = Color(0xFFF6F0E7),
        inversePrimary = Color(0xFFE7C439),
        surfaceDim = Color(0xFFDFD9D0),
        surfaceBright = Color(0xFFFFF8EF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF9F3E9),
        surfaceContainer = Color(0xFFF3EDE4),
        surfaceContainerHigh = Color(0xFFEEE7DE),
        surfaceContainerHighest = Color(0xFFE8E2D9),
        surfaceTint = Color(0xFF151719),
    )

internal val classicDark =
    darkColorScheme(
        primary = Color(0xFFF8D348),
        onPrimary = Color(0xFF151719),
        primaryContainer = Color(0xFF554500),
        onPrimaryContainer = Color(0xFFFFE179),
        secondary = Color(0xFFF8D348),
        onSecondary = Color(0xFF151719),
        secondaryContainer = Color(0xFF675719),
        onSecondaryContainer = Color(0xFFFFF3C2),
        tertiary = Color(0xFFABD0B0),
        onTertiary = Color(0xFF173721),
        tertiaryContainer = Color(0xFF2E4E36),
        onTertiaryContainer = Color(0xFFC6ECCC),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFB4AB),
        background = Color(0xFF101214),
        onBackground = Color(0xFFE8E2D9),
        surface = Color(0xFF101214),
        onSurface = Color(0xFFE8E2D9),
        surfaceVariant = Color(0xFF4B4639),
        onSurfaceVariant = Color(0xFFCEC6B4),
        outline = Color(0xFF979080),
        outlineVariant = Color(0xFF4B4639),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE8E2D9),
        inverseOnSurface = Color(0xFF33302A),
        inversePrimary = Color(0xFF715C00),
        surfaceDim = Color(0xFF15130E),
        surfaceBright = Color(0xFF3C3933),
        surfaceContainerLowest = Color(0xFF100E09),
        surfaceContainerLow = Color(0xFF1D1B16),
        surfaceContainer = Color(0xFF221F1A),
        surfaceContainerHigh = Color(0xFF2C2A24),
        surfaceContainerHighest = Color(0xFF37342E),
        surfaceTint = Color(0xFFF8D348),
    )

internal val blueLight =
    lightColorScheme(
        primary = Color(0xFF0161A3),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD1E4FF),
        onPrimaryContainer = Color(0xFF001D36),
        secondary = Color(0xFF535F70),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD6E3F7),
        onSecondaryContainer = Color(0xFF101C2B),
        tertiary = Color(0xFF6B5778),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF2DAFF),
        onTertiaryContainer = Color(0xFF251431),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFAF9FC),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFFAF9FC),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFDFE2EB),
        onSurfaceVariant = Color(0xFF42474E),
        outline = Color(0xFF73777F),
        outlineVariant = Color(0xFFC3C7CF),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2F3033),
        inverseOnSurface = Color(0xFFF1F0F4),
        inversePrimary = Color(0xFF9ECAFF),
        surfaceDim = Color(0xFFDADADD),
        surfaceBright = Color(0xFFFAF9FC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F3F7),
        surfaceContainer = Color(0xFFEEEDF1),
        surfaceContainerHigh = Color(0xFFE8E8EB),
        surfaceContainerHighest = Color(0xFFE2E2E6),
        surfaceTint = Color(0xFF0161A3),
    )

internal val blueDark =
    darkColorScheme(
        primary = Color(0xFF9ECAFF),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF00497D),
        onPrimaryContainer = Color(0xFFD1E4FF),
        secondary = Color(0xFFBBC7DB),
        onSecondary = Color(0xFF253140),
        secondaryContainer = Color(0xFF3B4858),
        onSecondaryContainer = Color(0xFFD6E3F7),
        tertiary = Color(0xFFD6BEE4),
        onTertiary = Color(0xFF3B2948),
        tertiaryContainer = Color(0xFF523F5F),
        onTertiaryContainer = Color(0xFFF2DAFF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFB4AB),
        background = Color(0xFF121316),
        onBackground = Color(0xFFE2E2E6),
        surface = Color(0xFF121316),
        onSurface = Color(0xFFE2E2E6),
        surfaceVariant = Color(0xFF42474E),
        onSurfaceVariant = Color(0xFFC3C7CF),
        outline = Color(0xFF8D9199),
        outlineVariant = Color(0xFF42474E),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE2E2E6),
        inverseOnSurface = Color(0xFF2F3033),
        inversePrimary = Color(0xFF0161A3),
        surfaceDim = Color(0xFF121316),
        surfaceBright = Color(0xFF38393C),
        surfaceContainerLowest = Color(0xFF0D0E11),
        surfaceContainerLow = Color(0xFF1A1C1E),
        surfaceContainer = Color(0xFF1E2022),
        surfaceContainerHigh = Color(0xFF282A2D),
        surfaceContainerHighest = Color(0xFF333538),
        surfaceTint = Color(0xFF9ECAFF),
    )

internal val greenLight =
    lightColorScheme(
        primary = Color(0xFF006D42),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF93F7BC),
        onPrimaryContainer = Color(0xFF002111),
        secondary = Color(0xFF4E6355),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD1E8D6),
        onSecondaryContainer = Color(0xFF0C1F14),
        tertiary = Color(0xFF3B6471),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBFE9F8),
        onTertiaryContainer = Color(0xFF001F27),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF8FAF5),
        onBackground = Color(0xFF191C1A),
        surface = Color(0xFFF8FAF5),
        onSurface = Color(0xFF191C1A),
        surfaceVariant = Color(0xFFDCE5DC),
        onSurfaceVariant = Color(0xFF404942),
        outline = Color(0xFF717972),
        outlineVariant = Color(0xFFC0C9C0),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2E312E),
        inverseOnSurface = Color(0xFFF0F1ED),
        inversePrimary = Color(0xFF76DAA1),
        surfaceDim = Color(0xFFD9DBD6),
        surfaceBright = Color(0xFFF8FAF5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F4EF),
        surfaceContainer = Color(0xFFEDEEEA),
        surfaceContainerHigh = Color(0xFFE7E9E4),
        surfaceContainerHighest = Color(0xFFE1E3DE),
        surfaceTint = Color(0xFF006D42),
    )

internal val greenDark =
    darkColorScheme(
        primary = Color(0xFF76DAA1),
        onPrimary = Color(0xFF003920),
        primaryContainer = Color(0xFF005231),
        onPrimaryContainer = Color(0xFF93F7BC),
        secondary = Color(0xFFB5CCBA),
        onSecondary = Color(0xFF213528),
        secondaryContainer = Color(0xFF374B3E),
        onSecondaryContainer = Color(0xFFD1E8D6),
        tertiary = Color(0xFFA3CDDC),
        onTertiary = Color(0xFF033541),
        tertiaryContainer = Color(0xFF224C58),
        onTertiaryContainer = Color(0xFFBFE9F8),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFB4AB),
        background = Color(0xFF111412),
        onBackground = Color(0xFFE1E3DE),
        surface = Color(0xFF111412),
        onSurface = Color(0xFFE1E3DE),
        surfaceVariant = Color(0xFF404942),
        onSurfaceVariant = Color(0xFFC0C9C0),
        outline = Color(0xFF8A938B),
        outlineVariant = Color(0xFF404942),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE1E3DE),
        inverseOnSurface = Color(0xFF2E312E),
        inversePrimary = Color(0xFF006D42),
        surfaceDim = Color(0xFF111412),
        surfaceBright = Color(0xFF373A37),
        surfaceContainerLowest = Color(0xFF0C0F0D),
        surfaceContainerLow = Color(0xFF191C1A),
        surfaceContainer = Color(0xFF1D201E),
        surfaceContainerHigh = Color(0xFF282B28),
        surfaceContainerHighest = Color(0xFF323633),
        surfaceTint = Color(0xFF76DAA1),
    )

internal val purpleLight =
    lightColorScheme(
        primary = Color(0xFF754B9C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF0DBFF),
        onPrimaryContainer = Color(0xFF2C0050),
        secondary = Color(0xFF665A6F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDDDF6),
        onSecondaryContainer = Color(0xFF211829),
        tertiary = Color(0xFF805157),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD9DC),
        onTertiaryContainer = Color(0xFF321016),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFEF7FC),
        onBackground = Color(0xFF1D1B1E),
        surface = Color(0xFFFEF7FC),
        onSurface = Color(0xFF1D1B1E),
        surfaceVariant = Color(0xFFE9DFEB),
        onSurfaceVariant = Color(0xFF4A454E),
        outline = Color(0xFF7C757E),
        outlineVariant = Color(0xFFCDC4CE),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF322F33),
        inverseOnSurface = Color(0xFFF6EFF4),
        inversePrimary = Color(0xFFDEB7FF),
        surfaceDim = Color(0xFFDFD8DD),
        surfaceBright = Color(0xFFFEF7FC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F2F6),
        surfaceContainer = Color(0xFFF3ECF1),
        surfaceContainerHigh = Color(0xFFEDE6EB),
        surfaceContainerHighest = Color(0xFFE7E1E5),
        surfaceTint = Color(0xFF754B9C),
    )

internal val purpleDark =
    darkColorScheme(
        primary = Color(0xFFDEB7FF),
        onPrimary = Color(0xFF44196A),
        primaryContainer = Color(0xFF5C3383),
        onPrimaryContainer = Color(0xFFF0DBFF),
        secondary = Color(0xFFD0C1D9),
        onSecondary = Color(0xFF372C3F),
        secondaryContainer = Color(0xFF4E4356),
        onSecondaryContainer = Color(0xFFEDDDF6),
        tertiary = Color(0xFFF3B7BD),
        onTertiary = Color(0xFF4B252A),
        tertiaryContainer = Color(0xFF653A40),
        onTertiaryContainer = Color(0xFFFFD9DC),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFB4AB),
        background = Color(0xFF151316),
        onBackground = Color(0xFFE7E1E5),
        surface = Color(0xFF151316),
        onSurface = Color(0xFFE7E1E5),
        surfaceVariant = Color(0xFF4A454E),
        onSurfaceVariant = Color(0xFFCDC4CE),
        outline = Color(0xFF968E98),
        outlineVariant = Color(0xFF4A454E),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE7E1E5),
        inverseOnSurface = Color(0xFF322F33),
        inversePrimary = Color(0xFF754B9C),
        surfaceDim = Color(0xFF151316),
        surfaceBright = Color(0xFF3B383C),
        surfaceContainerLowest = Color(0xFF0F0D11),
        surfaceContainerLow = Color(0xFF1D1B1E),
        surfaceContainer = Color(0xFF211F22),
        surfaceContainerHigh = Color(0xFF2C292D),
        surfaceContainerHighest = Color(0xFF373437),
        surfaceTint = Color(0xFFDEB7FF),
    )

@Composable
fun HeyReadTheme(
    theme: String,
    accent: String = "classic",
    motionEnabled: Boolean = true,
    onFallback: (String?) -> Unit = {},
    globalFontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit,
) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    var resumeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    @Suppress("UNUSED_VARIABLE") val refreshOnResume = resumeRevision + configuration.hashCode()
    val dynamic =
        if (accent == "wallpaper" && Build.VERSION.SDK_INT >= 31) {
            runCatching {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
        } else null
    val fallbackMessage =
        when {
            accent != "wallpaper" -> null
            Build.VERSION.SDK_INT < 31 -> "壁纸取色需要 Android 12 及以上，当前使用经典黄主题。"
            dynamic?.isFailure == true -> "无法读取系统壁纸配色，当前使用经典黄主题。"
            else -> null
        }
    LaunchedEffect(fallbackMessage) { onFallback(fallbackMessage) }
    val scheme =
        when (accent) {
            "wallpaper" -> dynamic?.getOrNull() ?: if (dark) classicDark else classicLight
            "blue" -> if (dark) blueDark else blueLight
            "green" -> if (dark) greenDark else greenLight
            "purple" -> if (dark) purpleDark else purpleLight
            else -> if (dark) classicDark else classicLight
        }
    val typography = remember(globalFontFamily) { Typography().withFontFamily(globalFontFamily) }
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalMotionEnabled provides motionEnabled,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = globalFontFamily),
            content = content,
        )
    }
}

private fun Typography.withFontFamily(fontFamily: FontFamily): Typography =
    copy(
        displayLarge = displayLarge.copy(fontFamily = fontFamily),
        displayMedium = displayMedium.copy(fontFamily = fontFamily),
        displaySmall = displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = titleLarge.copy(fontFamily = fontFamily),
        titleMedium = titleMedium.copy(fontFamily = fontFamily),
        titleSmall = titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = bodySmall.copy(fontFamily = fontFamily),
        labelLarge = labelLarge.copy(fontFamily = fontFamily),
        labelMedium = labelMedium.copy(fontFamily = fontFamily),
        labelSmall = labelSmall.copy(fontFamily = fontFamily),
    )
