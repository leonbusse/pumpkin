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

const val AuthSessionKey = "AuthSession"
const val PumpkinSessionKey = "PumpkinSession"

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

private val client: HttpClient by lazy {
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

    val pumpkinApi = PumpkinApi(client, basicAuthToken)

    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
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

        status(
            HttpStatusCode.NotFound,
            HttpStatusCode.BadRequest,
            HttpStatusCode.InternalServerError,
            HttpStatusCode.Unauthorized
        ) {
            val acceptList = call.request.header(HttpHeaders.Accept)?.split(",")
            if (acceptList != null && ContentType.Text.Html.toString() in acceptList) {
                call.respondHtml {
                    body {
                        h1 { +"Error ${it.value}" }
                        p { +it.description }
                    }
                }
            } else {
                call.respond(
                    mapOf(
                        "error" to mapOf(
                            "status" to it.value.toString(),
                            "message" to it.description
                        )
                    )
                )
            }
        }
    }

    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json {
            prettyPrint = true
        })
    }

    install(Sessions) {
        cookie<AuthSession>(AuthSessionKey)
        cookie<PumpkinSession>(PumpkinSessionKey)
    }

    routing {

        intercept(ApplicationCallPipeline.Features) {
            val path = (this.context as RoutingApplicationCall).route.path
            println("intercept $path")
            // whitelist routes without authentication
            when (path) {
                "/",
                "/spotify/login",
                "/spotify/callback" -> {
                    println("- whitelisted, proceed")
                    return@intercept proceed()
                }
            }

            val session = call.sessions.get<PumpkinSession>()
            if (session == null) {
                val redirectUrl = call.url {
                    path("spotify", "login")
                    parameters.append("redirect", call.url())
                }
                println("- no session, redirecting to $redirectUrl")
                call.respondRedirect(redirectUrl)
                return@intercept finish()
            } else {
                println("- has session, proceed")
                return@intercept proceed()
            }
        }

        get("/test-unauthorized") {
            call.respond(HttpStatusCode.Unauthorized)
        }

        get("/test-unauthorized-exception") {
            throw PumpkinApi.InvalidSpotifyAccessTokenException()
        }

        get("/") {
            val accessToken = call.sessions.get<PumpkinSession>()?.spotifyAccessToken
            if (accessToken.isNullOrBlank()) {
                call.respondHtml {
                    head { title { +"Pumpkin | Share your Spotify library with friends" } }
                    body {
                        h1 { +"Pumpkin - Share your Spotify library with friends" }
                        a(href = "/spotify/login") { +"Login with Spotify" }
                    }
                }
            } else {
                println("token: $accessToken")
                call.respondHtml {
                    head { title { +"Pumpkin | Share your Spotify library with friends" } }
                    body {
                        h1 { +"Pumpkin - Share your Spotify library with friends" }
                        a(href = "/get-link") { +"Generate link to my library" }
                    }
                }
            }
        }

        get("/get-link") {
            val session = call.sessions.get<PumpkinSession>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val (shareLink, updatedSpotifyAccessToken) = pumpkinApi.importLibrary(
                session.spotifyAccessToken,
                session.spotifyRefreshToken
            )
            call.sessions.set(session.copy(spotifyAccessToken = updatedSpotifyAccessToken))
            call.respondHtml {
                head { title { +"Pumpkin | Your library link" } }
                body {
                    h1 { +"Success" }
                    p {
                        +"Now you can share your library using this link: "
                        a(shareLink, target = "_blank") {
                            +shareLink
                        }
                    }
                }
            }
        }

        get("/share/{userId}") {
            // validate parameters
            val userId = call.parameters["userId"]
            if (userId.isNullOrEmpty()) {
                println("error: invalid userId")
                return@get call.respond(HttpStatusCode.BadRequest)
            }

            // update session
            val session = call.sessions.get<PumpkinSession>()!!
            val updatedSession = session.copy(userId = userId)
            call.sessions.set(updatedSession)

            val library = pumpkinApi.getLibrary(userId)
            if (library == null) {
                println("error: could not find library for userId $userId")
                return@get call.respond(HttpStatusCode.NotFound)
            }
            call.respondHtml {
                head { title { +"Pumpkin | ${library.user.display_name}'s library" } }
                body {
                    h1 { +"This is ${library.user.display_name}'s library" }
                    form(action = "${baseUrl}create-playlist", method = FormMethod.post) {
                        input(InputType.hidden) {
                            name = "libraryUserId"
                            value = library.user.id
                        }
                        table {
                            thead {
                                tr {
                                    th { +"like" }
                                    th { +"name" }
                                }
                            }
                            tbody {
                                library.tracks.take(10).forEach { track ->
                                    tr {
                                        td {
                                            input(InputType.checkBox) {
                                                this.name = "track-${track.id}"
                                            }
                                        }
                                        td {
                                            a(track.preview_url, target = "_blank") {
                                                +track.name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        label {
                            htmlFor = "playlistName"
                            +"Playlist Name"
                        }
                        br
                        input(InputType.text) {
                            name = "playlistName"
                            required = true
                        }
                        br
                        br
                        input(InputType.submit) {
                            value = "Create Playlist"
                        }
                    }
                }
            }
        }

        post("/create-playlist") {
            // parse parameters
            val parameters = call.receiveParameters()
            val playlistName: String? =
                parameters.entries()
                    .find { it.key == "playlistName" }
                    ?.value?.firstOrNull()
            val libraryUserId: String? =
                parameters.entries()
                    .find { it.key == "libraryUserId" }
                    ?.value?.firstOrNull()
            val trackIds = parameters.entries()
                .filter { it.key.startsWith("track-") }
                .mapNotNull { it.key.split("track-").getOrNull(1) }

            val session = call.sessions.get<PumpkinSession>()
            if (session?.userId == null || playlistName == null || libraryUserId == null) {
                throw IllegalArgumentException()
            }

            // like tracks
            pumpkinApi.like(trackIds, session.userId, libraryUserId)

            // create Spotify playlist
            val (playlist, spotifyAccessToken) = pumpkinApi.export(
                session.userId,
                playlistName,
                session.spotifyAccessToken,
                session.spotifyRefreshToken
            )

            // update session access token
            val updatedSession = session.copy(spotifyAccessToken = spotifyAccessToken)
            call.sessions.set(updatedSession)

            call.respondHtml {
                head { title { +"Pumpkin | Success" } }
                body {
                    h1 { +"Success" }
                    p { +"A new playlist \"${playlist.name}\" has been added to your Spotify library!" }
                    a("/") { +"Back" }
                }
            }
        }

        get("/clear-cookies") {
            call.sessions.clear<AuthSession>()
            call.sessions.clear<PumpkinSession>()
            call.respondRedirect("/")
        }

        route("/spotify") {

            get("/login") {
                val redirect = call.parameters["redirect"]
                println("parsed redirect: $redirect")
                val state = generateRandomString(16)
                call.sessions.set(AuthSession(state = state, redirect = redirect))

                val url = url {
                    protocol = URLProtocol.HTTPS
                    host = "accounts.spotify.com"
                    path("authorize")
                    parameters.apply {
                        append("response_type", "code")
                        append("client_id", spotifyClientId)
                        append("scope", spotifyScope)
                        append("redirect_uri", spotifyRedirectUri)
                        append("state", state)
                    }
                }
                call.respondRedirect(url)
            }

            get("/callback") {
                val state = call.sessions.get<AuthSession>()?.state
                val redirectUrl = call.sessions.get<AuthSession>()?.redirect
                val code = call.parameters["code"]

                if (call.parameters["state"] == null ||
                    call.parameters["state"] != state ||
                    code.isNullOrEmpty()
                ) {
                    println("error: auth state did not match")
                    return@get call.respond(HttpStatusCode.BadRequest)
                } else {
                    call.sessions.clear<AuthSession>()

                    val response: SpotifyTokenResponse? =
                        client.request {
                            method = HttpMethod.Post
                            body = FormDataContent(
                                parametersOf(
                                    Pair("code", listOf(code)),
                                    Pair("redirect_uri", listOf(spotifyRedirectUri)),
                                    Pair("grant_type", listOf("authorization_code"))
                                )
                            )
                            url {
                                protocol = URLProtocol.HTTPS
                                host = "accounts.spotify.com"
                                path("api", "token")
                            }
                            headers {
                                append("Authorization", basicAuthToken)
                            }
                        }

                    val session = try {
                        PumpkinSession(response!!.access_token, response.refresh_token, null)
                    } catch (e: NullPointerException) {
                        println("error: missing Spotify access token")
                        return@get call.respond(HttpStatusCode.Unauthorized)
                    }
                    call.sessions.set(session)
                    call.respondRedirect(redirectUrl ?: "/")
                }
            }
        }

        route("/api") {
            route("/v1") {
                post("/export") {
                    val response = call.receive<ExportRequest>()
                    val spotifyAccessToken = response.spotifyAccessToken
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

        static("/static") {
            resources("static")
        }
    }
}

val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
fun generateRandomString(length: Int): String = (1..length)
    .map { i -> kotlin.random.Random.nextInt(0, charPool.length) }
    .map(charPool::get)
    .joinToString("")

fun String.base64(): String = Base64.getEncoder().encodeToString(this.toByteArray())
