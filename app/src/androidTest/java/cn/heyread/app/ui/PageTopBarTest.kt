package cn.heyread.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.ui.components.PageTopBar
import cn.heyread.app.ui.theme.HeyReadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTopBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rootAndChildTitlesShareCoordinatesAndAreVerticallyCenteredInSixtyFourDpBars() {
        var expectedHeightPx = 0f
        compose.setContent {
            expectedHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
            HeyReadTheme("light") {
                Column {
                    PageTopBar("发现", isRoot = true, onBack = {}) {
                        IconButton(onClick = {}, modifier = Modifier.testTag("page-action")) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                    PageTopBar("文章详情", isRoot = false, onBack = {}) {
                        IconButton(onClick = {}, modifier = Modifier.testTag("page-action")) {
                            Icon(Icons.Filled.Search, contentDescription = "分享")
                        }
                    }
                }
            }
        }
        val titles = compose.onAllNodesWithTag("page-title").fetchSemanticsNodes()
        val titleSlots = compose.onAllNodesWithTag("page-title-slot").fetchSemanticsNodes()
        val bars = compose.onAllNodesWithTag("page-top-bar").fetchSemanticsNodes()
        val brands = compose.onAllNodesWithTag("page-brand").fetchSemanticsNodes()
        val backButtons = compose.onAllNodesWithTag("page-back").fetchSemanticsNodes()
        val actions = compose.onAllNodesWithTag("page-action").fetchSemanticsNodes()
        assertEquals(2, titles.size)
        assertEquals(2, titleSlots.size)
        assertEquals(2, bars.size)
        assertEquals(1, brands.size)
        assertEquals(1, backButtons.size)
        assertEquals(2, actions.size)
        assertEquals(titles[0].boundsInRoot.left, titles[1].boundsInRoot.left, 1f)
        assertEquals(bars[0].boundsInRoot.height, bars[1].boundsInRoot.height, 1f)
        assertEquals(expectedHeightPx, bars[0].boundsInRoot.height, 2f)
        for (index in bars.indices) {
            val centerY = bars[index].boundsInRoot.center.y
            assertEquals(centerY, titleSlots[index].boundsInRoot.center.y, 1f)
            assertEquals(centerY, titles[index].boundsInRoot.center.y, 1f)
            if (index == 0) assertEquals(centerY, brands.single().boundsInRoot.center.y, 1f)
            else assertEquals(centerY, backButtons.single().boundsInRoot.center.y, 1f)
            assertEquals(centerY, actions[index].boundsInRoot.center.y, 1f)
        }
    }
}
