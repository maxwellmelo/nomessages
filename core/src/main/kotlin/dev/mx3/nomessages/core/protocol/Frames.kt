package dev.mx3.nomessages.core.protocol

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Carries ciphertext only. No sender/contact/onion metadata is written. */
object FrameCodec {
    const val MAX_MESSAGE = 16*1024*1024
    private val buckets = intArrayOf(256,1024,4096,16384)
    private const val HEADER = 16
    private const val CHUNK = 16384-HEADER
    private val random=SecureRandom()
    fun encode(ciphertext: ByteArray): List<ByteArray> {
        require(ciphertext.size in 1..MAX_MESSAGE)
        val count=(ciphertext.size+CHUNK-1)/CHUNK
        return (0 until count).map { index ->
            val offset=index*CHUNK
            val length=minOf(CHUNK,ciphertext.size-offset)
            val bucket=buckets.first { it>=HEADER+length }
            val padded=ByteArray(bucket).also(random::nextBytes)
            ByteBuffer.wrap(padded).putInt(ciphertext.size).putInt(index).putInt(count).putInt(length).put(ciphertext,offset,length)
            ByteBuffer.allocate(4+bucket).putInt(bucket).put(padded).array()
        }
    }
    fun read(input: InputStream): ByteArray? {
        val first=input.read(); if(first<0) return null
        val prefix=ByteArray(4); prefix[0]=first.toByte()
        DataInputStream(input).readFully(prefix,1,3)
        val length=ByteBuffer.wrap(prefix).int
        require(length in buckets) { "Invalid frame length" }
        val body=ByteArray(length); DataInputStream(input).readFully(body)
        return prefix+body
    }
    internal fun parse(frame: ByteArray): Chunk {
        require(frame.size>=4)
        val b=ByteBuffer.wrap(frame)
        val bucket=b.int; require(bucket in buckets && frame.size==bucket+4)
        val total=b.int; val index=b.int; val count=b.int; val length=b.int
        require(total in 1..MAX_MESSAGE)
        require(count==(total+CHUNK-1)/CHUNK && index in 0 until count)
        val expected=minOf(CHUNK,total-index*CHUNK)
        require(length==expected && bucket==buckets.first { it>=HEADER+length })
        return Chunk(total,index,count,ByteArray(length).also(b::get))
    }
    internal data class Chunk(val total:Int,val index:Int,val count:Int,val bytes:ByteArray)
}

/** One assembler per peer stream; transport order mandatory; reset on any exception. */
class FrameAssembler {
    private var pending: ByteArrayOutputStream?=null
    private var index=0
    private var total=0
    fun accept(frame: ByteArray): ByteArray? = try {
        val c=FrameCodec.parse(frame)
        require(c.index==index)
        if(index==0) { total=c.total; pending=ByteArrayOutputStream(minOf(total,16384)) }
        require(c.total==total)
        pending!!.write(c.bytes); index++
        if(index==c.count) { val result=pending!!.toByteArray(); require(result.size==total); reset(); result } else null
    } catch(e: Exception) { reset(); throw e }
    fun reset() { pending?.reset(); pending=null; index=0; total=0 }
}
