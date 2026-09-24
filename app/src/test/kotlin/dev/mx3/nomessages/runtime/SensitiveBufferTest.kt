package dev.mx3.nomessages.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SensitiveBufferTest {
    @Test fun oversizedInputDoesNotPublishAPartialWrite() {
        SensitiveBuffer(4).use { output ->
            output.write(byteArrayOf(1, 2))
            assertThrows(IllegalArgumentException::class.java) { output.write(byteArrayOf(3, 4, 5)) }
            assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
        }
    }
    @Test fun closedBufferCannotReadOrAcceptNewPlaintext() {
        val output = SensitiveBuffer(8)
        output.write(byteArrayOf(1, 2, 3))
        output.close()
        assertThrows(IllegalStateException::class.java) { output.toByteArray() }
        assertThrows(IllegalStateException::class.java) { output.write(1) }
    }
    @Test fun growthPreservesInputAndReturnedCopiesAreIndependent() {
        SensitiveBuffer(131072).use { output ->
            val input = ByteArray(70000) { 0x5a }
            output.write(input)
            val first = output.toByteArray()
            first[0] = 0
            assertArrayEquals(input, output.toByteArray())
        }
    }
}
