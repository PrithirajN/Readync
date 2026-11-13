package io.github.prithirajn.trackersync.ui

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
        io.github.prithirajn.trackersync.source.WebNovels.browser = { url -> fetch(app, url) }
        io.github.prithirajn.trackersync.source.SeekquelWeb.browser = { url -> fetch(app, url) }
        io.github.prithirajn.trackersync.source.SffSocials.browser = { url -> fetch(app, url) }
        io.github.prithirajn.trackersync.source.SffArticleReader.browser = { url -> fetch(app, url) }
        io.github.prithirajn.trackersync.source.SffSources.browser = { url -> fetch(app, url, timeoutMs = 18_000) }
        io.github.prithirajn.trackersync.source.BookPrices.browser = { url -> fetch(app, url, timeoutMs = 20_000) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(context: Context, url: String, timeoutMs: Long = 25_000): String? {
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
                    if (!checking) { html = page; latch.countDown() }
                    else if (++tries < 14) { waiting = true; main.postDelayed({ grab() }, 1200) }
                    else latch.countDown()
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
