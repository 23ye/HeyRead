package cn.heyread.app.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun saveTargetCacheScreenshot(name: String): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val screenshot = instrumentation.uiAutomation.takeScreenshot()
    val file = File(instrumentation.targetContext.cacheDir, name)
    file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    screenshot.recycle()
    println("TEST_SCREENSHOT=${file.absolutePath}")
    return file
}
