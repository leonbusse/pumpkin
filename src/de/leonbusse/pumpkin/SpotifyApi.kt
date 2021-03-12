package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.RetryAfter
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException

class SpotifyApi(
    private val cache: SpotifyCache,
    private val client: HttpClient,
) {
    companion object {
        const val spotifyTracksMaxLimit = 50
        const val spotifyPlaylistsMaxLimit = 20
        const val spotifyAlbumsMaxLimit = 50
    }

    suspend fun getSpotifyLibrary(accessToken: String): SpotifyLibrary {
        if (Env.dev) println("using token: $accessToken")
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
//                    .also { println("playlists") }
//                    .alsoForEach { println("  - ${it.name}") }
                val albums = fetchAlbums(accessToken)
//                    .also { println("albums") }
//                    .alsoForEach { println("  - ${it.name}") }
                SpotifyLibrary(user, likedTracks, playlists, albums)
                    .also { cache.setLibraryByUserId(it) }
            }
    }

    private suspend fun fetchUserByToken(accessToken: String): SpotifyUser =
        cache.getSpotifyUserByToken(accessToken)
            ?: withRateLimitHandler {
                client.spotifyRequest<SpotifyUser>(accessToken) {
                    url {
                        protocol = URLProtocol.HTTPS
                        host = "api.spotify.com"
                        path("v1", "me")
                    }
                    method = HttpMethod.Get
                }
            }.also { cache.setSpotifyUserByToken(accessToken, it) }

    suspend fun createPlaylist(
        userId: String,
        trackIds: List<String>,
        playlistName: String,
        accessToken: String
    ): SpotifyPlaylist {
        val playlist: SpotifyPlaylist =
            withRateLimitHandler {
                client.spotifyRequest(accessToken) {
                    method = HttpMethod.Post
                    url {
                        protocol = URLProtocol.HTTPS
                        host = "api.spotify.com"
                        path("v1", "users", userId, "playlists")
                    }
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    body = CreatePlaylistBody(playlistName, false)
                }
            }
        val addTracksResponse: String =
            withRateLimitHandler {
                client.spotifyRequest(accessToken) {
                    method = HttpMethod.Post
                    url {
                        protocol = URLProtocol.HTTPS
                        host = "api.spotify.com"
                        path("v1", "playlists", playlist.id, "tracks")
                    }
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    body = AddTracksToPlaylistBody(trackIds.map { "spotify:track:$it" }, 0)
                }
            }
        return playlist
    }

    private suspend fun fetchTracks(href: String, accessToken: String): List<PumpkinTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var responseSize = spotifyTracksMaxLimit
        var offset = 0
        while (offset < 100 && responseSize == spotifyTracksMaxLimit) {
            try {
                val tracksResponse: SpotifyPaginationObject<SpotifySavedTrackObject> =
                    withRateLimitHandler {
                        client.spotifyRequest(accessToken) {
                            method = HttpMethod.Get
                            url(href)
                            parameter("limit", spotifyTracksMaxLimit)
                            parameter("offset", offset)
                        }
                    }
                tracks.addAll(tracksResponse.items.map { it.track })
                responseSize = tracksResponse.items.size
                offset += tracksResponse.items.size
            } catch (e: SerializationException) {
                e.printStackTrace()
                offset += spotifyTracksMaxLimit
            }
        }
        return tracks
            .mapNotNull { it.toPumpkinTrack() }
            .filter { it.previewUrl != null }
    }

    private suspend fun fetchPlaylists(accessToken: String): List<PumpkinPlaylist> = coroutineScope {
        val playlists = mutableListOf<Deferred<PumpkinPlaylist>>()
        var responseSize = spotifyPlaylistsMaxLimit
        var offset = 0
        while (offset < 100 && responseSize == spotifyPlaylistsMaxLimit) {
            try {
                val playlistsResponse: SpotifyPaginationObject<SpotifyPlaylist> =
                    withRateLimitHandler {
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
                    }

                playlistsResponse.items
                    .map {
                        async {
                            PumpkinPlaylist(
                                it.id,
                                it.name,
                                fetchTracks(it.tracks.href, accessToken)
                            )
                        }
                    }
                    .let { playlists.addAll(it) }

                responseSize = playlistsResponse.items.size
                offset += playlistsResponse.items.size
            } catch (e: SerializationException) {
                e.printStackTrace()
                offset += spotifyPlaylistsMaxLimit
            }
        }
        playlists.awaitAll()
    }

    private suspend fun fetchAlbums(accessToken: String): List<PumpkinAlbum> = coroutineScope {
        val albums = mutableListOf<PumpkinAlbum>()
        var responseSize = spotifyAlbumsMaxLimit
        var offset = 0
        while (offset < 100 && responseSize == spotifyAlbumsMaxLimit) {
            try {
                val albumsResponse: SpotifyPaginationObject<SpotifySavedAlbumObject> =
                    withRateLimitHandler {
                        client.spotifyRequest(accessToken) {
                            url {
                                protocol = URLProtocol.HTTPS
                                host = "api.spotify.com"
                                path("v1", "me", "albums")
                            }
                            method = HttpMethod.Get
                            parameter("limit", spotifyAlbumsMaxLimit)
                            parameter("offset", offset)
                        }
                    }

                albumsResponse.items
                    .map { savedAlbumObject ->
                        PumpkinAlbum(
                            savedAlbumObject.album.id,
                            savedAlbumObject.album.name,
                            savedAlbumObject.album.tracks.items
                                .mapNotNull {
                                    // tracks fetched via album endpoint don't include album, as it is obvious
                                    // we need to set the album manually
                                    it.toPumpkinTrack(album = savedAlbumObject.album.toSpotifyAlbumInfo())
                                }
                                .filter { it.previewUrl != null }
                        )
                    }
                    .let { albums.addAll(it) }

                responseSize = albumsResponse.items.size
                offset += albumsResponse.items.size
            } catch (e: SerializationException) {
                e.printStackTrace()
                offset += spotifyAlbumsMaxLimit
            }
        }
        albums
    }

    private suspend fun <T> withRateLimitHandler(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: RateLimitedException) {
            println("INFO: waiting ${e.retryAfter} seconds")
            delay(e.retryAfter * 1000L)
            withRateLimitHandler(block)
        }
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
            when (e.response.status) {
                HttpStatusCode.Unauthorized -> {
                    throw AuthenticationException(e)
                }
                HttpStatusCode.TooManyRequests -> {
                    try {
                        val retryAfter = e.response.headers[RetryAfter]?.toIntOrNull() ?: throw RetryAfterParseException()
                        println("WARNING: rate limited, retryAfter: $retryAfter")
                        throw RateLimitedException(retryAfter, e)
                    } catch (parseException: RetryAfterParseException) {
                        println("WARNING: rate limited, parsing Retry-After header failed")
                        parseException.printStackTrace()
                        throw e
                    }
                }
                else -> {
                    println("WARNING: uncaught exception with status: ${e.response.status}")
                    throw e
                }
            }
        }
}