package org.nanohttpd.protocols.websockets

import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status
import org.nanohttpd.protocols.websockets.CloseCode
import org.nanohttpd.protocols.websockets.WebSocketFrame.Companion.read
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.util.*
import java.util.logging.Level

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
abstract class WebSocket(handshakeRequest: IHTTPSession) {
    private val `in`: InputStream? = handshakeRequest.inputStream
    private var out: OutputStream? = null
    private var continuousOpCode: OpCode? = null
    private val continuousFrames: MutableList<WebSocketFrame> = LinkedList()
    private var state = State.UNCONNECTED
    private val enforceNoGzip = true
    val handshakeResponse: Response = object :
        Response(
            Status.SWITCH_PROTOCOL,
            null,
            null,
            0
        ) {
        override fun send(out: OutputStream) {
            this@WebSocket.out = out
            state = State.CONNECTING
            super.send(out)
            state = State.OPEN
            onOpen()
            readWebsocket()
        }
    }

    val isOpen: Boolean
        get() = state === State.OPEN

    protected abstract fun onOpen()
    protected abstract fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean)
    protected abstract fun onMessage(message: WebSocketFrame?)
    protected abstract fun onPong(pong: WebSocketFrame?)
    protected abstract fun onException(exception: IOException?)

    /**
     * Debug method. **Do not Override unless for debug purposes!**
     *
     * @param frame The received WebSocket Frame.
     */
    protected open fun debugFrameReceived(frame: WebSocketFrame?) {}

    /**
     * Debug method. **Do not Override unless for debug purposes!**<br></br>
     * This method is called before actually sending the frame.
     *
     * @param frame The sent WebSocket Frame.
     */
    protected open fun debugFrameSent(frame: WebSocketFrame?) {}

    @Throws(IOException::class)
    fun close(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        val oldState = state
        state = State.CLOSING
        if (oldState === State.OPEN) {
            sendFrame(CloseFrame(code, reason))
        } else {
            doClose(code, reason, initiatedByRemote)
        }
    }

    private fun doClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        if (state === State.CLOSED) return
        if (`in` != null) {
            try {
                `in`.close()
            } catch (e: IOException) {
                NanoWSD.LOG.log(Level.FINE, "close failed", e)
            }
        }

        if (out != null) {
            try {
                out!!.close()
            } catch (e: IOException) {
                NanoWSD.LOG.log(Level.FINE, "close failed", e)
            }
        }

        state = State.CLOSED
        onClose(code, reason, initiatedByRemote)
    }

    @Throws(IOException::class)
    private fun handleCloseFrame(frame: WebSocketFrame) {
        var code: CloseCode? = CloseCode.NormalClosure
        var reason: String? = ""
        if (frame is CloseFrame) {
            code = frame.closeCode
            reason = frame.closeReason
        }
        if (state === State.CLOSING) {
            // Answer for my requested close
            doClose(code, reason, false)
        } else {
            close(code, reason, true)
        }
    }

    @Throws(IOException::class)
    private fun handleFrameFragment(frame: WebSocketFrame) {
        when {
            frame.opCode !== OpCode.Continuation -> {
                // First
                if (continuousOpCode != null) {
                    throw WebSocketException(
                        CloseCode.ProtocolError,
                        "Previous continuous frame sequence not completed."
                    )
                }
                continuousOpCode = frame.opCode
                continuousFrames.clear()
                continuousFrames.add(frame)
            }
            frame.isFin -> {
                // Last
                if (continuousOpCode == null) {
                    throw WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.")
                }
                continuousFrames.add(frame)
                onMessage(WebSocketFrame(continuousOpCode, continuousFrames))
                continuousOpCode = null
                continuousFrames.clear()
            }
            continuousOpCode == null -> {
                // Unexpected
                throw WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.")
            }
            else -> {
                // Intermediate
                continuousFrames.add(frame)
            }
        }
    }

    @Throws(IOException::class)
    private fun handleWebsocketFrame(frame: WebSocketFrame) {
        debugFrameReceived(frame)
        when {
            frame.opCode === OpCode.Close -> handleCloseFrame(frame)
            frame.opCode === OpCode.Ping -> sendFrame(WebSocketFrame(OpCode.Pong, true, frame.binaryPayload))
            frame.opCode === OpCode.Pong -> onPong(frame)
            !frame.isFin || frame.opCode === OpCode.Continuation -> handleFrameFragment(frame)
            continuousOpCode != null -> throw WebSocketException(
                CloseCode.ProtocolError,
                "Continuous frame sequence not completed."
            )
            frame.opCode === OpCode.Text || frame.opCode === OpCode.Binary -> onMessage(frame)
            else -> throw WebSocketException(CloseCode.ProtocolError, "Non control or continuous frame expected.")
        }
    }

    // --------------------------------Close-----------------------------------
    @Throws(IOException::class)
    fun ping(payload: ByteArray?) = sendFrame(WebSocketFrame(OpCode.Ping, true, payload))

    // --------------------------------Public
    // Facade---------------------------
    private fun readWebsocket() {
        try {
            while (state === State.OPEN) {
                handleWebsocketFrame(read(`in`!!))
            }
        } catch (e: CharacterCodingException) {
            onException(e)
            doClose(CloseCode.InvalidFramePayloadData, e.toString(), false)
        } catch (e: IOException) {
            onException(e)
            if (e is WebSocketException) {
                doClose(e.code, e.reason, false)
            }
        } finally {
            doClose(CloseCode.InternalServerError, "Handler terminated without closing the connection.", false)
        }
    }

    @Throws(IOException::class)
    fun send(payload: ByteArray?) = sendFrame(WebSocketFrame(OpCode.Binary, true, payload))

    @Throws(IOException::class)
    fun send(payload: String?) = sendFrame(WebSocketFrame(OpCode.Text, true, payload!!))

    @Synchronized
    @Throws(IOException::class)
    fun sendFrame(frame: WebSocketFrame) {
        debugFrameSent(frame)
        frame.write(out!!)
    }

    init {
        if (enforceNoGzip) handshakeResponse.setUseGzip(false)
        handshakeResponse.addHeader(NanoWSD.HEADER_UPGRADE, NanoWSD.HEADER_UPGRADE_VALUE)
        handshakeResponse.addHeader(NanoWSD.HEADER_CONNECTION, NanoWSD.HEADER_CONNECTION_VALUE)
    }
}