package org.nanohttpd.protocols.websockets

import org.nanohttpd.protocols.websockets.OpCode.Companion.find
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.*
import kotlin.math.min

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
open class WebSocketFrame {
    var opCode: OpCode? = null
    var isFin = false
    private var maskingKey: ByteArray? = null
    private var payload: ByteArray? = null

    @Transient
    private var _payloadLength = 0

    @Transient
    private var _payloadString: String? = null

    private constructor(opCode: OpCode, fin: Boolean) {
        this.opCode = opCode
        this.isFin = fin
    }

    @JvmOverloads
    constructor(opCode: OpCode, fin: Boolean, payload: ByteArray?, maskingKey: ByteArray? = null) : this(opCode, fin) {
        setMaskingKey(maskingKey)
        binaryPayload = payload
    }

    @JvmOverloads
    constructor(opCode: OpCode, fin: Boolean, payload: String, maskingKey: ByteArray? = null) : this(opCode, fin) {
        setMaskingKey(maskingKey)
        setTextPayload(payload)
    }

    constructor(opCode: OpCode?, fragments: List<WebSocketFrame>) {
        this.opCode = opCode
        this.isFin = true
        var length: Long = 0
        for (inter in fragments) {
            length += inter.binaryPayload!!.size.toLong()
        }
        if (length < 0 || length > Int.MAX_VALUE) {
            throw WebSocketException(CloseCode.MessageTooBig, "Max frame length has been exceeded.")
        }
        this._payloadLength = length.toInt()
        val payload = ByteArray(this._payloadLength)
        var offset = 0
        for (inter in fragments) {
            System.arraycopy(inter.binaryPayload!!, 0, payload, offset, inter.binaryPayload!!.size)
            offset += inter.binaryPayload!!.size
        }
        binaryPayload = payload
    }

    constructor(clone: WebSocketFrame) {
        this.opCode = clone.opCode
        this.isFin = clone.isFin
        binaryPayload = clone.binaryPayload
        setMaskingKey(clone.maskingKey)
    }

    var binaryPayload: ByteArray?
        get() = payload
        set(payload) {
            this.payload = payload
            _payloadLength = payload!!.size
            _payloadString = null
        }

    private val textPayload: String?
        get() {
            if (_payloadString == null) {
                try {
                    _payloadString =
                        binary2Text(binaryPayload)
                } catch (e: CharacterCodingException) {
                    throw RuntimeException("Undetected CharacterCodingException", e)
                }
            }
            return _payloadString
        }

    val isMasked: Boolean
        get() = maskingKey != null && maskingKey!!.size == 4

    private fun payloadToString(): String {
        return if (payload == null) {
            "null"
        } else {
            val sb = StringBuilder()
            sb.append('[').append(payload!!.size).append("b] ")
            if (opCode === OpCode.Text) {
                val text = textPayload
                if (text!!.length > 100) {
                    sb.append(text, 0, 100).append("...")
                } else {
                    sb.append(text)
                }
            } else {
                sb.append("0x")
                for (i in 0 until min(payload!!.size, 50)) {
                    sb.append(Integer.toHexString((payload!![i].toLong() and 0xFF).toInt()))
                }
                if (payload!!.size > 50) {
                    sb.append("...")
                }
            }
            sb.toString()
        }
    }

    @Throws(IOException::class)
    private fun readPayload(stream: InputStream) {
        payload = ByteArray(_payloadLength)
        var read = 0
        while (read < _payloadLength) {
            read += checkedRead(stream.read(payload!!, read, _payloadLength - read))
        }
        if (isMasked) {
            for (i in payload!!.indices) {
                payload!![i] = (payload!![i].toLong() xor maskingKey!![i % 4].toLong()).toByte()
            }
        }

        // Test for Unicode errors
        if (opCode === OpCode.Text) {
            _payloadString = binary2Text(binaryPayload)
        }
    }

    @Throws(IOException::class)
    private fun readPayloadInfo(stream: InputStream) {
        val b: Long = checkedRead(stream.read()).toLong()
        val masked = b and 0x80L != 0L
        _payloadLength = (0x7F and b.toInt())
        if (_payloadLength == 126) {
            // checkedRead must return int for this to work
            _payloadLength =
                checkedRead(stream.read()) shl 8 or checkedRead(
                    stream.read()
                ) and 0xFFFF
            if (_payloadLength < 126) {
                throw WebSocketException(
                    CloseCode.ProtocolError,
                    "Invalid data frame 2byte length. (not using minimal length encoding)"
                )
            }
        } else if (_payloadLength == 127) {
            val length = checkedRead(stream.read()).toLong() shl 56 or
                    (checkedRead(stream.read()).toLong() shl 48) or
                    (checkedRead(stream.read()).toLong() shl 40) or
                    (checkedRead(stream.read()).toLong() shl 32) or
                    (checkedRead(stream.read()) shl 24).toLong() or
                    (checkedRead(stream.read()) shl 16).toLong() or
                    (checkedRead(stream.read()) shl 8).toLong() or
                    checkedRead(stream.read()).toLong()
            when {
                length < 65536 -> throw WebSocketException(
                    CloseCode.ProtocolError,
                    "Invalid data frame 4byte length. (not using minimal length encoding)"
                )
                length < 0 || length > Int.MAX_VALUE -> throw WebSocketException(
                    CloseCode.MessageTooBig,
                    "Max frame length has been exceeded."
                )
                else -> this._payloadLength = length.toInt()
            }
        }
        if (opCode!!.isControlFrame) {
            if (_payloadLength > 125) {
                throw WebSocketException(
                    CloseCode.ProtocolError,
                    "Control frame with payload length > 125 bytes."
                )
            }
            if (opCode === OpCode.Close && _payloadLength == 1) {
                throw WebSocketException(
                    CloseCode.ProtocolError,
                    "Received close frame with payload len 1."
                )
            }
        }
        if (masked) {
            maskingKey = ByteArray(4)
            var read = 0
            while (read < maskingKey!!.size) {
                read += checkedRead(stream.read(maskingKey!!, read, maskingKey!!.size - read))
            }
        }
    }

