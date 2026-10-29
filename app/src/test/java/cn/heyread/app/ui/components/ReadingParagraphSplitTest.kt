package cn.heyread.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingParagraphSplitTest {
  @Test
  fun splitsOnlyBlankLinesAndPreservesHardBreaksIndentationAndSentencePunctuation() {
    assertEquals(
        listOf("首句。次句！\n单换行", "  缩进后的段落"),
        splitReaderParagraphs("首句。次句！\r\n单换行\r\n  \r\n  缩进后的段落"),
    )
  }
}
