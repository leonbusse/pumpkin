package de.leonbusse.pumpkin

import io.ktor.features.*
import java.lang.Integer.min

class PumpkinApi(
    private val cache: PumpkinCache,
    private val spotifyApi: SpotifyApi,
) {

    suspend fun initializeSharedLibrary(accessToken: String): String {
        if (accessToken.isBlank()) {
            throw BadRequestException("invalid Spotify access token")
        } else {
            val spotifyLibrary = spotifyApi.getSpotifyLibrary(accessToken)
            val shareId = generateShareId()
            val likedTracks: List<PumpkinTrack> = spotifyLibrary.tracks.take(100) // TODO: take more than 100 liked tracks
            val albumTracks: List<PumpkinTrack> = spotifyLibrary.albums
                .shuffled()
                .take(100)
                .flatMap { it.tracks.take(100) }
            println("taking ${albumTracks.size} tracks from albums")

            val playlistTracks: List<PumpkinTrack> = spotifyLibrary.playlists
                .shuffled()
                .take(100)
                .flatMap { it.tracks.take(100) }

            val shareTracks: List<PumpkinTrack> =
                likedTracks
                    .plus(albumTracks)
                    .plus(playlistTracks)
                    .toSet()
                    .shuffled()
                    .take(1000)
            println("compiled share tracks of size ${shareTracks.size}")

            cache.setUserIdByShareId(shareId, spotifyLibrary.user.id)
            cache.setTracksByShareId(shareId, shareTracks)
            return shareId
        }
    }

    suspend fun getCachedUser(userId: String): PumpkinUser? = spotifyApi.getCachedSpotifyUser(userId)?.toPumpkinUser()

    suspend fun getCachedUserByShareId(shareId: String): PumpkinUser? {
        return cache.getUserIdByShareId(shareId)
            ?.let { userId -> spotifyApi.getCachedSpotifyUser(userId)?.toPumpkinUser() }
    }

    suspend fun getTracksByShareId(shareId: String, limit: Int? = null, offset: Int? = null): List<PumpkinTrack> {
        val o = offset ?: 0
        val l = limit ?: 10
        val tracks = cache.getTracksByShareId(shareId)
            ?: throw NotFoundException("no tracks for user $shareId are available")
        if (o >= tracks.size) {
            return listOf()
        }
        return tracks.slice(o until min(tracks.size, o + l))
    }

    suspend fun createPlaylist(
        userId: String,
        playlistName: String,
        trackIds: List<String>,
        accessToken: String
    ): SpotifyPlaylist {
        if (trackIds.isEmpty()) throw BadRequestException("Empty track ID list is invalid.")

        return spotifyApi.createPlaylist(
            userId,
            trackIds,
            playlistName,
            accessToken
        )
    }

    private fun generateShareId(): String = generateRandomString(8)
}
