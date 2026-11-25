package com.romankryvolapov.dialogaitranslator

import android.app.Application
import com.romankryvolapov.dialogaitranslator.core.ActiveMethod
import com.romankryvolapov.dialogaitranslator.core.CloudRepositoryImpl
import com.romankryvolapov.dialogaitranslator.core.DialogEngine
import com.romankryvolapov.dialogaitranslator.core.GeminiLiveRecognizer
import com.romankryvolapov.dialogaitranslator.core.LastRequests
import com.romankryvolapov.dialogaitranslator.core.QuestionsAssistant
import com.romankryvolapov.dialogaitranslator.core.QuestionsDisplay
import com.romankryvolapov.dialogaitranslator.core.RateLimiters
import com.romankryvolapov.dialogaitranslator.core.TokenUsageTracker
import com.romankryvolapov.dialogaitranslator.core.VoiceRecognitionEngine
import com.romankryvolapov.dialogaitranslator.data.DialogLog
import com.romankryvolapov.dialogaitranslator.data.SettingsStore
import com.romankryvolapov.dialogaitranslator.speech.SystemRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Holds the one instance of everything the screens and the service share. */
class DialogApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var engine: DialogEngine
        private set
    lateinit var questions: QuestionsAssistant
        private set

    val answers = AnswersList()
    val activeMethod = ActiveMethod()
    val tokenUsage = TokenUsageTracker()
    val lastRequests = LastRequests()
    val dialogLog by lazy { DialogLog(filesDir) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        val limiters = RateLimiters()
        val cloud = CloudRepositoryImpl(tokenUsage)
        questions = QuestionsAssistant(cloud, settings, limiters, lastRequests, answers)
        engine = DialogEngine(
            cloud, settings, activeMethod, tokenUsage, lastRequests, limiters, questions,
            recognizerFor = {
                when (it) {
                    VoiceRecognitionEngine.GEMINI_LIVE -> GeminiLiveRecognizer()
                    VoiceRecognitionEngine.SYSTEM -> SystemRecognizer(this)
                }
            },
            dialogLog = { dialogLog.write(it) },
        )
    }
}

data class Answer(val question: String, val answer: String)

/** The Questions screen's rows. */
class AnswersList : QuestionsDisplay {
    private val state = MutableStateFlow<List<Answer>>(emptyList())
    val rows: StateFlow<List<Answer>> = state

    override fun add(question: String, answer: String): Int = synchronized(this) {
        state.update { it + Answer(question, answer) }
        state.value.lastIndex
    }

    override fun update(row: Int, question: String, answer: String) = synchronized(this) {
        state.update { rows -> rows.mapIndexed { i, old -> if (i == row) Answer(question, answer) else old } }
    }

    override fun clear() = synchronized(this) { state.value = emptyList() }
}
