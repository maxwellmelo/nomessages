package dev.mx3.nomessages.runtime

import java.io.OutputStream

/** Bounded RAM buffer; growth and close erase arrays owned by this object. */
internal class SensitiveBuffer(private val limit: Int) : OutputStream() {
    private var buffer = ByteArray(minOf(64 * 1024, limit))
    private var length = 0
    private var closed = false
    init { require(limit > 0) }
    override fun write(value: Int) {
        ensure(1)
        buffer[length++] = value.toByte()
    }
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        ensure(count)
        bytes.copyInto(buffer, length, offset, offset + count)
        length += count
    }
    fun toByteArray(): ByteArray { check(!closed); return buffer.copyOf(length) }
    fun size(): Int { check(!closed); return length }
    private fun ensure(additional: Int) {
        check(!closed)
        require(additional <= limit - length) { "Arquivo acima do limite" }
        if (length + additional > buffer.size) {
            val previous = buffer
            buffer = previous.copyOf(minOf(limit, maxOf(length + additional, previous.size * 2)))
            previous.fill(0)
        }
    }
    override fun close() { buffer.fill(0); length = 0; closed = true }
}
