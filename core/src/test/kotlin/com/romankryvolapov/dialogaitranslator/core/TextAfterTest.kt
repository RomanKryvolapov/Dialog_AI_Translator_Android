package com.romankryvolapov.dialogaitranslator.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TextAfterTest {

    @Test
    fun `the words after what was given, found by content`() {
        assertEquals("", textAfter("I wanna deploy it", "I want to deploy it"))
        assertEquals("today", textAfter("I wanna deploy it", "I want to deploy it today"))
        assertEquals("and test it", textAfter("Deploy it.", "deploy it and test it"))
        assertEquals("", textAfter("what is a generator", "What is a generator?"))
        assertEquals("two three", textAfter("", "two three"))
    }

    @Test
    fun `a rewrite with no shared ending keeps only what goes past it`() {
        assertEquals("", textAfter("version twenty", "version 20"))
        assertEquals("is out now", textAfter("version twenty", "version 20 is out now"))
    }
}
