package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.features.*
import java.lang.Integer.min

class PumpkinApi(
    client: HttpClient,
    basicAuthToken: String,
) {

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
        val shareId: String,
        val spotifyAccessToken: String
    )

    suspend fun importLibrary(spotifyAccessToken: String, spotifyRefreshToken: String?): ImportLibraryResult {
        if (spotifyAccessToken.isBlank()) {
            throw BadRequestException("invalid Spotify access token")
        } else {
            val (spotifyLibrary, accessToken) = spotifyApi.import(spotifyAccessToken, spotifyRefreshToken)
            DB.saveSpotifyLibrary(spotifyLibrary)

            return ImportLibraryResult(generateShareId(spotifyLibrary), accessToken)
        }
    }

    fun getUser(userId: String): SpotifyUser? = DB.loadLibrary(userId)?.user

    fun getTracks(userId: String, limit: Int? = null, offset: Int? = null): List<SpotifyTrack>? {
        val o = offset ?: 0
        val l = limit ?: 10
        val tracks = DB.loadLibrary(userId)?.tracks
            ?: throw NotFoundException("no tracks for user $userId are available")
        if (o >= tracks.size) {
            return listOf()
        }
        return tracks.slice(o until min(tracks.size, o + l))
    }

    @Deprecated(message = "Retained for ServerSideApp")
    suspend fun like(trackIds: List<String>, userId: String, libraryUserId: String) {
        println("api.like")
        val alreadyLiked = DB.getLikes(userId)
            .map { it.id }
            .filter { it in trackIds }
        val newLikes = trackIds.filterNot { it in alreadyLiked }
        DB.saveLikes(newLikes, userId, libraryUserId)
        println("added likes: $newLikes")
    }

    data class ExportResult(
        val playlist: SpotifyPlaylist,
        val spotifyAccessToken: String
    )

    suspend fun export(
        userId: String,
        playlistName: String,
        trackIds:List<String>,
        accessToken: String,
        refreshToken: String?
    ): ExportResult {
        if (trackIds.isEmpty()) throw BadRequestException("Empty track ID list is invalid.")

        val (playlist, spotifyAccessToken) =
            spotifyApi.createPlaylist(
                userId,
                trackIds,
                playlistName,
                accessToken,
                refreshToken
            )
        return ExportResult(playlist, spotifyAccessToken)
    }

    private fun generateShareId(library: SpotifyLibrary): String =
        library.user.id
}
