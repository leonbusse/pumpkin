package de.leonbusse.pumpkin

import kotlinx.serialization.Serializable


/** Pumpkin API */
@Serializable
data class ImportRequest(
    val spotifyAccessToken: String
)

@Serializable
data class ImportResponse(
    val shareId: String
)

@Serializable
data class CreatePlaylistResponse(
    val playlistId: String
)

@Serializable
data class CreatePlaylistRequest(
    val spotifyAccessToken: String,
    val userId: String,
    val libraryUserId: String,
    val playlistName: String,
    val trackIds: List<String>,
)

/** Pumpkin models */

@Serializable
data class PumpkinTrack(
    val id: String,
    val name: String,
    val previewUrl: String?,
    val album: String,
    val artists: List<String>,
    val imageUrl: String
)

fun SpotifyTrack.toPumpkinTrack() = PumpkinTrack(
    id = this.id,
    name = this.name,
    previewUrl = this.preview_url,
    album = this.album.name,
    artists = this.artists.map { it.name },
    imageUrl = this.album.images.getOrNull(0)?.url ?: ""
)

@Serializable
data class PumpkinUser(
    val id: String,
    val displayName: String,
    val email: String,
)

fun SpotifyUser.toPumpkinUser() = PumpkinUser(
    this.id,
    this.display_name,
    this.email
)

@Serializable
data class PumpkinPlaylist(
    val id: String,
    val name: String,
    val tracks: List<PumpkinTrack>
)

/** Spotify models **/

@Serializable
data class SpotifyUser(
    val id: String,
    val display_name: String,
    val email: String,
    val product: String         // whether the user has Spotify Premium - "premium" if true
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val preview_url: String?,
    val album: SpotifyAlbum,
    val artists: List<SpotifyArtist>
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val images: List<SpotifyImage>,
)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val collaborative: Boolean,
    val images: List<SpotifyImage>,
    val public: Boolean,
    val tracks: SpotifyPlaylistTracks
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val name: String
)

@Serializable
data class SpotifyImage(
    val url: String,
    val height: Int,
    val width: Int
)

@Serializable
data class SpotifyLibrary(
    val user: SpotifyUser,
    val tracks: List<PumpkinTrack>,
    val playlists: List<PumpkinPlaylist>
)


/** Spotify intermediate models **/

@Serializable
data class SpotifyPlaylistTracks(
    val href: String,
    val total: Int
)

@Serializable
data class SpotifyTokenResponse(val access_token: String, val refresh_token: String)

@Serializable
data class SpotifyTrackEdge(
    val track: SpotifyTrack,
    val added_at: String
)

@Serializable
data class SpotifyPlaylistsResponse(
    val href: String,
    val items: List<SpotifyPlaylist>
)

@Serializable
data class SpotifyTracksResponse(
    val href: String,
    val items: List<SpotifyTrackEdge>
)

@Serializable
data class SpotifyPlaylistResponse(
    val href: String,
    val items: List<SpotifyPlaylist>
)