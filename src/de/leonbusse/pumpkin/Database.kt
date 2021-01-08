package de.leonbusse.pumpkin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.SetParams

// all time constants in seconds
const val ONE_MINUTE = 60
const val ONE_HOUR = 60 * ONE_MINUTE
const val ONE_DAY = 24 * ONE_HOUR
const val ONE_WEEK = 7 * ONE_DAY

const val USER_TTL = ONE_HOUR
const val LIBRARY_TTL = ONE_WEEK
const val ACCESS_TOKEN_TTL = 20 * ONE_MINUTE

val jedisMutex = Mutex()

class PumpkinCache(private val jedis: Jedis) {
    suspend fun setSpotifyLibrary(library: SpotifyLibrary): Unit = jedisMutex.withLock {
        jedis.set(
            "PumpkinCache:SpotifyLibrary:${library.user.id}",
            Json.encodeToString(library),
            SetParams().ex(LIBRARY_TTL)
        )
    }

    suspend fun getSpotifyLibrary(userId: String): SpotifyLibrary? = jedisMutex.withLock {
        jedis.get("PumpkinCache:SpotifyLibrary:$userId")?.let { Json.decodeFromString<SpotifyLibrary>(it) }
    }

    suspend fun rememberAccessToken(token: String): Unit = jedisMutex.withLock {
        jedis.set(
            "SpotifyAccessToken:$token",
            "",
            SetParams().nx().ex(ACCESS_TOKEN_TTL)
        )
    }

    suspend fun accessTokenIsKnown(token: String): Boolean = jedisMutex.withLock {
        return jedis.get("SpotifyAccessToken:$token") != null
    }
}


class SpotifyCache(private val jedis: Jedis) {
    suspend fun setSpotifyUser(key: String, user: SpotifyUser): Unit = jedisMutex.withLock {
        jedis.set(
            "SpotifyCache:SpotifyUser:$key",
            Json.encodeToString(user),
            SetParams().ex(USER_TTL)
        )
    }

    suspend fun getSpotifyUser(key: String): SpotifyUser? = jedisMutex.withLock {
        jedis.get("SpotifyCache:SpotifyUser:$key")?.let { Json.decodeFromString<SpotifyUser>(it) }
    }
}