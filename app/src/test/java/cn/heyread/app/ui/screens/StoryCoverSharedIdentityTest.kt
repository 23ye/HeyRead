package cn.heyread.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryCoverSharedIdentityTest {
  @Test
  fun identityRequiresAnAuthorizedOriginAndSameStoryMedia() {
    val key =
      storyCoverSharedIdentity(
        source = "HEYBOX",
        id = "190978471",
        imageUrl = "https://cdn.example/cover.jpg",
        originEntryId = "entry-search-1",
        enabled = true,
      )
    assertEquals("story-cover:entry-search-1:HEYBOX:190978471:https://cdn.example/cover.jpg", key)
    assertNotEquals(
      key,
      storyCoverSharedIdentity(
        "STEAM",
        "190978471",
        "https://cdn.example/cover.jpg",
        "entry-search-1",
        true,
      ),
    )
    assertNotEquals(
      key,
      storyCoverSharedIdentity(
        "HEYBOX",
        "190978471",
        "https://cdn.example/other.jpg",
        "entry-search-1",
        true,
      ),
    )
    assertNotEquals(
      key,
      storyCoverSharedIdentity(
        "HEYBOX",
        "190978471",
        "https://cdn.example/cover.jpg",
        "entry-other",
        true,
      ),
    )
  }

  @Test
  fun missingDisabledOrNonHttpsCoverFallsBackWithoutASharedKey() {
    assertNull(storyCoverSharedIdentity("HEYBOX", "1", null, "entry", true))
    assertNull(storyCoverSharedIdentity("HEYBOX", "1", "http://cdn.example/a.jpg", "entry", true))
    assertNull(storyCoverSharedIdentity("HEYBOX", "1", "https://cdn.example/a.jpg", "entry", false))
    assertNull(storyCoverSharedIdentity("HEYBOX", "1", "https://cdn.example/a.jpg", null, true))
  }
}