    fun setMaskingKey(maskingKey: ByteArray?) {
        require(!(maskingKey != null && maskingKey.size != 4)) { "MaskingKey " + Arrays.toString(maskingKey) + " hasn't length 4" }
        this.maskingKey = maskingKey
    }

    @Throws(CharacterCodingException::class)
    fun setTextPayload(payload: String) {
        this.payload = text2Binary(payload)
        _payloadLength = payload.length
        _payloadString = payload
    }

    fun setUnmasked() {
        setMaskingKey(null)
    }

    override fun toString(): String {
        return "WS[" + opCode +
                ", " + (if (isFin) "fin" else "inter") +
                ", " + (if (isMasked) "masked" else "unmasked") +
                ", " + payloadToString() +
                ']'
    }

    @Throws(IOException::class)
    fun write(out: OutputStream) {
        var header = 0L
        if (isFin) {
            header = header or 0x80
        }
        header = header or (opCode!!.value and 0x0FL)
        out.write(header.toInt())
        _payloadLength = binaryPayload!!.size
        when {
            _payloadLength <= 125 -> {
                out.write(if (isMasked) 0x80 or _payloadLength else _payloadLength)
            }
            _payloadLength <= 0xFFFF -> {
                out.write(if (isMasked) 0xFE else 126)
                out.write(_payloadLength ushr 8)
                out.write(_payloadLength)
            }
            else -> {
                out.write(if (isMasked) 0xFF else 127)
                out.write(_payloadLength ushr 56 and 0) // integer only
                // contains
                // 31 bit
                out.write(_payloadLength ushr 48 and 0)
                out.write(_payloadLength ushr 40 and 0)
                out.write(_payloadLength ushr 32 and 0)
                out.write(_payloadLength ushr 24)
                out.write(_payloadLength ushr 16)
                out.write(_payloadLength ushr 8)
                out.write(_payloadLength)
            }
        }
        if (isMasked) {
            out.write(maskingKey!!)
            for (i in 0 until _payloadLength) {
                out.write((binaryPayload!![i].toLong() xor maskingKey!![i % 4].toLong()).toInt())
            }
        } else {
            out.write(binaryPayload!!)
        }
        out.flush()
    }

    companion object {
        private val TEXT_CHARSET = Charset.forName("UTF-8")

        @Throws(CharacterCodingException::class)
        fun binary2Text(payload: ByteArray?) =
            String(payload!!, TEXT_CHARSET)

        @JvmStatic
        @Throws(CharacterCodingException::class)
        fun binary2Text(payload: ByteArray?, offset: Int, length: Int) =
            String(payload!!, offset, length, TEXT_CHARSET)

        @Throws(IOException::class)
        private fun checkedRead(read: Int): Int {
            if (read < 0) throw EOFException()
            return read
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(stream: InputStream): WebSocketFrame {
            val head = checkedRead(stream.read()).toLong()
            val fin = head and 0x80L != 0L
            val opCode = find(head and 0x0F)
            when {
                head and 0x70 != 0L -> throw WebSocketException(
                    CloseCode.ProtocolError,
                    "The reserved bits (${Integer.toBinaryString((head and 0x70).toInt())}) must be 0."
                )
                opCode == null -> throw WebSocketException(
                    CloseCode.ProtocolError,
                    "Received frame with reserved/unknown opcode ${head and 0x0F}."
                )
                opCode.isControlFrame && !fin ->
                    throw WebSocketException(CloseCode.ProtocolError, "Fragmented control frame.")
                else -> {
                    val frame = WebSocketFrame(opCode, fin)
                    frame.readPayloadInfo(stream)
                    frame.readPayload(stream)
                    return if (frame.opCode === OpCode.Close) CloseFrame(frame) else frame
                }
            }
        }

        @JvmStatic
        @Throws(CharacterCodingException::class)
        fun text2Binary(payload: String): ByteArray {
            return payload.toByteArray(TEXT_CHARSET)
        }
    }
}