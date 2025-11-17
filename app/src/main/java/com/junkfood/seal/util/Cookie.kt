package com.junkfood.seal.util

data class Cookie(
    val domain: String,
    val name: String,
    val value: String,
    val path: String = "/",
    val secure: Boolean = false,
    val expiry: Long = 0L
) {
    fun toNetscapeCookieString(): String {
        return "$domain\tTRUE\t$path\t${if (secure) "TRUE" else "FALSE"}\t$expiry\t$name\t$value"
    }
}
