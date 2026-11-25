package com.romankryvolapov.dialogaitranslator.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogEngineTest {

    private class Prefs(info: ApplicationInfo) : PreferencesRepository {
        override val appInfoFlow = MutableStateFlow(info)
        override fun getAppInfo() = appInfoFlow.value
        override fun update(change: (ApplicationInfo) -> ApplicationInfo) = appInfoFlow.update(change)
    }

    private open class Cloud : CloudRepository {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        open fun answer(call: String, prompt: ModelPrompt): Flow<String> = flowOf("перевод")
        private fun go(call: String, prompt: ModelPrompt) = answer(call, prompt).also { calls += call }
        override fun streamGoogleAI(model: String, apiKey: String, prompt: ModelPrompt, parameters: ModelParameters) = go("google", prompt)
        override fun streamGoogleAIWithAudio(model: String, apiKey: String, prompt: ModelPrompt, parameters: ModelParameters, wav: ByteArray) =
            go("google-audio", prompt)
        override fun streamOpenAI(model: String, apiKey: String, prompt: ModelPrompt, parameters: ModelParameters) = go("openai", prompt)
        override fun streamOpenAIWithAudio(model: String, apiKey: String, prompt: ModelPrompt, parameters: ModelParameters, wav: ByteArray) =
            go("openai-audio", prompt)
        override fun streamClaude(model: String, apiKey: String, prompt: ModelPrompt, parameters: ModelParameters) = go("claude", prompt)
    }

    private class Recognizer : SpeechRecognizer {
        @Volatile var listener: SpeechRecognizer.Listener? = null
        @Volatile var starts = 0
        override val feedsContinuously = false
        override fun start(info: ApplicationInfo, listener: SpeechRecognizer.Listener) {
            this.listener = listener
            starts += 1
        }
        override fun feed(pcm: ByteArray) = Unit
        override fun endOfUtterance() = Unit
        override fun stop() = Unit
    }

    private class Source : AudioSource {
        @Volatile var onFrame: ((ByteArray) -> Unit)? = null
        override fun start(onFrame: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
            this.onFrame = onFrame
        }
        override fun stop() {
            onFrame = null
        }
    }

    private object NoDisplay : QuestionsDisplay {
        override fun add(question: String, answer: String) = 0
        override fun update(row: Int, question: String, answer: String) = Unit
        override fun clear() = Unit
    }

    private class Setup(val cloud: Cloud, info: ApplicationInfo) {
        val prefs = Prefs(info)
        val recognizer = Recognizer()
        val source = Source()
        private val limiters = RateLimiters()
        private val last = LastRequests()
        val engine = DialogEngine(
            cloud, prefs, ActiveMethod(), TokenUsageTracker(), last, limiters,
            QuestionsAssistant(cloud, prefs, limiters, last, NoDisplay), { recognizer },
        )

        suspend fun start() {
            engine.start(source)
            engine.running.first { it }
            withTimeout(2_000) { while (source.onFrame == null) delay(5) }
        }

        suspend fun rows(test: (List<DialogRow>) -> Boolean): List<DialogRow> = withTimeout(8_000) { engine.rows.first(test) }
    }

    private val keys = ApplicationInfo(googleCloudToken = "g", openAiToken = "o")

    private fun tone(frames: Int): List<ByteArray> = List(frames) {
        ByteArray(FRAME_BYTES).also { bytes ->
            for (i in 0 until FRAME_BYTES / 2) {
                val sample = (sin(i * 0.2) * 8000).toInt()
                bytes[2 * i] = sample.toByte()
                bytes[2 * i + 1] = (sample shr 8).toByte()
            }
        }
    }

    private fun silence(frames: Int) = List(frames) { ByteArray(FRAME_BYTES) }

    @Test
    fun `a recognised phrase replaces its partials in one row and is translated`() = runBlocking {
        val s = Setup(Cloud(), keys)
        s.start()
        s.recognizer.listener!!.onPartial("hel")
        s.recognizer.listener!!.onPartial("hello")
        s.recognizer.listener!!.onFinal("Hello.")
        val rows = s.rows { it.singleOrNull()?.translation == "перевод" }
        assertEquals("Hello.", rows.single().original)
    }

    @Test
    fun `a failing method hands the dialog and the phrase over to the next one`() = runBlocking {
        val cloud = object : Cloud() {
            override fun answer(call: String, prompt: ModelPrompt): Flow<String> =
                if (call == "google") flow { throw IOException("down") } else flowOf("ответ")
        }
        val s = Setup(cloud, keys.copy(methodKeys = listOf("gemini-live:gemini-3.5-flash-lite", "gemini-live:gpt-6.1-terra")))
        s.start()
        s.recognizer.listener!!.onFinal("Hello.")
        s.rows { it.singleOrNull()?.translation == "ответ" }
        assertEquals(listOf("google", "openai"), cloud.calls.toList())
        withTimeout(2_000) { s.engine.status.first { it.startsWith("Reserve 1") } }
    }

    @Test
    fun `a failed recogniser on the only method is started again`() = runBlocking {
        val s = Setup(Cloud(), keys)
        s.start()
        s.recognizer.listener!!.onFailure("closed")
        withTimeout(5_000) { while (s.recognizer.starts < 2) delay(20) }
    }

    @Test
    fun `with translation off a phrase is shown and nothing is sent`() = runBlocking {
        val s = Setup(Cloud(), keys.copy(translationEnabled = false))
        s.start()
        s.recognizer.listener!!.onFinal("Hello.")
        s.rows { it.singleOrNull()?.original == "Hello." }
        delay(200)
        assertEquals("", s.engine.rows.value.single().translation)
        assertTrue(s.cloud.calls.isEmpty())
    }

    @Test
    fun `a voice method hears the phrase cut at a pause, and silence leaves no row`() = runBlocking {
        val answers = ArrayDeque(listOf("<said>Hi</said>\n<translated>Привет</translated>", "<said></said>"))
        val cloud = object : Cloud() {
            override fun answer(call: String, prompt: ModelPrompt) = flowOf(answers.removeFirst())
        }
        val s = Setup(cloud, keys.copy(methodKeys = listOf("voice:gemini-3.5-flash-lite")))
        s.start()
        (tone(10) + silence(6)).forEach { s.source.onFrame!!(it) }
        s.rows { it.singleOrNull()?.translation == "Привет" }
        (tone(10) + silence(6)).forEach { s.source.onFrame!!(it) }
        withTimeout(5_000) { while (cloud.calls.size < 2) delay(10) }
        s.rows { it.size == 1 && it.single().original == "Hi" }
        assertEquals(listOf("google-audio", "google-audio"), cloud.calls.toList())
    }

    @Test
    fun `without a key the dialog does not start and says why`() = runBlocking {
        val s = Setup(Cloud(), ApplicationInfo())
        s.engine.start(s.source)
        val status = withTimeout(2_000) { s.engine.status.first { it.isNotEmpty() } }
        assertTrue("no Google API key" in status, status)
        assertEquals(false, s.engine.running.value)
    }

    @Test
    fun `clear drops the rows and what is still on its way`() = runBlocking {
        val cloud = object : Cloud() {
            override fun answer(call: String, prompt: ModelPrompt) = flow { delay(400); emit("late") }
        }
        val s = Setup(cloud, keys)
        s.start()
        s.recognizer.listener!!.onFinal("Hello.")
        s.rows { it.size == 1 }
        s.engine.clear()
        s.rows { it.isEmpty() }
        delay(700)
        assertTrue(s.engine.rows.value.isEmpty())
    }

    @Test
    fun `a long phrase is cut at the maximum and goes on, a short noise is dropped`() {
        val phrases = ArrayList<Int>()
        val cutter = PhraseCutter(object : PhraseCutter.Listener {
            override fun onPhrase(wav: ByteArray) { phrases += (wav.size - 44) / FRAME_BYTES }
        })
        cutter.recordPhrases = true
        (silence(5) + tone(70) + silence(6) + tone(2) + silence(6)).forEach(cutter::frame)
        assertEquals(2, phrases.size, phrases.toString())
        assertEquals(52, phrases[0], "fifty frames and the two kept from before the sound")
    }

    @Test
    fun `env keys are read, quoted or not, and everything else is kept`() {
        val info = EnvFile.apply("﻿# c\nexport DIALOG_GOOGLE_API_KEY=\"a b\" # x\nDIALOG_OPENAI_API_KEY=k # y\nDIALOG_CLAUDE_API_KEY=\nOTHER=1", ApplicationInfo(claudeToken = "old"))
        assertEquals(listOf("a b", "k", "old"), listOf(info.googleCloudToken, info.openAiToken, info.claudeToken))
    }
}
