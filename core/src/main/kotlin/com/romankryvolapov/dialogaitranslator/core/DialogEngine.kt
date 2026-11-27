package com.romankryvolapov.dialogaitranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

/** One phrase on the Messages screen. */
data class DialogRow(val id: Long, val original: String, val translation: String)

/**
 * The live dialog: audio in, speech heard by the method in use, phrases translated by it and checked for questions.
 * The logic is the PC app's (its Messages screen), without the screen: what is shown is [rows].
 *
 * Everything runs on one single-threaded dispatcher, so no state here needs a lock; audio and recognisers hand their
 * events over to it, and only the model requests run elsewhere.
 */
class DialogEngine(
    private val cloud: CloudRepository,
    private val preferences: PreferencesRepository,
    private val activeMethod: ActiveMethod,
    private val tokenUsage: TokenUsageTracker,
    private val lastRequests: LastRequests,
    private val rateLimiters: RateLimiters,
    private val questions: QuestionsAssistant,
    private val recognizerFor: (VoiceRecognitionEngine) -> SpeechRecognizer,
    /** Each line of the dialog, for the dialog log. */
    private val dialogLog: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {

    private companion object {
        const val MAX_QUEUE = 8
        /** Attempts per phrase in all, across the methods the dialog moves through; on the last method, with pauses. */
        const val MAX_ATTEMPTS = 3
        const val TTL_MILLIS = 90_000L
        const val WAIT_SLICE_MILLIS = 1_000L
        const val SUMMARY_TIMEOUT_MILLIS = 60_000L
        /** An answer that has not started, or has stopped arriving, for this long is a failure. */
        const val STALL_TIMEOUT_MILLIS = 30_000L
        const val SKIPPED = "(skipped — no translation method could take it in time)"
        const val PENDING = "…"
        val CONCURRENCY = 2..8
        const val DEFAULT_CONCURRENCY = 6
        const val ASSUMED_REQUEST_SECONDS = 6.0
        /** About six seconds of audio between the source and the engine; more means the engine is stuck. */
        const val AUDIO_BACKLOG = 64
    }

    private val log = LoggerFactory.getLogger("DialogEngine")
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log.error("A dialog task failed: {}", e.message, e) },
    )

    private val rowsState = MutableStateFlow<List<DialogRow>>(emptyList())
    private val runningState = MutableStateFlow(false)
    private val statusState = MutableStateFlow("")
    private val levelState = MutableStateFlow(0)
    private val methodState = MutableStateFlow(preferences.getAppInfo().translationMethods.first().shortTitle)

    val rows: StateFlow<List<DialogRow>> = rowsState.asStateFlow()
    val running: StateFlow<Boolean> = runningState.asStateFlow()
    val status: StateFlow<String> = statusState.asStateFlow()
    /** Loudness of what is heard, 0–100. */
    val level: StateFlow<Int> = levelState.asStateFlow()
    val methodInUse: StateFlow<String> = methodState.asStateFlow()

    private val context = ConversationContext()
    private var epoch = 0L
    private var nextId = 0L
    private var nextSeq = 0L

    private var source: AudioSource? = null
    private var audioJob: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var wiring: Wiring? = null
    /** The row the recogniser's partials go to; null until the next phrase starts. */
    private var liveRow: Long? = null

    private val queue = ArrayList<Task>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var schedulerJob: Job? = null
    private var concurrency = DEFAULT_CONCURRENCY
    private var dispatchSlots = Semaphore(concurrency)
    /** Voice phrases go one at a time, in the order they were said: each prompt needs the transcript before it. */
    private val voiceSlot = Semaphore(1)

    /** What decides how the audio is taken in; a change rewires it. */
    private data class Wiring(val engine: VoiceRecognitionEngine?, val language: String, val key: String) {
        override fun toString() = "Wiring($engine, $language, key of ${key.length})"
    }

    private val cutter = PhraseCutter(object : PhraseCutter.Listener {
        override fun onSpeech(pcm: ByteArray) {
            recognizer?.takeIf { !it.feedsContinuously }?.feed(pcm)
        }

        override fun onSpeechEnd() {
            recognizer?.takeIf { !it.feedsContinuously }?.endOfUtterance()
        }

        override fun onPhrase(wav: ByteArray) = onVoicePhrase(wav)
    })

    init {
        scope.launch {
            combine(preferences.appInfoFlow, activeMethod.flow) { info, _ -> info }.collect { info ->
                methodState.value = activeMethod.current(info.translationMethods).shortTitle
                cutter.settings = info.phrases
                if (runningState.value) wire(info)
            }
        }
        scope.launch {
            // Another chain or another key starts the dialog on method 1 again.
            preferences.appInfoFlow
                .map { listOf(it.translationMethods.map(TranslationMethod::key), it.googleCloudToken, it.openAiToken, it.claudeToken) }
                .distinctUntilChanged().drop(1)
                .collect { activeMethod.reset() }
        }
    }

    /** Starts listening to [audio]; when no method can work, the status says why and nothing starts. */
    fun start(audio: AudioSource) {
        scope.launch {
            if (runningState.value) return@launch
            val info = preferences.getAppInfo()
            val chain = info.translationMethods
            activeMethod.reset()
            val skipped = ArrayList<String>()
            while (true) {
                val method = activeMethod.current(chain)
                val problem = startProblem(method, info) ?: break
                skipped += "${method.shortTitle}: $problem"
                if (!activeMethod.failed(chain, method, problem)) {
                    statusState.value = "Cannot start — ${skipped.joinToString("; ")}"
                    return@launch
                }
            }
            runningState.value = true
            statusState.value = skipped.joinToString("; ") { "skipped $it" }
            dialogLog("--- dialog started: ${activeMethod.current(chain).title}")
            wiring = null
            cutter.reset()
            wire(info)
            val frames = Channel<ByteArray>(AUDIO_BACKLOG)
            audioJob = launch {
                for (pcm in frames) {
                    try {
                        onFrame(pcm)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("A frame was not handled: {}", e.message, e)
                        fail(audio, "Listening broke off: ${e.message}")
                    }
                }
            }
            source = audio
            try {
                audio.start(
                    onFrame = { if (frames.trySend(it).isFailure) log.warn("A frame of audio was lost: the engine is behind") },
                    onFailure = { fail(audio, it) },
                )
            } catch (e: Exception) {
                log.error("The audio source did not start: {}", e.message, e)
                fail(audio, "Cannot listen: ${e.message}")
            }
        }
    }

    /** [audio] cannot go on: the dialog stops and the status says why. */
    private fun fail(audio: AudioSource, reason: String) {
        scope.launch {
            if (source !== audio) return@launch
            stopNow()
            statusState.value = reason
        }
    }

    fun stop() {
        scope.launch { stopNow() }
    }

    private fun stopNow() {
        // Stopped already, perhaps by a failure whose reason the status still shows.
        if (!runningState.value) return
        source?.stop()
        source = null
        audioJob?.cancel()
        audioJob = null
        recognizer?.stop()
        recognizer = null
        wiring = null
        liveRow?.let { id -> rowsState.value.firstOrNull { it.id == id && it.translation.isEmpty() }?.let { remove(id) } }
        liveRow = null
        cutter.reset()
        levelState.value = 0
        dialogLog("--- dialog stopped")
        runningState.value = false
        statusState.value = ""
    }

    /** Empties the screen and forgets the conversation; a running dialog goes on with a new one. */
    fun clear() {
        scope.launch {
            epoch += 1
            tokenUsage.reset()
            schedulerJob?.cancel()
            schedulerJob = null
            queue.clear()
            context.clear()
            lastRequests.summaryIs("")
            questions.clear()
            liveRow = null
            rowsState.value = emptyList()
            statusState.value = ""
        }
    }

    // ---------- Hearing ----------

    /** The recogniser of [method], or the phone's own while translation is off and nobody asks the voice model. */
    private fun engineFor(method: TranslationMethod, info: ApplicationInfo): VoiceRecognitionEngine? =
        method.recognizer ?: VoiceRecognitionEngine.SYSTEM.takeIf { !info.translationEnabled }

    private fun wire(info: ApplicationInfo) {
        val engine = engineFor(activeMethod.current(info.translationMethods), info)
        val wanted = Wiring(engine, info.speechLanguage, if (engine == VoiceRecognitionEngine.GEMINI_LIVE) info.googleCloudToken else "")
        if (wanted == wiring) return
        wiring = wanted
        recognizer?.stop()
        liveRow?.let(::remove)
        liveRow = null
        cutter.recordPhrases = engine == null
        recognizer = engine?.let(recognizerFor)
        recognizer?.start(info, RecognizerEvents(wanted))
    }

    private inner class RecognizerEvents(private val mine: Wiring) : SpeechRecognizer.Listener {
        override fun onPartial(text: String) = post { if (text.isNotBlank()) show(liveRowId(), text, "") }

        override fun onFinal(text: String, pauseBeforeMillis: Long?) = post {
            if (text.isBlank()) {
                liveRow?.let(::remove)
                liveRow = null
                return@post
            }
            val id = liveRowId()
            liveRow = null
            show(id, text, pending())
            dialogLog("> $text")
            val added = beginTurn(text)
            added.turn.pauseBeforeMillis = pauseBeforeMillis
            phraseHeard(added.turn)
            enqueue(id, added, null)
        }

        override fun onFailure(reason: String) = post {
            val chain = preferences.getAppInfo().translationMethods
            if (switchAfterFailure(chain, activeMethod.current(chain), reason)) return@post
            // The last method: it stays, so its recogniser is started again after a pause.
            statusState.value = "$reason — trying again"
            delay(2_000)
            if (wiring === mine) {
                wiring = null
                wire(preferences.getAppInfo())
            }
        }

        /** Events of a recogniser that has been replaced, or of a dialog that has ended, are no longer news. */
        private fun post(action: suspend () -> Unit) {
            scope.launch { if (runningState.value && wiring === mine) action() }
        }
    }

    private fun liveRowId(): Long = liveRow ?: addRow(PENDING, "").also { liveRow = it }

    private fun onFrame(pcm: ByteArray) {
        levelState.value = voiceLevel(pcm)
        recognizer?.takeIf { it.feedsContinuously }?.feed(pcm)
        cutter.frame(pcm)
    }

    private fun onVoicePhrase(wav: ByteArray) {
        val id = addRow(PENDING, PENDING)
        enqueue(id, beginTurn(""), wav)
    }

    private fun phraseHeard(turn: ConversationTurn) = questions.phraseHeard(turn, context.currentSummary)

    /**
     * The phrase's turn in the conversation, taken when its row appears, so turns keep the order the phrases were
     * said in. A turn with no text yet stays out of every prompt and every summary until it gets one.
     */
    private fun beginTurn(text: String): ConversationContext.Added {
        val info = preferences.getAppInfo()
        // The summary serves the translations and the questions: with both off nobody would read it.
        val canSummarize = (info.translationEnabled || info.questions.enabled) &&
            info.translationMethods.any { it.model.readsText && info.apiKey(it.model).isNotBlank() }
        val added = context.add(text, TranslationPrompts.contextSettings(info.prompts, canSummarize))
        added.fold?.let { fold(it, info) }
        return added
    }

    /** Why [method] cannot work, or null if it can. */
    private fun startProblem(method: TranslationMethod, info: ApplicationInfo): String? {
        if (info.translationEnabled && info.apiKey(method.model).isBlank()) return "no ${method.model.engine.type} API key"
        return when (engineFor(method, info)) {
            VoiceRecognitionEngine.GEMINI_LIVE -> "no Google API key".takeIf { info.googleCloudToken.isBlank() }
            VoiceRecognitionEngine.SYSTEM, null -> null
        }
    }

    // ---------- Rows ----------

    private fun addRow(original: String, translation: String): Long {
        val id = nextId++
        rowsState.update { it + DialogRow(id, original, translation) }
        return id
    }

    private fun show(id: Long, original: String, translation: String) =
        rowsState.update { rows -> rows.map { if (it.id == id) DialogRow(id, original, translation) else it } }

    private fun remove(id: Long) = rowsState.update { rows -> rows.filter { it.id != id } }

    private fun pending() = if (preferences.getAppInfo().translationEnabled) PENDING else ""

    // ---------- Translation ----------

    private class Task(
        val seq: Long,
        val row: Long,
        val turn: ConversationTurn,
        /** The conversation as it stood before this phrase. */
        val context: ConversationContext.Snapshot,
        val epoch: Long,
        /** The recorded phrase, for a voice method. */
        val wav: ByteArray?,
        /** The dialog (start) it belongs to: a failure from an ended dialog switches nothing in the next. */
        val dialog: Long,
    ) {
        val bornAt = System.currentTimeMillis()
        var attempts = 0
        val failures = ArrayList<String>()
        /** A voice answer in the wrong shape, shown as it is if no other method does better. */
        var rawAnswer: String? = null

        val stale: Boolean get() = System.currentTimeMillis() - bornAt > TTL_MILLIS

        /** Its transcript can only come from a model, and the next phrase's prompt needs it: such phrases go in order. */
        val awaitsTranscript: Boolean get() = wav != null && turn.source.isEmpty()
    }

    private fun enqueue(row: Long, added: ConversationContext.Added, wav: ByteArray?) {
        if (!preferences.getAppInfo().translationEnabled && added.turn.source.isNotBlank()) {
            show(row, added.turn.source, "")
            return
        }
        requeue(Task(++nextSeq, row, added.turn, added.before, epoch, wav, activeMethod.flow.value.dialog))
        if (schedulerJob?.isActive != true) schedulerJob = scope.launch { schedule() }
    }

    private fun requeue(task: Task) {
        queue.add(task)
        queue.sortByDescending { it.seq }
        while (queue.size > MAX_QUEUE) drop(queue.removeAt(queue.lastIndex))
        wakeups.trySend(Unit)
    }

    /** Text phrases go newest first, so a backlog never delays what is said now; those awaiting a transcript, oldest first. */
    private suspend fun take(): Task {
        while (true) {
            val task = queue.filter { it.awaitsTranscript }.minByOrNull { it.seq } ?: queue.firstOrNull()
            if (task != null) {
                queue.remove(task)
                return task
            }
            wakeups.receive()
        }
    }

    /** Sends each queued phrase to the method in use: text phrases several at a time, voice phrases one by one. */
    private suspend fun schedule() {
        while (true) {
            val task = take()
            if (task.epoch != epoch) continue
            if (task.stale) {
                drop(task)
                continue
            }
            if (!preferences.getAppInfo().translationEnabled) {
                // Switched off while the phrase waited: it is not sent after all.
                if (task.turn.source.isNotBlank()) show(task.row, task.turn.source, "")
                else {
                    context.discard(task.turn)
                    remove(task.row)
                }
                continue
            }
            sizePool(activeMethod.current(preferences.getAppInfo().translationMethods).model)

            // The slot before the choice: a voice phrase waits here for the one before it, and its prompt then
            // carries that phrase's transcript.
            val slot = if (task.awaitsTranscript) voiceSlot else dispatchSlots
            slot.acquire()
            val info = preferences.getAppInfo()
            val chain = info.translationMethods
            if (task.awaitsTranscript && queue.any { it.awaitsTranscript && it.seq < task.seq }) {
                slot.release()
                requeue(task)
                continue
            }
            val method = activeMethod.current(chain)
            val problem = startProblem(method, info)
            if (problem != null && switchAfterFailure(chain, method, problem, task.dialog)) {
                slot.release()
                requeue(task)
                continue
            }
            val unusable = problem ?: when {
                method.voice && task.wav == null -> "hears only recorded phrases"
                !method.voice && task.turn.source.isBlank() -> "reads text only, and this phrase was recorded for a voice model"
                else -> null
            }
            if (unusable != null) {
                slot.release()
                give(task, task.failures + "${method.shortTitle}: $unusable")
                continue
            }
            val limiter = rateLimiters.forModel(method.model)
            val wait = limiter.tryReserve(estimateTokens(method, task, info))
            if (wait > 0) {
                slot.release()
                if (wait > SHORT_WAIT_MILLIS) {
                    // Its own rate limit would hold the phrase back: for live speech a failure like any other.
                    val busy = "its rate limit would hold the phrase for ${wait / 1000} s"
                    if (switchAfterFailure(chain, method, busy, task.dialog)) {
                        requeue(task)
                        continue
                    }
                    if (wait > task.bornAt + TTL_MILLIS - System.currentTimeMillis()) {
                        give(task, task.failures + "${method.shortTitle}: $busy")
                        continue
                    }
                    statusState.value = "Waiting: ${method.shortTitle} — $busy"
                }
                queue.add(task)
                queue.sortByDescending { it.seq }
                withTimeoutOrNull(wait.coerceAtMost(WAIT_SLICE_MILLIS)) { wakeups.receive() }
                continue
            }
            scope.launch {
                try {
                    translate(task, info, method, limiter)
                } finally {
                    slot.release()
                    wakeups.trySend(Unit)
                }
            }
        }
    }

    private fun sizePool(model: LlmModel) {
        val wanted = model.limitRequestsInMinute
            ?.let { ceil(it * ASSUMED_REQUEST_SECONDS / 60).toInt().coerceIn(CONCURRENCY) } ?: DEFAULT_CONCURRENCY
        if (wanted == concurrency) return
        concurrency = wanted
        dispatchSlots = Semaphore(wanted)
    }

    /** Built when it is sent, so it carries the transcripts and translations that came in meanwhile. */
    private fun promptFor(method: TranslationMethod, task: Task, info: ApplicationInfo): ModelPrompt =
        if (method.voice) TranslationPrompts.voice(info.prompts, task.context)
        else TranslationPrompts.text(info.prompts, task.context, task.turn.source)

    /** Gemini counts 32 tokens per second of audio, and a second is 32,000 bytes. */
    private fun estimateTokens(method: TranslationMethod, task: Task, info: ApplicationInfo): Int =
        estimateTokens(promptFor(method, task, info)) + if (method.voice) task.wav!!.size / 1000 else 0

    private fun estimateTokens(prompt: ModelPrompt): Int = (prompt.system.length + prompt.user.length) / 4 + 256

    private fun stream(model: LlmModel, info: ApplicationInfo, prompt: ModelPrompt, wav: ByteArray? = null): Flow<String> {
        val parameters = effectiveParameters(model.parameterSupport, info.modelParameters[model.id])
        val key = info.apiKey(model)
        return when (model.engine) {
            LlmModelEngine.GOOGLE ->
                if (wav == null) cloud.streamGoogleAI(model.id, key, prompt, parameters)
                else cloud.streamGoogleAIWithAudio(model.id, key, prompt, parameters, wav)
            LlmModelEngine.OPENAI ->
                if (wav == null) cloud.streamOpenAI(model.id, key, prompt, parameters)
                else cloud.streamOpenAIWithAudio(model.id, key, prompt, parameters, wav)
            LlmModelEngine.CLAUDE -> {
                check(wav == null) { "${model.id} does not hear audio" }
                cloud.streamClaude(model.id, key, prompt, parameters)
            }
        }.flowOn(Dispatchers.IO)
    }

    @OptIn(FlowPreview::class)
    private suspend fun translate(task: Task, info: ApplicationInfo, method: TranslationMethod, limiter: TranslationRateLimiter) {
        val prompt = promptFor(method, task, info)
        val sent = LastRequests.Sent(method.model.title, prompt)
        if (method.voice) lastRequests.voiceSent(sent) else lastRequests.textSent(sent)
        var answer = ""
        try {
            stream(method.model, info, prompt, task.wav.takeIf { method.voice })
                .timeout(STALL_TIMEOUT_MILLIS.milliseconds)
                .collect { text ->
                    answer = text
                    if (task.epoch == epoch && info.useStreamingResponse) showPart(task, method, text)
                }
        } catch (e: TimeoutCancellationException) {
            failed(task, method, limiter, Failure(FailureKind.TIMEOUT, "nothing arrived for ${STALL_TIMEOUT_MILLIS / 1000} s"))
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed(task, method, limiter, classifyFailure(e))
            return
        }
        if (task.epoch != epoch) return
        if (method.voice) finishVoice(task, method, limiter, answer) else finishText(task, method, limiter, answer)
    }

    private fun showPart(task: Task, method: TranslationMethod, answer: String) {
        if (!method.voice) return show(task.row, task.turn.source, answer.ifBlank { PENDING })
        val parsed = VoiceAnswer.parse(answer)
        show(task.row, parsed.said?.ifBlank { null } ?: PENDING, parsed.translated?.ifBlank { null } ?: PENDING)
    }

    private suspend fun finishText(task: Task, method: TranslationMethod, limiter: TranslationRateLimiter, answer: String) {
        val translation = answer.withoutEmptyLines()
        if (translation.isBlank()) return failed(task, method, limiter, Failure(FailureKind.BAD_ANSWER, "empty answer"))
        noteSuccess(method)
        task.turn.translation = translation
        dialogLog("< $translation")
        show(task.row, task.turn.source, translation)
    }

    private suspend fun finishVoice(task: Task, method: TranslationMethod, limiter: TranslationRateLimiter, answer: String) {
        val parsed = VoiceAnswer.parse(answer)
        val said = parsed.said.orEmpty()
        val translated = parsed.translated.orEmpty()
        when {
            said.isNotBlank() && translated.isNotBlank() -> {
                noteSuccess(method)
                task.turn.source = said
                task.turn.translation = translated
                dialogLog("> $said")
                dialogLog("< $translated")
                phraseHeard(task.turn)
                show(task.row, said, translated)
            }
            parsed.said != null && said.isBlank() -> {
                // The model heard no speech: an answer, not a failure.
                noteSuccess(method)
                context.discard(task.turn)
                remove(task.row)
            }
            else -> {
                task.rawAnswer = answer
                failed(task, method, limiter, Failure(FailureKind.BAD_ANSWER, "answer not in the expected format"))
            }
        }
    }

    private fun noteSuccess(method: TranslationMethod) {
        val position = preferences.getAppInfo().translationMethods.indexOfFirst { it.key == method.key }
        statusState.value = if (position > 0) "Reserve $position in use: ${method.title}" else ""
    }

    /**
     * [method] could not translate [task]: the dialog moves on to the next method for good, and the phrase goes back
     * to the queue for it — after a pause when the method in use is still the same one, the last of the chain.
     * Runs before the failed request gives back its slot: a phrase awaiting a transcript is back in the queue before
     * the next one can take it.
     */
    private suspend fun failed(task: Task, method: TranslationMethod, limiter: TranslationRateLimiter, failure: Failure) {
        log.warn("{} failed a phrase ({}): {}", method.title, failure.kind, failure.detail)
        limiter.note(failure)
        task.attempts += 1
        task.failures += "${method.shortTitle}: ${failure.kind.reason}"
        val chain = preferences.getAppInfo().translationMethods
        switchAfterFailure(chain, method, failure.kind.reason, task.dialog)
        if (task.epoch != epoch) return
        if (task.attempts >= MAX_ATTEMPTS || task.stale) return give(task, task.failures)
        val pause = if (activeMethod.current(chain).key == method.key) 1000L shl task.attempts else 0L
        if (pause == 0L || task.awaitsTranscript) {
            delay(pause)
            if (task.epoch == epoch) requeue(task)
        } else {
            scope.launch {
                delay(pause)
                if (task.epoch == epoch) requeue(task)
            }
        }
    }

    private fun switchAfterFailure(
        chain: List<TranslationMethod>,
        method: TranslationMethod,
        reason: String,
        dialog: Long = activeMethod.flow.value.dialog,
    ): Boolean {
        val switched = activeMethod.failed(chain, method, reason, dialog)
        if (switched) {
            val now = activeMethod.current(chain)
            log.warn("{} failed ({}): {} takes over until the dialog is started again", method.title, reason, now.title)
            statusState.value = "${method.shortTitle}: $reason — switched to ${now.shortTitle} until the dialog is started again"
        }
        return switched
    }

    /** No method could translate [task]: its row says so and why. */
    private fun give(task: Task, reasons: List<String>) {
        if (task.awaitsTranscript) context.discard(task.turn)
        if (task.epoch != epoch) return
        val why = reasons.distinct().joinToString("; ").ifBlank { "no translation method could take it" }
        log.warn("Phrase not translated: {}", why)
        val raw = task.rawAnswer
        val parsed = raw?.let(VoiceAnswer::parse)
        show(
            task.row,
            parsed?.said?.ifBlank { null } ?: task.turn.source.ifBlank { PENDING },
            parsed?.translated?.ifBlank { null } ?: raw?.withoutEmptyLines()?.ifBlank { null } ?: "(not translated — $why)",
        )
        statusState.value = "Not translated: $why"
    }

    /** A phrase no method could get to in time. */
    private fun drop(task: Task) {
        if (task.awaitsTranscript) context.discard(task.turn)
        if (task.epoch != epoch) return
        show(task.row, task.turn.source.ifBlank { PENDING }, SKIPPED)
        statusState.value = "Some phrases skipped: speech is faster than the translation methods' rate limits"
    }

    // ---------- Summary ----------

    /** Folds the phrases that left the window into the running summary: one background request. */
    private fun fold(fold: ConversationContext.Fold, info: ApplicationInfo) {
        val chain = info.translationMethods
        val model = (listOf(activeMethod.current(chain)) + chain)
            .firstOrNull { it.model.readsText && info.apiKey(it.model).isNotBlank() }?.model
        if (model == null) {
            context.foldDone(fold, null)
            return
        }
        scope.launch {
            var summary: String? = null
            val limiter = rateLimiters.forModel(model)
            try {
                val prompt = TranslationPrompts.summary(info.prompts, fold)
                // Bounded, the wait for quota included: a request that never ends would block every later summary.
                summary = withTimeoutOrNull(SUMMARY_TIMEOUT_MILLIS) {
                    while (true) {
                        val wait = limiter.tryReserve(estimateTokens(prompt))
                        if (wait == 0L) break
                        delay(wait.coerceAtMost(WAIT_SLICE_MILLIS))
                    }
                    lastRequests.summarySent(LastRequests.Sent(model.title, prompt))
                    stream(model, info, prompt).lastOrNull()
                }?.withoutEmptyLines()?.takeIf { it.isNotBlank() }
                if (summary == null) log.warn("Conversation summary timed out or came back empty")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val failure = classifyFailure(e)
                limiter.note(failure)
                log.warn("Conversation summary failed ({}): {}", failure.kind, failure.detail)
            } finally {
                context.foldDone(fold, summary)
                lastRequests.summaryIs(context.currentSummary)
            }
        }
    }

    private fun String.withoutEmptyLines(): String =
        lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")
}
