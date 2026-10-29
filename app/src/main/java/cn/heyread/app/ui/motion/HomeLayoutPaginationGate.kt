package cn.heyread.app.ui.motion

import cn.heyread.app.data.model.HomeLayout

internal data class HomeLayoutPaginationGate(
    val observedLayout: HomeLayout,
    val suppressAutoPagination: Boolean = false,
) {
  fun observeLayout(layout: HomeLayout): HomeLayoutPaginationGate =
      if (layout == observedLayout) this
      else copy(observedLayout = layout, suppressAutoPagination = true)

  fun userScrolled(): HomeLayoutPaginationGate =
      if (!suppressAutoPagination) this else copy(suppressAutoPagination = false)

  fun suppressForPaneResize(): HomeLayoutPaginationGate =
      if (suppressAutoPagination) this else copy(suppressAutoPagination = true)
}
