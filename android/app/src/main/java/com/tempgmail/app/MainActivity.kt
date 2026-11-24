package com.tempgmail.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tempgmail.app.api.Captcha
import com.tempgmail.app.api.ErrorKind
import com.tempgmail.app.api.MailRepo
import com.tempgmail.app.model.MailAccount
import com.tempgmail.app.model.MailMessage
import com.tempgmail.app.model.Provider
import com.tempgmail.app.store.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Filter { INBOX, UNREAD, STARRED }

    private lateinit var statusPill: TextView
    private lateinit var addressText: TextView
    private lateinit var btnCopy: ImageButton
    private lateinit var btnNewAddress: MaterialButton
    private lateinit var metaText: TextView
    private lateinit var errorBanner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerRetry: MaterialButton
    private lateinit var filterGroup: ChipGroup
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var loadingState: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var adapter: MailAdapter

    private val scope by lazy { lifecycleScope }
    private var allMessages: List<MailMessage> = emptyList()
    private var currentFilter = Filter.INBOX
    private var busy = false
    private var pollJob: Job? = null
    private var bannerAction: (() -> Unit)? = null
    private var firstRefreshDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        Captcha.host = this
        applyThemeFromSession()
        setContentView(R.layout.activity_main)

        bindViews()
        wireToolbar()
        wireList()

        val existing = Session.loadAccount()
        if (existing == null) {
            createAddress(Provider.SMAIL_GMAIL, isBoot = true)
        } else {
            renderAccount(existing)
            startPolling()
            refresh(manual = false)
        }
    }

    override fun onStart() {
        super.onStart()
        Captcha.host = this
    }

    override fun onDestroy() {
        if (Captcha.host === this) Captcha.host = null
        pollJob?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (Session.account != null) refresh(manual = false)
    }

    // ---------------- wiring ----------------

    private fun bindViews() {
        statusPill = findViewById(R.id.statusPill)
        addressText = findViewById(R.id.addressText)
        btnCopy = findViewById(R.id.btnCopy)
        btnNewAddress = findViewById(R.id.btnNewAddress)
        metaText = findViewById(R.id.metaText)
        errorBanner = findViewById(R.id.errorBanner)
        bannerText = findViewById(R.id.bannerText)
        bannerRetry = findViewById(R.id.bannerRetry)
        filterGroup = findViewById(R.id.filterGroup)
        swipe = findViewById(R.id.swipe)
        recycler = findViewById(R.id.recycler)
        emptyState = findViewById(R.id.emptyState)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyHint = findViewById(R.id.emptyHint)
        loadingState = findViewById(R.id.loadingState)
        toolbar = findViewById(R.id.toolbar)

        swipe.setColorSchemeResources(R.color.brand_red)
        swipe.setOnRefreshListener { refresh(manual = true) }

        btnCopy.setOnClickListener { copyAddress() }
        btnNewAddress.setOnClickListener { requestNewAddress() }
        bannerRetry.setOnClickListener { bannerAction?.invoke() }

        filterGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.chipUnread -> Filter.UNREAD
                R.id.chipStarred -> Filter.STARRED
                else -> Filter.INBOX
            }
            render()
        }
    }

    private fun wireToolbar() {
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    refresh(manual = true)
                    true
                }
                R.id.action_new_address -> {
                    requestNewAddress()
                    true
                }
                R.id.action_clear -> {
                    confirmClear()
                    true
                }
                R.id.action_theme -> {
                    cycleTheme()
                    true
                }
                else -> false
            }
        }
        updateThemeMenuTitle()
    }

    private fun wireList() {
        adapter = MailAdapter(
            onOpen = { m -> openReader(m) },
            onStar = { m -> toggleStar(m) },
        )
        adapter.isRead = { Session.isRead(it) }
        adapter.isStarred = { Session.isStarred(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    // ---------------- account ----------------

    private fun renderAccount(acc: MailAccount) {
        addressText.text = acc.address
        statusPill.text = getString(R.string.status_live)
        statusPill.setTextColor(ContextCompat.getColor(this, R.color.statusPillFg))
        val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US)
        metaText.text =
            "${acc.provider.displayName} • তৈরি ${fmt.format(Date(acc.createdAt))}"
    }

    private fun copyAddress() {
        val text = Session.account?.address ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Temp Gmail", text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun requestNewAddress() {
        if (busy) return
        if (Session.account != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_new_title)
                .setMessage(R.string.confirm_new_msg)
                .setPositiveButton(R.string.confirm_new_yes) { _, _ -> showProviderChoice() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        } else {
            showProviderChoice()
        }
    }

    private fun showProviderChoice() {
        val options = arrayOf(
            "${getString(R.string.opt_gmail_title)} — ${getString(R.string.opt_gmail_desc)}",
            "${getString(R.string.opt_outlook_title)} — ${getString(R.string.opt_outlook_desc)}",
            "${getString(R.string.opt_temp_title)} — ${getString(R.string.opt_temp_desc)}",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_addr_title)
            .setItems(options) { _, which ->
                val provider = when (which) {
                    0 -> Provider.SMAIL_GMAIL
                    1 -> Provider.SMAIL_OUTLOOK
                    else -> Provider.MAIL_TM
                }
                createAddress(provider, isBoot = false)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun createAddress(provider: Provider, isBoot: Boolean) {
        if (busy) return
        busy = true
        hideBanner()
        loadingState.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        statusPill.text = getString(R.string.address_creating)
        addressText.text = getString(R.string.address_creating)

        scope.launch {
            try {
                val acc = MailRepo.create(provider)
                Session.saveAccount(acc)
                Session.clearMessageState()
                allMessages = emptyList()
                firstRefreshDone = false
                renderAccount(acc)
                startPolling()
                fetchAndRender()
                if (!isBoot) {
                    Toast.makeText(this@MainActivity, R.string.toast_new_address, Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showAccountError(e) { createAddress(provider, isBoot) }
            } finally {
                busy = false
                loadingState.visibility = View.GONE
                swipe.isRefreshing = false
            }
        }
    }

    // ---------------- inbox ----------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(6_000)
                if (!busy && Session.account != null) refresh()
            }
        }
    }

    private fun refresh(manual: Boolean = false) {
        if (busy) return
        busy = true
        if (manual) swipe.isRefreshing = true
        scope.launch {
            try {
                fetchAndRender()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showAccountError(e) { refresh(manual = true) }
            } finally {
                busy = false
                swipe.isRefreshing = false
            }
        }
    }

    private suspend fun fetchAndRender() {
        val acc = Session.account ?: return
        val list = MailRepo.list(acc)
        val live = list.filterNot { Session.isHidden(it.id) }

        val prevUnread = allMessages.count { !Session.isRead(it.id) }
        val unreadNow = live.count { !Session.isRead(it.id) }
        val wasFirst = !firstRefreshDone
        firstRefreshDone = true

        allMessages = live
        loadingState.visibility = View.GONE
        render()
        hideBanner()

        if (!wasFirst && unreadNow > prevUnread) {
            Toast.makeText(this, R.string.toast_new_mail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        val all = allMessages
        val list = when (currentFilter) {
            Filter.INBOX -> all
            Filter.UNREAD -> all.filter { !Session.isRead(it.id) }
            Filter.STARRED -> all.filter { Session.isStarred(it.id) }
        }
        adapter.submit(list)

        if (list.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            when (currentFilter) {
                Filter.UNREAD -> {
                    emptyTitle.setText(R.string.empty_unread_title)
                    emptyHint.setText(R.string.empty_unread_hint)
                }
                Filter.STARRED -> {
                    emptyTitle.setText(R.string.empty_starred_title)
                    emptyHint.setText(R.string.empty_starred_hint)
                }
                else -> {
                    emptyTitle.setText(R.string.empty_inbox_title)
                    emptyHint.setText(R.string.empty_inbox_hint)
                }
            }
        } else {
            emptyState.visibility = View.GONE
        }
    }

    private fun toggleStar(m: MailMessage) {
        Session.toggleStar(m.id)
        render()
    }

    private fun openReader(m: MailMessage) {
        if (!Session.isRead(m.id)) {
            Session.markRead(m.id)
            val acc = Session.account
            if (acc != null) {
                scope.launch { MailRepo.markReadRemote(acc, m.id) }
            }
            render()
        }
        val intent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_ID, m.id)
            .putExtra(ReaderActivity.EXTRA_FROM, m.from)
            .putExtra(ReaderActivity.EXTRA_TO, m.to)
            .putExtra(ReaderActivity.EXTRA_SUBJECT, m.subject)
            .putExtra(ReaderActivity.EXTRA_DATE_RAW, m.dateRaw)
            .putExtra(ReaderActivity.EXTRA_DATE_MILLIS, m.dateMillis)
        startActivity(intent)
    }

    private fun confirmClear() {
        if (allMessages.isEmpty()) {
            Toast.makeText(this, R.string.empty_inbox_title, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(R.string.confirm_clear_msg)
            .setPositiveButton(R.string.btn_delete) { _, _ -> clearInbox() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun clearInbox() {
        val acc = Session.account ?: return
        val ids = allMessages.map { it.id }
        busy = true
        scope.launch {
            try {
                for (id in ids) {
                    MailRepo.deleteRemote(acc, id)
                    Session.hide(id)
                }
                allMessages = emptyList()
                render()
                Toast.makeText(this@MainActivity, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    // ---------------- errors ----------------

    private fun showAccountError(e: Throwable, retry: () -> Unit) {
        val kind = MailRepo.userError(e)
        val text = when (kind) {
            ErrorKind.NETWORK -> R.string.err_network
            ErrorKind.CAPTCHA -> R.string.err_captcha
            ErrorKind.SERVER -> R.string.err_server
            ErrorKind.CREATE -> R.string.err_create
        }
        android.util.Log.w("TempGmail", "error ($kind): ${e.message}")
        bannerText.setText(text)
        errorBanner.visibility = View.VISIBLE
        bannerAction = retry
        loadingState.visibility = View.GONE
        swipe.isRefreshing = false
        if (addressText.text?.toString() == getString(R.string.address_creating)) {
            addressText.text = getString(R.string.address_label)
        }
    }

    private fun hideBanner() {
        errorBanner.visibility = View.GONE
        bannerAction = null
    }

    // ---------------- theme ----------------

    private fun applyThemeFromSession() {
        AppCompatDelegate.setDefaultNightMode(
            when (Session.themeMode()) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    private fun cycleTheme() {
        val next = when (Session.themeMode()) {
            "system" -> "dark"
            "dark" -> "light"
            else -> "system"
        }
        Session.setThemeMode(next)
        applyThemeFromSession()
        updateThemeMenuTitle()
        Toast.makeText(this, R.string.toast_theme, Toast.LENGTH_SHORT).show()
    }

    private fun updateThemeMenuTitle() {
        val next = when (Session.themeMode()) {
            "system" -> "dark"
            "dark" -> "light"
            else -> "system"
        }
        val label = when (next) {
            "dark" -> getString(R.string.menu_theme_dark)
            "light" -> getString(R.string.menu_theme_light)
            else -> getString(R.string.menu_theme_system)
        }
        toolbar.menu.findItem(R.id.action_theme)?.title = label
    }
}
