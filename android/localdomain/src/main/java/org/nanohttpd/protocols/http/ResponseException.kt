package org.nanohttpd.protocols.http

import org.nanohttpd.protocols.http.response.Status

class ResponseException : Exception {
    val status: Status

    constructor(
        status: Status,
        message: String?
    ) : super(message) {
        this.status = status
    }

    constructor(
        status: Status,
        message: String?,
        e: Exception?
    ) : super(message, e) {
        this.status = status
    }

    companion object {
        private const val serialVersionUID = 6569838532917408380L
    }
}