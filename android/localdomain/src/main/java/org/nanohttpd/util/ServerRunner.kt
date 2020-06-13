package org.nanohttpd.util

import org.nanohttpd.protocols.http.NanoHTTPD
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/*
 * #%L
 * NanoHttpd-Webserver
 * %%
 * Copyright (C) 2012 - 2015 nanohttpd
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

object ServerRunner {
    /**
     * logger to log to.
     */
    private val LOG = Logger.getLogger(ServerRunner::class.java.name)

    @Suppress("MemberVisibilityCanBePrivate")
    fun executeInstance(server: NanoHTTPD) {
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (ioe: IOException) {
            System.err.println("Couldn't start server:\n$ioe")
            System.exit(-1)
        }

        println("Server started, Hit Enter to stop.\n")

        try {
            System.`in`.read()
        } catch (ignored: Throwable) {
        }

        server.stop()
        println("Server stopped.\n")
    }

    fun <T : NanoHTTPD> run(serverClass: Class<T>) {
        try {
            executeInstance(serverClass.newInstance())
        } catch (e: Exception) {
            LOG.log(Level.SEVERE, "Could not create server", e)
        }
    }
}