package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*

class SpotifyImporter(private val client: HttpClient, val accessToken: String) {
    companion object {
        const val spotifyTracksMaxLimit = 50
    }

    suspend fun import(): SpotifyLibrary {
        val user: SpotifyUser =
            client.get("https://api.spotify.com/v1/me") {
                withAuthHeader()
            }
        println("user: $user")
        val likedTracks = try {
            fetchTracks()
        } catch (e: ClientRequestException) {
            if(e.response.status == HttpStatusCode.Unauthorized) {
                throw PumpkinApi.InvalidSpotifyAccessTokenException()
            } else throw e
        }

        return SpotifyLibrary(user, likedTracks)
    }

    private suspend fun fetchTracks(): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var responseSize = spotifyTracksMaxLimit
        var offset = 0
        while (offset < 150 && responseSize == spotifyTracksMaxLimit) {
            val tracksResponse: SpotifyTracksResponse =
                client.get("https://api.spotify.com/v1/me/tracks") {
                    headers {
                        append("Authorization", "Bearer $accessToken")
                    }
                    parameter("limit", 50)
                    parameter("offset", offset)
                }
            tracks.addAll(tracksResponse.items.map { it.track })
            responseSize = tracksResponse.items.size
            offset += tracksResponse.items.size
        }
        return tracks
    }

    private fun HttpRequestBuilder.withAuthHeader() =
        headers {
            append("Authorization", "Bearer $accessToken")
        }
}