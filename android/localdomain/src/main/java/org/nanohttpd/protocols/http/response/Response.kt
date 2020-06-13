package org.nanohttpd.protocols.http.response

import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.content.ContentType
import org.nanohttpd.protocols.http.request.Method
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.logging.Level
import java.util.zip.GZIPOutputStream

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
 * HTTP response. Return one of these from serve().
 */
class Response protected constructor(
    var status: Status?,
    var mimeType: String?,
    data: InputStream?,
    totalBytes: Long
) : Closeable {

    /**
     * Data of the response, may be null.
     */
    var data: InputStream? = null
    private var contentLength: Long = 0

    /**
     * Headers for the HTTP response. Use addHeader() to add lines. the
     * lowercase map is automatically kept up to date.
     */
    private val header: MutableMap<String, String> = mutableMapOf()

    /**
     * copy of the header map with all the keys lowercase for faster searching.
     */
    private val lowerCaseHeader: MutableMap<String, String> = mutableMapOf()

    /**
     * The request method that spawned this response.
     */
    var requestMethod: Method? = null

    /**
     * Use chunkedTransfer
     */
    private var chunkedTransfer: Boolean
    private var keepAlive: Boolean
    private val cookieHeaders: MutableList<String?>
    private var gzipUsage = GzipUsage.DEFAULT

    private enum class GzipUsage {
        DEFAULT, ALWAYS, NEVER
    }

    @Throws(IOException::class)
    override fun close() {
        if (data != null) {
            data!!.close()
        }
    }

    /**
     * Adds a cookie header to the list. Should not be called manually, this is
     * an internal utility.
     */
    fun addCookieHeader(cookie: String?) {
        cookieHeaders.add(cookie)
    }

    /**
     * Should not be called manually. This is an internally utility for JUnit
     * test purposes.
     *
     * @return All unloaded cookie headers.
     */
    fun getCookieHeaders(): List<String?> {
        return cookieHeaders
    }

    /**
     * Adds given line to the header.
     */
    fun addHeader(name: String, value: String) {
        header[name] = value
        lowerCaseHeader[name.toLowerCase()] = value
    }

    /**
     * Indicate to close the connection after the Response has been sent.
     *
     * @param close
     * `true` to hint connection closing, `false` to let
     * connection be closed by client.
     */
    fun closeConnection(close: Boolean) {
        if (close) header["connection"] = "close" else header.remove("connection")
    }

    /**
     * @return `true` if connection is to be closed after this Response
     * has been sent.
     */
    val isCloseConnection: Boolean
        get() = "close" == getHeader("connection")

    fun getHeader(name: String): String? {
        return lowerCaseHeader[name.toLowerCase()]
    }

    fun setKeepAlive(useKeepAlive: Boolean) {
        keepAlive = useKeepAlive
    }

    /**
     * Sends given response to the socket.
     */
    fun send(outputStream: OutputStream) {
        val gmtFrmt =
            SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        gmtFrmt.timeZone = TimeZone.getTimeZone("GMT")
        try {
            if (status == null) {
                throw Error("sendResponse(): Status can't be null.")
            }
            val pw = PrintWriter(
                BufferedWriter(
                    OutputStreamWriter(
                        outputStream,
                        ContentType(mimeType)
                            .getEncoding()
                    )
                ), false
            )
            pw.append("HTTP/1.1 ").append(status!!.getDescription()).append(" \r\n")
            if (mimeType != null) {
                printHeader(pw, "Content-Type", mimeType)
            }
            if (getHeader("date") == null) {
                printHeader(pw, "Date", gmtFrmt.format(Date()))
            }
            for ((key, value) in header) {
                printHeader(pw, key, value)
            }
            for (cookieHeader in cookieHeaders) {
                printHeader(pw, "Set-Cookie", cookieHeader)
            }
            if (getHeader("connection") == null) {
                printHeader(pw, "Connection", if (keepAlive) "keep-alive" else "close")
            }
            if (getHeader("content-length") != null) {
                setUseGzip(false)
            }
            if (useGzipWhenAccepted()) {
                printHeader(pw, "Content-Encoding", "gzip")
                setChunkedTransfer(true)
            }
            var pending = if (data != null) contentLength else 0
            if (requestMethod !== Method.HEAD && chunkedTransfer) {
                printHeader(pw, "Transfer-Encoding", "chunked")
            } else if (!useGzipWhenAccepted()) {
                pending = sendContentLengthHeaderIfNotAlreadyPresent(pw, pending)
            }
            pw.append("\r\n")
            pw.flush()
            sendBodyWithCorrectTransferAndEncoding(outputStream, pending)
            outputStream.flush()
            NanoHTTPD.safeClose(data)
        } catch (ioe: IOException) {
            NanoHTTPD.LOG.log(
                Level.SEVERE,
                "Could not send response to the client",
                ioe
            )
        }
    }

    protected fun printHeader(
        pw: PrintWriter,
        key: String?,
        value: String?
    ) {
        pw.append(key).append(": ").append(value).append("\r\n")
    }

    protected fun sendContentLengthHeaderIfNotAlreadyPresent(
        pw: PrintWriter,
        defaultSize: Long
    ): Long {
        val contentLengthString = getHeader("content-length")
        var size = defaultSize
        if (contentLengthString != null) {
            try {
                size = contentLengthString.toLong()
            } catch (ex: NumberFormatException) {
                NanoHTTPD.LOG.severe("content-length was no number $contentLengthString")
            }
        } else {
            pw.print("Content-Length: $size\r\n")
        }
        return size
    }

    @Throws(IOException::class)
    private fun sendBodyWithCorrectTransferAndEncoding(
        outputStream: OutputStream,
        pending: Long
    ) {
        if (requestMethod !== Method.HEAD && chunkedTransfer) {
            val chunkedOutputStream =
                ChunkedOutputStream(outputStream)
            sendBodyWithCorrectEncoding(chunkedOutputStream, -1)
            try {
                chunkedOutputStream.finish()
            } catch (e: Exception) {
                if (data != null) {
                    data!!.close()
                }
            }
        } else {
            sendBodyWithCorrectEncoding(outputStream, pending)
        }
    }

    @Throws(IOException::class)
    private fun sendBodyWithCorrectEncoding(
        outputStream: OutputStream,
        pending: Long
    ) {
        if (useGzipWhenAccepted()) {
            var gzipOutputStream: GZIPOutputStream? = null
            try {
                gzipOutputStream = GZIPOutputStream(outputStream)
            } catch (e: Exception) {
                if (data != null) {
                    data!!.close()
                }
            }
            if (gzipOutputStream != null) {
                sendBody(gzipOutputStream, -1)
                gzipOutputStream.finish()
            }
        } else {
            sendBody(outputStream, pending)
        }
    }

    /**
     * Sends the body to the specified OutputStream. The pending parameter
     * limits the maximum amounts of bytes sent unless it is -1, in which case
     * everything is sent.
     *
     * @param outputStream
     * the OutputStream to send data to
     * @param pending
     * -1 to send everything, otherwise sets a max limit to the
     * number of bytes sent
     * @throws IOException
     * if something goes wrong while sending the data.
     */
    @Throws(IOException::class)
    private fun sendBody(outputStream: OutputStream, pending: Long) {
        var pending = pending
        val BUFFER_SIZE = 16 * 1024.toLong()
        val buff = ByteArray(BUFFER_SIZE.toInt())
        val sendEverything = pending == -1L
        while (pending > 0 || sendEverything) {
            val bytesToRead =
                if (sendEverything) BUFFER_SIZE else Math.min(pending, BUFFER_SIZE)
            val read = data!!.read(buff, 0, bytesToRead.toInt())
            if (read <= 0) {
                break
            }
            try {
                outputStream.write(buff, 0, read)
            } catch (e: Exception) {
                if (data != null) {
                    data!!.close()
                }
            }
            if (!sendEverything) {
                pending -= read.toLong()
            }
        }
    }

    fun setChunkedTransfer(chunkedTransfer: Boolean) {
        this.chunkedTransfer = chunkedTransfer
    }

    fun setUseGzip(useGzip: Boolean): Response {
        gzipUsage = if (useGzip) GzipUsage.ALWAYS else GzipUsage.NEVER
        return this
    }

    // If a Gzip usage has been enforced, use it.
    // Else decide whether or not to use Gzip.
    fun useGzipWhenAccepted(): Boolean {
        return if (gzipUsage == GzipUsage.DEFAULT) mimeType != null && (mimeType!!.toLowerCase()
            .contains("text/") || mimeType!!.toLowerCase()
            .contains("/json")) else gzipUsage == GzipUsage.ALWAYS
    }

    companion object {
        /**
         * Create a response with unknown length (using HTTP 1.1 chunking).
         */
        @JvmStatic
        fun newChunkedResponse(
            status: Status?,
            mimeType: String?,
            data: InputStream?
        ): Response {
            return Response(status, mimeType, data, -1)
        }

        @JvmStatic
        fun newFixedLengthResponse(
            status: Status?,
            mimeType: String?,
            data: ByteArray
        ): Response {
            return newFixedLengthResponse(
                status,
                mimeType,
                ByteArrayInputStream(data),
                data.size.toLong()
            )
        }

        /**
         * Create a response with known length.
         */
        @JvmStatic
        fun newFixedLengthResponse(
            status: Status?,
            mimeType: String?,
            data: InputStream?,
            totalBytes: Long
        ): Response {
            return Response(
                status,
                mimeType,
                data,
                totalBytes
            )
        }

        /**
         * Create a text response with known length.
         */
        @JvmStatic
        fun newFixedLengthResponse(
            status: Status?,
            mimeType: String?,
            txt: String?
        ): Response {
            var contentType =
                ContentType(mimeType)
            return if (txt == null) {
                newFixedLengthResponse(
                    status,
                    mimeType,
                    ByteArrayInputStream(ByteArray(0)),
                    0
                )
            } else {
                var bytes: ByteArray
                try {
                    val newEncoder =
                        Charset.forName(contentType.getEncoding()).newEncoder()
                    if (!newEncoder.canEncode(txt)) {
                        contentType = contentType.tryUTF8()
                    }
                    bytes = txt.toByteArray(charset(contentType.getEncoding()))
                } catch (e: UnsupportedEncodingException) {
                    NanoHTTPD.LOG.log(
                        Level.SEVERE,
                        "encoding problem, responding nothing",
                        e
                    )
                    bytes = ByteArray(0)
                }
                newFixedLengthResponse(
                    status,
                    contentType.contentTypeHeader,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        }

        /**
         * Create a text response with known length.
         */
        @JvmStatic
        fun newFixedLengthResponse(msg: String?): Response {
            return newFixedLengthResponse(
                Status.OK,
                NanoHTTPD.MIME_HTML,
                msg
            )
        }
    }

    /**
     * Creates a fixed length response if totalBytes>=0, otherwise chunked.
     */
    init {
        if (data == null) {
            this.data = ByteArrayInputStream(ByteArray(0))
            contentLength = 0L
        } else {
            this.data = data
            contentLength = totalBytes
        }
        chunkedTransfer = contentLength < 0
        keepAlive = true
        cookieHeaders = mutableListOf()
    }
}