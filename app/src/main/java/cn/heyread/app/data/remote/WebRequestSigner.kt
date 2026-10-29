package cn.heyread.app.data.remote

import java.security.MessageDigest
import java.security.SecureRandom

/** Implements the public web request compatibility check used by read-only feeds. */
class WebRequestSigner(private val random: SecureRandom = SecureRandom()) {
    data class Signature(val time: Long, val nonce: String, val hkey: String)

    fun create(path: String): Signature {
        val time = System.currentTimeMillis() / 1000
        val bytes = ByteArray(16).also(random::nextBytes)
        val nonce = bytes.joinToString("") { "%02X".format(it) }
        return Signature(time, nonce, sign(path, time, nonce))
    }

    fun sign(path: String, time: Long, nonce: String): String {
        val normalized = "/" + path.split('/').filter(String::isNotEmpty).joinToString("/") + "/"
        val a = map((time + 1).toString(), alphabet.dropLast(2))
        val b = map(normalized, alphabet)
        val c = map(nonce, alphabet)
        val merged = buildString {
            for (i in 0 until maxOf(a.length, b.length, c.length)) {
                if (i < a.length) append(a[i])
                if (i < b.length) append(b[i])
                if (i < c.length) append(c[i])
            }
        }
        val h =
            MessageDigest.getInstance("MD5")
                .digest(merged.take(20).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val x = h.takeLast(6).map { it.code }
        fun d(v: Int) = if (v and 128 != 0) ((v shl 1) xor 27) and 255 else v shl 1
        fun p(v: Int) = d(v) xor v
        fun s(v: Int) = p(d(v))
        fun bb(v: Int) = s(p(d(v)))
        fun aa(v: Int) = bb(v) xor s(v) xor p(v)
        val y =
            listOf(
                aa(x[0]) xor bb(x[1]) xor s(x[2]) xor p(x[3]),
                p(x[0]) xor aa(x[1]) xor bb(x[2]) xor s(x[3]),
                s(x[0]) xor p(x[1]) xor aa(x[2]) xor bb(x[3]),
                bb(x[0]) xor s(x[1]) xor p(x[2]) xor aa(x[3]),
                x[4],
                x[5],
            )
        val checksum = (y.sum() % 100).toString().padStart(2, '0')
        return map(h.take(5), alphabet.dropLast(4)) + checksum
    }

    private fun map(value: String, dict: String) =
        value.map { dict[it.code % dict.length] }.joinToString("")

    companion object {
        const val alphabet = "AB45STUVWZEFGJ6CH01D237IXYPQRKLMN89"
    }
}
