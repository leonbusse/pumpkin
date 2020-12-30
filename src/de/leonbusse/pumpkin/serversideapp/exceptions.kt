package de.leonbusse.pumpkin.serversideapp

class MissingSpotifyTokenException(val redirect: String) : IllegalArgumentException("A Spotify token is required fort this route.")