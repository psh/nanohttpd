package org.nanohttpd.protocols.http.threading

import org.nanohttpd.protocols.http.ClientHandler
import java.util.ArrayList
import java.util.Collections

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
 * Default threading strategy for NanoHTTPD.
 *
 * By default, the server spawns a new Thread for every incoming request. These
 * are set to *daemon* status, and named according to the request number.
 * The name is useful when profiling the application.
 */
open class DefaultAsyncRunner : IAsyncRunner {
    private var requestCount: Long = 0
    private val running = Collections.synchronizedList(ArrayList<ClientHandler>())

    /**
     * @return a list with currently running clients.
     */
    fun getRunning(): List<ClientHandler?> = running

    override fun closeAll() {
        // copy of the list for concurrency
        ArrayList(running).forEach { it.close() }
    }

    override fun closed(clientHandler: ClientHandler?) {
        running.remove(clientHandler)
    }

    override fun exec(code: ClientHandler?) {
        ++requestCount
        running.add(code)
        createThread(code).start()
    }

    private fun createThread(clientHandler: ClientHandler?): Thread {
        return Thread(clientHandler).apply {
            isDaemon = true
            name = "NanoHttpd Request Processor (#$requestCount)"
        }
    }
}