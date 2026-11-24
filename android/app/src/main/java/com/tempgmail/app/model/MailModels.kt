package com.tempgmail.app.model

/** Where the mailbox lives. All options are real, working inboxes — no demo data. */
enum class Provider {
    /** SmailPro managed pool → real @gmail.com addresses */
    SMAIL_GMAIL,
    /** SmailPro managed pool → real @outlook.com addresses */
    SMAIL_OUTLOOK,
    /** SmailPro regular disposable domains */
    SMAIL_TEMP,
    /** mail.tm disposable domains (always available fallback) */
    MAIL_TM;

    val isSmailpro: Boolean get() = this != MAIL_TM

    val displayName: String get() = when (this) {
        SMAIL_GMAIL -> "Gmail পুল"
        SMAIL_OUTLOOK -> "Outlook পুল"
        SMAIL_TEMP -> "SmailPro টেম্প"
        MAIL_TM -> "mail.tm"
    }

    val sonjjPath: String get() = when (this) {
        SMAIL_GMAIL -> "temp_gmail"
        SMAIL_OUTLOOK -> "temp_outlook"
        else -> "temp_email"
    }

    val domain: String get() = when (this) {
        SMAIL_GMAIL -> "gmail.com"
        SMAIL_OUTLOOK -> "outlook.com"
        else -> "devpoet.com"
    }
}

/** Persisted mailbox credentials (SharedPreferences via Session). */
data class MailAccount(
    val provider: Provider,
    val address: String,
    val createdAt: Long,
    /** SmailPro: JWT key; mail.tm: account password */
    val secret: String = "",
    /** SmailPro only */
    val timestamp: Long = 0L,
    /** mail.tm only */
    val mailboxId: String = "",
    /** mail.tm only */
    val token: String = "",
)

/** One row of the inbox. */
data class MailMessage(
    val id: String,
    val from: String,
    val to: String,
    val subject: String,
    val dateRaw: String,
    val dateMillis: Long,
    val snippet: String,
    val hasBody: Boolean,
)

/** Full body of one message: HTML (preferred) or plain text. */
data class MailBody(
    val html: String?,
    val text: String?,
)
