package de.leonbusse.pumpkin

import io.ktor.client.*

class PumpkinApi(private val client: HttpClient, private val basicAuthToken: String) {

    class InvalidSpotifyAccessTokenException(msg: String? = null) : Exception(msg)

    private object DB {
        private data class Like(
            val userId: String,
            val libraryUserId: String,
            val trackId: String
        )

        private val libraries = mutableMapOf<String, SpotifyLibrary>()
        private val likes = mutableMapOf<String, MutableList<Like>>()

        fun saveSpotifyLibrary(library: SpotifyLibrary) {
            libraries[library.user.id] = library
        }

        fun loadLibrary(userId: String): SpotifyLibrary? {
            return libraries[userId]
        }

        fun saveLikes(trackIds: List<String>, userId: String, libraryUserId: String) {
            val userLikes = likes.getOrPut(userId, { mutableListOf() })
            userLikes.addAll(trackIds.map { Like(userId, libraryUserId, it) })
        }

        fun getLikes(userId: String): List<SpotifyTrack> {
            val userLikes = likes[userId]
                ?: return listOf()
            return userLikes.mapNotNull { like ->
                libraries[like.libraryUserId]
                    ?.tracks // TODO: go through all songs, including playlists etc.
                    ?.find { track -> track.id == like.trackId }
            }
        }

        fun clearLikes(userId: String) {
            likes.remove(userId)
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

    suspend fun like(trackIds: List<String>, userId: String, libraryUserId: String) {
        DB.saveLikes(trackIds, userId, libraryUserId)
    }

    data class ExportResult(
        val playlist: SpotifyPlaylist,
        val spotifyAccessToken: String
    )

    suspend fun export(
        userId: String,
        playlistName: String,
        accessToken: String,
        refreshToken: String?
    ): ExportResult {
        val trackIds = DB.getLikes(userId).map { it.id }
        val (playlist, spotifyAccessToken) =
            spotifyApi.createPlaylist(
                userId,
                trackIds,
                playlistName,
                accessToken,
                refreshToken
            )
        DB.clearLikes(userId)
        return ExportResult(playlist, spotifyAccessToken)
    }
}

fun generateShareLink(library: SpotifyLibrary): String = "http://localhost:8080/share/${library.user.id}"
