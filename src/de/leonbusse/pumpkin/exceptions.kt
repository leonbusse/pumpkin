package de.leonbusse.pumpkin

class MissingSpotifyTokenException(val redirect: String) : IllegalArgumentException("A Spotify token is required fort this route.")