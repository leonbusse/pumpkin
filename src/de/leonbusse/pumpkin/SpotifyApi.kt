package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.util.*

class SpotifyApi(
    private val cache: SpotifyCache,
    private val client: HttpClient,
    private val basicAuthToken: String?
) {
    companion object {
        const val spotifyTracksMaxLimit = 50
    }

    private class SpotifySession(
        var accessToken: String,
        val refreshToken: String?
    )

    suspend fun import(accessToken: String, refreshToken: String?): Pair<SpotifyLibrary, String> {
        val session = SpotifySession(accessToken, refreshToken)

        val user: SpotifyUser = cache.getSpotifyUser(accessToken)
            ?: client.spotifyRequest<SpotifyUser>(session) {
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.spotify.com"
                    path("v1", "me")
                }
                method = HttpMethod.Get
            }.also { cache.setSpotifyUser(accessToken, it) }
//        println("user: $user")
        val likedTracks = fetchTracks(session)

        return SpotifyLibrary(user, likedTracks) to session.accessToken
    }

    suspend fun createPlaylist(
        userId: String,
        trackIds: List<String>,
        playlistName: String,
        accessToken: String,
        refreshToken: String?
    ): Pair<SpotifyPlaylist, String> {
        val session = SpotifySession(accessToken, refreshToken)
        val playlist: SpotifyPlaylist =
            client.spotifyRequest(session) {
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
            client.spotifyRequest(session) {
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
        return playlist to session.accessToken
    }

    private suspend fun fetchTracks(session: SpotifySession): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var responseSize = spotifyTracksMaxLimit
        var offset = 0
        while (offset < 150 && responseSize == spotifyTracksMaxLimit) {
            val tracksResponse: SpotifyTracksResponse =
                client.spotifyRequest(session) {
                    url {
                        protocol = URLProtocol.HTTPS
                        host = "api.spotify.com"
                        path("v1", "me", "tracks")
                    }
                    method = HttpMethod.Get
                    parameter("limit", 50)
                    parameter("offset", offset)
                }
            tracks.addAll(tracksResponse.items.map { it.track })
            responseSize = tracksResponse.items.size
            offset += tracksResponse.items.size
        }
        return tracks
    }

    private suspend inline fun <reified T> HttpClient.spotifyRequest(
        session: SpotifySession,
        block: HttpRequestBuilder.() -> Unit
    ): T =
        try {
            this.request {
                block()
                headers {
                    append("Authorization", "Bearer ${session.accessToken}")
                }
            }
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Unauthorized
                && refreshToken(session)
            ) {
                this.request {
                    block()
                    headers {
                        append("Authorization", "Bearer ${session.accessToken}")
                    }
                }
            } else throw e
        }

    private suspend fun refreshToken(session: SpotifySession): Boolean {
        println("refreshing access token...")
        return if (session.refreshToken == null) {
            println("can't refresh access token without refresh token")
            false
        } else if (basicAuthToken == null) {
            println("can't refresh access token basic authentication credentials")
            false
        } else {
            try {
                val response: SpotifyRefreshTokenResponse = client.request {
                    method = HttpMethod.Post
                    url {
                        protocol = URLProtocol.HTTPS
                        host = "accounts.spotify.com"
                        path("api", "token")
                    }
                    body = FormDataContent(
                        parametersOf(
                            Pair("grant_type", listOf("refresh_token")),
                            Pair("refresh_token", listOf(session.refreshToken))
                        )
                    )
                    header("Authorization", basicAuthToken)
                }
                session.accessToken = response.access_token
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    data class SpotifyRefreshTokenResponse(
        val access_token: String,
        val token_type: String,
        val scope: String,
        val expires_in: Int
    )
}