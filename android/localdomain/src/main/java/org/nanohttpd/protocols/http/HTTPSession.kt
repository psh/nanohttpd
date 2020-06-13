package org.nanohttpd.protocols.http

import org.nanohttpd.protocols.http.NanoHTTPD.ResponseException
import org.nanohttpd.protocols.http.content.ContentType
import org.nanohttpd.protocols.http.content.CookieHandler
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.request.Method.Companion.lookup
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.Companion.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import org.nanohttpd.protocols.http.tempfiles.ITempFileManager
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.HashMap
import java.util.Locale
import java.util.StringTokenizer
import java.util.logging.Level
import javax.net.ssl.SSLException

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

class HTTPSession : IHTTPSession {
    private val httpd: NanoHTTPD
    private val tempFileManager: ITempFileManager
    private val outputStream: OutputStream
    private var splitbyte = 0
    private var rlen = 0
    private var protocolVersion: String? = null
    override var uri: String? = null
    override var method: Method? = null
    override var cookies: CookieHandler? = null
    override var queryParameterString: String? = null
    override var remoteIpAddress: String? = null
    override var headers: MutableMap<String?, String?>? = null
    override var inputStream: InputStream? = null

    // override var parms: MutableMap<String, String> = mutableMapOf()
    private var parms2: MutableMap<String, MutableList<String>> = mutableMapOf()

    constructor(
        httpd: NanoHTTPD,
        tempFileManager: ITempFileManager,
        inputStream: InputStream?,
        outputStream: OutputStream
    ) {
        this.httpd = httpd
        this.tempFileManager = tempFileManager
        this.inputStream = BufferedInputStream(inputStream, BUFSIZE)
        this.outputStream = outputStream
    }

    constructor(
        httpd: NanoHTTPD,
        tempFileManager: ITempFileManager,
        inputStream: InputStream?,
        outputStream: OutputStream,
        inetAddress: InetAddress
    ) {
        this.httpd = httpd
        this.tempFileManager = tempFileManager
        this.inputStream = BufferedInputStream(inputStream, BUFSIZE)
        this.outputStream = outputStream
        remoteIpAddress =
            if (inetAddress.isLoopbackAddress || inetAddress.isAnyLocalAddress) "127.0.0.1" else inetAddress.toString()
        headers = HashMap()
    }

    /**
     * Decodes the sent headers and loads the data into Key/value pairs
     */
    @Throws(ResponseException::class)
    private fun decodeHeader(
        `in`: BufferedReader,
        pre: MutableMap<String, String>,
        parms: MutableMap<String, MutableList<String>>,
        headers: MutableMap<String?, String?>?
    ) {
        try {
            // Read the request line
            val inLine = `in`.readLine() ?: return
            val st = StringTokenizer(inLine)
            if (!st.hasMoreTokens()) {
                throw ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: Syntax error. Usage: GET /example/file.html"
                )
            }
            pre["method"] = st.nextToken()
            if (!st.hasMoreTokens()) {
                throw ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: Missing URI. Usage: GET /example/file.html"
                )
            }
            var uri = st.nextToken()

            // Decode parameters from the URI
            val qmi = uri.indexOf('?')
            uri = if (qmi >= 0) {
                decodeParms(uri.substring(qmi + 1), parms)
                NanoHTTPD.decodePercent(uri.substring(0, qmi))
            } else {
                NanoHTTPD.decodePercent(uri)
            }

