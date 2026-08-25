package com.hereliesaz.illumera.data.debrid

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin shared HTTP helper for the debrid provider implementations. Each provider's REST API
 * is small and shaped differently enough (auth scheme, response envelope) that a single
 * Retrofit interface per provider would mostly be boilerplate — plain requests + Gson's
 * dynamic JsonElement parsing keeps each provider file self-contained and short.
 */
@Singleton
class DebridHttp @Inject constructor(private val client: OkHttpClient) {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): DebridResult<JsonElement> = execute {
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val requestBuilder = Request.Builder().url(httpUrl).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        requestBuilder.build()
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): DebridResult<JsonElement> = execute {
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val bodyBuilder = FormBody.Builder()
        form.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val requestBuilder = Request.Builder().url(httpUrl).post(bodyBuilder.build())
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        requestBuilder.build()
    }

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): DebridResult<JsonElement> = execute {
        val requestBuilder = Request.Builder().url(url).delete()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        requestBuilder.build()
    }

    private suspend fun execute(buildRequest: () -> Request): DebridResult<JsonElement> =
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest()
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext DebridResult.Failure(
                            "HTTP ${response.code}${if (bodyString.isNotBlank()) ": ${bodyString.take(200)}" else ""}"
                        )
                    }
                    if (bodyString.isBlank()) {
                        return@withContext DebridResult.Success(com.google.gson.JsonObject())
                    }
                    DebridResult.Success(JsonParser.parseString(bodyString))
                }
            } catch (e: Exception) {
                DebridResult.Failure(e.message ?: "Network error")
            }
        }
}

internal fun JsonElement.asObjectOrNull() = if (isJsonObject) asJsonObject else null
internal fun JsonElement.asArrayOrNull() = if (isJsonArray) asJsonArray else null
