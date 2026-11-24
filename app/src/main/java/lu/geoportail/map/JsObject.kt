package lu.geoportail.map

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.Keep
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import lu.geoportail.map.backends.IBackend
import lu.geoportail.map.backends.LocalforageSqliteBackend
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONArray
import org.json.JSONObject


class JsObject(view: WebView) {

    private val backend: IBackend = LocalforageSqliteBackend(view.context)
    private val tag = "JsObject"
    private val webview: WebView = view
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backendHandler: Handler by lazy {
        val thread = HandlerThread("backendThread")
        thread.start()
        Handler(thread.looper)
    }

    @JavascriptInterface
    @Keep
    fun postMessageToAndroid(action: String) {
        Log.e(tag, "postMessageToAndroid $action")
        handleAction(action)
    }


    private fun handleAction(serializedAction: String) {
        val action = JSONObject(serializedAction)

        val plugin = action.getString("plugin")
        if (plugin != "localforage") {
            return
        }

        when (val command = action.getString("command")) {
            "getItem" -> getItem(action)
            "setItem" -> setItem(action)
            "removeItem" -> removeItem(action)
            "clear" -> clear(action)
            "config" -> config(action)
            else -> postErrorToWebview("Unhandled command: $command", action)
        }
    }

    private fun getItem(action: JSONObject) {
        val args = action.getJSONArray("args")
        val key = args.getString(0)
        backendHandler.post {
            val responseArgs = JSONArray()
            val value = backend.getItem(key)
            if (value?.startsWith('{') == true) {
                // If the value is actually a JSON object we parse it
                // Otherwise we would return an array of string, which is not what is expected
                // from the localforage API.
                responseArgs.put(JSONObject(value))
            } else {
                responseArgs.put(value)
            }
            postResponseToWebview(responseArgs, action)
        }
    }

    private fun setItem(action: JSONObject) {
        val args = action.getJSONArray("args")
        val key = args.getString(0)
        val value = args.getString(1)
        backendHandler.post {
            backend.setItem(key, value)
            postResponseToWebview(null, action)
        }
    }

    private fun removeItem(action: JSONObject) {
        val args = action.getJSONArray("args")
        val key = args.getString(0)
        backendHandler.post {
            backend.removeItem(key)
            postResponseToWebview(null, action)
        }
    }

    private fun clear(action: JSONObject) {
        backendHandler.post {
            backend.clear()
            postResponseToWebview(null, action)
        }
    }

    private fun config(action: JSONObject) {
        backendHandler.post {
            backend.config()
            postResponseToWebview(null, action)
        }
    }

    private fun postResponseToWebview(args: JSONArray?, action: JSONObject) {
        val json = JSONObject()
        json.put("id", action.get("id"))
        json.put("command", "response")
        if (args != null) {
            json.put("args", args)
        }
        postJsonObjectToWebview(json)
    }

    private fun postErrorToWebview(msg: String, action: JSONObject) {
        val json = JSONObject()
        json.put("id'", action.get("id"))
        json.put("command", "error")
        json.put("msg", msg)
        postJsonObjectToWebview(json)
    }

    private fun postJsonObjectToWebview(obj: JSONObject) {
        val string = obj.toString()
        mainHandler.post {
            Log.e(tag, "Before receiveFromAndroid('$string')")
            val escaped = StringEscapeUtils.escapeEcmaScript(string)
            webview.evaluateJavascript("window.androidWrapper.receiveFromAndroid('$escaped');") {
                // do nothing with the returned value
            }
        }
    }
}