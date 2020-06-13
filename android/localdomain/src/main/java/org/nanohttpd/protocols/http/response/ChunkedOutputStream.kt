package org.nanohttpd.protocols.http.response

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

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
 * Output stream that will automatically send every write to the wrapped
 * OutputStream according to chunked transfer:
 * http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
 */
class ChunkedOutputStream(out: OutputStream?) : FilterOutputStream(out) {

    @Throws(IOException::class)
    override fun write(b: Int) =
        write(byteArrayOf(b.toByte()), 0, 1)

    @Throws(IOException::class)
    override fun write(b: ByteArray) =
        write(b, 0, b.size)

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        out.write(String.format("%x\r\n", len).toByteArray())
        out.write(b, off, len)
        out.write("\r\n".toByteArray())
    }

    @Throws(IOException::class)
    fun finish() =
        out.write("0\r\n\r\n".toByteArray())
}