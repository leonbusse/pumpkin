package de.leonbusse.pumpkin

import io.ktor.routing.*


fun String.splitPath() = this.split("/").filter(String::isNotBlank)

val Route.path
    get() = parent.toString()
