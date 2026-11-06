package com.gmdveast.financeflux.updates

import com.gmdveast.financeflux.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PublicRelease(
    val tagName: String,
    val url: String,
    val notes: String,
)

object GitHubReleaseChecker {
    const val RELEASES_PAGE = "https://github.com/gmdveast/finance-flux/releases"
    private const val LATEST_API = "https://api.github.com/repos/gmdveast/finance-flux/releases/latest"

    fun latest(): PublicRelease? {
        val connection = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Finance-Flux/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val release = JSONObject(body)
            return PublicRelease(
                tagName = release.optString("tag_name"),
                url = release.optString("html_url", RELEASES_PAGE),
                notes = release.optString("body").take(2_000),
            ).takeIf { it.tagName.isNotBlank() && it.url.startsWith("https://github.com/") }
        } finally {
            connection.disconnect()
        }
    }
}
