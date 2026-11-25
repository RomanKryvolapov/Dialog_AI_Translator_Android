package com.romankryvolapov.dialogaitranslator.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceSplitterTest {

    @Test
    fun `a sentence is taken once words follow it, and never twice`() {
        val s = SentenceSplitter()
        assertEquals(emptyList(), s.grow("Good morning, let us talk about the project budget now.").phrases)
        assertEquals(emptyList(), s.grow("Good morning, let us talk about the project budget now. How").phrases)
        val grown = s.grow("Good morning, let us talk about the project budget now. How long will")
        assertEquals(listOf("Good morning, let us talk about the project budget now."), grown.phrases)
        assertEquals("How long will", grown.rest)
        assertEquals(emptyList(), s.grow("Good morning, let us talk about the project budget now. How long will it take?").phrases)
        assertEquals("How long will it take?", s.finish("Good morning, let us talk about the project budget now. How long will it take?"))
        assertEquals("Next one.", s.finish("Next one."), "the next transcript starts from its own beginning")
    }

    @Test
    fun `segments joined without a space are told apart, short sentences go with the next`() {
        val s = SentenceSplitter()
        val grown = s.grow("How long will it take to build the mobile application?Hello. Good morning. Let us talk about the project budget. And")
        assertEquals(listOf("How long will it take to build the mobile application?"), grown.phrases)
        assertEquals("Hello. Good morning. Let us talk about the project budget. And", grown.rest)
    }

    @Test
    fun `a version number is not a sentence end and a rewritten comma changes nothing`() {
        val s = SentenceSplitter()
        assertEquals(1, s.grow("We moved all of our servers to version 3.5 last week. It works").phrases.size)
        assertEquals("It works well", s.finish("We moved all of our servers, to version 3.5 last week. It works well"))
    }

    @Test
    fun `a rewrite that shortens what was taken neither repeats nor loses a word`() {
        val s = SentenceSplitter()
        s.grow("Let us talk about the mobile application. We will see how long it takes. And then")
        assertEquals("And then we go", s.finish("Let's talk about the mobile application. We'll see how long it takes. And then we go"))
        s.grow("It's fine, we'll build the mobile application for you. And then")
        assertEquals("And then", s.grow("It is fine, we will build the mobile application for you. And then").rest)
    }

    @Test
    fun `speech without sentence ends is cut by length`() {
        val s = SentenceSplitter()
        val grown = s.grow(List(45) { "word$it" }.joinToString(" "))
        assertEquals(40, grown.phrases.single().split(" ").size)
    }
}
