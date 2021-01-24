package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.features.logging.*
import io.ktor.serialization.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder


object TestData {
    const val accessToken = ""
}

class SpotifyApiTest {
    //    @Test
    fun `fetch Spotify library`() {
        val client = HttpClient(CIO) {
            install(JsonFeature) {
                serializer = KotlinxSerializer(
                    Json(DefaultJson) {
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.HEADERS
            }
        }
        val dummyCache = mockk<SpotifyCache>(relaxed = true)
        coEvery { dummyCache.getLibraryByUserId(any()) } returns null
        coEvery { dummyCache.getSpotifyUserById(any()) } returns null
        coEvery { dummyCache.getSpotifyUserByToken(any()) } returns null
        val spotifyApi = SpotifyApi(dummyCache, client)

        val spotifyLibrary = runBlocking {
            spotifyApi.getSpotifyLibrary(TestData.accessToken)
        }
        println("result: $spotifyLibrary")
    }
}