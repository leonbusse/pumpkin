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
const val SHARE_TRACKS_TTL = ONE_WEEK

val jedisMutex = Mutex()

class PumpkinCache(private val jedis: Jedis) {
    suspend fun setTracksByShareId(shareId: String, tracks: List<PumpkinTrack>): Unit = jedisMutex.withLock {
        jedis.set(
            "PumpkinCache:SharedTracks:$shareId",
            Json.encodeToString(tracks),
            SetParams().ex(SHARE_TRACKS_TTL)
        )
    }

    suspend fun getTracksByShareId(shareId: String): List<PumpkinTrack>? = jedisMutex.withLock {
        jedis.get("PumpkinCache:SharedTracks:$shareId")
            ?.let { Json.decodeFromString<List<PumpkinTrack>>(it) }
            .logCacheAccess("getTracksByShareId")
    }

    suspend fun setUserIdByShareId(shareId: String, userId: String): Unit = jedisMutex.withLock {
        jedis.set(
            "PumpkinCache:UserIdByShareId:$shareId",
            userId,
            SetParams().ex(SHARE_TRACKS_TTL)
        )
    }

    suspend fun getUserIdByShareId(shareId: String): String? = jedisMutex.withLock {
        jedis.get("PumpkinCache:UserIdByShareId:$shareId")
            .logCacheAccess("getUserIdByShareId")
    }
}

class SpotifyCache(private val jedis: Jedis) {
    suspend fun setSpotifyUserByToken(token: String, user: SpotifyUser): Unit = jedisMutex.withLock {
        jedis.set(
            "SpotifyCache:SpotifyUserId:$token",
            user.id,
            SetParams().ex(ACCESS_TOKEN_TTL)
        )
        setSpotifyUserByIdInternal(user.id, user)
    }

    suspend fun getSpotifyUserByToken(token: String): SpotifyUser? = jedisMutex.withLock {
        jedis.get("SpotifyCache:SpotifyUserId:$token")
            ?.let { getSpotifyUserByIdInternal(it) }
            .logCacheAccess("getSpotifyUserByToken")
    }

    suspend fun setSpotifyUserById(userId: String, user: SpotifyUser): Unit = jedisMutex.withLock {
        setSpotifyUserByIdInternal(userId, user)
    }

    suspend fun getSpotifyUserById(userId: String): SpotifyUser? = jedisMutex.withLock {
        getSpotifyUserByIdInternal(userId)
            .logCacheAccess("getSpotifyUserById")
    }

    private fun setSpotifyUserByIdInternal(userId: String, user: SpotifyUser) {
        jedis.set(
            "SpotifyCache:SpotifyUser:$userId",
            Json.encodeToString(user),
            SetParams().ex(USER_TTL)
        )
    }

    private fun getSpotifyUserByIdInternal(userId: String): SpotifyUser? =
        jedis.get("SpotifyCache:SpotifyUser:$userId")
            ?.let { Json.decodeFromString<SpotifyUser>(it) }
            .logCacheAccess("getSpotifyUserByIdInternal")

    suspend fun setLibraryByUserId(library: SpotifyLibrary): Unit = jedisMutex.withLock {
        jedis.set(
            "SpotifyCache:SpotifyLibrary:${library.user.id}",
            Json.encodeToString(library),
            SetParams().ex(LIBRARY_TTL)
        )
    }

    suspend fun getLibraryByUserId(userId: String): SpotifyLibrary? = jedisMutex.withLock {
        jedis.get("SpotifyCache:SpotifyLibrary:$userId")
            ?.let { Json.decodeFromString<SpotifyLibrary>(it) }
            .logCacheAccess("getLibraryByUserId")
    }
}

fun <T> T?.logCacheAccess(name: String): T? = this.also {
    if (this == null) println("Cache MISS - $name: $this")
    else println("Cache HIT: $name")
}
//fun <T> T?.logCacheAccess(name: String): T? = this.also {
//    println("Cache disabled - $name: $this")
//    return null
//}