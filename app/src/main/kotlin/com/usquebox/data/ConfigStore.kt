package com.usquebox.data

import android.content.Context
import android.content.SharedPreferences

enum class ProxyMode {
    GLOBAL,     // All traffic through VPN
    BYPASS,     // All apps proxied EXCEPT selected (blacklist)
    PROXY_ONLY  // ONLY selected apps proxied (whitelist)
}

class ConfigStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var configJson: String?
        get() = prefs.getString(KEY_CONFIG, null)
        set(value) {
            if (value != null) {
                prefs.edit().putString(KEY_CONFIG, value).apply()
            } else {
                prefs.edit().remove(KEY_CONFIG).apply()
            }
        }

    fun hasConfig(): Boolean = !configJson.isNullOrBlank()

    var proxyMode: ProxyMode
        get() = try {
            ProxyMode.valueOf(prefs.getString(KEY_PROXY_MODE, ProxyMode.GLOBAL.name) ?: ProxyMode.GLOBAL.name)
        } catch (_: Exception) {
            ProxyMode.GLOBAL
        }
        set(value) {
            prefs.edit().putString(KEY_PROXY_MODE, value.name).apply()
        }

    var selectedApps: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_SELECTED_APPS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "usquebox_config"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_SELECTED_APPS = "selected_apps"
    }
}
