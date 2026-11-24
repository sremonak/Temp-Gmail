package com.tempgmail.app.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWith

/** Shared OkHttp client: browser-like UA + per-host in-memory cookies. */
object Net {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host] ?: emptyList()
        })
        .build()

    /** Executes a request and returns the body string; throws typed errors. */
    fun execute(request: Request): String {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: UnknownHostException) {
            throw NoNetworkException(cause = e)
        } catch (e: ConnectException) {
            throw NoNetworkException(cause = e)
        } catch (e: SocketTimeoutException) {
            throw NoNetworkException("timeout", e)
        } catch (e: IOException) {
            throw ApiException("network", e)
        }
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, body)
            return body
        }
    }

    fun json(request: Request): JSONObject {
        val raw = execute(request)
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw ApiException("bad-json", e)
        }
    }
}

/** Could not reach the internet at all. */
class NoNetworkException(message: String = "offline", cause: Throwable? = null) :
    IOException(message, cause)

/** Server replied with a non-2xx status. */
class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

/** Generic API/parse problem with a machine-readable reason. */
class ApiException(reason: String, cause: Throwable? = null) : IOException(reason, cause)

/** Captcha verification failed or was cancelled by the user. */
class CaptchaException(message: String = "captcha") : IOException(message)

object Dates {

    private val PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd MMM yyyy HH:mm:ss",
    )

    /** Best-effort parse of API date strings; returns null when unknown. */
    fun parseMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        s.toLongOrNull()?.let { epoch ->
            return when {
                epoch > 1_000_000_000_000L -> epoch
                epoch > 1_000_000_000L -> epoch * 1000
                else -> null
            }
        }
        for (p in PATTERNS) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (p.contains("'Z'")) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(s)?.let { return it.time }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Bengali relative time for list rows. */
    fun relTime(millis: Long): String {
        if (millis <= 0L) return ""
        val diff = millis - System.currentTimeMillis()
        val abs = kotlin.math.abs(diff)
        val minutes = abs / 60_000L
        val future = diff > 0
        val core = when {
            minutes < 1 -> "এখনই"
            minutes < 60 -> "$minutes মিনিট"
            abs < 86_400_000L -> "${abs / 3_600_000L} ঘণ্টা"
            abs < 604_800_000L -> "${abs / 86_400_000L} দিন"
            else -> SimpleDateFormat("d MMM", Locale.US).format(Date(millis))
        }
        return when {
            core == "এখনই" -> core
            abs < 604_800_000L -> if (future) "$core পরে" else "$core আগে"
            else -> SimpleDateFormat("d MMM", Locale.US).format(Date(millis))
        }
    }

    /** Full date for the reader header. */
    fun fullDate(millis: Long, raw: String): String {
        if (millis <= 0L) return raw
        return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(millis))
    }
}

object Avatars {
    private val PALETTE = intArrayOf(
        0xFF5F6368.toInt(), 0xFF1A73E8.toInt(), 0xFFD93025.toInt(), 0xFF188038.toInt(),
        0xFF9334E6.toInt(), 0xFFE37400.toInt(), 0xFF007B83.toInt(), 0xFFC5221F.toInt(),
    )

    fun color(seed: String): Int {
        var h = 0
        for (c in seed) h = h * 31 + c.code
        return PALETTE[(h and 0x7fffffff) % PALETTE.size]
    }

    fun initial(displayName: String): String {
        val t = displayName.trim()
        return if (t.isEmpty()) "?" else t.first().uppercase()
    }
}
