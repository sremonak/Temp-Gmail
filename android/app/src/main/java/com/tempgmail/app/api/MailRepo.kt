package com.tempgmail.app.api

import com.tempgmail.app.model.MailAccount
import com.tempgmail.app.model.MailBody
import com.tempgmail.app.model.MailMessage
import com.tempgmail.app.model.Provider
import com.tempgmail.app.store.Session
import java.io.IOException

/**
 * Single entry point the UI talks to. Every result is real data fetched
 * from the live provider for the current account — there is no demo path.
 */
object MailRepo {

    suspend fun create(provider: Provider): MailAccount =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Clean removal of a previous mail.tm mailbox (best effort).
            val old = Session.account
            if (old != null && old.provider == Provider.MAIL_TM) {
                runCatching { MailTmClient().deleteAccountBestEffort(old) }
            }
            when {
                provider.isSmailpro -> SmailproClient(provider).create()
                provider == Provider.GUERRILLA -> RestMailClients.guerrillaCreate()
                // Instant catch-all providers: the address exists on arrival —
                // no create call, no captcha, nothing to wait for.
                provider.isInstant -> MailAccount(
                    provider = provider,
                    address = randomLocal() + "@" + provider.domain,
                    createdAt = System.currentTimeMillis(),
                )
                else -> MailTmClient().create()
            }
        }

    private fun randomLocal(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..8).map { alphabet.random() }.joinToString("")
        return "tg.$suffix"
    }

    suspend fun list(account: MailAccount): List<MailMessage> = when {
        account.provider.isSmailpro -> SmailproClient(account.provider).list(account)
        account.provider == Provider.MAIL_TM -> MailTmClient().list(account)
        account.provider == Provider.GUERRILLA -> RestMailClients.guerrillaList(account)
        account.provider == Provider.MAILDROP -> RestMailClients.maildropList(account)
        else -> RestMailClients.listRest(account) // GRABMAIL, CATCHMAIL
    }

    suspend fun body(account: MailAccount, id: String): MailBody = when {
        account.provider.isSmailpro -> SmailproClient(account.provider).body(account, id)
        account.provider == Provider.MAIL_TM -> MailTmClient().body(account, id)
        account.provider == Provider.GUERRILLA -> htmlBody(RestMailClients.guerrillaBody(account, id))
        account.provider == Provider.MAILDROP -> htmlBody(RestMailClients.maildropBody(account, id))
        else -> htmlBody(RestMailClients.bodyRest(account, id))
    }

    private fun htmlBody(html: String) = MailBody(html = html, text = null)

    /** Server-side state, if the provider supports it. Never throws to the UI. */
    suspend fun markReadRemote(account: MailAccount, id: String) {
        if (account.provider == Provider.MAIL_TM) {
            runCatching { MailTmClient().markRead(account, id) }
        }
    }

    /** Best-effort server delete; local hiding is handled by Session. */
    suspend fun deleteRemote(account: MailAccount, id: String) {
        if (account.provider == Provider.MAIL_TM) {
            runCatching { MailTmClient().deleteMessage(account, id) }
        }
    }

    /** Maps any failure to one of the typed errors the UI understands. */
    fun userError(e: Throwable): ErrorKind = when (e) {
        is NoNetworkException -> ErrorKind.NETWORK
        is CaptchaException -> ErrorKind.CAPTCHA
        is HttpException -> ErrorKind.SERVER
        is ApiException -> ErrorKind.SERVER
        is IOException -> ErrorKind.NETWORK
        else -> ErrorKind.CREATE
    }
}

enum class ErrorKind { NETWORK, CAPTCHA, SERVER, CREATE }
