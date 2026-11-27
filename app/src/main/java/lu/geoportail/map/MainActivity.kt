package lu.geoportail.map

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.webkit.*
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient


class MainActivity : Activity() {
    private val websiteUrl = "https://map.geoportail.lu?localforage=android&applogin=yes&embeddedserver=127.0.0.1:8766&embeddedserverprotocol=http&version=3"

    // Since Android 12, FINE must be requested together with COARSE (the user may grant only COARSE)
    private val locationPermissions = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val MY_PERMISSIONS_REQUEST_LOCATION = 1
    private var mGeoLocationRequestOrigin: String? = null
    private var mGeoLocationCallback: GeolocationPermissions.Callback? = null
    /**
     * Only enabled for debuggable builds.
     * See https://developers.google.com/web/tools/chrome-devtools/remote-debugging/webviews
     */
    private fun allowWebviewDebugging() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun hasLocationPermission(): Boolean = locationPermissions.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Since targetSdk 35 the app is drawn edge-to-edge: keep the web content out of the
     * system bars and display cutout by padding the container with the window insets.
     */
    private fun wrapInInsetContainer(view: WebView): FrameLayout {
        val container = FrameLayout(this)
        container.setBackgroundColor(getColor(R.color.colorPrimaryDark))
        container.addView(view)
        container.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        return container
    }

    /**
     * See https://developer.android.com/guide/webapps/webview#kotlin
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndConfigureWebView() : WebView {
        val view = WebView(this)
        val settings = view.settings
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.javaScriptEnabled = true
        view.addJavascriptInterface(JsObject(view), "ngeoHost")

        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        settings.setGeolocationEnabled(true)

        // Without a WebViewClient, every navigation started by the page (e.g. location.reload()
        // in the "App reload needed" modal) is handed to Android and opens the browser.
        val appHost = websiteUrl.toUri().host
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                // the map itself and the embedded tile server stay inside the app
                if (host == appHost || host == "127.0.0.1" || host == "localhost") {
                    return false
                }
                // everything else (external links, mailto:, ...) keeps opening outside the app, as before
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                    // no app can handle this URL: ignore it
                }
                return true
            }
        }

        view.setWebChromeClient(object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                mGeoLocationRequestOrigin = null
                mGeoLocationCallback = null

                if (!hasLocationPermission()) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.permission_location)
                            .setNeutralButton(
                                android.R.string.ok
                            ) { _, _ ->
                                mGeoLocationRequestOrigin = origin
                                mGeoLocationCallback = callback
                                ActivityCompat.requestPermissions(
                                    this@MainActivity,
                                    locationPermissions,
                                    MY_PERMISSIONS_REQUEST_LOCATION
                                )
                            }
                            .show()
                    }
                    else {
                        mGeoLocationRequestOrigin = origin
                        mGeoLocationCallback = callback
                        ActivityCompat.requestPermissions(this@MainActivity, locationPermissions, MY_PERMISSIONS_REQUEST_LOCATION)
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
                // Either FINE or COARSE (approximate) location is enough for the map.
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
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
        setContentView(wrapInInsetContainer(view))

        // the server lives as long as the app process: start it only once, even if the
        // activity is recreated (otherwise the new server cannot bind port 8766)
        if (tileServer == null) {
            tileServer = LuxTileServer(this.filesDir).also { it.start() }
        }

        view.loadUrl(websiteUrl)
    }

    companion object {
        private var tileServer: LuxTileServer? = null
    }
}
