package de.leonbusse.pumpkin

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.features.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.sessions.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.html.*
import kotlinx.serialization.SerializationException
import java.util.*

lateinit var dotenv: Dotenv

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)


@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    dotenv = dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    val baseUrl = URLBuilder().takeFrom(dotenv["BASE_URL"]).build()
    val spotifyRedirectUriPath = dotenv["SPOTIFY_REDIRECT_URI_PATH"]
    val spotifyRedirectUri = URLBuilder().takeFrom(baseUrl).apply {
        path(
            *baseUrl.encodedPath.splitPath().toTypedArray(),
            *spotifyRedirectUriPath.splitPath().toTypedArray()
        )
    }.buildString()
    val spotifyClientId = dotenv["SPOTIFY_CLIENT_ID"]
    val spotifyClientSecret = dotenv["SPOTIFY_CLIENT_SECRET"]
    val spotifyScope =
        "user-read-private playlist-read-private user-read-email user-library-read playlist-modify-private"
    val basicAuthToken = "Basic " + "$spotifyClientId:$spotifyClientSecret".base64()

    println("base URL at $baseUrl")

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

    val serverSideWebApp = install(ServerSideWebApp) {
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

    install(StatusPages) {
        exception<ClientRequestException> { cause ->
            if (cause.response.status == HttpStatusCode.Unauthorized) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                call.respond(HttpStatusCode.InternalServerError)
            }
            throw cause
        }
        exception<SerializationException> { cause ->
            call.respond(HttpStatusCode.BadRequest)
            throw cause
        }
        exception<IllegalArgumentException> { cause ->
            call.respond(HttpStatusCode.BadRequest)
            throw cause
        }
        exception<IllegalStateException> { cause ->
            call.respond(HttpStatusCode.InternalServerError)
            throw cause
        }
        exception<Throwable> { cause ->
            call.respond(HttpStatusCode.InternalServerError)
            throw cause
        }
        exception<PumpkinApi.InvalidSpotifyAccessTokenException> { cause ->
            call.respond(HttpStatusCode.Unauthorized)
            throw cause
        }
        exception<MissingSpotifyTokenException> { cause ->
            call.respond(HttpStatusCode.Unauthorized)
            throw cause
        }

        status(
            HttpStatusCode.NotFound,
            HttpStatusCode.BadRequest,
            HttpStatusCode.InternalServerError,
            HttpStatusCode.Unauthorized
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

        install(serverSideWebApp)
    }

    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json {
            prettyPrint = true
        })
    }

    install(Sessions) {
        install(serverSideWebApp)
    }

    routing {

        route("/api") {
            route("/v1") {
                post("/export") {
                    val request = call.receive<ExportRequest>()
                    val spotifyAccessToken = request.spotifyAccessToken
                    if (spotifyAccessToken.isBlank()) {
                        println("error: missing access token")
                        return@post call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        val shareLink = pumpkinApi.importLibrary(spotifyAccessToken, null)
                        call.respond(
                            HttpStatusCode.OK,
                            "{\"shareLink\":\"$shareLink\"}"
                        )
                    }
                }

                get("/library/{userId}") {
                    val userId = call.parameters["userId"]
                    if (userId.isNullOrEmpty()) {
                        println("error: invalid userId")
                        return@get call.respond(HttpStatusCode.BadRequest)
                    }
                    val library = pumpkinApi.getLibrary(userId)
                    if (library == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(HttpStatusCode.OK, library)
                    }
                }
            }
        }

        install(serverSideWebApp)
    }
}
