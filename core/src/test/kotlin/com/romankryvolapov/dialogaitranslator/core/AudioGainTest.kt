package com.romankryvolapov.dialogaitranslator.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals

class AudioGainTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    @Test
    fun `gain scales each sample and holds the loud ones at the limit`() {
        val sound = pcm(0, 100, -100, 20_000, -20_000)
        amplify(sound, 200)
        assertContentEquals(pcm(0, 200, -200, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()), sound)
    }

    @Test
    fun `gain below 100 makes it quieter, and 100 leaves it alone`() {
        val sound = pcm(1_000, -1_000, 3)
        amplify(sound, 50)
        assertContentEquals(pcm(500, -500, 1), sound)
        amplify(sound, 100)
        assertContentEquals(pcm(500, -500, 1), sound)
    }
}
