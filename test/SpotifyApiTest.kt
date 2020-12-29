package de.leonbusse.pumpkin

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.logging.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

object TestData {
    val playlistName = "Test Playlist"
    val accessToken = "BQDrUG5wzUrFJ3FJPftAXSqEcAt8IEAr9lo5Egdo3wIN7zEQiMkSIGNzE1aguiUaoeLeIvmx3FpTFzRYfcF8zv-ZmbSRZtsvcmRN__C9HW2ARixMO-i1xkDhumX60HNSGbS3tcx-Svs5gSXMBRfDRsC0vkyWBtsvlPwhRWhWKvle4FYc0E8EJaLQUIVvnPXXkg7JZfe7veR7a_YevQ"
    val userId = "1137307640"
    val tracks = listOf("1qmLqt74b1Fe4IugAWxu3Y", "6Z8rLq7Is0mjLxEscXsp58")
}

class SpotifyApiTest {
    @Test
    fun `export library creates a playlist`() {
        val client = HttpClient(CIO) {
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.ALL
            }
        }
        val api = SpotifyApi(client, null)
        val (playlist, accessToken) = runBlocking {
            api.createPlaylist(
                TestData.userId,
                TestData.tracks,
                TestData.playlistName,
                TestData.accessToken,
                null
            )
        }
        assertFalse(playlist.public)
//        assertEquals(TestData.tracks.size, playlist.tracks.total)
        assertEquals(TestData.playlistName, playlist.name)
    }
}