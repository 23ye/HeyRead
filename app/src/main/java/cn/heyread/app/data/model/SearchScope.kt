package cn.heyread.app.data.model

enum class SearchScopeType {
    GLOBAL,
    SECTION,
    HASHTAG,
}

data class SearchScope(
    val type: SearchScopeType = SearchScopeType.GLOBAL,
    val id: String = "",
    val name: String = "全站",
) {
    val isValid: Boolean
        get() = type == SearchScopeType.GLOBAL || id.isNotBlank()

    fun key(query: String): String =
        "${type.name}:${if (type == SearchScopeType.GLOBAL) "" else id}:${query.trim().lowercase(java.util.Locale.ROOT)}"

    companion object {
        val GLOBAL = SearchScope()
    }
}
