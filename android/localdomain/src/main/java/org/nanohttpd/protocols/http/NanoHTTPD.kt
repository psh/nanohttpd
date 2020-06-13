package org.nanohttpd.protocols.http

import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.Companion.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import org.nanohttpd.protocols.http.sockets.DefaultServerSocketFactory
import org.nanohttpd.protocols.http.sockets.SecureServerSocketFactory
import org.nanohttpd.protocols.http.tempfiles.DefaultTempFileManagerFactory
import org.nanohttpd.protocols.http.tempfiles.ITempFileManager
import org.nanohttpd.protocols.http.threading.DefaultAsyncRunner
import org.nanohttpd.protocols.http.threading.IAsyncRunner
import org.nanohttpd.util.IFactory
import org.nanohttpd.util.IHandler
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.util.ArrayList
import java.util.HashMap
import java.util.Properties
import java.util.StringTokenizer
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Pattern
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory

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
 */ /**
 * A simple, tiny, nicely embeddable HTTP server in Java
 *
 *
 *
 *
 * NanoHTTPD
 *
 *
 * Copyright (c) 2012-2013 by Paul S. Hawke, 2001,2005-2013 by Jarno Elonen,
 * 2010 by Konstantinos Togias
 *
 *
 *
 *
 *
 * **Features + limitations: **
 *
 *
 *
 *  * Only one Java file
 *  * Java 5 compatible
 *  * Released as open source, Modified BSD licence
 *  * No fixed config files, logging, authorization etc. (Implement yourself if
 * you need them.)
 *  * Supports parameter parsing of GET and POST methods (+ rudimentary PUT
 * support in 1.25)
 *  * Supports both dynamic content and file serving
 *  * Supports file upload (since version 1.2, 2010)
 *  * Supports partial content (streaming)
 *  * Supports ETags
 *  * Never caches anything
 *  * Doesn't limit bandwidth, request time or simultaneous connections
 *  * Default code serves files and shows all HTTP parameters and headers
 *  * File server supports directory listing, index.html and index.htm
 *  * File server supports partial content (streaming)
 *  * File server supports ETags
 *  * File server does the 301 redirection trick for directories without '/'
 *  * File server supports simple skipping for files (continue download)
 *  * File server serves also very long files without memory overhead
 *  * Contains a built-in list of most common MIME types
 *  * All header names are converted to lower case so they don't vary between
 * browsers/clients
 *
 *
 *
 *
 *
 *
 *
 * **How to use: **
 *
 *
 *
 *  * Subclass and implement serve() and embed to your own program
 *
 *
 *
 *
 *
 * See the separate "LICENSE.md" file for the distribution license (Modified BSD
 * licence)
 */
abstract class NanoHTTPD(val hostname: String?, val myPort: Int) {

    @Volatile
    open var myServerSocket: ServerSocket? = null
    open var serverSocketFactory: IFactory<ServerSocket> = DefaultServerSocketFactory()
    open var myThread: Thread? = null
    open lateinit var httpHandler: IHandler<IHTTPSession, Response>
    open var interceptors: MutableList<IHandler<IHTTPSession, Response>> = mutableListOf()

    /**
     * Pluggable strategy for asynchronously executing requests.
     */
    var asyncRunner: IAsyncRunner? = null

    /**
     * Pluggable strategy for creating and cleaning up temporary files.
     *
     * @param tempFileManagerFactory
     * new strategy for handling temp files.
     */
    /**
     * Pluggable strategy for creating and cleaning up temporary files.
     */
    var tempFileManagerFactory: IFactory<ITempFileManager>? = null

    /**
     * Constructs an HTTP server on given port.
     */
    constructor(port: Int) : this(null, port)

    fun setHTTPHandler(handler: IHandler<IHTTPSession, Response>) {
        httpHandler = handler
    }

    fun addHTTPInterceptor(interceptor: IHandler<IHTTPSession, Response>) {
        interceptors.add(interceptor)
    }

    /**
     * Forcibly closes all connections that are open.
     */
    @Synchronized
    fun closeAllConnections() {
        stop()
    }

    /**
     * create a instance of the client handler, subclasses can return a subclass
     * of the ClientHandler.
     *
     * @param finalAccept
     * the socket the cleint is connected to
     * @param inputStream
     * the input stream
     * @return the client handler
     */
    fun createClientHandler(
        finalAccept: Socket?,
        inputStream: InputStream?
    ): ClientHandler {
        return ClientHandler(this, inputStream!!, finalAccept!!)
    }

