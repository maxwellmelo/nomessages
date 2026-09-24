package dev.mx3.nomessages.core.protocol

import java.io.*
import java.util.Base64

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun String.unhex(): ByteArray { require(matches(Regex("[0-9a-f]{64}"))); return chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
internal fun pack(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
internal fun <T> unpack(bytes: ByteArray, limit: Int = 64*1024*1024, block: DataInputStream.() -> T): T {
    require(bytes.size <= limit)
    return DataInputStream(ByteArrayInputStream(bytes)).use { val value = it.block(); require(it.available()==0) { "Trailing data" }; value }
}
internal fun DataOutputStream.blob(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
internal fun DataInputStream.blob(max: Int = 16*1024*1024): ByteArray { val n=readInt(); require(n in 0..max && n<=available()); return ByteArray(n).also { readFully(it) } }

/** RFC8949 deterministic CBOR arrays; only integers, UTF8 text, and bytes are permitted. */
internal fun cbor(vararg fields: Any): ByteArray = ByteArrayOutputStream().also { out ->
    fun head(major: Int, n: Long) {
        require(n>=0)
        when {
            n<24 -> out.write((major shl 5) or n.toInt())
            n<=255 -> { out.write((major shl 5) or 24); out.write(n.toInt()) }
            n<=65535 -> { out.write((major shl 5) or 25); for(s in 8 downTo 0 step 8) out.write((n shr s).toInt()) }
            n<=0xffffffffL -> { out.write((major shl 5) or 26); for(s in 24 downTo 0 step 8) out.write((n shr s).toInt()) }
            else -> { out.write((major shl 5) or 27); for(s in 56 downTo 0 step 8) out.write((n shr s).toInt()) }
        }
    }
    head(4,fields.size.toLong())
    fields.forEach { when(it) {
        is ByteArray -> { head(2,it.size.toLong()); out.write(it) }
        is String -> { val b=it.toByteArray(Charsets.UTF_8); head(3,b.size.toLong()); out.write(b) }
        is Int -> head(0,it.toLong())
        is Long -> head(0,it)
        else -> error("Unsupported CBOR value")
    } }
}.toByteArray()

/** Strict bounded protobuf wire subset. Reject duplicates, unknown fields and noncanonical varints. */
internal object Proto {
    fun encode(fields: List<Any>): ByteArray = ByteArrayOutputStream().also { out ->
        fun vint(v: Long) { require(v>=0); var n=v; while(n>127) { out.write((n.toInt() and 127) or 128); n=n ushr 7 }; out.write(n.toInt()) }
        fields.forEachIndexed { index,value ->
            if(value is Long) { vint(((index+1)*8).toLong()); vint(value) }
            else { val b=value as ByteArray; vint(((index+1)*8+2).toLong()); vint(b.size.toLong()); out.write(b) }
        }
    }.toByteArray()
    fun decode(bytes: ByteArray, kinds: String): List<Any> {
        require(bytes.size<=6144)
        val input=ByteArrayInputStream(bytes)
        fun vint(): Long { var n=0L; var count=0; while(true) {
            val b=input.read(); require(b>=0 && count<9); n=n or ((b and 127).toLong() shl (7*count)); count++
            if(b<128) { require(count==1 || b!=0); return n }
        } }
        val fields=kinds.mapIndexed { index,kind ->
            require(vint()==((index+1)*8+if(kind=='b')2 else 0).toLong())
            if(kind=='b') { val n=vint(); require(n<=input.available()); ByteArray(n.toInt()).also { require(input.read(it)==it.size || it.isEmpty()) } } else vint()
        }
        require(input.available()==0); return fields
    }
    fun qr(prefix: String, bytes: ByteArray)=prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun fromQr(prefix: String, qr: String): ByteArray { require(qr.length<=8192 && qr.startsWith(prefix)); val raw=qr.substring(prefix.length); require(raw.matches(Regex("[A-Za-z0-9_-]+"))); return Base64.getUrlDecoder().decode(raw).also { require(Proto.qr(prefix,it)==qr) } }
}
