package com.tempgmail.app.api

import com.tempgmail.app.model.MailAccount
import com.tempgmail.app.model.MailBody
import com.tempgmail.app.model.MailMessage
import com.tempgmail.app.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Client for smailpro.com (free web flow) + api.sonjj.com (mail data).
 * Validated end-to-end against the live service during development:
 *
 *  1. GET  smailpro.com/temporary-email        → session cookies
 *  2. captcha (Fgsi auto → WebView fallback)   → Turnstile token
 *  3. GET  smailpro.com/app/create?…x-captcha  → {address, timestamp, key}
 *  4. POST smailpro.com/app/inbox              → [{payload}]
 *  5. GET  api.sonjj.com/v1/<path>/inbox       → {messages:[{mid,textFrom,textTo,textSubject,textDate}]}
 *  6. GET  smailpro.com/app/message?…x-captcha → payload → v1/<path>/message → {body}
 */
class SmailproClient(private val provider: Provider) {

    private val base = "https://smailpro.com"
    private val sonjj = "https://api.sonjj.com/v1"
    private val path = provider.sonjjPath
    private val domain = provider.domain

    private fun sessionRequest(): Request = Request.Builder()
        .url("$base/temporary-email")
        .header("User-Agent", Net.UA)
        .header("Accept", "text/html,application/json")
        .build()

    private fun ensureSession() {
        Net.execute(sessionRequest())
    }

    private fun referer(req: okhttp3.Request.Builder): okhttp3.Request.Builder =
        req.header("Referer", "$base/temporary-email")
            .header("User-Agent", Net.UA)

    private fun postInbox(postBody: okhttp3.RequestBody): String {
        val req = referer(Request.Builder().url("$base/app/inbox").post(postBody)).build()
        return Net.execute(req)
    }

    private fun extractPayload(raw: String): String = try {
        JSONArray(raw).optJSONObject(0)?.optString("payload").orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Create a brand-new mailbox. Suspends while solving the captcha. */
    suspend fun create(): MailAccount = withContext(Dispatchers.IO) {
        ensureSession()
        val token = Captcha.getToken("smailpro_create")

        val url = "$base/app/create".toHttpUrl().newBuilder()
            .addQueryParameter("username", "random")
            .addQueryParameter("type", "alias")
            .addQueryParameter("domain", domain)
            .addQueryParameter("server", "1")
            .build()

        val req = referer(Request.Builder().url(url).header("x-captcha", token))
        val obj = try {
            Net.json(req.build())
        } catch (e: HttpException) {
            if (e.code == 403 && e.body.contains("Captcha", ignoreCase = true)) {
                throw CaptchaException("server-rejected")
            }
            throw e
        }

        val address = obj.optString("address").trim()
        if (address.isEmpty() || !address.contains("@")) {
            val msg = obj.optString("msg").ifEmpty { obj.optString("message") }
            throw ApiException(msg.ifEmpty { "create-failed" })
        }
        MailAccount(
            provider = provider,
            address = address,
            createdAt = System.currentTimeMillis(),
            secret = obj.optString("key"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis() / 1000),
        )
    }

    /** Fetch the inbox (empty list is a valid, real result). */
    suspend fun list(account: MailAccount): List<MailMessage> =
        withContext(Dispatchers.IO) {
            val payloadArr = JSONArray().put(
                JSONObject()
                    .put("address", account.address)
                    .put("timestamp", account.timestamp)
                    .put("key", account.secret),
            )
            val postBody = payloadArr.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            // Fast path: post WITHOUT the 285KB session-page fetch first.
            // Many smailpro endpoints answer from the jar cookies (or none).
            var payload = ""
            try {
                payload = extractPayload(postInbox(postBody))
            } catch (e: Exception) {
                android.util.Log.w("Smailpro", "inbox fast path failed: ${e.message}")
            }
            if (payload.isEmpty()) {
                ensureSession()
                payload = try {
                    extractPayload(postInbox(postBody))
                } catch (e: Exception) {
                    throw ApiException("inbox-payload", e)
                }
            }
            if (payload.isEmpty()) return@withContext emptyList()

            val inboxUrl = "$sonjj/$path/inbox?payload=${enc(payload)}"
            val obj = Net.json(
                Request.Builder().url(inboxUrl).header("User-Agent", Net.UA).build(),
            )
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val mid = m.optString("mid")
                if (mid.isEmpty()) return@mapNotNull null
                val dateRaw = firstNonEmpty(
                    m.optString("textDate"),
                    m.optString("date"),
                    m.optString("timestamp"),
                )
                MailMessage(
                    id = mid,
                    from = firstNonEmpty(m.optString("textFrom"), m.optString("from"), "?"),
                    to = firstNonEmpty(m.optString("textTo"), m.optString("to")),
                    subject = firstNonEmpty(m.optString("textSubject"), m.optString("subject")),
                    dateRaw = dateRaw,
                    dateMillis = Dates.parseMillis(dateRaw) ?: 0L,
                    snippet = "",
                    hasBody = true,
                )
            }
        }

    /**
     * Full HTML body of one message. Speed path: try the fetch WITHOUT a
     * captcha first (fast when the server doesn't enforce it for this call);
     * only fall back to session + captcha + one-tap dialog when needed.
     */
    suspend fun body(account: MailAccount, id: String): MailBody =
        withContext(Dispatchers.IO) {
            val url = "$base/app/message".toHttpUrl().newBuilder()
                .addQueryParameter("email", account.address)
                .addQueryParameter("mid", id)
                .build()

            fun fetch(token: String?): String {
                val rb = referer(Request.Builder().url(url))
                if (token != null) rb.header("x-captcha", token)
                return Net.execute(rb.build()).trim()
            }

            fun payloadOrThrow(raw: String): String {
                if (raw.isEmpty()) throw ApiException("empty-payload")
                if (raw.startsWith("{") && raw.contains("\"code\"")) {
                    val msg = runCatching { JSONObject(raw).optString("msg") }.getOrNull()
                    if (!msg.isNullOrEmpty()) throw ApiException(msg)
                    throw ApiException("message-payload")
                }
                return raw
            }

            fun isCaptchaRejection(raw: String): Boolean =
                raw.isEmpty() ||
                    (raw.startsWith("{") && raw.contains("Captcha", ignoreCase = true)) ||
                    raw.contains("captcha", ignoreCase = true) && raw.length < 400

            // 1) fast path — no captcha header at all
            var payload: String? = null
            try {
                val raw = fetch(null)
                if (!isCaptchaRejection(raw)) payload = payloadOrThrow(raw)
            } catch (e: HttpException) {
                val captchaHit = e.code == 403 && e.body.contains("aptcha", ignoreCase = true)
                if (!captchaHit) throw e
            }

            // 2) fallback — fresh session + solver/dialog, single attempt
            if (payload == null) {
                ensureSession()
                val token = Captcha.getToken("smailpro_message")
                payload = payloadOrThrow(fetch(token))
            }

            val url2 = "$sonjj/$path/message?payload=${enc(payload!!)}"
            val obj = Net.json(Request.Builder().url(url2).header("User-Agent", Net.UA).build())
            val content = obj.optString("body").ifEmpty { obj.optString("text") }
            if (content.isEmpty()) throw ApiException("empty-body")
            val looksHtml = content.contains("<")
            MailBody(
                html = if (looksHtml) content else null,
                text = if (looksHtml) null else content,
            )
        }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun firstNonEmpty(vararg values: String?): String {
        for (v in values) if (!v.isNullOrBlank()) return v
        return ""
    }
}