    /**
     * Instantiate the server runnable, can be overwritten by subclasses to
     * provide a subclass of the ServerRunnable.
     *
     * @param timeout
     * the socet timeout to use.
     * @return the server runnable.
     */
    protected fun createServerRunnable(timeout: Int): ServerRunnable {
        return ServerRunnable(this, timeout)
    }

    val listeningPort: Int
        get() = if (myServerSocket == null) -1 else myServerSocket!!.localPort

    val isAlive: Boolean
        get() = wasStarted() && !myServerSocket!!.isClosed && myThread!!.isAlive

    /**
     * Call before start() to serve over HTTPS instead of HTTP
     */
    fun makeSecure(
        sslServerSocketFactory: SSLServerSocketFactory?,
        sslProtocols: Array<String>?
    ) {
        serverSocketFactory = SecureServerSocketFactory(sslServerSocketFactory, sslProtocols)
    }

    /**
     * This is the "master" method that delegates requests to handlers and makes
     * sure there is a response to every request. You are not supposed to call
     * or override this method in any circumstances. But no one will stop you if
     * you do. I'm a Javadoc, not Code Police.
     *
     * @param session
     * the incoming session
     * @return a response to the incoming session
     */
    fun handle(session: IHTTPSession): Response {
        for (interceptor in interceptors) {
            return interceptor.handle(session)
        }
        return httpHandler.handle(session)
    }

    /**
     * Override this to customize the server.
     *
     *
     *
     *
     * (By default, this returns a 404 "Not Found" plain text error response.)
     *
     * @param session The HTTP session
     * @return HTTP response, see class Response for details
     */
    @Deprecated("")
    protected open fun serve(session: IHTTPSession?): Response {
        return newFixedLengthResponse(
            Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not Found"
        )
    }

    /**
     * Start the server.
     *
     * @param timeout
     * timeout to use for socket connections.
     * @param daemon
     * start the thread daemon or not.
     * @throws IOException
     * if the socket is in use.
     */
    /**
     * Starts the server (in setDaemon(true) mode).
     */
    /**
     * Start the server.
     *
     * @throws IOException
     * if the socket is in use.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun start(
        timeout: Int = SOCKET_READ_TIMEOUT,
        daemon: Boolean = true
    ) {
        myServerSocket = serverSocketFactory.create()
        myServerSocket!!.reuseAddress = true
        val serverRunnable = createServerRunnable(timeout)
        myThread = Thread(serverRunnable)
        myThread!!.isDaemon = daemon
        myThread!!.name = "NanoHttpd Main Listener"
        myThread!!.start()
        while (!serverRunnable.hasBinded() && serverRunnable.bindException == null) {
            try {
                Thread.sleep(10L)
            } catch (e: Throwable) {
                // on android this may not be allowed, that's why we
                // catch throwable the wait should be very short because we are
                // just waiting for the bind of the socket
            }
        }
        if (serverRunnable.bindException != null) {
            throw serverRunnable.bindException!!
        }
    }

    /**
     * Stop the server.
     */
    fun stop() {
        try {
            safeClose(myServerSocket)
            asyncRunner!!.closeAll()
            if (myThread != null) {
                myThread!!.join()
            }
        } catch (e: Exception) {
            LOG.log(
                Level.SEVERE,
                "Could not stop all connections",
                e
            )
        }
    }

    fun wasStarted(): Boolean {
        return myServerSocket != null && myThread != null
    }

    /**
     * Constructs an HTTP server on given hostname and port.
     */
    init {
        tempFileManagerFactory = DefaultTempFileManagerFactory()
        asyncRunner = DefaultAsyncRunner()

        // creates a default handler that redirects to deprecated serve();
        httpHandler = object : IHandler<IHTTPSession, Response> {
            override fun handle(input: IHTTPSession): Response {
                return serve(input)
            }
        }
    }

