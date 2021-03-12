package de.leonbusse.pumpkin

class AuthenticationException(inner: Exception) : Exception(inner)
class AuthorizationException : Exception()
class ConflictException : Exception()
class RateLimitedException(val retryAfter: Int, inner: Exception) : Exception(inner)
class ParseRetryAfterException() : Exception()