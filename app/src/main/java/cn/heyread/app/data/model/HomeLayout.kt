package cn.heyread.app.data.model

enum class HomeLayout(val storedValue: String, val title: String, val description: String) {
    A("a", "A · 柔和独立卡片", "首条大封面，后续紧凑横卡"),
    B("b", "B · 紧凑描边列表", "统一描边，突出标题与摘要"),
    C("c", "C · 大封面画廊", "大封面搭配独立文字面板");

    companion object {
        fun fromStoredValue(value: String?): HomeLayout =
            entries.firstOrNull { it.storedValue == value?.trim()?.lowercase() } ?: A
    }
}