    companion object {
        const val CONTENT_DISPOSITION_REGEX = "([ |\t]*Content-Disposition[ |\t]*:)(.*)"
        val CONTENT_DISPOSITION_PATTERN = Pattern.compile(
            CONTENT_DISPOSITION_REGEX,
            Pattern.CASE_INSENSITIVE
        )
        const val CONTENT_TYPE_REGEX = "([ |\t]*content-type[ |\t]*:)(.*)"
        val CONTENT_TYPE_PATTERN = Pattern.compile(
            CONTENT_TYPE_REGEX,
            Pattern.CASE_INSENSITIVE
        )
        const val CONTENT_DISPOSITION_ATTRIBUTE_REGEX =
            "[ |\t]*([a-zA-Z]*)[ |\t]*=[ |\t]*['|\"]([^\"^']*)['|\"]"
        val CONTENT_DISPOSITION_ATTRIBUTE_PATTERN =
            Pattern.compile(CONTENT_DISPOSITION_ATTRIBUTE_REGEX)

        /**
         * Maximum time to wait on Socket.getInputStream().read() (in milliseconds)
         * This is required as the Keep-Alive HTTP connections would otherwise block
         * the socket reading thread forever (or as long the browser is open).
         */
        const val SOCKET_READ_TIMEOUT = 5000

        /**
         * Common MIME type for dynamic content: plain text
         */
        const val MIME_PLAINTEXT = "text/plain"

        /**
         * Common MIME type for dynamic content: html
         */
        const val MIME_HTML = "text/html"

        /**
         * Pseudo-Parameter to use to store the actual query string in the
         * parameters map for later re-processing.
         */
        private const val QUERY_STRING_PARAMETER = "NanoHttpd.QUERY_STRING"

        /**
         * logger to log to.
         */
        val LOG = Logger.getLogger(NanoHTTPD::class.java.name)

        /**
         * Hashtable mapping (String)FILENAME_EXTENSION -> (String)MIME_TYPE
         */
        protected var MIME_TYPES: MutableMap<String, String> = mutableMapOf()

        @JvmStatic
        fun mimeTypes(): Map<String, String>? {
            if (MIME_TYPES.isEmpty()) {
                loadMimeTypes(
                    MIME_TYPES,
                    "META-INF/nanohttpd/default-mimetypes.properties"
                )
                loadMimeTypes(
                    MIME_TYPES,
                    "META-INF/nanohttpd/mimetypes.properties"
                )
                if (MIME_TYPES.isEmpty()) {
                    LOG.log(
                        Level.WARNING,
                        "no mime types found in the classpath! please provide mimetypes.properties"
                    )
                }
            }
            return MIME_TYPES
        }

        private fun loadMimeTypes(
            result: MutableMap<String, String>,
            resourceName: String
        ) {
            try {
                val resources =
                    NanoHTTPD::class.java.classLoader!!.getResources(resourceName)
                while (resources.hasMoreElements()) {
                    val url = resources.nextElement()
                    val properties = Properties()
                    var stream: InputStream? = null
                    try {
                        stream = url.openStream()
                        properties.load(stream)
                    } catch (e: IOException) {
                        LOG.log(
                            Level.SEVERE,
                            "could not load mimetypes from $url",
                            e
                        )
                    } finally {
                        safeClose(stream)
                    }
                    properties.forEach { t, u ->
                        result[t.toString()] = u.toString()
                    }
                }
            } catch (e: IOException) {
                LOG.log(
                    Level.INFO,
                    "no mime types available at $resourceName"
                )
            }
        }

        /**
         * Creates an SSLSocketFactory for HTTPS. Pass a loaded KeyStore and an
         * array of loaded KeyManagers. These objects must properly
         * loaded/initialized by the caller.
         */
        @Throws(IOException::class)
        fun makeSSLSocketFactory(
            loadedKeyStore: KeyStore?,
            keyManagers: Array<KeyManager?>?
        ): SSLServerSocketFactory {
            val res: SSLServerSocketFactory
            res = try {
                val trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(loadedKeyStore)
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(keyManagers, trustManagerFactory.trustManagers, null)
                ctx.serverSocketFactory
            } catch (e: Exception) {
                throw IOException(e.message)
            }
            return res
        }

        /**
         * Creates an SSLSocketFactory for HTTPS. Pass a loaded KeyStore and a
         * loaded KeyManagerFactory. These objects must properly loaded/initialized
         * by the caller.
         */
        @Throws(IOException::class)
        fun makeSSLSocketFactory(
            loadedKeyStore: KeyStore?,
            loadedKeyFactory: KeyManagerFactory
        ): SSLServerSocketFactory {
            return try {
                makeSSLSocketFactory(
                    loadedKeyStore,
                    loadedKeyFactory.keyManagers
                )
            } catch (e: Exception) {
                throw IOException(e.message)
            }
        }

        /**
         * Creates an SSLSocketFactory for HTTPS. Pass a KeyStore resource with your
         * certificate and passphrase
         */
        @JvmStatic
        @Throws(IOException::class)
        fun makeSSLSocketFactory(
            keyAndTrustStoreClasspathPath: String,
            passphrase: CharArray?
        ): SSLServerSocketFactory {
            return try {
                val keystore =
                    KeyStore.getInstance(KeyStore.getDefaultType())
                val keystoreStream =
                    NanoHTTPD::class.java.getResourceAsStream(keyAndTrustStoreClasspathPath)
                        ?: throw IOException("Unable to load keystore from classpath: $keyAndTrustStoreClasspathPath")
                keystore.load(keystoreStream, passphrase)
                val keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keystore, passphrase)
                makeSSLSocketFactory(keystore, keyManagerFactory)
            } catch (e: Exception) {
                throw IOException(e.message)
            }
        }

