package com.findmycar.app.providers

import android.content.SharedPreferences
import com.findmycar.shared.PersistenceProvider

class AndroidPersistenceProvider(
    private val prefs: SharedPreferences
) : PersistenceProvider {

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
