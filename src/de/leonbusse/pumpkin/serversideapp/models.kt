package de.leonbusse.pumpkin.serversideapp


/** Session models **/

data class AuthSession(val state: String, val redirect: String?)

data class PumpkinSession(
    val spotifyAccessToken: String,
    val spotifyRefreshToken: String,
    val userId: String?
)
