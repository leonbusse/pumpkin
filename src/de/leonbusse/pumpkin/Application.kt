package de.leonbusse.pumpkin

import de.leonbusse.pumpkin.serversideapp.ServerSideApp
import de.leonbusse.pumpkin.serversideapp.install
import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.logging.*
import io.ktor.features.*
import io.ktor.features.ContentTransformationException
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.sessions.*

lateinit var dotenv: Dotenv

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)


@Suppress("unused")
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    dotenv = dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    println("init pumpkin...")
    println("BASE_URL: ${dotenv["BASE_URL"]}")
    println("SPOTIFY_CLIENT_ID: ${dotenv["SPOTIFY_CLIENT_ID"]}")

    val baseUrl = URLBuilder().takeFrom(dotenv["BASE_URL"]).build()
    val spotifyRedirectUri = URLBuilder().takeFrom(baseUrl).apply {
        path(
            *baseUrl.encodedPath.splitPath().toTypedArray(),
            "spotify", "callback"
        )
    }.buildString()
    val spotifyClientId = dotenv["SPOTIFY_CLIENT_ID"]
    val spotifyClientSecret = dotenv["SPOTIFY_CLIENT_SECRET"]
    val spotifyScope =
        "user-read-private playlist-read-private user-read-email user-library-read playlist-modify-private"
    val basicAuthToken = "Basic " + "$spotifyClientId:$spotifyClientSecret".base64()

    val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.ALL
            }
        }
    }

    val pumpkinApi = PumpkinApi(client, basicAuthToken)

    val serverSideApp = install(ServerSideApp) {
        this.pumpkinApi = pumpkinApi
        this.client = client
        this.basicAuthToken = basicAuthToken
        this.baseUrl = baseUrl
        this.spotifyClientId = spotifyClientId
        this.spotifyScope = spotifyScope
        this.spotifyRedirectUri = spotifyRedirectUri
    }

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
                        "status" to it.value.toString(),
                        "message" to it.description
                    )
                )
            )
        }

        install(serverSideApp)
    }

    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json {
            prettyPrint = true
        })
    }

    install(Sessions) {
        install(serverSideApp)
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
                    val (shareId, _) = pumpkinApi.importLibrary(spotifyAccessToken, null)
                    call.respond(ImportResponse(shareId))
                }

                get("/tracks/{userId}") {
                    val userId = call.parameters["userId"]
                        ?: throw BadRequestException("missing path parameter userId")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull()

                    val tracks = pumpkinApi.getTracks(userId, limit = limit, offset = offset)
                        ?: throw NotFoundException()
                    println("tracks: $tracks")
                    call.respond(HttpStatusCode.OK, tracks)
                }

                get("/user/{userId}") {
                    val userId = call.parameters["userId"]
                        ?: throw BadRequestException("missing path parameter userId")

                    val user = pumpkinApi.getUser(userId)
                        ?: throw NotFoundException()
                    call.respond(HttpStatusCode.OK, user)
                }

                put("/like") {
                    println("/like put")
                    val request = call.receive<LikeTracksRequest>()
                    pumpkinApi.like(request.trackIds, request.userId, request.libraryUserId)
                    call.respond(HttpStatusCode.OK)
                }

                post("/like") {
                    println("/like post")
                    val request = call.receive<LikeTracksRequest>()
                    pumpkinApi.like(request.trackIds, request.userId, request.libraryUserId)
                    call.respond(HttpStatusCode.OK)
                }

                post("/create-playlist") {
                    println("/create-playlist")
                    val request = call.receive<CreatePlaylistRequest>()
                    val (playlist, _) = pumpkinApi.export(
                        request.userId,
                        request.playlistName,
                        request.spotifyAccessToken,
                        null
                    )
                    call.respond(CreatePlaylistResponse(playlist))
                }
            }
        }

        install(serverSideApp)
    }
}
