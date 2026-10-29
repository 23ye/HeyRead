package cn.heyread.app.data.account

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Cookie handling is confined to HTTPS requests on the official API host. */
class OfficialCookieJar : CookieJar {
    private val lock = Any()
    private val values = linkedMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!isOfficial(url)) return
        synchronized(lock) {
            cookies.forEach { cookie ->
                if (allowedDomain(cookie.domain) && cookie.path.startsWith("/")) {
                    val key = "${cookie.name}|${cookie.domain}|${cookie.path}"
                    if (cookie.expiresAt <= System.currentTimeMillis()) values.remove(key)
                    else values[key] = secureCookie(cookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isOfficial(url)) return emptyList()
        synchronized(lock) {
            values.entries.removeAll { it.value.expiresAt <= System.currentTimeMillis() }
            return values.values.filter { it.matches(url) && it.secure && allowedDomain(it.domain) }
        }
    }

    fun cookieHeader(): String =
        synchronized(lock) {
            values.values
                .filter { it.expiresAt > System.currentTimeMillis() && it.secure }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }

    fun mergeExplicitCookieHeader(header: String) {
        val host = "https://api.xiaoheihe.cn/".toHttpUrl()
        val allowed =
            setOf(
                "pkey",
                "user_pkey",
                "x_pkey",
                "heybox_id",
                "user_heybox_id",
                "x_heybox_id",
                "userid",
                "heyboxid",
            )
        val cookies =
            header.split(';').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                if (name !in allowed || value.isBlank()) return@mapNotNull null
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .hostOnlyDomain(host.host)
                    .path("/")
                    .secure()
                    .build()
            }
        saveFromResponse(host, cookies)
    }

    fun restoreSessionCookieHeader(header: String) {
        val host = API_HOST
        synchronized(lock) {
            header.split(';').forEach { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@forEach
                val name = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                if (name.isBlank() || value.isBlank()) return@forEach
                val cookie =
                    Cookie.Builder().name(name).value(value).domain(host).path("/").secure().build()
                values["${cookie.name}|${cookie.domain}|${cookie.path}"] = cookie
            }
        }
    }

    fun clear() = synchronized(lock) { values.clear() }

    private fun isOfficial(url: HttpUrl) = url.isHttps && url.host.equals(API_HOST, true)

    private fun allowedDomain(domain: String) =
        domain.equals(API_HOST, true) || domain.equals("xiaoheihe.cn", true)

    private fun secureCookie(cookie: Cookie): Cookie {
        val builder =
            Cookie.Builder().name(cookie.name).value(cookie.value).path(cookie.path).secure()
        if (cookie.hostOnly) builder.hostOnlyDomain(cookie.domain)
        else builder.domain(cookie.domain)
        if (cookie.persistent) builder.expiresAt(cookie.expiresAt)
        if (cookie.httpOnly) builder.httpOnly()
        return builder.build()
    }

    companion object {
        const val API_HOST = "api.xiaoheihe.cn"
    }
}
