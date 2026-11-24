package com.tempgmail.app.api

import okhttp3.Request

/** One public receive-sms-free.cc number. */
data class SmsNumber(
    val country: String,
    val phone: String,
    /** URL prefix on the site, e.g. Free-USA-Phone-Number */
    val prefix: String,
)

/** One SMS received by a public number. */
data class SmsInboxItem(
    val sender: String,
    val time: String,
    val text: String,
    /** First 4–8 digit code found in the text, if any (OTP highlighting). */
    val otp: String?,
)

/**
 * Free public SMS numbers from receive-sms-free.cc — no API key, no account.
 * Parsers ported from the otpkit project (verified live via CI probe:
 * 24 numbers listed, message pages parse with real OTPs intact).
 */
object SmsClient {

    private const val BASE = "https://receive-sms-free.cc"

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Net.UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return Net.execute(req)
    }

    /** All currently listed public numbers, sorted by country. */
    fun listNumbers(): List<SmsNumber> {
        val html = fetch("$BASE/")
        val re = Regex("""href="/(Free-[^/"]+)/(\d+)/"""")
        return re.findAll(html)
            .map { m ->
                val country = m.groupValues[1]
                    .removePrefix("Free-")
                    .removeSuffix("-Phone-Number")
                    .replace('-', ' ')
                SmsNumber(country = country, phone = m.groupValues[2], prefix = m.groupValues[1])
            }
            .distinctBy { it.phone }
            .sortedBy { it.country.lowercase() }
            .toList()
    }

    /** Live inbox of one number (public shared inbox). */
    fun listMessages(number: SmsNumber): List<SmsInboxItem> {
        val html = fetch("$BASE/${number.prefix}/${number.phone}/")
        val blockRe = Regex(
            """<div[^>]*class="sms-item[^"]*"[^>]*>(.*?)</div>\s*(?=<div|$)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val senderRe = Regex("""class="sender-badge[^"]*"[^>]*>([^<]+)<""")
        val timeRe = Regex(
            """class="time-text[^"]*"[^>]*>.*?(?:<[^>]+>)*\s*([^<]+?)\s*<""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val textRe = Regex(
            """class="sms-content[^"]*"[^>]*>(.*?)</p>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val otpRe = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

        return blockRe.findAll(html).mapNotNull { block ->
            val inner = block.groupValues[1]
            val sender = senderRe.find(inner)?.groupValues?.get(1)?.trim().orEmpty()
            val time = timeRe.find(inner)?.groupValues?.get(1)?.trim().orEmpty()
            val rawText = textRe.find(inner)?.groupValues?.get(1).orEmpty()
            val text = stripHtml(rawText)
            if (text.isEmpty()) return@mapNotNull null
            SmsInboxItem(
                sender = sender.ifEmpty { "—" },
                time = time,
                text = text,
                otp = otpRe.find(text)?.groupValues?.get(1),
            )
        }.toList()
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
