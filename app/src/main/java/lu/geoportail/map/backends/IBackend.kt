package lu.geoportail.map.backends

interface IBackend {
    fun getItem(key: String): String?
    fun setItem(key: String, base64: String)
    fun removeItem(key: String)
    fun clear()
    fun config()
}
