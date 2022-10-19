package lu.geoportail.map

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.webkit.*
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient


class MainActivity : Activity() {
    // must remove "static in path when prod is up to date
    // private val websiteUrl = "https://map.geoportail.lu?localforage=android&applogin=yes&embeddedserver=127.0.0.1:8766/static&embeddedserverprotocol=http&version=3"
    private val websiteUrl = "https://migration.geoportail.lu?localforage=android&applogin=yes&embeddedserver=127.0.0.1:8766&embeddedserverprotocol=http&version=3"

    private val MY_PERMISSIONS_REQUEST_LOCATION = 1
    private var mGeoLocationRequestOrigin: String? = null
    private var mGeoLocationCallback: GeolocationPermissions.Callback? = null
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
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.javaScriptEnabled = true
        settings.setAppCachePath(appCachePath)
        settings.setAppCacheEnabled(true)
        view.addJavascriptInterface(JsObject(view), "ngeoHost")

        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        settings.setGeolocationEnabled(true)

        view.setWebChromeClient(object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                mGeoLocationRequestOrigin = null
                mGeoLocationCallback = null

                if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.permission_location)
                            .setNeutralButton(android.R.string.ok, object : DialogInterface.OnClickListener {
                                override fun onClick(dialog: DialogInterface?, which: Int) {
                                    mGeoLocationRequestOrigin = origin
                                    mGeoLocationCallback = callback
                                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), MY_PERMISSIONS_REQUEST_LOCATION)
                                }
                            })
                            .show()
                    }
                    else {
                        mGeoLocationRequestOrigin = origin
                        mGeoLocationCallback = callback
                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), MY_PERMISSIONS_REQUEST_LOCATION)
                    }
                }
                else {
                    callback.invoke(origin, true, true)
                }
            }
        })

        return view
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_LOCATION -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.

                    if (mGeoLocationCallback != null) {
                        mGeoLocationCallback?.invoke(mGeoLocationRequestOrigin, true, true)
                    }
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.

                    if (mGeoLocationCallback != null) {
                        mGeoLocationCallback?.invoke(mGeoLocationRequestOrigin, false, false)
                    }
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allowWebviewDebugging()
        val view = createAndConfigureWebView()
        setContentView(view)

        val context = getApplicationContext()

        val srv = LuxTileServer(this.getFilesDir())
        srv.start()

        view.loadUrl(websiteUrl)
    }
}

class MyWebViewclient : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request?.requestHeaders?.put("Referer", "http://localhost:5000/hack")
        return super.shouldInterceptRequest(view, request)
    }
}
