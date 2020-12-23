package de.leonbusse.pumpkin

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.features.*
import io.ktor.features.ContentTransformationException
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.sessions.*
import io.ktor.util.*
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.serialization.SerializationException
import java.lang.IllegalArgumentException
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
    }
}

private val pumpkinApi = PumpkinApi(client)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    dotenv = dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    val spotifyClientId = dotenv["SPOTIFY_CLIENT_ID"]
    val spotifyClientSecret = dotenv["SPOTIFY_CLIENT_SECRET"]
    val spotifyRedirectUri = dotenv["SPOTIFY_REDIRECT_URI"]
    val spotifyScope = "user-read-private playlist-read-private user-read-email user-library-read"

    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
    }

    install(StatusPages) {
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

        get("/") {
            val accessToken = call.sessions.get<PumpkinSession>()?.spotifyAccessToken
            if (accessToken.isNullOrEmpty()) {
                call.respondHtml {
                    body {
                        h1 { +"Pumpkin - Share your Spotify library with friends" }
                        a(href = "/spotify/login") { +"Login with Spotify" }
                    }
                }
            } else {
                call.respondHtml {
                    body {
                        h1 { +"Pumpkin - Share your Spotify library with friends" }
                        a(href = "/get-link") { +"Generate link to my library" }
                    }
                }
            }
        }

        get("/get-link") {
            val accessToken = call.sessions.get<PumpkinSession>()?.spotifyAccessToken
            if (accessToken.isNullOrEmpty()) {
                println("error: missing access token")
                call.respondRedirect("/error")
                return@get
            }
            val shareLink = pumpkinApi.importLibrary(accessToken)
            call.respondHtml {
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
            val userId = call.parameters["userId"]
            if (userId.isNullOrEmpty()) {
                println("error: invalid userId")
                call.respondRedirect("/error")
                return@get
            }
            val library = pumpkinApi.getLibrary(userId)
            if (library == null) {
                println("error: could not find library for userId $userId")
                call.respondRedirect("/error")
                return@get
            }
            call.respondHtml {
                body {
                    h1 { +"This is ${library.user.display_name}'s library" }
                    library.tracks.take(50).forEach { track ->
                        p {
                            a(track.preview_url, target = "_blank") {
                                +track.name
                            }
                        }
                    }
                }
            }
        }

        get("/error") {
            call.respondHtml {
                body {
                    h1 { +"Error" }
                    p { +"Something went wrong. Please try again." }
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
                val state = generateRandomString(16)
                call.sessions.set(AuthSession(state = state))

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
                val code = call.parameters["code"]

                if (call.parameters["state"] == null ||
                    call.parameters["state"] != state ||
                    code.isNullOrEmpty()
                ) {
                    println("error: auth state did not match")
                    call.respondRedirect("/error")
                    return@get
                } else {
                    call.sessions.clear<AuthSession>()
                    val basicAuthValue = "$spotifyClientId:$spotifyClientSecret".base64()

                    val response: SpotifyTokenResponse? = try {
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
                                append("Authorization", "Basic $basicAuthValue")
                            }
                        }
                    } catch (e: Exception) {
                        println("error: $e")
                        call.respondRedirect("/error")
                        return@get
                    }

                    val session = try {
                        PumpkinSession(response!!.access_token, response.refresh_token)
                    } catch (e: Exception) {
                        println("error: $e")
                        call.respondRedirect("/error")
                        return@get
                    }
                    call.sessions.set(session)
                    call.respondRedirect("/")
                }
            }
        }

        route("/api") {
            route("/v1") {
                post("/export") {
                    val request = call.receive<ExportRequest>()
                    val spotifyAccessToken = request.spotifyAccessToken
                    if (spotifyAccessToken.isBlank()) {
                        println("error: missing access token")
                        call.respond(
                            HttpStatusCode.BadRequest,
                            "{\"error\":\"missing access token\"}"
                        )
                        return@post
                    } else {
                        val shareLink = try {
                            pumpkinApi.importLibrary(spotifyAccessToken)
                        } catch (e: PumpkinApi.InvalidSpotifyAccessTokenException) {
                            println("error: invalid access token")
                            call.respond(
                                HttpStatusCode.Unauthorized
                            )
                            return@post
                        }
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
                        call.respond(
                            HttpStatusCode.BadRequest,
                            "{\"error\":\"invalid userId\"}"
                        )
                        return@get
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
