package com.gmdveast.financeflux.data

import android.content.Context
import android.util.Base64
import com.gmdveast.financeflux.core.ConnectorProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class EventSeed(val eventId: String, val createdAt: String)

class ProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences("finance-flux", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }

    fun loadProfiles(): List<ConnectorProfile> = runCatching {
        json.decodeFromString(ListSerializer(ConnectorProfile.serializer()), preferences.getString("profiles", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun saveProfile(profile: ConnectorProfile): Boolean {
        val current = loadProfiles().filterNot { it.id == profile.id } + profile
        return preferences.edit().putString("profiles", json.encodeToString(ListSerializer(ConnectorProfile.serializer()), current)).commit()
    }

    fun removeProfile(profileId: String): Boolean {
        val current = loadProfiles().filterNot { it.id == profileId }
        return preferences.edit().putString("profiles", json.encodeToString(ListSerializer(ConnectorProfile.serializer()), current)).commit()
    }

    fun eventSeed(connectorId: String, profileId: String, candidateId: String): EventSeed? {
        val key = "candidate:${hashKey("$connectorId\u0000$profileId\u0000$candidateId")}"
        val existing = preferences.getString(key, null)
        if (existing != null) {
            return runCatching {
                val stored = JSONObject(existing)
                EventSeed(stored.getString("eventId"), stored.getString("createdAt"))
            }.getOrNull()
        }

        val seed = EventSeed("event-${UUID.randomUUID().toString().replace("-", "")}", java.time.Instant.now().toString())
        val saved = preferences.edit().putString(key, JSONObject()
            .put("eventId", seed.eventId)
            .put("createdAt", seed.createdAt)
            .toString()).commit()
        return seed.takeIf { saved }
    }

    private fun hashKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
