package org.nanohttpd.protocols.websockets

import com.sun.org.apache.xml.internal.security.utils.Base64
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.logging.Logger

/*
 * #%L
 * NanoHttpd-Websocket
 * %%
 * Copyright (C) 2012 - 2016 nanohttpd
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
abstract class NanoWSD : NanoHTTPD {
    constructor(port: Int) : super(port) {
        addHTTPInterceptor { handleWebSocket(it) }
    }

    constructor(hostname: String?, port: Int) : super(hostname, port) {
        addHTTPInterceptor { handleWebSocket(it) }
    }

    private fun isWebsocketRequested(session: IHTTPSession) =
        HEADER_UPGRADE_VALUE.equals(session.headers[HEADER_UPGRADE], ignoreCase = true) &&
                session.headers[HEADER_CONNECTION]?.toLowerCase()
                    ?.contains(HEADER_CONNECTION_VALUE.toLowerCase()) ?: false

    protected abstract fun openWebSocket(handshake: IHTTPSession?): WebSocket

    fun handleWebSocket(session: IHTTPSession): Response? {
        val headers = session.headers
        return if (isWebsocketRequested(session)) {
            if (!HEADER_WEBSOCKET_VERSION_VALUE.equals(headers[HEADER_WEBSOCKET_VERSION], ignoreCase = true)) {
                return Response.newFixedLengthResponse(
                    Status.BAD_REQUEST, MIME_PLAINTEXT,
                    """Invalid Websocket-Version ${headers[HEADER_WEBSOCKET_VERSION]}"""
                )
            }
            if (!headers.containsKey(HEADER_WEBSOCKET_KEY)) {
                return Response.newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing Websocket-Key")
            }
            val webSocket = openWebSocket(session)
            val handshakeResponse = webSocket.handshakeResponse
            try {
                handshakeResponse.addHeader(HEADER_WEBSOCKET_ACCEPT, makeAcceptKey(headers[HEADER_WEBSOCKET_KEY]))
            } catch (e: NoSuchAlgorithmException) {
                return Response.newFixedLengthResponse(
                    Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "The SHA-1 Algorithm required for websockets is not available on the server."
                )
            }

            if (headers.containsKey(HEADER_WEBSOCKET_PROTOCOL)) {
                handshakeResponse.addHeader(
                    HEADER_WEBSOCKET_PROTOCOL,
                    headers[HEADER_WEBSOCKET_PROTOCOL]!!.split(",").toTypedArray()[0]
                )
            }

            handshakeResponse
        } else {
            null
        }
    }

    companion object {
        /**
         * logger to log to.
         */
        @JvmField
        val LOG: Logger = Logger.getLogger(NanoWSD::class.java.name)

        const val HEADER_UPGRADE = "upgrade"
        const val HEADER_UPGRADE_VALUE = "websocket"
        const val HEADER_CONNECTION = "connection"
        const val HEADER_CONNECTION_VALUE = "Upgrade"
        const val HEADER_WEBSOCKET_VERSION = "sec-websocket-version"
        const val HEADER_WEBSOCKET_VERSION_VALUE = "13"
        const val HEADER_WEBSOCKET_KEY = "sec-websocket-key"
        const val HEADER_WEBSOCKET_ACCEPT = "sec-websocket-accept"
        const val HEADER_WEBSOCKET_PROTOCOL = "sec-websocket-protocol"
        private const val WEBSOCKET_KEY_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        @Throws(NoSuchAlgorithmException::class)
        fun makeAcceptKey(key: String?): String {
            return Base64.encode(MessageDigest.getInstance("SHA-1").apply {
                val text = "$key$WEBSOCKET_KEY_MAGIC"
                update(text.toByteArray(), 0, text.length)
            }.digest())
        }
    }
}