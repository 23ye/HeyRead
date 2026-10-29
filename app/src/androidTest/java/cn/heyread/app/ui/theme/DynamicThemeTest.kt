package cn.heyread.app.ui.theme

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wallpaperAccentMatchesMaterialPlatformLightAndDarkSchemes() {
        assumeTrue("Dynamic color is supported on Android 12+", Build.VERSION.SDK_INT >= 31)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedLight = dynamicLightColorScheme(context).primary
        val expectedDark = dynamicDarkColorScheme(context).primary
        var actualLight: Color? = null
        var actualDark: Color? = null

        compose.setContent {
            Column {
                HeyReadTheme("light", "wallpaper") {
                    actualLight = MaterialTheme.colorScheme.primary
                }
                HeyReadTheme("dark", "wallpaper") { actualDark = MaterialTheme.colorScheme.primary }
            }
        }
        compose.waitForIdle()

        assertEquals(expectedLight, actualLight)
        assertEquals(expectedDark, actualDark)
    }
}
