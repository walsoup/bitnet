package com.bluenet.utils

import android.content.Context
import android.content.SharedPreferences

object PreferencesManager {
    private const val PREF_NAME = "bluenet_prefs"

    private const val KEY_LAST_MAC = "last_host_mac"
    private const val KEY_LAST_PSM = "last_host_psm"
    private const val KEY_COMPAT_MODE = "compat_mode"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_LAST_MODE = "last_mode" // "client" or "host"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveClientConnection(context: Context, mac: String, psm: Int, compatMode: Boolean) {
        getPrefs(context).edit()
            .putString(KEY_LAST_MAC, mac)
            .putInt(KEY_LAST_PSM, psm)
            .putBoolean(KEY_COMPAT_MODE, compatMode)
            .apply()
    }

    fun getLastMac(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_MAC, "") ?: ""
    }

    fun getLastPsm(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAST_PSM, 1)
    }

    fun getCompatMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COMPAT_MODE, false)
    }

    fun setAutoConnect(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun isAutoConnectEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CONNECT, false)
    }

    fun saveLastMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_LAST_MODE, mode).apply()
    }

    fun getLastMode(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_MODE, "client") ?: "client"
    }
}
