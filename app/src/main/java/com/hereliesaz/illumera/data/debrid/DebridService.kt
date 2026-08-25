package com.hereliesaz.illumera.data.debrid

import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridResult

/**
 * Common surface every debrid provider implementation exposes. Implementations live
 * under data/debrid/providers/ and are looked up by [com.hereliesaz.illumera.data.model.debrid.DebridProvider]
 * via [DebridServiceRegistry].
 */
interface DebridService {

    /** Confirms the API key works and returns basic account info. */
    suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo>

    /** Lists the items currently stored in the account's cloud (torrents/downloads/magnets). */
    suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>>

    /** Resolves a playable direct-download URL for a library item, unrestricting it if needed. */
    suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String>

    /** Removes an item from the account's cloud storage. */
    suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit>
}
