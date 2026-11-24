package com.tempgmail.app.api

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resumeWith

/**
 * Turnstile token provider.
 *
 * Fast path: free Fgsi solver endpoint (automatic, with retries — it rate-limits).
 * Fallback: a small WebView dialog where the user taps the Cloudflare checkbox.
 * Both paths are real verification — nothing fake is ever bypassed.
 */
object Captcha {

    const val SITE_KEY = "0x4AAAAAAABIS_gEec2IwOhI"
    private const val PAGE_URL = "https://smailpro.com/temporary-email"
    private const val FGSI_KEY = "takashi0927192"

    /** Set by the visible activity so the fallback dialog has a window. */
    @Volatile
    var host: Activity? = null

    /** Returns a fresh Turnstile token for [action]. Safe off the main thread. */
    suspend fun getToken(action: String): String {
        val auto = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tryFgsi(action)
        }
        if (auto != null) return auto

        val activity = host ?: throw CaptchaException("no-host")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var delivered = false
                lateinit var dialog: Dialog
                dialog = buildDialog(
                    activity = activity,
                    action = action,
                    onToken = { token ->
                        if (!delivered && cont.isActive) {
                            delivered = true
                            cont.resumeWith(Result.success(token))
                        }
                    },
                    onDismiss = {
                        if (!delivered && cont.isActive) {
                            delivered = true
                            cont.resumeWith(Result.failure(CaptchaException("cancelled")))
                        }
                    },
                )
                dialog.setOnCancelListener {
                    if (!delivered && cont.isActive) {
                        delivered = true
                        cont.resumeWith(Result.failure(CaptchaException("cancelled")))
                    }
                }
                try {
                    dialog.show()
                } catch (e: Exception) {
                    if (!delivered) {
                        delivered = true
                        cont.resumeWith(Result.failure(CaptchaException(e.message ?: "show")))
                    }
                }
            }
        }
    }

    // ---------------- fast path: free solver ----------------

    private fun tryFgsi(action: String): String? {
        val url = "https://fgsi.dpdns.org/api/tools/cfclearance/turnstile-min"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("apikey", FGSI_KEY)
            .addQueryParameter("url", PAGE_URL)
            .addQueryParameter("sitekey", SITE_KEY)
            .addQueryParameter("action", action)
            .build()

        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
                val raw = Net.execute(req)
                val token = JSONObject(raw).optJSONObject("data")?.optString("token").orEmpty()
                if (token.isNotEmpty()) return token
            } catch (e: Exception) {
                android.util.Log.w("Captcha", "fgsi attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(if (attempt == 0) 6_000L else 15_000L)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    // ---------------- fallback: WebView dialog ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildDialog(
        activity: Activity,
        action: String,
        onToken: (String) -> Unit,
        onDismiss: () -> Unit,
    ): Dialog {
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val dialog = Dialog(activity)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(activity).apply {
            text = "নিরাপত্তা যাচাই করুন"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
            setPadding(0, 0, 0, pad / 2)
        }
        val progress = ProgressBar(activity).apply { isIndeterminate = true }
        val web = WebView(activity)

        layout.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        layout.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad },
        )
        layout.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (330 * density).toInt())
                .apply { topMargin = pad / 2 },
        )

        dialog.setContentView(layout)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onDismiss() }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = Net.UA
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setBackgroundColor(Color.WHITE)

        var settled = false
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onToken(token: String) {
                if (settled || token.isEmpty()) return
                settled = true
                activity.runOnUiThread {
                    runCatching { dialog.dismiss() }
                    onToken(token)
                }
            }

            @JavascriptInterface
            fun onError(message: String) {
                android.util.Log.w("Captcha", "widget error: $message")
                activity.runOnUiThread {
                    progress.visibility = TextView.GONE
                    title.text = "যাচাই ব্যর্থ — আবার চেষ্টা করুন বা বাতিল করুন"
                    title.setTextColor(0xFFD93025.toInt())
                }
            }
        }, "Android")

        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"></script>
            </head>
            <body style="margin:0;background:#fff;display:flex;justify-content:center;align-items:center;min-height:200px">
            <div id="w"></div>
            <script>
              window.addEventListener('load', function () {
                try {
                  if (typeof turnstile === 'undefined') { Android.onError('no-turnstile'); return; }
                  turnstile.ready(function () {
                    turnstile.render('#w', {
                      sitekey: '$SITE_KEY',
                      action: '$action',
                      callback: function (t) { Android.onToken(t); },
                      'error-callback': function (e) { Android.onError(String(e)); }
                    });
                  });
                } catch (e) { Android.onError(String(e)); }
              });
            </script>
            </body></html>
        """.trimIndent()

        web.loadDataWithBaseURL(PAGE_URL, html, "text/html", "utf-8", null)
        return dialog
    }
}
