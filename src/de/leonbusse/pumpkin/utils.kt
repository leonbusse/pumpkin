package de.leonbusse.pumpkin


fun String.splitPath() = this.split("/").filter(String::isNotBlank)
