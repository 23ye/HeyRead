package cn.heyread.app.data.media

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class MediaImage(
    val previewUrl: String,
    val originalUrl: String? = null,
    val description: String = "",
    /** A known animated source (usually the explicit original GIF) used for inline playback. */
    val animationUrl: String? = null,
) {
    val exportUrl: String?
        get() = MediaUrlPolicy.originalOrPreview(previewUrl, originalUrl)

    val hasHigherResolutionSource: Boolean
        get() = exportUrl != null && exportUrl != previewUrl
}

object MediaUrlPolicy {
    fun originalOrPreview(previewUrl: String, explicitOriginalUrl: String? = null): String? {
        val preview = previewUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        val explicit = explicitOriginalUrl?.toHttpUrlOrNull()?.takeIf { it.isHttps }
        if (explicit != null && explicit.toString() != preview.toString())
            return explicit.toString()
        val host = preview.host
        val knownImageCdn = host == "max-c.com" || host.endsWith(".max-c.com")
        if (!knownImageCdn || preview.encodedQuery.isNullOrBlank()) return preview.toString()
        val transformations = preview.encodedQuery!!.split('&')
        if (transformations.isEmpty() || transformations.any { !isKnownTransform(it) })
            return preview.toString()
        return preview.newBuilder().query(null).build().toString()
    }

    private fun isKnownTransform(rawParameter: String): Boolean {
        val encodedName = rawParameter.substringBefore('=')
        val decodedName =
            runCatching { java.net.URLDecoder.decode(encodedName, Charsets.UTF_8.name()) }
                .getOrNull() ?: return false
        return decodedName == "imageMogr2" ||
            decodedName.startsWith("imageMogr2/") ||
            decodedName == "imageView2" ||
            decodedName.startsWith("imageView2/")
    }
}
