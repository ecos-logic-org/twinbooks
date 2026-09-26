package org.ecos.logic.twinbooks.alignment.repository

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ecos.logic.twinbooks.BuildConfig

/**
 * TwinBooks server used for paragraph alignment (two-book mode) and translation
 * (single-book mode). Turned off (or without an address) means no server: everything runs
 * on-device. Turning it off keeps [baseUrl] and [apiKey] so turning it on again restores them.
 */
data class ServerConfig(val baseUrl: String, val apiKey: String, val enabled: Boolean = true) {
    val isEnabled: Boolean get() = enabled && baseUrl.isNotBlank()
}

/**
 * Server chosen by the user. Until they save one in the app, the build-time values
 * (secrets.properties / environment, see app/build.gradle.kts) are used, so developer
 * builds keep talking to their server and public builds start with no server.
 */
class ServerSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    /** Saves [config]; from now on it takes precedence over the build-time default. */
    fun save(config: ServerConfig) {
        prefs.edit {
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_API_KEY, config.apiKey)
            putBoolean(KEY_ENABLED, config.enabled)
        }
        _config.value = withBuildKey(config)
    }

    private fun load(): ServerConfig {
        if (!prefs.contains(KEY_BASE_URL)) {
            return ServerConfig(BuildConfig.ALIGNMENT_BASE_URL, BuildConfig.ALIGNMENT_API_KEY)
        }
        val baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty()
        // Early versions saved "off" as an empty address and key, with no enabled flag
        val enabled = prefs.getBoolean(KEY_ENABLED, baseUrl.isNotBlank())
        val saved = ServerConfig(baseUrl, prefs.getString(KEY_API_KEY, "").orEmpty(), enabled)
        // Nothing to restore when turning it on again: offer the build-time server
        return if (baseUrl.isBlank()) {
            saved.copy(baseUrl = BuildConfig.ALIGNMENT_BASE_URL, apiKey = BuildConfig.ALIGNMENT_API_KEY)
        } else {
            withBuildKey(saved)
        }
    }

    /**
     * The build-time key belongs to the build-time server: use it when the user picked
     * that same server without typing a key (or saved one before the key field was visible).
     */
    private fun withBuildKey(config: ServerConfig): ServerConfig =
        if (config.apiKey.isBlank() && BuildConfig.ALIGNMENT_API_KEY.isNotBlank() &&
            config.baseUrl == BuildConfig.ALIGNMENT_BASE_URL
        ) config.copy(apiKey = BuildConfig.ALIGNMENT_API_KEY) else config

    companion object {
        private const val PREFS_NAME = "server_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENABLED = "enabled"
        private const val API_PATH = "api/v1/"

        /**
         * Turns what the user typed into the API base URL, or null if it isn't a valid
         * http(s) URL. A bare host ("twinbooks.example.org") gets https and the API path.
         */
        fun normalizeBaseUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val path = url.encodedPath.trimEnd('/')
            val newPath = if (path.isEmpty()) "/$API_PATH" else "$path/"
            return url.newBuilder().encodedPath(newPath).build().toString()
        }
    }
}
