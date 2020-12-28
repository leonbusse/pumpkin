package de.leonbusse.pumpkin

import io.ktor.client.*
import java.lang.IllegalArgumentException

class PumpkinApi(private val client: HttpClient, private val basicAuthToken: String) {

    class InvalidSpotifyAccessTokenException(msg: String? = null) : Exception(msg)

    private object DB {
        private val libraries = mutableMapOf<String, SpotifyLibrary>()

        fun saveSpotifyLibrary(library: SpotifyLibrary) {
            libraries[library.user.id] = library
        }

        fun loadLibrary(userId: String): SpotifyLibrary? {
            return libraries[userId]
        }
    }

    private val spotifyApi = SpotifyApi(client, basicAuthToken)

    data class ImportLibraryResult(
        val shareLink: String,
        val spotifyAccessToken: String
    )

    suspend fun importLibrary(spotifyAccessToken: String, spotifyRefreshToken: String?): ImportLibraryResult {
        if (spotifyAccessToken.isBlank()) {
            throw IllegalArgumentException("invalid Spotify access token")
        } else {
            val (spotifyLibrary, accessToken) = spotifyApi.import(spotifyAccessToken, spotifyRefreshToken)
            DB.saveSpotifyLibrary(spotifyLibrary)

            return ImportLibraryResult(generateShareLink(spotifyLibrary), accessToken)
        }
    }

    fun getLibrary(userId: String) = DB.loadLibrary(userId)
}

fun generateShareLink(library: SpotifyLibrary): String = "http://localhost:8080/share/${library.user.id}"
