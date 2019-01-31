package lu.geoportail.map

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import lu.geoportail.map.backends.IBackend
import lu.geoportail.map.backends.LocalforageMemoryBackend
import lu.geoportail.map.backends.LocalforageSqliteBackend
import org.json.JSONArray
import org.json.JSONObject


class JsObject {

    private val backend: IBackend
    private val TAG = "JsObject"
    private val webview: WebView
    private val mainHandler = Handler()
    private var backendThread: HandlerThread? = null
    private val backendHandler: Handler by lazy {
        val thread = HandlerThread("backendThread")
        thread.start()
        backendThread = thread
        Handler(thread.looper)
    }

    constructor(view: WebView) {
        webview = view
        backend = LocalforageSqliteBackend(view.context)
    }

    @JavascriptInterface
    fun postMessageToAndroid(action: String) {
        Log.e(TAG, "postMessageToAndroid $action")
        handleAction(action)
    }


    private fun handleAction(serializedAction: String) {
        val action = JSONObject(serializedAction)

        val plugin = action.getString("plugin")
        if (plugin != "localforage") {
            return
        }

        val args = action.getJSONArray("args")
        val command = action.getString("command")
        when (command) {
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
            var value = backend.getItem(key, action)
            if (value?.first() == '{') {
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
            backend.setItem(key, value, action)
            postResponseToWebview(null, action)
        }
    }

    private fun removeItem(action: JSONObject) {
        val args = action.getJSONArray("args")
        val key = args.getString(0)
        backendHandler.post {
            backend.removeItem(key, action)
            postResponseToWebview(null, action)
        }
    }

    private fun clear(action: JSONObject) {
        backendHandler.post {
            backend.clear(action)
            postResponseToWebview(null, action)
        }
    }

    private fun config(action: JSONObject) {
        backendHandler.post {
            backend.config(action)
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
        json.put("'id'", action.get("id"))
        json.put("'command'", "error")
        json.put("'msg'", msg)
        postJsonObjectToWebview(json)
    }

    private fun postJsonObjectToWebview(obj: JSONObject) {
        val string = obj.toString()
        mainHandler.post {
            Log.e(TAG, "Before receiveFromAndroid('$string')")
            val escaped = string.replace("\\", "\\\\")
            webview.evaluateJavascript("window.androidWrapper.receiveFromAndroid('$escaped');") {
                    _ -> // do nothing with the returned value
            }
        }
    }
}