package com.tempgmail.app.api

import com.tempgmail.app.model.MailAccount
import com.tempgmail.app.model.MailBody
import com.tempgmail.app.model.MailMessage
import com.tempgmail.app.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * mail.tm client — independent second provider (free, no API key, CORS-free
 * native HTTP). Used as the reliable fallback domain source.
 * Docs: https://docs.mail.tm
 */
class MailTmClient {

    private val base = "https://api.mail.tm"

    suspend fun create(): MailAccount = withContext(Dispatchers.IO) {
        val domainsRaw = Net.execute(
            Request.Builder().url("$base/domains").header("User-Agent", Net.UA).build(),
        )
        val members = JSONObject(domainsRaw).optJSONArray("hydra:member")
        val domain = (0 until (members?.length() ?: 0))
            .mapNotNull { members?.optJSONObject(it) }
            .firstOrNull { it.optBoolean("isActive", true) && !it.optBoolean("isPrivate", false) }
            ?.optString("domain")
            ?: throw ApiException("no-domains")

        val address = "tg.${randomLocal()}@$domain"
        val password = randomPassword()

        val json = "application/json; charset=utf-8".toMediaType()
        Net.execute(
            Request.Builder()
                .url("$base/accounts")
                .post(JSONObject().put("address", address).put("password", password).toString().toRequestBody(json))
                .header("User-Agent", Net.UA)
                .build(),
        )
        val tok = Net.json(
            Request.Builder()
                .url("$base/token")
                .post(JSONObject().put("address", address).put("password", password).toString().toRequestBody(json))
                .header("User-Agent", Net.UA)
                .build(),
        )
        MailAccount(
            provider = Provider.MAIL_TM,
            address = address,
            createdAt = System.currentTimeMillis(),
            secret = password,
            mailboxId = tok.optString("id"),
            token = tok.optString("token"),
        )
    }

    suspend fun list(account: MailAccount): List<MailMessage> = withContext(Dispatchers.IO) {
        val data = api(account, "/messages?page=1")
        val arr = data.optJSONArray("hydra:member") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val from = m.optJSONObject("from")
            val senderAddr = from?.optString("address").orEmpty()
            val senderName = from?.optString("name").orEmpty()
            val created = m.optString("createdAt")
            MailMessage(
                id = id,
                from = senderName.ifEmpty { senderAddr.substringBefore('@') }.ifEmpty { "?" },
                to = (m.optJSONArray("to")?.optJSONObject(0)?.optString("address")) ?: account.address,
                subject = m.optString("subject").ifEmpty { "(বিষয়হীন)" },
                dateRaw = created,
                dateMillis = Dates.parseMillis(created) ?: 0L,
                snippet = m.optString("intro"),
                hasBody = true,
            )
        }
    }

    suspend fun body(account: MailAccount, id: String): MailBody = withContext(Dispatchers.IO) {
        val m = api(account, "/messages/$id")
        val htmlArr = m.optJSONArray("html")
        val html = buildString {
            if (htmlArr != null) for (i in 0 until htmlArr.length()) append(htmlArr.optString(i))
        }
        val text = m.optString("text")
        MailBody(
            html = if (html.isNotBlank()) html else null,
            text = if (html.isNotBlank()) null else text.ifEmpty { null },
        )
    }

    suspend fun markRead(account: MailAccount, id: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                api(
                    account, "/messages/$id", method = "PATCH",
                    body = JSONObject().put("seen", true).toString(),
                    contentType = "application/merge-patch+json",
                )
            }
        }
    }

    suspend fun deleteMessage(account: MailAccount, id: String) {
        withContext(Dispatchers.IO) {
            api(account, "/messages/$id", method = "DELETE")
        }
    }

    /** Best-effort mailbox removal (called when the user starts fresh). */
    fun deleteAccountBestEffort(account: MailAccount) {
        if (account.mailboxId.isEmpty() || account.token.isEmpty()) return
        runCatching {
            Net.execute(
                Request.Builder()
                    .url("$base/accounts/${account.mailboxId}")
                    .delete()
                    .header("Authorization", "Bearer ${account.token}")
                    .header("User-Agent", Net.UA)
                    .build(),
            )
        }
    }

    // ---------------- low level ----------------

    private fun api(
        account: MailAccount,
        path: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/json",
        allowRetry: Boolean = true,
    ): JSONObject {
        fun call(token: String): Request {
            val b = Request.Builder().url("$base$path").header("User-Agent", Net.UA)
                .header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> b.get()
                "DELETE" -> b.delete()
                else -> b.patch(body!!.toRequestBody(contentType.toMediaType()))
            }
            return b.build()
        }

        try {
            return Net.json(call(account.token))
        } catch (e: HttpException) {
            if (e.code == 401 && allowRetry) {
                val fresh = retoken(account)
                return Net.json(call(fresh))
            }
            throw e
        }
    }

    private fun retoken(account: MailAccount): String {
        val json = "application/json; charset=utf-8".toMediaType()
        val obj = Net.json(
            Request.Builder()
                .url("$base/token")
                .post(
                    JSONObject()
                        .put("address", account.address)
                        .put("password", account.secret)
                        .toString()
                        .toRequestBody(json),
                )
                .header("User-Agent", Net.UA)
                .build(),
        )
        return obj.optString("token").ifEmpty { throw ApiException("reauth-failed") }
    }

    private fun randomLocal(): String {
        val a = listOf("swift", "mint", "coral", "nova", "pixel", "ember", "lunar", "cobalt", "amber", "zephyr")
        val b = listOf("falcon", "river", "tiger", "otter", "comet", "maple", "stone", "drift", "spark", "wave")
        val n = (1000..9999).random()
        return "${a.random()}.${b.random()}$n"
    }

    private fun randomPassword(): String {
        val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..18).map { chars.random() }.joinToString("") + "7!"
    }
}
