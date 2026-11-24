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
                else -> MailTmClient().create()
            }
        }

    suspend fun list(account: MailAccount): List<MailMessage> = when {
        account.provider.isSmailpro -> SmailproClient(account.provider).list(account)
        else -> MailTmClient().list(account)
    }

    suspend fun body(account: MailAccount, id: String): MailBody = when {
        account.provider.isSmailpro -> SmailproClient(account.provider).body(account, id)
        else -> MailTmClient().body(account, id)
    }

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
