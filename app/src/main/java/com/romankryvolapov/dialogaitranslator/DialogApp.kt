package com.romankryvolapov.dialogaitranslator

import android.app.Application
import com.romankryvolapov.dialogaitranslator.core.ActiveMethod
import com.romankryvolapov.dialogaitranslator.core.AnswerExpander
import com.romankryvolapov.dialogaitranslator.core.CloudRepositoryImpl
import com.romankryvolapov.dialogaitranslator.core.DialogEngine
import com.romankryvolapov.dialogaitranslator.core.GeminiLiveRecognizer
import com.romankryvolapov.dialogaitranslator.core.LastRequests
import com.romankryvolapov.dialogaitranslator.core.QuestionsAssistant
import com.romankryvolapov.dialogaitranslator.core.RateLimiters
import com.romankryvolapov.dialogaitranslator.core.TokenUsageTracker
import com.romankryvolapov.dialogaitranslator.core.VoiceRecognitionEngine
import com.romankryvolapov.dialogaitranslator.data.AnswersList
import com.romankryvolapov.dialogaitranslator.data.DialogLog
import com.romankryvolapov.dialogaitranslator.data.SettingsStore
import com.romankryvolapov.dialogaitranslator.speech.SpeechPacks
import com.romankryvolapov.dialogaitranslator.speech.SystemRecognizer

/** Holds the one instance of everything the screens and the service share. */
class DialogApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var engine: DialogEngine
        private set
    lateinit var questions: QuestionsAssistant
        private set
    lateinit var answers: AnswersList
        private set

    val activeMethod = ActiveMethod()
    val tokenUsage = TokenUsageTracker()
    val lastRequests = LastRequests()
    val dialogLog by lazy { DialogLog(filesDir) }
    val speechPacks by lazy { SpeechPacks(this) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        val limiters = RateLimiters()
        val cloud = CloudRepositoryImpl(tokenUsage)
        answers = AnswersList(AnswerExpander(cloud, settings, limiters, lastRequests))
        questions = QuestionsAssistant(cloud, settings, limiters, lastRequests, answers)
        engine = DialogEngine(
            cloud, settings, activeMethod, tokenUsage, lastRequests, limiters, questions,
            recognizerFor = {
                when (it) {
                    VoiceRecognitionEngine.GEMINI_LIVE -> GeminiLiveRecognizer()
                    VoiceRecognitionEngine.SYSTEM -> SystemRecognizer(this, speechPacks)
                }
            },
            dialogLog = { dialogLog.write(it) },
        )
    }
}
