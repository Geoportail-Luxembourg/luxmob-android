package com.c2c.map.backends

import org.json.JSONObject

interface IBackend {
    fun getItem(key: String, action: JSONObject): String?
    fun setItem(key: String, base64: String, action: JSONObject)
    fun removeItem(key: String, action: JSONObject)
    fun clear(action: JSONObject)
    fun config(action: JSONObject)
}
