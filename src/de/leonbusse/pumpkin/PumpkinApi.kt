package de.leonbusse.pumpkin

import io.ktor.client.*
import java.lang.IllegalArgumentException

class PumpkinApi(private val client: HttpClient) {

    class InvalidSpotifyAccessTokenException(msg: String? = null): Exception(msg)

    private object DB {
        private val libraries = mutableMapOf<String, SpotifyLibrary>()

        fun saveSpotifyLibrary(library: SpotifyLibrary) {
            libraries[library.user.id] = library
        }

        fun loadLibrary(userId: String): SpotifyLibrary? {
            return libraries[userId]
        }
    }

    suspend fun importLibrary(spotifyAccessToken: String): String {
        if (spotifyAccessToken.isBlank()) {
            throw IllegalArgumentException("invalid Spotify access token")
        } else {
            val spotifyLibrary = SpotifyImporter(client, spotifyAccessToken).import()
            DB.saveSpotifyLibrary(spotifyLibrary)

            return generateShareLink(spotifyLibrary)
        }
    }

    fun getLibrary(userId: String) = DB.loadLibrary(userId)
}

fun generateShareLink(library: SpotifyLibrary): String = "http://localhost:8080/share/${library.user.id}"