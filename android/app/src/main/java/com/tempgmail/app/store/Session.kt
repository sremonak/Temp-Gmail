package com.tempgmail.app.store

import android.content.Context
import android.content.SharedPreferences
import com.tempgmail.app.model.MailAccount
import com.tempgmail.app.model.Provider

/**
 * Single source of truth for the mailbox account and local message state.
 * Everything here is real state — there is no demo/fake mode.
 */
object Session {

    private const val PREFS = "tempgmail_session"
    private const val KEY_ACCOUNT = "account_json"
    private const val KEY_READ = "read_ids"
    private const val KEY_STAR = "star_ids"
    private const val KEY_HIDDEN = "hidden_ids"
    private const val KEY_THEME = "theme_mode"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // ---------------- account ----------------

    var account: MailAccount? = null
        private set

    fun loadAccount(): MailAccount? {
        val raw = prefs.getString(KEY_ACCOUNT, null) ?: return null
        account = runCatching { decode(raw) }.getOrNull()
        return account
    }

    fun saveAccount(acc: MailAccount) {
        account = acc
        prefs.edit().putString(KEY_ACCOUNT, encode(acc)).apply()
    }

    fun clearAccount() {
        account = null
        prefs.edit().remove(KEY_ACCOUNT).apply()
    }

    private fun encode(a: MailAccount): String =
        listOf(
            a.provider.name,
            a.address,
            a.createdAt.toString(),
            a.secret,
            a.timestamp.toString(),
            a.mailboxId,
            a.token,
        ).joinToString("\u0001")

    private fun decode(raw: String): MailAccount {
        val p = raw.split("\u0001")
        require(p.size >= 7) { "bad account" }
        return MailAccount(
            provider = Provider.valueOf(p[0]),
            address = p[1],
            createdAt = p[2].toLongOrNull() ?: System.currentTimeMillis(),
            secret = p[3],
            timestamp = p[4].toLongOrNull() ?: 0L,
            mailboxId = p[5],
            token = p[6],
        )
    }

    // ---------------- local message state ----------------

    private fun setOf(key: String): MutableSet<String> =
        prefs.getStringSet(key, null)?.toMutableSet() ?: mutableSetOf()

    private fun putSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, HashSet(value)).apply()
    }

    fun isRead(id: String) = setOf(KEY_READ).contains(id)
    fun markRead(id: String) = putSet(KEY_READ, setOf(KEY_READ).also { it.add(id) })

    fun isStarred(id: String) = setOf(KEY_STAR).contains(id)
    fun toggleStar(id: String): Boolean {
        val s = setOf(KEY_STAR)
        val added = if (s.contains(id)) s.remove(id) else s.add(id)
        putSet(KEY_STAR, s)
        return added
    }

    fun isHidden(id: String) = setOf(KEY_HIDDEN).contains(id)
    fun hide(id: String) = putSet(KEY_HIDDEN, setOf(KEY_HIDDEN).also { it.add(id) })
    fun unhideAll() = putSet(KEY_HIDDEN, emptySet())

    /** Wipe local message state when the user starts with a fresh mailbox. */
    fun clearMessageState() {
        prefs.edit()
            .remove(KEY_READ)
            .remove(KEY_STAR)
            .remove(KEY_HIDDEN)
            .apply()
    }

    // ---------------- theme ----------------

    fun themeMode(): String = prefs.getString(KEY_THEME, "system") ?: "system"
    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
    }
}
