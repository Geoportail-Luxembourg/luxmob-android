package lu.geoportail.map

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.*


class MainActivity : Activity() {
    // val websiteUrl = "https://map.geoportail.lu/?localforage=android" // production
    private val websiteUrl = "https://offline-demo.geoportail.lu/?localforage=android" // integration
    // private val websiteUrl = "http://10.0.2.2:5000/?localforage=android&localhost" // localhost

    /**
     * See https://developers.google.com/web/tools/chrome-devtools/remote-debugging/webviews
     */
    private fun allowWebviewDebugging() {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * See https://developer.android.com/guide/webapps/webview#kotlin
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndConfigureWebView() : WebView {
        val view = WebView(applicationContext)
        val settings = view.settings
        val appCachePath = this.cacheDir.absolutePath
        settings.allowFileAccess = true
        settings.javaScriptEnabled = true
        settings.setAppCachePath(appCachePath)
        settings.setAppCacheEnabled(true)
        view.addJavascriptInterface(JsObject(view), "ngeoHost")

        view.webViewClient = MyWebViewclient()
        view.webChromeClient = WebChromeClient()
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allowWebviewDebugging()
        val view = createAndConfigureWebView()
        setContentView(view)

        view.loadUrl(websiteUrl)
    }
}

class MyWebViewclient : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request?.requestHeaders?.put("Referer", "http://localhost:5000/hack")
        return super.shouldInterceptRequest(view, request)
    }
}
