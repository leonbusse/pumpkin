package de.leonbusse.pumpkin

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.features.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.*
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.p
import java.util.*

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

const val AuthSessionKey = "AuthSession"
const val PumpkinSessionKey = "PumpkinSession"

private val client: HttpClient by lazy {
    HttpClient(CIO) {
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
    }
}

private object db {
    private val libraries = mutableMapOf<String, SpotifyLibrary>()

    fun saveSpotifyLibrary(library: SpotifyLibrary) {
        libraries[library.user.id] = library
    }

    fun loadLibrary(userId: String): SpotifyLibrary? {
        return libraries[userId]
    }
}

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    val spotifyClientId = environment.config.property("spotify.clientId").getString()
    val spotifyClientSecret = environment.config.property("spotify.clientSecret").getString()
    val spotifyRedirectUri = environment.config.property("spotify.redirectUri").getString()
    val spotifyScope = "user-read-private playlist-read-private user-read-email user-library-read"

    println("spotifyClientId: $spotifyClientId")
    println("spotifyClientSecret: $spotifyClientSecret")
    println("spotifyRedirectUri: $spotifyRedirectUri")

    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
    }

    install(ContentNegotiation) {
    }

    install(Sessions) {
        cookie<AuthSession>(AuthSessionKey)
        cookie<PumpkinSession>(PumpkinSessionKey)
    }

    routing {

        get("/") {
            val accessToken = call.sessions.get<PumpkinSession>()?.spotifyAccessToken
            println("accessToken: $accessToken")
            if (accessToken.isNullOrEmpty()) {
                call.respondHtml {
                    body {
                        h1 { +"Pumpkin - Share your Spotify library with friends" }
                        a(href = "/login") { +"Login with Spotify" }
                    }
                }
            } else {
                call.respondHtml {
                    body {
                        h1 { +"Pumpkin - Share your Spotify library with friends" }
                        a(href = "/export") { +"Generate link to my library" }
                    }
                }
            }
        }

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

        get("/spotify-callback") {
            val state = call.sessions.get<AuthSession>()?.state
            val code = call.parameters["code"]

            if (call.parameters["state"] == null ||
                call.parameters["state"] != state ||
                code.isNullOrEmpty()
            ) {
                println("error: auth state did not match")
                call.respondRedirect("/error")
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
                println("!!! response: $response")
                call.sessions.set(session)
                call.respondRedirect("/")
            }
        }

        get("/export") {
            val accessToken = call.sessions.get<PumpkinSession>()?.spotifyAccessToken
            if (accessToken.isNullOrEmpty()) {
                println("error: missing access token")
                call.respondRedirect("/error")
            } else {
                val spotifyLibrary = SpotifyImporter(client, accessToken).import()
                db.saveSpotifyLibrary(spotifyLibrary)

                val shareLink = generateShareLink(spotifyLibrary)

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
        }

        get("/share/{userId}") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrEmpty()) {
                println("error: invalid userId")
                call.respondRedirect("/error")
                return@get
            }
            val library = db.loadLibrary(userId)
            if (library == null) {
                println("error: no library found for $userId")
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

        // Static feature. Try to access `/static/ktor_logo.svg`
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

fun generateShareLink(library: SpotifyLibrary): String = "http://localhost:8080/share/${library.user.id}"
