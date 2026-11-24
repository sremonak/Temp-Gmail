package com.tempgmail.app.model

/** Where the mailbox lives. All options are real, working inboxes — no demo data. */
enum class Provider {
    /** SmailPro managed pool → real @gmail.com addresses */
    SMAIL_GMAIL,
    /** SmailPro managed pool → real @outlook.com addresses */
    SMAIL_OUTLOOK,
    /** SmailPro regular disposable domains */
    SMAIL_TEMP,
    /** mail.tm disposable domains (account-based, JWT) */
    MAIL_TM,
    /** GrabMail — instant catch-all address, zero create call */
    GRABMAIL,
    /** CatchMail — same instant pattern as GrabMail */
    CATCHMAIL,
    /** Guerrilla Mail — session-based, since 2006 */
    GUERRILLA,
    /** Maildrop — public inbox by username (GraphQL) */
    MAILDROP;
    val isSmailpro: Boolean
        get() = this == SMAIL_GMAIL || this == SMAIL_OUTLOOK || this == SMAIL_TEMP

    /** Providers that never need a network "create" call. */
    val isInstant: Boolean
        get() = this == GRABMAIL || this == CATCHMAIL || this == MAILDROP

    val displayName: String get() = when (this) {
        SMAIL_GMAIL -> "Gmail পুল"
        SMAIL_OUTLOOK -> "Outlook পুল"
        SMAIL_TEMP -> "SmailPro টেম্প"
        MAIL_TM -> "mail.tm"
        GRABMAIL -> "GrabMail"
        CATCHMAIL -> "CatchMail"
        GUERRILLA -> "Guerrilla Mail"
        MAILDROP -> "Maildrop"
    }

    val sonjjPath: String get() = when (this) {
        SMAIL_GMAIL -> "temp_gmail"
        SMAIL_OUTLOOK -> "temp_outlook"
        else -> "temp_email"
    }

    val domain: String get() = when (this) {
        SMAIL_GMAIL -> "gmail.com"
        SMAIL_OUTLOOK -> "outlook.com"
        SMAIL_TEMP -> "devpoet.com"
        GRABMAIL -> "grabmail.io"
        CATCHMAIL -> "catchmail.io"
        MAILDROP -> "maildrop.cc"
        MAIL_TM, GUERRILLA -> "guerrillamailblock.com"
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
