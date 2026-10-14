package com.bluenet.node

import android.content.Context
import android.util.Base64
import com.bluenet.core.Log
import com.bluenet.core.crypto.Identity
import com.bluenet.core.mesh.MeshPayloads

/** Persistent per-device state. The identity key never leaves the device (backups are disabled). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bluenet", Context.MODE_PRIVATE)

    fun loadOrCreateIdentity(): Identity {
        val privateKey = prefs.getString(KEY_PRIVATE, null)
        val publicKey = prefs.getString(KEY_PUBLIC, null)
        if (privateKey != null && publicKey != null) {
            try {
                return Identity.restore(decode(privateKey), decode(publicKey))
            } catch (e: Exception) {
                Log.e(TAG, "Stored identity is unreadable, generating a new one", e)
            }
        }
        val identity = Identity.generate()
        prefs.edit()
            .putString(KEY_PRIVATE, encode(identity.encodePrivate()))
            .putString(KEY_PUBLIC, encode(identity.publicKey))
            .apply()
        return identity
    }

    fun nickname(peerId: String): String = prefs.getString(KEY_NICKNAME, null) ?: "BlueNet-${peerId.take(4)}"

    fun setNickname(value: String) {
        prefs.edit().putString(KEY_NICKNAME, value).apply()
    }

    var sharing: Boolean
        get() = prefs.getBoolean(KEY_SHARING, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARING, value).apply()

    /** The sharer used most recently, so the quick settings tile can reconnect in one tap. */
    var lastSharerId: String?
        get() = prefs.getString(KEY_LAST_SHARER, null)
        set(value) = prefs.edit().putString(KEY_LAST_SHARER, value).apply()

    companion object {
        private const val TAG = "Settings"
        private const val KEY_PRIVATE = "identity_private"
        private const val KEY_PUBLIC = "identity_public"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_SHARING = "sharing"
        private const val KEY_LAST_SHARER = "last_sharer"

        /** Trims and limits a user-entered name to what fits in a mesh announcement. */
        fun sanitizeNickname(input: String): String {
            val cleaned = input.trim().replace(Regex("\\s+"), " ").filter { !it.isISOControl() }
            return String(MeshPayloads.truncateUtf8(cleaned, MeshPayloads.MAX_NICKNAME_BYTES), Charsets.UTF_8)
        }

        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    }
}
