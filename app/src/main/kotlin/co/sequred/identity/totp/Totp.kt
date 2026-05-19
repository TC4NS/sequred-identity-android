package co.sequred.identity.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * RFC 6238 TOTP generator with SHA-1 HMAC, 30-second time-step, 6 digits —
 * the defaults that every authenticator app implements. Lives in pure Kotlin
 * for now; could migrate into the Rust core later if we want exactly one
 * implementation across platforms.
 */
object Totp {

    fun code(base32Secret: String, timeSeconds: Long = System.currentTimeMillis() / 1000): String {
        val key = decodeBase32(base32Secret.replace(" ", "").uppercase())
        val counter = timeSeconds / STEP_SECONDS
        val counterBytes = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hmac = mac.doFinal(counterBytes)
        val offset = (hmac[hmac.size - 1] and 0x0F).toInt()
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)
        val code = binary % 1_000_000
        return code.toString().padStart(DIGITS, '0')
    }

    /** Seconds remaining in the current 30s window — drives the countdown pip. */
    fun secondsRemaining(timeSeconds: Long = System.currentTimeMillis() / 1000): Int =
        (STEP_SECONDS - (timeSeconds % STEP_SECONDS)).toInt()

    // ─── Base32 (RFC 4648, no padding required) ─────────────────────────────

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val DIGITS = 6
    private const val STEP_SECONDS = 30L

    private fun decodeBase32(s: String): ByteArray {
        val cleaned = s.trimEnd('=')
        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var oi = 0
        for (c in cleaned) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) continue // skip stray separators
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out[oi++] = (buffer shr bitsLeft and 0xFF).toByte()
            }
        }
        return out.copyOf(oi)
    }
}
