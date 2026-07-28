package com.bluenet.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object PreferencesManager {
    private const val PREF_NAME = "bluenet_prefs"

    private const val KEY_LAST_MAC = "last_host_mac"
    private const val KEY_LAST_PSM = "last_host_psm"
    private const val KEY_COMPAT_MODE = "compat_mode"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_LAST_MODE = "last_mode" // "client" or "host"
    
    private const val KEY_PEER_ID = "peer_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_SHARING_INTERNET = "sharing_internet"
    private const val KEY_LAST_CONNECTED_PEER_ID = "last_connected_peer_id"

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
    
    fun getPeerId(context: Context): String {
        var id = getPrefs(context).getString(KEY_PEER_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            savePeerId(context, id)
        }
        return id
    }

    fun savePeerId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_PEER_ID, id).apply()
    }

    fun getDisplayName(context: Context): String {
        var name = getPrefs(context).getString(KEY_DISPLAY_NAME, null)
        if (name == null) {
            val peerId = getPeerId(context)
            val suffix = if (peerId.length >= 4) peerId.takeLast(4) else "0000"
            name = "Peer$suffix"
            saveDisplayName(context, name)
        }
        return name
    }

    fun saveDisplayName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    fun isSharingInternet(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHARING_INTERNET, false)
    }

    fun setSharingInternet(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHARING_INTERNET, enabled).apply()
    }

    fun getLastConnectedPeerId(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_CONNECTED_PEER_ID, "") ?: ""
    }

    fun saveLastConnectedPeerId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_LAST_CONNECTED_PEER_ID, id).apply()
    }
}
