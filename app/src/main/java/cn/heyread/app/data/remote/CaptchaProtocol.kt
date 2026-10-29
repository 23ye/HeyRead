package cn.heyread.app.data.remote

import java.math.BigDecimal
import java.net.URI
import org.json.JSONObject

sealed interface CaptchaCallbackResult {
    data class Completed(val proof: CaptchaProof) : CaptchaCallbackResult

    data object Cancelled : CaptchaCallbackResult

    data class Failed(val message: String) : CaptchaCallbackResult
}

/** Parses only the Tencent callback fields the official flow uses; tokens stay out of logs. */
fun parseCaptchaCallback(json: String): CaptchaCallbackResult =
    try {
        require(json.length <= 16 * 1024)
        val result = JSONObject(json)
        val ret = result.opt("ret")
        if (ret !is Number || ret.toInt().toDouble() != ret.toDouble()) {
            CaptchaCallbackResult.Failed("验证码返回了无效结果")
        } else if (ret.toInt() == 2) {
            CaptchaCallbackResult.Cancelled
        } else if (ret.toInt() != 0) {
            CaptchaCallbackResult.Failed("验证码未完成，请重试")
        } else {
            val errorCode = result.opt("errorCode")
            val ticket = scalar(result.opt("ticket"))
            val randstr = scalar(result.opt("randstr"))
            if (
                errorCode != null &&
                    errorCode !== org.json.JSONObject.NULL &&
                    (errorCode !is Number || errorCode.toDouble() != 0.0)
            ) {
                CaptchaCallbackResult.Failed("验证码返回了错误凭据")
            } else if (
                ticket == null ||
                    randstr == null ||
                    ticket.startsWith("trerror_", true) ||
                    ticket.length > 4096 ||
                    randstr.length > 512
            ) {
                CaptchaCallbackResult.Failed("验证码票据无效，请重新验证")
            } else {
                CaptchaCallbackResult.Completed(CaptchaProof(ticket, randstr))
            }
        }
    } catch (_: Exception) {
        CaptchaCallbackResult.Failed("无法读取验证码结果")
    }

private fun scalar(value: Any?): String? =
    when (value) {
        null,
        JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
        else -> null
    }

private val captchaHosts =
    setOf(
        "turing.captcha.qcloud.com",
        "ca.turing.captcha.qcloud.com",
        "turing.captcha.gtimg.com",
        "global.turing.captcha.gtimg.com",
        "rce.tencentrio.com",
        "cloudcache.tencentcs.com",
    )

/** Exact HTTPS host allowlist used by both top-level navigation and captcha subresources. */
fun isAllowedCaptchaUrl(rawUrl: String): Boolean =
    runCatching {
            val uri = URI(rawUrl)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in captchaHosts &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawUserInfo == null
        }
        .getOrDefault(false)

fun resolveCaptchaAppId(rawRootAppId: Any?, rawResultAppId: Any?, path: String): String =
    validCaptchaAppId(rawRootAppId)
        ?: validCaptchaAppId(rawResultAppId)
        ?: if (
            path == "/bbs/app/profile/events" ||
                path == "/bbs/app/profile/user/link/list/v2" ||
                path == "/bbs/app/api/general/search/v1"
        )
            "199251710"
        else "2061805035"

fun resolveCaptchaAppId(rawAppId: Any?, path: String): String =
    resolveCaptchaAppId(rawAppId, null, path)

private fun validCaptchaAppId(value: Any?): String? =
    when (value) {
        is String ->
            value.takeIf { it.matches(Regex("[0-9]{1,20}")) && it.any { digit -> digit != '0' } }
        is Number -> validNumericCaptchaAppId(value)
        else -> null
    }

private fun validNumericCaptchaAppId(value: Number): String? =
    runCatching {
            val decimal = BigDecimal(value.toString())
            if (decimal.signum() <= 0) null
            else {
                val normalized = decimal.stripTrailingZeros()
                val scale = normalized.scale().toLong()
                val integerDigits = normalized.precision().toLong() - scale
                if (scale > 0 || integerDigits !in 1L..20L) null
                else normalized.toBigIntegerExact().toString()
            }
        }
        .getOrNull()
