package org.nanohttpd.protocols.http

import org.nanohttpd.protocols.http.NanoHTTPD
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.logging.Level

/*
 * #%L
 * NanoHttpd-Core
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

/**
 * The runnable that will be used for every new client connection.
 */
class ClientHandler(
    private val httpd: NanoHTTPD,
    private val inputStream: InputStream,
    private val acceptSocket: Socket
) : Runnable {
    fun close() {
        NanoHTTPD.safeClose(inputStream)
        NanoHTTPD.safeClose(acceptSocket)
    }

    override fun run() {
        var outputStream: OutputStream? = null
        try {
            outputStream = acceptSocket.getOutputStream()
            val tempFileManager = httpd.tempFileManagerFactory?.create()
            val session = HTTPSession(
                httpd,
                tempFileManager,
                inputStream,
                outputStream,
                acceptSocket.inetAddress
            )
            while (!acceptSocket.isClosed) {
                session.execute()
            }
        } catch (e: Exception) {
            // When the socket is closed by the client,
            // we throw our own SocketException
            // to break the "keep alive" loop above. If
            // the exception was anything other
            // than the expected SocketException OR a
            // SocketTimeoutException, print the
            // stacktrace
            if (!(e is SocketException && "NanoHttpd Shutdown" == e.message) && e !is SocketTimeoutException) {
                NanoHTTPD.LOG.log(Level.SEVERE, "Communication with the client broken, or an bug in the handler code", e)
            }
        } finally {
            NanoHTTPD.safeClose(outputStream)
            NanoHTTPD.safeClose(inputStream)
            NanoHTTPD.safeClose(acceptSocket)
            httpd.asyncRunner?.closed(this)
        }
    }

}