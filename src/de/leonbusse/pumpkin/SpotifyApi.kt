package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SpotifyApi(
    private val cache: SpotifyCache,
    private val client: HttpClient,
) {
    companion object {
        const val spotifyTracksMaxLimit = 50
        const val spotifyPlaylistsMaxLimit = 20
    }

    suspend fun getSpotifyLibrary(accessToken: String): SpotifyLibrary {
        val user = fetchUserByToken(accessToken)
        return fetchLibrary(user, accessToken)
    }

    suspend fun getCachedSpotifyUser(userId: String): SpotifyUser? =
        cache.getSpotifyUserById(userId)

    private suspend fun fetchLibrary(user: SpotifyUser, accessToken: String): SpotifyLibrary {
        return cache.getLibraryByUserId(user.id)
            ?: run {
                val likedTracks = fetchTracks("https://api.spotify.com/v1/me/tracks", accessToken)
                val playlists = fetchPlaylists(accessToken)
                SpotifyLibrary(user, likedTracks, playlists)
                    .also { cache.setLibraryByUserId(it) }
            }
    }

    private suspend fun fetchUserByToken(accessToken: String): SpotifyUser =
        cache.getSpotifyUserByToken(accessToken)
            ?: client.spotifyRequest<SpotifyUser>(accessToken) {
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.spotify.com"
                    path("v1", "me")
                }
                method = HttpMethod.Get
            }.also { cache.setSpotifyUserByToken(accessToken, it) }

    suspend fun createPlaylist(
        userId: String,
        trackIds: List<String>,
        playlistName: String,
        accessToken: String
    ): SpotifyPlaylist {
        val playlist: SpotifyPlaylist =
            client.spotifyRequest(accessToken) {
                method = HttpMethod.Post
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.spotify.com"
                    path("v1", "users", userId, "playlists")
                }
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                body = mapOf(
                    "name" to playlistName,
                    "public" to false
                )
            }
        val addTracksResponse: String =
            client.spotifyRequest(accessToken) {
                method = HttpMethod.Post
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.spotify.com"
                    path("v1", "playlists", playlist.id, "tracks")
                }
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                body = mapOf(
                    "uris" to trackIds.map { "spotify:track:$it" },
                    "position" to 0
                )
            }
        return playlist
    }

    private suspend fun fetchTracks(href: String, accessToken: String): List<PumpkinTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var responseSize = spotifyTracksMaxLimit
        var offset = 0
        while (offset < 150 && responseSize == spotifyTracksMaxLimit) {
            val tracksResponse: SpotifyTracksResponse =
                client.spotifyRequest(accessToken) {
                    method = HttpMethod.Get
                    url(href)
                    parameter("limit", 50)
                    parameter("offset", offset)
                }
            tracks.addAll(tracksResponse.items.map { it.track })
            responseSize = tracksResponse.items.size
            offset += tracksResponse.items.size
        }
        return tracks
            .map { it.toPumpkinTrack() }
            .filter { it.previewUrl != null }
    }

    private suspend fun fetchPlaylists(accessToken: String): List<PumpkinPlaylist> = coroutineScope {
        val playlists = mutableListOf<SpotifyPlaylist>()
        var responseSize = spotifyPlaylistsMaxLimit
        var offset = 0
        while (offset < 150 && responseSize == spotifyTracksMaxLimit) {
            val tracksResponse: SpotifyPlaylistsResponse =
                client.spotifyRequest(accessToken) {
                    url {
                        protocol = URLProtocol.HTTPS
                        host = "api.spotify.com"
                        path("v1", "me", "playlists")
                    }
                    method = HttpMethod.Get
                    parameter("limit", spotifyPlaylistsMaxLimit)
                    parameter("offset", offset)
                }
            playlists.addAll(tracksResponse.items)
            responseSize = tracksResponse.items.size
            offset += tracksResponse.items.size
        }

        playlists.map {
            async {
                PumpkinPlaylist(
                    it.id,
                    it.name,
                    fetchTracks(it.tracks.href, accessToken)
                )
            }
        }.awaitAll()
    }

    private suspend inline fun <reified T> HttpClient.spotifyRequest(
        accessToken: String,
        block: HttpRequestBuilder.() -> Unit
    ): T =
        try {
            this.request {
                block()
                headers {
                    append("Authorization", "Bearer $accessToken")
                }
            }
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Unauthorized) {
                throw AuthenticationException(e)
            } else {
                throw e
            }
        }
}