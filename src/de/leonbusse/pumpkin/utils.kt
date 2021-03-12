package de.leonbusse.pumpkin

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.util.pipeline.*
import java.util.*


fun <T> List<T>.alsoForEach(block: (T) -> Unit): List<T> {
    this.forEach(block)
    return this
}

fun String.splitPath() = this.split("/").filter(String::isNotBlank)

const val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
fun generateRandomString(length: Int): String = (1..length)
    .map { i -> kotlin.random.Random.nextInt(0, charPool.length) }
    .map(charPool::get)
    .joinToString("")

fun String.base64(): String = Base64.getEncoder().encodeToString(this.toByteArray())

fun ApplicationRequest.accepts(contentType: ContentType): Boolean {
    val acceptList = call.request.header(HttpHeaders.Accept)?.split(",")
    return acceptList != null && contentType.toString() in acceptList
}

fun StatusPages.Configuration.composeStatusHandler(
    statusCode: HttpStatusCode,
    condition: suspend PipelineContext<*, ApplicationCall>.() -> Boolean,
    handler: suspend PipelineContext<*, ApplicationCall>.(HttpStatusCode) -> Unit
) {
    val previousStatusHandler = statuses[statusCode]
    status(statusCode) { code ->
        if (condition.invoke(this)) {
            handler.invoke(this, statusCode)
        } else {
            previousStatusHandler?.invoke(this, code)
        }
    }
}

inline fun <reified T : Throwable> StatusPages.Configuration.composeExceptionHandler(
    crossinline condition: suspend PipelineContext<*, ApplicationCall>.() -> Boolean,
    crossinline handler: suspend PipelineContext<*, ApplicationCall>.(T) -> Unit
) {
    val previousExceptionHandler = exceptions[T::class.java]
    exception<T> { c ->
        if (condition.invoke(this)) {
            handler.invoke(this, c)
        } else {
            previousExceptionHandler?.invoke(this, c)
        }
    }
}