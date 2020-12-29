package de.leonbusse.pumpkin

import kotlinx.serialization.Serializable

/** Pumpkin API */
@Serializable
data class ExportRequest(
    val spotifyAccessToken: String
)

/** Session models **/

data class AuthSession(val state: String)

data class PumpkinSession(
    val spotifyAccessToken: String,
    val spotifyRefreshToken: String,
    val userId: String?
)


/** Spotify models **/

data class SpotifyUser(
    val id: String,
    val display_name: String,
    val email: String,
    val product: String         // whether the user has Spotify Premium - "premium" if true
)

data class SpotifyTrack(
    val id: String,
    val name: String,
    val preview_url: String,
    val album: SpotifyAlbum,
    val artist: SpotifyArtist
)

data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val images: List<SpotifyImage>,
)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val collaborative: Boolean,
    val images: List<SpotifyImage>,
    val public: Boolean,
    val tracks: SpotifyPlaylistTracks
)

data class SpotifyArtist(
    val id: String,
    val name: String
)

data class SpotifyImage(
    val url: String,
    val height: Int,
    val width: Int
)

data class SpotifyLibrary(
    val user: SpotifyUser,
    val tracks: List<SpotifyTrack>
)


/** Spotify intermediate models **/

data class SpotifyPlaylistTracks(
    val href: String,
    val total: Int
)
data class SpotifyTokenResponse(val access_token: String, val refresh_token: String)

data class SpotifyTrackEdge(
    val track: SpotifyTrack,
    val added_at: String
)

data class SpotifyTracksResponse(
    val href: String,
    val items: List<SpotifyTrackEdge>
)

data class SpotifyPlaylistResponse(
    val href: String,
    val items: List<SpotifyPlaylist>
)