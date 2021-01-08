package de.leonbusse.pumpkin

import io.ktor.features.*
import java.lang.Integer.min

class PumpkinApi(
    private val cache: PumpkinCache,
    private val spotifyApi: SpotifyApi,
) {

    data class ImportLibraryResult(
        val shareId: String,
        val spotifyAccessToken: String
    )

    suspend fun importLibrary(spotifyAccessToken: String, spotifyRefreshToken: String?): ImportLibraryResult {
        if (spotifyAccessToken.isBlank()) {
            throw BadRequestException("invalid Spotify access token")
        } else {
            val (spotifyLibrary, accessToken) = spotifyApi.import(spotifyAccessToken, spotifyRefreshToken)
            cache.rememberAccessToken(spotifyAccessToken)
            cache.setSpotifyLibrary(spotifyLibrary)

            return ImportLibraryResult(generateShareId(spotifyLibrary), accessToken)
        }
    }

    suspend fun getUser(userId: String): PumpkinUser? = cache.getSpotifyLibrary(userId)?.user?.toPumpkinUser()

    suspend fun getTracks(userId: String, limit: Int? = null, offset: Int? = null): List<PumpkinTrack> {
        val o = offset ?: 0
        val l = limit ?: 10
        val tracks = cache.getSpotifyLibrary(userId)?.tracks
            ?: throw NotFoundException("no tracks for user $userId are available")
        if (o >= tracks.size) {
            return listOf()
        }
        return tracks
            .slice(o until min(tracks.size, o + l))
            .map { it.toPumpkinTrack() }
    }

    data class ExportResult(
        val playlist: SpotifyPlaylist,
        val spotifyAccessToken: String
    )

    suspend fun export(
        userId: String,
        playlistName: String,
        trackIds: List<String>,
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
