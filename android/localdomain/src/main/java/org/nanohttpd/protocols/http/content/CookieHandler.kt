package org.nanohttpd.protocols.http.content

import org.nanohttpd.protocols.http.content.Cookie.Companion.getHTTPTime
import org.nanohttpd.protocols.http.response.Response

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
 * Provides rudimentary support for cookies. Doesn't support 'path', 'secure'
 * nor 'httpOnly'. Feel free to improve it and/or add unsupported features. This
 * is old code and it's flawed in many ways.
 *
 * @author LordFokas
 */
class CookieHandler(httpHeaders: Map<String?, String?>) : Iterable<String?> {
    private val cookies: MutableMap<String, String> = mutableMapOf()
    private val queue: MutableList<Cookie> = mutableListOf()

    init {
        httpHeaders["cookie"]?.let {
            for (token in it.split(";".toRegex()).toTypedArray()) {
                val data = token.trim { t -> t <= ' ' }
                    .split("=".toRegex()).toTypedArray()
                if (data.size == 2) {
                    cookies[data[0]] = data[1]
                }
            }
        }
    }

    /**
     * Set a cookie with an expiration date from a month ago, effectively
     * deleting it on the client side.
     *
     * @param name The cookie name.
     */
    fun delete(name: String) {
        set(name, "-delete-", -30)
    }

    override fun iterator(): MutableIterator<String> = cookies.keys.iterator()

    /**
     * Read a cookie from the HTTP Headers.
     *
     * @param name The cookie's name.
     * @return The cookie's value if it exists, null otherwise.
     */
    fun read(name: String): String? = cookies[name]

    fun set(cookie: Cookie) = queue.add(cookie)

    /**
     * Sets a cookie.
     *
     * @param name The cookie's name.
     * @param value The cookie's value.
     * @param expires How many days until the cookie expires.
     */
    operator fun set(name: String, value: String, expires: Int) = queue.add(
        Cookie(name, value, getHTTPTime(expires))
    )

    /**
     * Internally used by the webserver to add all queued cookies into the
     * Response's HTTP Headers.
     *
     * @param response
     * The Response object to which headers the queued cookies will
     * be added.
     */
    fun unloadQueue(response: Response) {
        queue.forEach {
            response.addCookieHeader(it.httpHeader)
        }
    }
}