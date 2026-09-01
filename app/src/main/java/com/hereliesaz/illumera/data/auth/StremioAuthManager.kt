package com.hereliesaz.illumera.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.remote.StremioAddonDescriptor
import com.hereliesaz.illumera.data.remote.StremioAddonEntry
import com.hereliesaz.illumera.data.remote.StremioAddonFlags
import com.hereliesaz.illumera.data.remote.StremioAuthError
import com.hereliesaz.illumera.data.remote.StremioAuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the Stremio integration.
 */
sealed class StremioConnectionState {
    object Disconnected : StremioConnectionState()
    data class Connected(val email: String) : StremioConnectionState()
}

/**
 * Manages Stremio authentication state using EncryptedSharedPreferences.
 * Provides secure storage for auth tokens and handles login/logout operations.
 */
@Singleton
class StremioAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stremioAuthService: StremioAuthService
) {
    companion object {
        private const val PREFS_FILE = "stremio_secure_prefs"
        private const val KEY_AUTH_KEY = "stremio_auth_key"
        private const val KEY_EMAIL = "stremio_email"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // AEADBadTagException / KeyStore corruption — nuke and rebuild
            Log.e("StremioAuthManager", "EncryptedSharedPreferences corrupted, resetting", e)
            clearCorruptedPrefs()
            try {
                createEncryptedPrefs()
            } catch (e2: Exception) {
                // KeyStore is permanently broken on this device — fall back to plain prefs
                Log.e("StremioAuthManager", "KeyStore permanently broken, using plain prefs", e2)
                context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedPrefs() {
        val prefsDir = "shared_prefs"
        File(context.filesDir.parent, "$prefsDir/$PREFS_FILE.xml").delete()
        File(context.filesDir.parent, "$prefsDir/$PREFS_FILE.xml.bak").delete()

        // Remove the master key from Android KeyStore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            keyStore.deleteEntry("_androidx_security_master_key")
        } catch (e: Exception) {
            Log.e("StremioAuthManager", "Failed to clear master key", e)
        }
    }

    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)
    val connectionState: StateFlow<StremioConnectionState> = _connectionState.asStateFlow()

    init {
        refreshConnectionState()
    }

    /**
     * Refreshes the connection state from stored credentials.
     */
    fun refreshConnectionState() {
        val authKey = getStoredAuthKey()
        val email = getStoredEmail()

        _connectionState.value = if (authKey != null && email != null) {
            StremioConnectionState.Connected(email)
        } else {
            StremioConnectionState.Disconnected
        }
    }

    /**
     * Gets the stored auth key (for API calls).
     */
    fun getStoredAuthKey(): String? {
        return encryptedPrefs.getString(KEY_AUTH_KEY, null)
    }

    /**
     * Gets the stored email (for display purposes).
     */
    fun getStoredEmail(): String? {
        return encryptedPrefs.getString(KEY_EMAIL, null)
    }

    private fun profileScopedAuthKey(profileId: Int): String = "${KEY_AUTH_KEY}_profile_$profileId"
    private fun profileScopedEmail(profileId: Int): String = "${KEY_EMAIL}_profile_$profileId"

    fun saveCredentialsForProfile(profileId: Int) {
        val authKey = getStoredAuthKey()
        val email = getStoredEmail()
        val authProfileKey = profileScopedAuthKey(profileId)
        val emailProfileKey = profileScopedEmail(profileId)

        encryptedPrefs.edit().apply {
            if (authKey != null && email != null) {
                putString(authProfileKey, authKey)
                putString(emailProfileKey, email)
            } else {
                remove(authProfileKey)
                remove(emailProfileKey)
            }
        }.apply()
    }

    fun loadCredentialsForProfile(profileId: Int) {
        val authKey = encryptedPrefs.getString(profileScopedAuthKey(profileId), null)
        val email = encryptedPrefs.getString(profileScopedEmail(profileId), null)

        encryptedPrefs.edit().apply {
            if (authKey != null && email != null) {
                putString(KEY_AUTH_KEY, authKey)
                putString(KEY_EMAIL, email)
            } else {
                remove(KEY_AUTH_KEY)
                remove(KEY_EMAIL)
            }
        }.apply()

        refreshConnectionState()
    }

    fun copyCredentialsBetweenProfiles(sourceProfileId: Int, targetProfileId: Int) {
        val authKey = encryptedPrefs.getString(profileScopedAuthKey(sourceProfileId), null)
        val email = encryptedPrefs.getString(profileScopedEmail(sourceProfileId), null)
        val authProfileKey = profileScopedAuthKey(targetProfileId)
        val emailProfileKey = profileScopedEmail(targetProfileId)

        encryptedPrefs.edit().apply {
            if (authKey != null && email != null) {
                putString(authProfileKey, authKey)
                putString(emailProfileKey, email)
            } else {
                remove(authProfileKey)
                remove(emailProfileKey)
            }
        }.apply()
    }

    fun clearCredentialsForProfile(profileId: Int) {
        encryptedPrefs.edit()
            .remove(profileScopedAuthKey(profileId))
            .remove(profileScopedEmail(profileId))
            .apply()
    }

    /**
     * Logs in to Stremio and stores the credentials securely.
     * Returns the auth key on success.
     */
    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val authKey = stremioAuthService.login(email, password)
            
            encryptedPrefs.edit()
                .putString(KEY_AUTH_KEY, authKey)
                .putString(KEY_EMAIL, email)
                .apply()

            _connectionState.value = StremioConnectionState.Connected(email)

            Result.success(authKey)
        } catch (e: StremioAuthError.InvalidCredentials) {
            Result.failure(e)
        } catch (e: StremioAuthError.NetworkError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Fetches the user's addon collection using the stored auth key.
     */
    suspend fun fetchAddons(): Result<List<StremioAddonEntry>> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.InvalidCredentials("Not logged in"))

        try {
            val addons = stremioAuthService.getAddonCollection(authKey)
            Result.success(addons)
        } catch (e: StremioAuthError) {
            // If auth fails, the token might be expired - clear it
            if (e is StremioAuthError.InvalidCredentials) {
                disconnect()
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Starts a Facebook login: returns the URL to show as a QR code / link for
     * the user to open in a browser (their phone, typically — a TV usually has
     * none). Call [completeFacebookLogin] with the same state afterward.
     */
    fun startFacebookLogin(): Pair<String, String> = stremioAuthService.startFacebookLogin()

    /**
     * Polls for Facebook login completion and, once the user finishes the
     * OAuth flow in their browser, logs in and stores the credentials —
     * mirroring [login] but sourced from Facebook instead of a typed password.
     */
    suspend fun completeFacebookLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (email, fbToken) = stremioAuthService.pollFacebookLogin(state)
                ?: return@withContext Result.failure(StremioAuthError.NetworkError("Facebook login timed out or was not completed"))

            val authKey = stremioAuthService.login(email, fbToken, facebook = true)

            encryptedPrefs.edit()
                .putString(KEY_AUTH_KEY, authKey)
                .putString(KEY_EMAIL, email)
                .apply()

            _connectionState.value = StremioConnectionState.Connected(email)
            Result.success(authKey)
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Pushes the given addons up to the account's addon collection, replacing
     * it there. Fetches each addon's manifest fresh from its transport URL
     * (rather than any locally-cached/trimmed copy) to avoid corrupting the
     * account's collection with an incomplete manifest.
     */
    suspend fun pushAddonCollection(transportUrls: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.InvalidCredentials("Not logged in"))

        try {
            val descriptors = transportUrls.mapNotNull { url ->
                runCatching {
                    StremioAddonDescriptor(
                        manifest = stremioAuthService.fetchRawManifest(url),
                        transportUrl = if (url.endsWith("manifest.json")) url else "${url.trimEnd('/')}/manifest.json",
                        flags = StremioAddonFlags()
                    )
                }.getOrNull()
            }
            stremioAuthService.setAddonCollection(authKey, descriptors)
            Result.success(Unit)
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Disconnects from Stremio: invalidates the authKey server-side (best
     * effort) and clears stored credentials.
     */
    fun disconnect() {
        val authKey = getStoredAuthKey()
        encryptedPrefs.edit()
            .remove(KEY_AUTH_KEY)
            .remove(KEY_EMAIL)
            .apply()

        _connectionState.value = StremioConnectionState.Disconnected

        if (authKey != null) {
            CoroutineScope(Dispatchers.IO).launch {
                stremioAuthService.logout(authKey)
            }
        }
    }
}
