package de.leonbusse.pumpkin

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertNotNull

class PumpkinApiTest {

    object TestData {
        val accessToken = "dummyAccessToken"
        val artist = SpotifyArtist("789", "Test Artist")
        val album = SpotifyAlbumInfo("4367", "Test Album", listOf(artist), images = listOf())
        val track = PumpkinTrack(
            "456", "Test Track", "https://preview.track.com/listen", "Test Album",
            listOf("Test Artist"), ""
        )
        val library = SpotifyLibrary(
            SpotifyUser("123", "Mr. Test", "test@test.com", "premium"),
            listOf(track),
            listOf(),
            listOf()
        )
        val spotifyLibraries = mapOf(accessToken to library)
    }

    @Test
    fun `initialize share link - happy path`(): Unit = runBlocking {
        val trackCache = mutableMapOf<String, List<PumpkinTrack>>()
        val userIdCache = mutableMapOf<String, String>()
        val cache = mockk<PumpkinCache>()
        coEvery { cache.setTracksByShareId(any(), any()) } answers {
            trackCache[this.arg(0)] = this.arg(1)
        }
        coEvery { cache.getTracksByShareId(any()) } answers {
            trackCache[this.arg(0)]
        }
        coEvery { cache.setUserIdByShareId(any(), any()) } answers {
            userIdCache[this.arg(0)] = this.arg(1)
        }
        coEvery { cache.getUserIdByShareId(any()) } answers {
            userIdCache[this.arg(0)]
        }

        val spotifyApi = mockk<SpotifyApi>()
        coEvery { spotifyApi.getSpotifyLibrary(any()) } answers {
            TestData.spotifyLibraries[this.arg(0)]
                ?: throw Exception("requested library not available")
        }

        val pumpkinApi = PumpkinApi(cache, spotifyApi)
        val shareLink = pumpkinApi.initializeSharedLibrary(TestData.accessToken)
        assertNotNull(shareLink)
    }
}
