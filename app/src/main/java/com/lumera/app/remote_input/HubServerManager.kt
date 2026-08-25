package com.lumera.app.remote_input

import com.lumera.app.domain.HubShape
import fi.iki.elonen.NanoHTTPD

/**
 * Singleton manager for the Hub bulk image upload server.
 * Manages the HTTP server lifecycle for the web portal
 * where users can upload images for multiple hub items at once.
 */
object HubServerManager {

    private var server: NanoHTTPD? = null

    /**
     * Start the Bulk Hub upload server.
     *
     * @param items List of items to manage
     * @param shape The shape constraint for all items
     * @param port Port to listen on
     * @param onImageReceived Callback when an image is uploaded for a specific ID
     * @return The URL string for QR code generation, or null on failure
     */
    fun startBulkServer(
        items: List<com.lumera.app.data.model.HubRowItemEntity>,
        shape: HubShape,
        port: Int = 8085,
        onImageReceived: (String, ByteArray) -> Unit,
        onImageDeleted: ((String) -> Unit)? = null
    ): String? {
        stopServer()

        return try {
            val pairingToken = java.util.UUID.randomUUID().toString()
            server = HubBulkUploadServer(
                port = port,
                pairingToken = pairingToken,
                items = items,
                shape = shape,
                onImageReceived = onImageReceived,
                onImageDeleted = onImageDeleted
            )
            server?.start()

            val ip = NetworkUtils.getLocalIpAddress()
            if (ip != null) "http://$ip:$port/?pin=$pairingToken" else null
        } catch (e: Exception) {
            if (com.lumera.app.BuildConfig.DEBUG) android.util.Log.w("HubServerManager", "Server start failed", e)
            null
        }
    }

    /**
     * Stop the Hub upload server.
     */
    fun stopServer() {
        server?.stop()
        server = null
    }

}
