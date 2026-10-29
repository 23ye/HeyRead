package cn.heyread.app.data.account

data class AccountSession(
    val cookieHeader: String,
    val userId: String,
    val nickname: String,
    val avatarUrl: String,
) {
    val isValid: Boolean
        get() = cookieHeader.containsPkey() && userId.isNotBlank()

    override fun toString(): String =
        "AccountSession(userId=$userId, nickname=$nickname, avatarUrl=$avatarUrl, cookieHeader=<redacted>)"
}

private fun String.containsPkey(): Boolean =
    split(';').any { item ->
        val name = item.substringBefore('=').trim()
        name in setOf("pkey", "user_pkey", "x_pkey") && item.substringAfter('=', "").isNotBlank()
    }

data class QrChallenge(val url: String, val key: String, val expiresAtMillis: Long)

sealed interface QrPollResult {
    data object Waiting : QrPollResult

    data object Ready : QrPollResult

    data object Cancelled : QrPollResult

    data class LoggedIn(val session: AccountSession) : QrPollResult

    data class Rejected(val message: String) : QrPollResult
}
