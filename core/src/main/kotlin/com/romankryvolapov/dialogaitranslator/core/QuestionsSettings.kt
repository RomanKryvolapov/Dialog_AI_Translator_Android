package com.romankryvolapov.dialogaitranslator.core

import kotlinx.serialization.Serializable

/** Settings → Questions: the mode that answers the questions asked in the conversation (Questions screen). */
@Serializable
data class QuestionsSettings(
    /** On: every phrase heard also goes to the questions request. */
    val enabled: Boolean = false,
    /** The model that finds and answers the questions, chosen apart from the translation methods. */
    val modelId: String = OpenAiModelsEnum.GPT_6_1_TERRA.type,
    /** How many of the latest phrases each request carries; a question often spans several. */
    val phrases: Int = 8,
    /** How many of the latest answers the history keeps in full; older entries keep only their question. */
    val fullAnswers: Int = 3,
    /**
     * The languages the Questions screen shows a question and its answer in, as English names ("Russian"); a blank
     * question language is the language of the call. They fill the instructions' placeholders.
     */
    val questionDisplayLanguage: String = "",
    val answerDisplayLanguage: String = "English",
    val system: String = QuestionPrompts.INSTRUCTIONS,
    val user: String = QuestionPrompts.MESSAGE,
)