            // If there's another token, its protocol version,
            // followed by HTTP headers.
            // NOTE: this now forces header names lower case since they are
            // case insensitive and vary by client.
            if (st.hasMoreTokens()) {
                protocolVersion = st.nextToken()
            } else {
                protocolVersion = "HTTP/1.1"
                NanoHTTPD.LOG.log(
                    Level.FINE,
                    "no protocol version specified, strange. Assuming HTTP/1.1."
                )
            }
            var line = `in`.readLine()
            while (line != null && !line.trim { it <= ' ' }.isEmpty()) {
                val p = line.indexOf(':')
                if (p >= 0) {
                    headers!![line.substring(0, p).trim { it <= ' ' }.toLowerCase(Locale.US)] =
                        line.substring(p + 1).trim { it <= ' ' }
                }
                line = `in`.readLine()
            }
            pre["uri"] = uri
        } catch (ioe: IOException) {
            throw ResponseException(
                Status.INTERNAL_ERROR,
                "SERVER INTERNAL ERROR: IOException: " + ioe.message,
                ioe
            )
        }
    }

    /**
     * Decodes the Multipart Body data and put it into Key/Value pairs.
     */
    @Throws(ResponseException::class)
    private fun decodeMultipartFormData(
        contentType: ContentType,
        fbuf: ByteBuffer,
        parms: MutableMap<String, MutableList<String>>,
        files: MutableMap<String, String>
    ) {
        var pcount = 0
        try {
            val boundaryIdxs =
                getBoundaryPositions(fbuf, contentType.boundary!!.toByteArray())
            if (boundaryIdxs.size < 2) {
                throw ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: Content type is multipart/form-data but contains less than two boundary strings."
                )
            }
            val partHeaderBuff =
                ByteArray(MAX_HEADER_SIZE)
            for (boundaryIdx in 0 until boundaryIdxs.size - 1) {
                fbuf.position(boundaryIdxs[boundaryIdx])
                val len =
                    Math.min(fbuf.remaining(), MAX_HEADER_SIZE)
                fbuf[partHeaderBuff, 0, len]
                val `in` = BufferedReader(
                    InputStreamReader(
                        ByteArrayInputStream(
                            partHeaderBuff,
                            0,
                            len
                        ), Charset.forName(contentType.getEncoding())
                    ), len
                )
                var headerLines = 0
                // First line is boundary string
                var mpline = `in`.readLine()
                headerLines++
                if (mpline == null || !mpline.contains(contentType.boundary!!)) {
                    throw ResponseException(
                        Status.BAD_REQUEST,
                        "BAD REQUEST: Content type is multipart/form-data but chunk does not start with boundary."
                    )
                }
                var partName: StringBuilder? = null
                var fileName: String? = null
                var partContentType: String? = null
                // Parse the reset of the header lines
                mpline = `in`.readLine()
                headerLines++
                while (mpline != null && mpline.trim { it <= ' ' }.length > 0) {
                    var matcher =
                        NanoHTTPD.CONTENT_DISPOSITION_PATTERN.matcher(mpline)
                    if (matcher.matches()) {
                        val attributeString = matcher.group(2)
                        matcher =
                            NanoHTTPD.CONTENT_DISPOSITION_ATTRIBUTE_PATTERN.matcher(attributeString)
                        while (matcher.find()) {
                            val key = matcher.group(1)
                            if ("name".equals(key, ignoreCase = true)) {
                                partName = StringBuilder(matcher.group(2))
                            } else if ("filename".equals(key, ignoreCase = true)) {
                                fileName = matcher.group(2)
                                // add these two line to support multiple
                                // files uploaded using the same field Id
                                if (!fileName.isEmpty()) {
                                    if (pcount > 0) partName!!.append(pcount++) else pcount++
                                }
                            }
                        }
                    }
                    matcher = NanoHTTPD.CONTENT_TYPE_PATTERN.matcher(mpline)
                    if (matcher.matches()) {
                        partContentType = matcher.group(2).trim { it <= ' ' }
                    }
                    mpline = `in`.readLine()
                    headerLines++
                }
                var partHeaderLength = 0
                while (headerLines-- > 0) {
                    partHeaderLength = scipOverNewLine(partHeaderBuff, partHeaderLength)
                }
                // Read the part data
                if (partHeaderLength >= len - 4) {
                    throw ResponseException(
                        Status.INTERNAL_ERROR,
                        "Multipart header size exceeds MAX_HEADER_SIZE."
                    )
                }
                val partDataStart = boundaryIdxs[boundaryIdx] + partHeaderLength
                val partDataEnd = boundaryIdxs[boundaryIdx + 1] - 4
                fbuf.position(partDataStart)
                if (partContentType == null) {
                    // Read the part into a string
                    val data_bytes = ByteArray(partDataEnd - partDataStart)
                    fbuf[data_bytes]
                    val key = partName.toString()
                    val element = String(data_bytes, Charset.forName(contentType.getEncoding()))
                    parms.getOrPut(key, { mutableListOf() }).add(element)
                } else {
                    // Read it into a file
                    val path =
                        saveTmpFile(fbuf, partDataStart, partDataEnd - partDataStart, fileName)
                    if (!files.containsKey(partName.toString())) {
                        files[partName.toString()] = path
                    } else {
                        var count = 2
                        while (files.containsKey(partName.toString() + count)) {
                            count++
                        }
                        files.put(partName.toString() + count, path)
                    }
                    fileName?.let {
                        parms.getOrPut(partName.toString(), { mutableListOf() }).add(it)
                    }
                }
            }
        } catch (re: ResponseException) {
            throw re
        } catch (e: Exception) {
            throw ResponseException(
                Status.INTERNAL_ERROR,
                e.toString()
            )
        }
    }

    private fun scipOverNewLine(partHeaderBuff: ByteArray, index: Int): Int {
        var index = index
        while (partHeaderBuff[index].toChar() != '\n') {
            index++
        }
        return ++index
    }

    /**
     * Decodes parameters in percent-encoded URI-format ( e.g.
     * "name=Jack%20Daniels&pass=Single%20Malt" ) and adds them to given Map.
     */
    private fun decodeParms(
        parms: String?,
        p: MutableMap<String, MutableList<String>>
    ) {
        if (parms == null) {
            queryParameterString = ""
            return
        }
        queryParameterString = parms
        val st = StringTokenizer(parms, "&")
        while (st.hasMoreTokens()) {
            val e = st.nextToken()
            val sep = e.indexOf('=')
            var key: String
            var value: String?
            if (sep >= 0) {
                key = NanoHTTPD.decodePercent(e.substring(0, sep)).trim { it <= ' ' }
                value = NanoHTTPD.decodePercent(e.substring(sep + 1))
            } else {
                key = NanoHTTPD.decodePercent(e).trim { it <= ' ' }
                value = ""
            }
            value?.let {
                val list = p.getOrPut(key, { mutableListOf() })
                list.add(it)
            }
        }
    }

    @Throws(IOException::class)
    override fun execute() {
        var r: Response? = null
        try {
            // Read the first 8192 bytes.
            // The full header should fit in here.
            // Apache's default header limit is 8KB.
            // Do NOT assume that a single read will get the entire header
            // at once!
            val buf = ByteArray(BUFSIZE)
            splitbyte = 0
            rlen = 0
            var read: Int
            inputStream!!.mark(BUFSIZE)
            read = try {
                inputStream!!.read(buf, 0, BUFSIZE)
            } catch (e: SSLException) {
                throw e
            } catch (e: IOException) {
                NanoHTTPD.safeClose(inputStream)
                NanoHTTPD.safeClose(outputStream)
                throw SocketException("NanoHttpd Shutdown")
            }
            if (read == -1) {
                // socket was been closed
                NanoHTTPD.safeClose(inputStream)
                NanoHTTPD.safeClose(outputStream)
                throw SocketException("NanoHttpd Shutdown")
            }
            while (read > 0) {
                rlen += read
                splitbyte = findHeaderEnd(buf, rlen)
                if (splitbyte > 0) {
                    break
                }
                read = inputStream!!.read(buf, rlen, BUFSIZE - rlen)
            }
            if (splitbyte < rlen) {
                inputStream!!.reset()
                inputStream!!.skip(splitbyte.toLong())
            }
            if (null == headers) {
                headers = mutableMapOf()
            } else {
                headers!!.clear()
            }

            // Create a BufferedReader for parsing the header.
            val hin = BufferedReader(
                InputStreamReader(
                    ByteArrayInputStream(
                        buf,
                        0,
                        rlen
                    )
                )
            )

            // Decode the header into parms and header java properties
            val pre: MutableMap<String, String> = mutableMapOf()
            decodeHeader(hin, pre, parms2, headers)
            if (null != remoteIpAddress) {
                headers!!["remote-addr"] = remoteIpAddress
                headers!!["http-client-ip"] = remoteIpAddress
            }
            method = lookup(pre["method"])
            if (method == null) {
                throw ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: Syntax error. HTTP verb " + pre["method"] + " unhandled."
                )
            }
            uri = pre["uri"]
            cookies = CookieHandler(headers!!)
            val connection = headers!!["connection"]
            val keepAlive =
                "HTTP/1.1" == protocolVersion && (connection == null || !connection.matches("(?i).*close.*".toRegex()))

            // Ok, now do the serve()

            // TODO: long body_size = getBodySize();
            // TODO: long pos_before_serve = this.inputStream.totalRead()
            // (requires implementation for totalRead())
            r = httpd.handle(this)
            // TODO: this.inputStream.skip(body_size -
            // (this.inputStream.totalRead() - pos_before_serve))
            if (r == null) {
                throw ResponseException(
                    Status.INTERNAL_ERROR,
                    "SERVER INTERNAL ERROR: Serve() returned a null response."
                )
            } else {
                val acceptEncoding = headers!!["accept-encoding"]
                cookies!!.unloadQueue(r)
                r.requestMethod = method
                if (acceptEncoding == null || !acceptEncoding.contains("gzip")) {
                    r.setUseGzip(false)
                }
                r.setKeepAlive(keepAlive)
                r.send(outputStream)
            }
            if (!keepAlive || r.isCloseConnection) {
                throw SocketException("NanoHttpd Shutdown")
            }
        } catch (e: SocketException) {
            // throw it out to close socket object (finalAccept)

            // treat socket timeouts the same way we treat socket exceptions
            // i.e. close the stream & finalAccept object by throwing the
            // exception up the call stack.
            throw e
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (ssle: SSLException) {
            val resp =
                newFixedLengthResponse(
                    Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "SSL PROTOCOL FAILURE: " + ssle.message
                )
            resp.send(outputStream)
            NanoHTTPD.safeClose(outputStream)
        } catch (ioe: IOException) {
            val resp =
                newFixedLengthResponse(
                    Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "SERVER INTERNAL ERROR: IOException: " + ioe.message
                )
            resp.send(outputStream)
            NanoHTTPD.safeClose(outputStream)
        } catch (re: ResponseException) {
            val resp =
                newFixedLengthResponse(
                    re.status,
                    NanoHTTPD.MIME_PLAINTEXT,
                    re.message
                )
            resp.send(outputStream)
            NanoHTTPD.safeClose(outputStream)
        } finally {
            NanoHTTPD.safeClose(r)
            tempFileManager.clear()
        }
    }

    /**
     * Find byte index separating header from body. It must be the last byte of
     * the first two sequential new lines.
     */
    private fun findHeaderEnd(buf: ByteArray, rlen: Int): Int {
        var splitbyte = 0
        while (splitbyte + 1 < rlen) {

            // RFC2616
            if (buf[splitbyte].toChar() == '\r' && buf[splitbyte + 1].toChar() == '\n' && splitbyte + 3 < rlen && buf[splitbyte + 2].toChar() == '\r' && buf[splitbyte + 3].toChar() == '\n'
            ) {
                return splitbyte + 4
            }

            // tolerance
            if (buf[splitbyte].toChar() == '\n' && buf[splitbyte + 1].toChar() == '\n') {
                return splitbyte + 2
            }
            splitbyte++
        }
        return 0
    }

    /**
     * Find the byte positions where multipart boundaries start. This reads a
     * large block at a time and uses a temporary buffer to optimize (memory
     * mapped) file access.
     */
    private fun getBoundaryPositions(b: ByteBuffer, boundary: ByteArray): IntArray {
        var res = IntArray(0)
        if (b.remaining() < boundary.size) {
            return res
        }
        var search_window_pos = 0
        val search_window = ByteArray(4 * 1024 + boundary.size)
        val first_fill = Math.min(b.remaining(), search_window.size)
        b[search_window, 0, first_fill]
        var new_bytes = first_fill - boundary.size
        do {
            // Search the search_window
            for (j in 0 until new_bytes) {
                for (i in boundary.indices) {
                    if (search_window[j + i] != boundary[i]) break
                    if (i == boundary.size - 1) {
                        // Match found, add it to results
                        val new_res = IntArray(res.size + 1)
                        System.arraycopy(res, 0, new_res, 0, res.size)
                        new_res[res.size] = search_window_pos + j
                        res = new_res
                    }
                }
            }
            search_window_pos += new_bytes

            // Copy the end of the buffer to the start
            System.arraycopy(
                search_window,
                search_window.size - boundary.size,
                search_window,
                0,
                boundary.size
            )

            // Refill search_window
            new_bytes = search_window.size - boundary.size
            new_bytes = Math.min(b.remaining(), new_bytes)
            b[search_window, boundary.size, new_bytes]
        } while (new_bytes > 0)
        return res
    }

    // we won't recover, so throw an error
    private val tmpBucket: RandomAccessFile
        private get() = try {
            val tempFile = tempFileManager.createTempFile(null)
            RandomAccessFile(tempFile!!.name, "rw")
        } catch (e: Exception) {
            throw Error(e) // we won't recover, so throw an error
        }

    /**
     * Deduce body length in bytes. Either from "content-length" header or read
     * bytes.
     */
    val bodySize: Long
        get() {
            if (headers!!.containsKey("content-length")) {
                return headers!!["content-length"]!!.toLong()
            } else if (splitbyte < rlen) {
                return (rlen - splitbyte).toLong()
            }
            return 0
        }

    override fun getParameters(): MutableMap<String, MutableList<String>> = parms2

    override fun parseBody(files: MutableMap<String, String>) {
        var randomAccessFile: RandomAccessFile? = null
        try {
            var size = bodySize
            var baos: ByteArrayOutputStream? = null
            val requestDataOutput: DataOutput?

            // Store the request in memory or a file, depending on size
            if (size < MEMORY_STORE_LIMIT) {
                baos = ByteArrayOutputStream()
                requestDataOutput = DataOutputStream(baos)
            } else {
                randomAccessFile = tmpBucket
                requestDataOutput = randomAccessFile
            }

            // Read all the body and write it to request_data_output
            val buf = ByteArray(REQUEST_BUFFER_LEN)
            while (rlen >= 0 && size > 0) {
                rlen =
                    inputStream!!.read(buf, 0, Math.min(size, REQUEST_BUFFER_LEN.toLong()).toInt())
                size -= rlen.toLong()
                if (rlen > 0) {
                    requestDataOutput.write(buf, 0, rlen)
                }
            }
            val fbuf: ByteBuffer
            if (baos != null) {
                fbuf = ByteBuffer.wrap(baos.toByteArray(), 0, baos.size())
            } else {
                fbuf = randomAccessFile!!.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    randomAccessFile.length()
                )
                randomAccessFile.seek(0)
            }

            // If the method is POST, there may be parameters
            // in data section, too, read it:
            if (Method.POST == method) {
                val contentType =
                    ContentType(headers!!["content-type"])
                if (contentType.isMultipart) {
                    val boundary = contentType.boundary
                        ?: throw ResponseException(
                            Status.BAD_REQUEST,
                            "BAD REQUEST: Content type is multipart/form-data but boundary missing. Usage: GET /example/file.html"
                        )
                    decodeMultipartFormData(contentType, fbuf, parms2, files)
                } else {
                    val postBytes = ByteArray(fbuf.remaining())
                    fbuf[postBytes]
                    val postLine = String(
                        postBytes,
                        Charset.forName(contentType.getEncoding())
                    ).trim { it <= ' ' }
                    // Handle application/x-www-form-urlencoded
                    if ("application/x-www-form-urlencoded".equals(
                            contentType.contentType,
                            ignoreCase = true
                        )
                    ) {
                        decodeParms(postLine, parms2)
                    } else if (postLine.length != 0) {
                        // Special case for raw POST data => create a
                        // special files entry "postData" with raw content
                        // data
                        files.put(POST_DATA, postLine)
                    }
                }
            } else if (Method.PUT == method) {
                files.put("content", saveTmpFile(fbuf, 0, fbuf.limit(), null))
            }
        } finally {
            NanoHTTPD.safeClose(randomAccessFile)
        }
    }

    /**
     * Retrieves the content of a sent file and saves it to a temporary file.
     * The full path to the saved file is returned.
     */
    private fun saveTmpFile(
        b: ByteBuffer,
        offset: Int,
        len: Int,
        filename_hint: String?
    ): String {
        var path = ""
        if (len > 0) {
            var fileOutputStream: FileOutputStream? = null
            try {
                val tempFile = tempFileManager.createTempFile(filename_hint)
                val src = b.duplicate()
                fileOutputStream = FileOutputStream(tempFile!!.name)
                val dest = fileOutputStream.channel
                src.position(offset).limit(offset + len)
                dest.write(src.slice())
                path = tempFile.name
            } catch (e: Exception) { // Catch exception if any
                throw Error(e) // we won't recover, so throw an error
            } finally {
                NanoHTTPD.safeClose(fileOutputStream)
            }
        }
        return path
    }

    companion object {
        const val POST_DATA = "postData"
        private const val REQUEST_BUFFER_LEN = 512
        private const val MEMORY_STORE_LIMIT = 1024
        const val BUFSIZE = 8192
        const val MAX_HEADER_SIZE = 1024
    }
}