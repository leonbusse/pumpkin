package de.leonbusse.pumpkin

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.features.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.util.url
import kotlinx.html.*

const val AuthSessionKey = "AuthSession"
const val PumpkinSessionKey = "PumpkinSession"

class ServerSideWebApp(configuration: Configuration) {
    private val pumpkinApi = configuration.pumpkinApi
    private val client = configuration.client

    private val basicAuthToken = configuration.basicAuthToken
    private val baseUrl = configuration.baseUrl
    private val spotifyClientId = configuration.spotifyClientId
    private val spotifyScope = configuration.spotifyScope
    private val spotifyRedirectUri = configuration.spotifyRedirectUri

    class Configuration {
        lateinit var pumpkinApi: PumpkinApi
        lateinit var client: HttpClient

        lateinit var basicAuthToken: String
        lateinit var baseUrl: Url
        lateinit var spotifyClientId: String
        lateinit var spotifyScope: String
        lateinit var spotifyRedirectUri: String
    }

    companion object Feature :
        ApplicationFeature<ApplicationCallPipeline, Configuration, ServerSideWebApp> {
        override val key = AttributeKey<ServerSideWebApp>("ServerSideWebApp")

        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): ServerSideWebApp {
            val configuration = Configuration().apply(configure)
            val feature = ServerSideWebApp(configuration)
            return feature
        }
    }

    fun install(sessions: Sessions.Configuration) = sessions.apply {
        cookie<AuthSession>(AuthSessionKey)
        cookie<PumpkinSession>(PumpkinSessionKey)
    }

    fun install(statusPages: StatusPages.Configuration) = statusPages.apply {
        composeExceptionHandler<MissingSpotifyTokenException>({ call.request.accepts(ContentType.Text.Html) })
        { cause -> call.respondRedirect(cause.redirect) }

        suspend fun PipelineContext<*, ApplicationCall>.renderErrorPage(code: HttpStatusCode) {
            call.respondHtml {
                body {
                    h1 { +"Error ${code.value}" }
                    p { +code.description }
                }
            }
        }

        listOf(
            HttpStatusCode.NotFound,
            HttpStatusCode.BadRequest,
            HttpStatusCode.InternalServerError,
            HttpStatusCode.Unauthorized
        ).forEach {
            composeStatusHandler(it, { call.request.accepts(ContentType.Text.Html) })
            { code -> this.renderErrorPage(code) }
        }
    }

    fun install(routing: Routing) = routing.apply {

        get("/test-unauthorized") {
            call.respond(HttpStatusCode.Unauthorized)
        }

        get("/test-unauthorized-exception") {
            throw PumpkinApi.InvalidSpotifyAccessTokenException()
        }

        get("/clear-cookies") {
            call.sessions.clear<AuthSession>()
            call.sessions.clear<PumpkinSession>()
            call.respondRedirect("/")
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
            val session = requirePumpkinSession()

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
            val session = requirePumpkinSession()
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

            val session = requirePumpkinSession()
            if (session.userId == null || playlistName == null || libraryUserId == null) {
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


        static("/static") {
            resources("static")
        }
    }
}

fun Routing.install(app: ServerSideWebApp) = app.install(this)
fun StatusPages.Configuration.install(app: ServerSideWebApp) = app.install(this)
fun Sessions.Configuration.install(app: ServerSideWebApp) = app.install(this)


fun Route.requirePumpkinSession() {
    intercept(ApplicationCallPipeline.Features) {
        requirePumpkinSession()
    }
}

fun PipelineContext<Unit, ApplicationCall>.requirePumpkinSession(): PumpkinSession {
    val path = call.url()
    println("requirePumpkinSession: $path")

    val session = call.sessions.get<PumpkinSession>()
    if (session == null) {
        val redirectUrl = call.url {
            path("spotify", "login")
            parameters.append("redirect", call.url())
        }
        println("- no session, redirecting to $redirectUrl")
        throw MissingSpotifyTokenException(redirectUrl)
    } else {
        println("- has session, proceed")
        return session
    }
}
