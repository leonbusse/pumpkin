package de.leonbusse.pumpkin

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.features.logging.*
import io.ktor.features.*
import io.ktor.features.ContentTransformationException
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import redis.clients.jedis.Jedis

lateinit var dotenv: Dotenv

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

object Env {
    var dev: Boolean = false
    val prod get() = !dev
}

@Suppress("unused")
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    println("init pumpkin...")

    dotenv = dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    Env.dev = dotenv["ENV"] == "DEV"
    println("running in ${if (Env.dev) "development" else "production"} mode")


    val jedis = Jedis("redis", 6379)
    val spotifyCache = SpotifyCache(jedis)
    val pumpkinCache = PumpkinCache(jedis)

    val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(JsonFeature) {
                serializer = KotlinxSerializer(
                    kotlinx.serialization.json.Json(DefaultJson) {
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.HEADERS
            }
        }
    }

    val spotifyApi = SpotifyApi(spotifyCache, httpClient)
    val pumpkinApi = PumpkinApi(pumpkinCache, spotifyApi)

    install(DefaultHeaders) {
        header("X-Engine", "Ktor")
    }

    install(CORS) {
        anyHost()
        HttpMethod.DefaultMethods.forEach { method(it) }
        listOf(
            HttpHeaders.Accept,
            HttpHeaders.AcceptEncoding,
            HttpHeaders.AcceptLanguage,
            HttpHeaders.CacheControl,
            HttpHeaders.Connection,
            HttpHeaders.ContentLength,
            HttpHeaders.ContentType,
            HttpHeaders.Host,
            HttpHeaders.Origin,
            HttpHeaders.Pragma,
            HttpHeaders.Referrer,
            HttpHeaders.UserAgent
        ).forEach { exposeHeader(it) }
        allowCredentials = true
        allowNonSimpleContentTypes = true
    }

    install(StatusPages) {
        exception<ClientRequestException> { cause ->
            if (cause.response.status == HttpStatusCode.Unauthorized) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                call.respond(HttpStatusCode.InternalServerError)
            }
            throw cause
        }
        exception<ContentTransformationException> { cause ->
            call.respond(HttpStatusCode.BadRequest)
            throw cause
        }
        exception<AuthenticationException> { cause ->
            call.respond(HttpStatusCode.Unauthorized)
            throw cause
        }
        exception<AuthorizationException> { cause ->
            call.respond(HttpStatusCode.Forbidden)
            throw cause
        }
        exception<ConflictException> { cause ->
            call.respond(HttpStatusCode.Conflict)
            throw cause
        }

        status(
            HttpStatusCode.NotFound,
            HttpStatusCode.BadRequest,
            HttpStatusCode.InternalServerError,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Conflict
        ) {
            call.respond(
                mapOf(
                    "error" to mapOf(
                        "statusCode" to it.value.toString(),
                        "message" to it.description,
                    )
                )
            )
        }
    }

    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json {
            prettyPrint = true
        })
    }

    routing {

        route("/api") {
            route("/v1") {

                get("ping") {
                    call.respond("ACK ping")
                }

                post("/import") {
                    val request = call.receive<ImportRequest>()
                    val spotifyAccessToken = request.spotifyAccessToken
                    val shareId = pumpkinApi.initializeSharedLibrary(spotifyAccessToken)
                    call.respond(ImportResponse(shareId))
                }

                get("/tracks/{shareId}") {
                    val shareId = call.parameters["shareId"]
                        ?: throw BadRequestException("missing path parameter shareId")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull()

                    val tracks = pumpkinApi.getTracksByShareId(shareId, limit = limit, offset = offset)
                    call.respond(HttpStatusCode.OK, tracks)
                }

                get("/share/user/{shareId}") {
                    val shareId = call.parameters["shareId"]
                        ?: throw BadRequestException("missing path parameter userId")

                    val user = pumpkinApi.getCachedUserByShareId(shareId)
                        ?: throw NotFoundException()
                    call.respond(HttpStatusCode.OK, user)
                }

                get("/user/{userId}") {
                    val userId = call.parameters["userId"]
                        ?: throw BadRequestException("missing path parameter userId")

                    val user = pumpkinApi.getCachedUser(userId)
                        ?: throw NotFoundException()
                    call.respond(HttpStatusCode.OK, user)
                }

                post("/create-playlist") {
                    println("/create-playlist")
                    val request = call.receive<CreatePlaylistRequest>()
                    val playlist = pumpkinApi.createPlaylist(
                        request.userId,
                        request.playlistName,
                        request.trackIds,
                        request.spotifyAccessToken
                    )
                    call.respond(CreatePlaylistResponse(playlist.id))
                }
            }
        }
    }
}