        /**
         * Get MIME type from file name extension, if possible
         *
         * @param uri
         * the string representing a file
         * @return the connected mime/type
         */
        @JvmStatic
        fun getMimeTypeForFile(uri: String): String {
            val dot = uri.lastIndexOf('.')
            var mime: String? = null
            if (dot >= 0) {
                mime = mimeTypes()!![uri.substring(dot + 1).toLowerCase()]
            }
            return mime ?: "application/octet-stream"
        }

        fun safeClose(closeable: Any?) {
            try {
                if (closeable != null) {
                    if (closeable is Closeable) {
                        closeable.close()
                    } else {
                        throw IllegalArgumentException("Unknown object to close")
                    }
                }
            } catch (e: IOException) {
                LOG.log(Level.SEVERE, "Could not close", e)
            }
        }

        /**
         * Decode parameters from a URL, handing the case where a single parameter
         * name might have been supplied several times, by return lists of values.
         * In general these lists will contain a single element.
         *
         * @param parms
         * original **NanoHTTPD** parameters values, as passed to the
         * `serve()` method.
         * @return a map of `String` (parameter name) to
         * `List<String>` (a list of the values supplied).
         */
        protected fun decodeParameters(parms: Map<String?, String?>): Map<String, MutableList<String>> {
            return decodeParameters(parms[QUERY_STRING_PARAMETER])
        }
        // -------------------------------------------------------------------------------
        // //
        /**
         * Decode parameters from a URL, handing the case where a single parameter
         * name might have been supplied several times, by return lists of values.
         * In general these lists will contain a single element.
         *
         * @param queryString
         * a query string pulled from the URL.
         * @return a map of `String` (parameter name) to
         * `List<String>` (a list of the values supplied).
         */
        @JvmStatic
        protected fun decodeParameters(queryString: String?): Map<String, MutableList<String>> {
            val parms: MutableMap<String, MutableList<String>> =
                HashMap()
            if (queryString != null) {
                val st = StringTokenizer(queryString, "&")
                while (st.hasMoreTokens()) {
                    val e = st.nextToken()
                    val sep = e.indexOf('=')
                    val propertyName = if (sep >= 0) decodePercent(
                        e.substring(
                            0,
                            sep
                        )
                    )?.trim { it <= ' ' } else decodePercent(e)?.trim { it <= ' ' }
                    propertyName?.let {
                        if (!parms.containsKey(propertyName)) {
                            parms[propertyName] = ArrayList()
                        }
                        val propertyValue =
                            if (sep >= 0) decodePercent(e.substring(sep + 1)) else null
                        if (propertyValue != null) {
                            parms[propertyName]!!.add(propertyValue)
                        }
                    }
                }
            }
            return parms
        }

        /**
         * Decode percent encoded `String` values.
         *
         * @param str
         * the percent encoded `String`
         * @return expanded form of the input, for example "foo%20bar" becomes
         * "foo bar"
         */
        @JvmStatic
        fun decodePercent(str: String?): String? {
            var decoded: String? = null
            try {
                decoded = URLDecoder.decode(str, "UTF8")
            } catch (ignored: UnsupportedEncodingException) {
                LOG.log(
                    Level.WARNING,
                    "Encoding not supported, ignored",
                    ignored
                )
            }
            return decoded
        }
    }
}