package lu.geoportail.map.backends

import org.json.JSONObject

class LocalforageMemoryBackend : IBackend {
    val map = HashMap<String, String>()

    override fun getItem(key: String, action: JSONObject): String? {
        return map.get(key)
    }

    override fun setItem(key: String, base64: String, action: JSONObject) {
        map.set(key, base64)
    }

    override fun removeItem(key: String, action: JSONObject) {
        map.remove(key)
    }

    override fun clear(action: JSONObject) {
        map.clear()
    }

    override fun config(action: JSONObject) {
        // nothing to do
    }

}