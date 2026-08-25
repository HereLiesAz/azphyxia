package com.hereliesaz.illumera.data.debrid

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.debrid.providers.AllDebridService
import com.hereliesaz.illumera.data.debrid.providers.DebridLinkService
import com.hereliesaz.illumera.data.debrid.providers.EasyDebridService
import com.hereliesaz.illumera.data.debrid.providers.OffcloudService
import com.hereliesaz.illumera.data.debrid.providers.PremiumizeService
import com.hereliesaz.illumera.data.debrid.providers.RealDebridService
import com.hereliesaz.illumera.data.debrid.providers.TorBoxService
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the currently-connected debrid provider + API key (stored encrypted, per-profile —
 * mirrors [com.hereliesaz.illumera.data.trakt.TraktAuthManager]) and routes library/stream
 * calls to the matching [DebridService] implementation.
 */
@Singleton
class DebridManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val realDebrid: RealDebridService,
    private val allDebrid: AllDebridService,
    private val premiumize: PremiumizeService,
    private val torBox: TorBoxService,
    private val debridLink: DebridLinkService,
    private val offcloud: OffcloudService,
    private val easyDebrid: EasyDebridService
) {
    companion object {
        private const val TAG = "DebridManager"
        private const val PREFS_NAME = "debrid_auth"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_USERNAME = "username"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun activeProfileId(): Int = profileConfigurationManager.getLastActiveProfileId() ?: 1
    private fun profileKey(key: String, profileId: Int = activeProfileId()) = "${key}_$profileId"

    private val _connectedProvider = MutableStateFlow<DebridProvider?>(null)
    val connectedProvider: StateFlow<DebridProvider?> = _connectedProvider

    private val _connectedUsername = MutableStateFlow<String?>(null)
    val connectedUsername: StateFlow<String?> = _connectedUsername

    init {
        refreshConnectionState()
    }

    /** Refresh connection state for the current profile (call after profile switch). */
    fun refreshConnectionState() {
        _connectedProvider.value = DebridProvider.fromId(prefs.getString(profileKey(KEY_PROVIDER), null))
        _connectedUsername.value = prefs.getString(profileKey(KEY_USERNAME), null)
    }

    private fun serviceFor(provider: DebridProvider): DebridService = when (provider) {
        DebridProvider.REAL_DEBRID -> realDebrid
        DebridProvider.ALL_DEBRID -> allDebrid
        DebridProvider.PREMIUMIZE -> premiumize
        DebridProvider.TORBOX -> torBox
        DebridProvider.DEBRID_LINK -> debridLink
        DebridProvider.OFFCLOUD -> offcloud
        DebridProvider.EASY_DEBRID -> easyDebrid
    }

    fun getApiKey(): String? = prefs.getString(profileKey(KEY_API_KEY), null)

    /** Validates the key against the provider's API and, on success, saves it as the active connection. */
    suspend fun connect(provider: DebridProvider, apiKey: String): DebridResult<DebridAccountInfo> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) return DebridResult.Failure("API key cannot be empty")

        return when (val result = serviceFor(provider).validateApiKey(trimmedKey)) {
            is DebridResult.Success -> {
                val pid = activeProfileId()
                prefs.edit()
                    .putString(profileKey(KEY_PROVIDER, pid), provider.id)
                    .putString(profileKey(KEY_API_KEY, pid), trimmedKey)
                    .putString(profileKey(KEY_USERNAME, pid), result.value.username)
                    .apply()
                _connectedProvider.value = provider
                _connectedUsername.value = result.value.username
                result
            }
            is DebridResult.Failure -> {
                Log.w(TAG, "Failed to connect to ${provider.displayName}: ${result.message}")
                result
            }
        }
    }

    fun disconnect() {
        val pid = activeProfileId()
        prefs.edit()
            .remove(profileKey(KEY_PROVIDER, pid))
            .remove(profileKey(KEY_API_KEY, pid))
            .remove(profileKey(KEY_USERNAME, pid))
            .apply()
        _connectedProvider.value = null
        _connectedUsername.value = null
    }

    /** Clear the debrid connection for a specific profile (e.g. when deleting the profile). */
    fun clearForProfile(profileId: Int) {
        prefs.edit()
            .remove(profileKey(KEY_PROVIDER, profileId))
            .remove(profileKey(KEY_API_KEY, profileId))
            .remove(profileKey(KEY_USERNAME, profileId))
            .apply()
    }

    suspend fun listLibrary(): DebridResult<List<DebridItem>> {
        val provider = _connectedProvider.value ?: return DebridResult.Failure("No debrid service connected")
        val apiKey = getApiKey() ?: return DebridResult.Failure("No debrid service connected")
        return serviceFor(provider).listLibrary(apiKey)
    }

    suspend fun getStreamUrl(item: DebridItem): DebridResult<String> {
        val apiKey = getApiKey() ?: return DebridResult.Failure("No debrid service connected")
        return serviceFor(item.provider).getStreamUrl(apiKey, item)
    }

    suspend fun deleteItem(item: DebridItem): DebridResult<Unit> {
        val apiKey = getApiKey() ?: return DebridResult.Failure("No debrid service connected")
        return serviceFor(item.provider).deleteItem(apiKey, item)
    }
}
