package com.tempgmail.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.tempgmail.app.api.Avatars
import com.tempgmail.app.api.Captcha
import com.tempgmail.app.api.Dates
import com.tempgmail.app.api.ErrorKind
import com.tempgmail.app.api.MailRepo
import com.tempgmail.app.store.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "msg_id"
        const val EXTRA_FROM = "msg_from"
        const val EXTRA_TO = "msg_to"
        const val EXTRA_SUBJECT = "msg_subject"
        const val EXTRA_DATE_RAW = "msg_date_raw"
        const val EXTRA_DATE_MILLIS = "msg_date_millis"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var topProgress: ProgressBar
    private lateinit var readerSubject: TextView
    private lateinit var readerAvatar: TextView
    private lateinit var readerFrom: TextView
    private lateinit var readerTo: TextView
    private lateinit var readerTime: TextView
    private lateinit var bodyLoading: ProgressBar
    private lateinit var bodyError: LinearLayout
    private lateinit var bodyErrorText: TextView
    private lateinit var bodyRetry: com.google.android.material.button.MaterialButton
    private lateinit var bodyPlain: TextView
    private lateinit var webView: WebView

    private val scope by lazy { lifecycleScope }
    private var messageId: String = ""
    private var fromName: String = ""
    private var toName: String = ""
    private var subject: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        Captcha.host = this
        setContentView(R.layout.activity_reader)

        messageId = intent.getStringExtra(EXTRA_ID).orEmpty()
        fromName = intent.getStringExtra(EXTRA_FROM).orEmpty()
        toName = intent.getStringExtra(EXTRA_TO).orEmpty()
        subject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty()
        val dateRaw = intent.getStringExtra(EXTRA_DATE_RAW).orEmpty()
        val dateMillis = intent.getLongExtra(EXTRA_DATE_MILLIS, 0L)

        bindViews()
        renderHeader(dateMillis, dateRaw)
        loadBody()
    }

    override fun onStart() {
        super.onStart()
        Captcha.host = this
    }

    override fun onDestroy() {
        runCatching { webView.destroy() }
        if (Captcha.host === this) Captcha.host = null
        super.onDestroy()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        topProgress = findViewById(R.id.topProgress)
        readerSubject = findViewById(R.id.readerSubject)
        readerAvatar = findViewById(R.id.readerAvatar)
        readerFrom = findViewById(R.id.readerFrom)
        readerTo = findViewById(R.id.readerTo)
        readerTime = findViewById(R.id.readerTime)
        bodyLoading = findViewById(R.id.bodyLoading)
        bodyError = findViewById(R.id.bodyError)
        bodyErrorText = findViewById(R.id.bodyErrorText)
        bodyRetry = findViewById(R.id.bodyRetry)
        bodyPlain = findViewById(R.id.bodyPlain)
        webView = findViewById(R.id.webView)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_star -> {
                    val added = Session.toggleStar(messageId)
                    updateStarIcon(added)
                    true
                }
                R.id.action_delete -> {
                    confirmDelete()
                    true
                }
                else -> false
            }
        }
        bodyRetry.setOnClickListener { loadBody() }
    }

    private fun renderHeader(dateMillis: Long, dateRaw: String) {
        readerSubject.text = subject.ifEmpty { getString(R.string.reader_subject_default) }
        readerFrom.text = fromName.ifEmpty { getString(R.string.reader_unknown) }
        readerTo.text = getString(R.string.reader_to, toName.ifEmpty { Session.account?.address ?: "?" })
        readerTime.text = if (dateMillis > 0) {
            Dates.fullDate(dateMillis, dateRaw)
        } else {
            dateRaw
        }
        readerAvatar.text = Avatars.initial(fromName)
        val bg = readerAvatar.background.mutate()
        if (bg is android.graphics.drawable.GradientDrawable) {
            bg.setColor(Avatars.color(fromName + toName + subject))
        }
        updateStarIcon(Session.isStarred(messageId))
    }

    private fun updateStarIcon(starred: Boolean) {
        val item = toolbar.menu.findItem(R.id.action_star) ?: return
        item.setIcon(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
        val tint = ContextCompat.getColor(
            this,
            if (starred) R.color.star_gold else R.color.colorTextTertiary,
        )
        item.icon?.setTint(tint)
    }

    private fun loadBody() {
        val acc = Session.account
        if (acc == null || messageId.isEmpty()) {
            showBodyError(getString(R.string.body_error))
            return
        }
        topProgress.visibility = View.VISIBLE
        bodyLoading.visibility = View.VISIBLE
        bodyError.visibility = View.GONE
        bodyPlain.visibility = View.GONE
        webView.visibility = View.GONE

        scope.launch {
            try {
                val body = MailRepo.body(acc, messageId)
                val html = body.html
                if (html != null) {
                    showHtml(html)
                } else if (body.text != null) {
                    bodyPlain.text = body.text
                    bodyPlain.visibility = View.VISIBLE
                } else {
                    showBodyError(getString(R.string.body_error))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = MailRepo.userError(e)
                val msg = when (kind) {
                    ErrorKind.NETWORK -> getString(R.string.err_network)
                    ErrorKind.CAPTCHA -> getString(R.string.err_captcha)
                    ErrorKind.SERVER -> getString(R.string.err_server)
                    ErrorKind.CREATE -> getString(R.string.err_create)
                }
                android.util.Log.w("TempGmail", "body error: ${e.message}")
                showBodyError(msg)
            } finally {
                topProgress.visibility = View.GONE
                bodyLoading.visibility = View.GONE
            }
        }
    }

    private fun showHtml(html: String) {
        webView.settings.apply {
            javaScriptEnabled = false // never run scripts from emails
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            setDisplayZoomControls(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.setBackgroundColor(Color.WHITE)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                return false
            }
        }
        val doc = """
            <base target="_blank">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{margin:0;padding:12px;font-family:Roboto,'Noto Sans Bengali',Arial,sans-serif;color:#202124;word-break:break-word}img{max-width:100%;height:auto}</style>
            $html
        """.trimIndent()
        webView.loadDataWithBaseURL("https://smailpro.com/", doc, "text/html", "utf-8", null)
        webView.visibility = View.VISIBLE
        bodyPlain.visibility = View.GONE
    }

    private fun showBodyError(message: String) {
        bodyErrorText.text = message
        bodyError.visibility = View.VISIBLE
        bodyPlain.visibility = View.GONE
        webView.visibility = View.GONE
    }

    private fun confirmDelete() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_msg)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteAndClose() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun deleteAndClose() {
        val acc = Session.account ?: run { finish(); return }
        scope.launch {
            MailRepo.deleteRemote(acc, messageId)
            Session.hide(messageId)
            Toast.makeText(this@ReaderActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
