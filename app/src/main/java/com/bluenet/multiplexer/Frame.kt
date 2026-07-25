package com.bluenet.multiplexer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.Inflater

data class Frame(
    val type: FrameType,
    val streamId: Int,
    val payload: ByteArray = ByteArray(0)
) {
    fun writeTo(dos: DataOutputStream) {
        synchronized(dos) {
            dos.writeByte(MAGIC.toInt())
            dos.writeByte(type.code.toInt())
            dos.writeInt(streamId)
            dos.writeShort(payload.size)
            if (payload.isNotEmpty()) {
                dos.write(payload)
            }
            dos.flush()
        }
    }

    fun compressIfBeneficial(): Frame {
        if (type != FrameType.DATA || payload.size < 64) return this

        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(payload)
        deflater.finish()

        val baos = ByteArrayOutputStream(payload.size)
        val buffer = ByteArray(512)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            baos.write(buffer, 0, count)
        }
        deflater.end()

        val compressed = baos.toByteArray()
        // Only use compressed frame if size actually decreased
        return if (compressed.size < payload.size - 4) {
            Frame(FrameType.COMPRESSED_DATA, streamId, compressed)
        } else {
            this
        }
    }

    fun decompressPayload(): ByteArray {
        if (type != FrameType.COMPRESSED_DATA) return payload

        val inflater = Inflater()
        inflater.setInput(payload)
        val baos = ByteArrayOutputStream(payload.size * 2)
        val buffer = ByteArray(512)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                baos.write(buffer, 0, count)
            }
            inflater.end()
            return baos.toByteArray()
        } catch (e: Exception) {
            inflater.end()
            return payload
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Frame
        if (type != other.type) return false
        if (streamId != other.streamId) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC: Byte = 0xBE.toByte()
        const val HEADER_SIZE = 8 // 1(Magic) + 1(Type) + 4(StreamId) + 2(Length)

        fun readFrom(dis: DataInputStream): Frame? {
            var magic: Byte
            while (true) {
                try {
                    magic = dis.readByte()
                } catch (_: java.io.EOFException) {
                    return null
                }
                if (magic == MAGIC) {
                    break
                }
            }
            val typeByte = dis.readByte()
            val type = FrameType.fromCode(typeByte)
                ?: throw IOException("Unknown frame type: $typeByte")
            val streamId = dis.readInt()
            val length = dis.readUnsignedShort()
            val payload = ByteArray(length)
            if (length > 0) {
                dis.readFully(payload)
            }
            return Frame(type, streamId, payload)
        }
    }
}
