package de.leonbusse.pumpkin

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.SetParams

const val ONE_HOUR = 3600
const val USER_TTL = ONE_HOUR

class PumpkinCache(
    private val jedis: Jedis
) {

    companion object {
        const val ONE_HOUR = 3600
    }

    fun setSpotifyLibrary(library: SpotifyLibrary) {
        jedis.set("PumpkinCache:spotifylibrary:${library.user.id}", Json.encodeToString(library), SetParams().ex(ONE_HOUR))
    }

    fun getSpotifyLibrary(userId: String): SpotifyLibrary? =
        jedis.get("PumpkinCache:spotifylibrary:$userId")?.let { Json.decodeFromString<SpotifyLibrary>(it) }
}


class SpotifyCache(
    private val jedis: Jedis
) {

    fun setSpotifyUser(key: String, user: SpotifyUser) {
        jedis.set("SpotifyCache:spotifyuser:${user.id}", Json.encodeToString(user), SetParams().ex(USER_TTL))
    }

    fun getSpotifyUser(key: String) =
        jedis.get("SpotifyCache:spotifyuser:$key")?.let { Json.decodeFromString<SpotifyUser>(it) }
}