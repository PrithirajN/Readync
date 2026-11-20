package io.github.prithirajn.readync.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads a page the way a browser does, unseen: for sites whose bot check (Cloudflare's "Just a
 * moment…") turns plain requests away. It loads the page in a WebView, waits for the check to
 * clear, and hands back the page's HTML. Called off the main thread; the WebView lives on it.
 */
object PageBrowser {

    fun install(app: Context) {
        io.github.prithirajn.readync.source.WebNovels.browser = { url -> fetch(app, url) }
        io.github.prithirajn.readync.source.SeekquelWeb.browser = { url -> fetch(app, url) }
        io.github.prithirajn.readync.source.SffSocials.browser = { url -> fetch(app, url) }
        io.github.prithirajn.readync.source.SffArticleReader.browser = { url -> fetch(app, url) }
        io.github.prithirajn.readync.source.SffSources.browser = { url -> fetch(app, url, timeoutMs = 18_000) }
        io.github.prithirajn.readync.source.BookPrices.browser = { url -> fetch(app, url, timeoutMs = 20_000) }
        io.github.prithirajn.readync.source.BookPrices.browserUntil = { url, ready -> fetch(app, url, timeoutMs = 25_000, ready = ready) }
        io.github.prithirajn.readync.net.BotCheck.solver = { url, cookies, ua -> clearCheck(app, url, cookies, ua) }
    }

    /**
     * Clears a site's bot check (Goodreads' Amazon WAF, StoryGraph's Cloudflare) as a browser would: loads [url] unseen with
     * the saved [cookieHeader] and [userAgent], lets the check's script run, and once the real page
     * shows, hands back the cookies the site then has (the check's own among them). Null if it
     * didn't clear in time. Called off the main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun clearCheck(context: Context, url: String, cookieHeader: String, userAgent: String?, timeoutMs: Long = 25_000): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var out: String? = null
        var view: WebView? = null
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return null
        val domain = "." + host.removePrefix("www.")
        main.post {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            // The saved session goes in first, so the page loads signed in, as it did when you signed in.
            for (part in cookieHeader.split(';')) {
                val kv = part.trim()
                if (kv.indexOf('=') <= 0) continue
                cm.setCookie(url, "$kv; Domain=$domain; Path=/; Secure")
            }
            val w = runCatching { WebView(context.applicationContext) }.getOrNull() ?: run { latch.countDown(); return@post }
            view = w
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            if (!userAgent.isNullOrBlank()) w.settings.userAgentString = userAgent
            var tries = 0
            var waiting = false
            fun check() {
                if (latch.count == 0L) return
                w.evaluateJavascript("(function(){return document.title+'\\u0001'+document.documentElement.outerHTML.length+'\\u0001'+(function(h){return h.indexOf('awsWaf')>=0||h.indexOf('challenge.js')>=0||h.indexOf('challenge-platform')>=0||h.indexOf('cf-chl')>=0})(document.documentElement.outerHTML)})()") { raw ->
                    waiting = false
                    val s2 = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                    val parts = s2.split('\u0001')
                    val title = parts.getOrNull(0).orEmpty()
                    val length = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val challenged = parts.getOrNull(2) == "true" || title.contains("human verification", true) || title.contains("just a moment", true) || title.contains("attention required", true) || length < 3000
                    if (!challenged) {
                        cm.flush()
                        out = cm.getCookie(url)
                        latch.countDown()
                    } else if (++tries < (timeoutMs / 1200).toInt()) { waiting = true; main.postDelayed({ check() }, 1200) }
                    else latch.countDown()
                }
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    if (!waiting) { waiting = true; main.postDelayed({ check() }, 800) }
                }
            }
            w.loadUrl(url)
        }
        latch.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        main.post { view?.run { stopLoading(); destroy() } }
        return out
    }

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * [ready]: what the page must contain before it's handed back (search results a shop's script
     * fills in after the page itself has loaded); it's looked at again every second or so until then.
     */
    fun fetch(context: Context, url: String, timeoutMs: Long = 25_000, ready: Regex? = null): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null // never block the screen
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var html: String? = null
        var view: WebView? = null
        main.post {
            val w = runCatching { WebView(context.applicationContext) }.getOrNull() ?: run { latch.countDown(); return@post }
            view = w
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            var tries = 0
            var waiting = false
            fun grab() {
                if (latch.count == 0L) return
                w.evaluateJavascript("(function(){return document.title+'\\u0001'+document.documentElement.outerHTML})()") { raw ->
                    waiting = false
                    val s = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                    val title = s.substringBefore('\u0001')
                    val page = s.substringAfter('\u0001', "")
                    val checking = title.contains("just a moment", true) || title.contains("attention required", true) || page.length < 500
                    val notYet = ready != null && !ready.containsMatchIn(page)
                    if (!checking && !notYet) { html = page; latch.countDown() }
                    else if (++tries < (timeoutMs / 1200).toInt().coerceAtLeast(3)) { waiting = true; main.postDelayed({ grab() }, 1200) }
                    else { if (!checking && page.length >= 500) html = page; latch.countDown() }
                }
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    if (!waiting) { waiting = true; main.postDelayed({ grab() }, 500) }
                }
            }
            w.loadUrl(url)
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        main.post { view?.run { stopLoading(); destroy() } }
        return html
    }
}
