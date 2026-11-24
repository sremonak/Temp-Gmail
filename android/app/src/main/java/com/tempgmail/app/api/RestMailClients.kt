package com.tempgmail.app.api

import com.tempgmail.app.model.MailAccount
import com.tempgmail.app.model.MailMessage
import com.tempgmail.app.model.Provider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Clients for the keyless REST/GraphQL mail providers: GrabMail, CatchMail
 * (instant catch-all), Guerrilla Mail (session), Maildrop (public GraphQL).
 * All endpoints verified live via CI probe — zero API keys, zero accounts.
 */
object RestMailClients {

    private const val GRABMAIL = "https://grabmail.io/api/v1"
    private const val CATCHMAIL = "https://api.catchmail.io/api/v1"
    private const val MAILDROP = "https://api.maildrop.cc/graphql"
    private const val GUERRILLA = "https://api.guerrillamail.com/ajax.php"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", Net.UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        return Net.execute(b.build())
    }

    private fun post(url: String, body: String): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", Net.UA)
            .post(body.toRequestBody(JSON_TYPE))
        return Net.execute(b.build())
    }

    private fun msg(
        addr: String, id: String, from: String, subject: String,
        dateRaw: String?, snippet: String?
    ) = MailMessage(
        id = id,
        from = from.ifEmpty { "(unknown sender)" },
        to = addr,
        subject = subject.ifEmpty { "(no subject)" },
        dateRaw = dateRaw.orEmpty(),
        dateMillis = parseEpoch(dateRaw) ?: 0L,
        snippet = snippet.orEmpty(),
        hasBody = true,
    )

    // ---------- GrabMail / CatchMail (identical shape) ----------

    private fun restBase(p: Provider) = when (p) {
        Provider.GRABMAIL -> GRABMAIL
        Provider.CATCHMAIL -> CATCHMAIL
        else -> error("not a rest provider: $p")
    }

    fun listRest(account: MailAccount): List<MailMessage> {
        val base = restBase(account.provider)
        val url = "$base/mailbox?address=" + enc(account.address)
        val raw = get(url)
        val msgs = JSONObject(raw).optJSONArray("messages") ?: JSONArray()
        val out = ArrayList<MailMessage>()
        for (i in 0 until msgs.length()) {
            val o = msgs.optJSONObject(i) ?: continue
            val id = first(o, listOf("id", "message_id", "messageId", "uid", "mid"))
                ?: o.toString().hashCode().toUInt().toString(16)
            out.add(
                msg(
                    addr = account.address,
                    id = id,
                    from = first(o, listOf("from", "sender", "mail_from", "from_address", "headerfrom", "address")) ?: "",
                    subject = first(o, listOf("subject", "mail_subject", "title")) ?: "",
                    dateRaw = first(o, listOf("date", "timestamp", "mail_date", "created_at", "received_at")),
                    snippet = first(o, listOf("snippet", "intro", "preview", "excerpt", "mail_excerpt", "body_text"))
                )
            )
        }
        return out
    }

    fun bodyRest(account: MailAccount, messageId: String): String {
        val base = restBase(account.provider)
        val url = "$base/message/" + enc(messageId) + "?mailbox=" + enc(account.address)
        val raw = get(url)
        return htmlOf(JSONObject(raw))
    }

    // ---------- Guerrilla Mail ----------

    fun guerrillaCreate(): MailAccount {
        val o = JSONObject(get("$GUERRILLA?f=get_email_address&lang=en"))
        val addr = o.optString("email_addr")
        val sid = o.optString("sid_token")
        require(addr.isNotEmpty() && sid.isNotEmpty()) { "guerrilla: empty address" }
        return MailAccount(
            provider = Provider.GUERRILLA,
            address = addr,
            createdAt = System.currentTimeMillis(),
            secret = sid,
        )
    }

    fun guerrillaList(account: MailAccount): List<MailMessage> {
        val list = guerrillaCheck(account.secret)
            ?: run {
                // session lost — mint a fresh one and retry once
                val fresh = guerrillaCreate()
                guerrillaCheck(fresh.secret) ?: JSONArray()
            }
        val out = ArrayList<MailMessage>()
        for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            val id = m.optString("email_id").takeIf { it.isNotEmpty() } ?: continue
            out.add(
                msg(
                    addr = account.address,
                    id = id,
                    from = m.optString("mail_from"),
                    subject = m.optString("mail_subject"),
                    dateRaw = m.optString("mail_timestamp"),
                    snippet = m.optString("mail_excerpt").takeIf { it.isNotEmpty() }
                )
            )
        }
        return out
    }

    private fun guerrillaCheck(sid: String): JSONArray? {
        val o = JSONObject(get("$GUERRILLA?f=check_email&seq=0", mapOf("Cookie" to "SUBSCR=$sid")))
        return o.optJSONArray("list")
    }

    fun guerrillaBody(account: MailAccount, messageId: String): String {
        val raw = get(
            "$GUERRILLA?f=fetch_email&email_id=" + enc(messageId),
            mapOf("Cookie" to "SUBSCR=${account.secret}")
        )
        val o = JSONObject(raw)
        val html = o.optString("mail_html").takeIf { it.isNotEmpty() && it.contains('<') }
            ?: o.optString("mail_body")
        require(html.isNotEmpty()) { "guerrilla: empty body" }
        return html
    }

    // ---------- Maildrop (public GraphQL) ----------

    fun maildropList(account: MailAccount): List<MailMessage> {
        val mailbox = account.address.substringBefore('@')
        val q = """{ inbox(mailbox: "$mailbox") { id headerfrom subject date } }"""
        val o = JSONObject(post(MAILDROP, JSONObject().put("query", q).toString()))
        val inbox = o.optJSONObject("data")?.optJSONArray("inbox") ?: JSONArray()
        val out = ArrayList<MailMessage>()
        for (i in 0 until inbox.length()) {
            val m = inbox.optJSONObject(i) ?: continue
            val id = m.optString("id").takeIf { it.isNotEmpty() } ?: continue
            out.add(
                msg(
                    addr = account.address,
                    id = id,
                    from = m.optString("headerfrom"),
                    subject = m.optString("subject"),
                    dateRaw = m.optString("date"),
                    snippet = null
                )
            )
        }
        return out
    }

    fun maildropBody(account: MailAccount, messageId: String): String {
        val mailbox = account.address.substringBefore('@')
        val q = """{ message(mailbox: "$mailbox", id: "$messageId") { id html } }"""
        val o = JSONObject(post(MAILDROP, JSONObject().put("query", q).toString()))
        val html = o.optJSONObject("data")?.optJSONObject("message")?.optString("html").orEmpty()
        require(html.isNotEmpty()) { "maildrop: empty body" }
        return html
    }

    // ---------- shared helpers ----------

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun first(o: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            val v = o.optString(k)
            if (!v.isNullOrEmpty() && v != "null") return v
        }
        return null
    }

    private fun htmlOf(o: JSONObject): String {
        val direct = first(o, listOf("body", "html", "content", "text", "raw", "mail_html", "mail_body"))
        if (direct != null) {
            return if (direct.contains('<')) direct
            else "<p>" + direct.replace("&", "&amp;").replace("<", "&lt;")
                .replace("\n", "<br>") + "</p>"
        }
        // deep scan — payload may be nested
        val stack = ArrayDeque<JSONObject>()
        stack.addLast(o)
        var hops = 0
        while (stack.isNotEmpty() && hops++ < 6) {
            val cur = stack.removeFirst()
            val it = cur.keys()
            while (it.hasNext()) {
                val v = cur.opt(it.next())
                when (v) {
                    is JSONObject -> stack.addLast(v)
                    is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { stack.addLast(it) }
                    is String -> if (v.contains('<') && v.length > 200) return v
                }
            }
        }
        error("message body not found in response")
    }

    private fun parseEpoch(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        raw.toLongOrNull()?.let {
            return if (it > 10_000_000_000L) it else it * 1000
        }
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
