package com.romankryvolapov.dialogaitranslator.core

import kotlinx.serialization.Serializable

/** Settings → Questions: the mode that answers the questions asked in the conversation (Questions screen). */
@Serializable
data class QuestionsSettings(
    /** On: every phrase heard also goes to the questions request. */
    val enabled: Boolean = false,
    /** The model that finds and answers the questions, chosen apart from the translation methods. */
    val modelId: String = OpenAiModelsEnum.GPT_6_1_TERRA.type,
    /** The model that writes the fuller answers: they are asked for by hand, so a slower, stronger one fits. */
    val expansionModelId: String = OpenAiModelsEnum.GPT_6_1_TERRA.type,
    /** How many of the latest phrases each request carries; a question often spans several. */
    val phrases: Int = 8,
    /** How many of the latest answers the history keeps in full; older entries keep only their question. */
    val fullAnswers: Int = 3,
    /**
     * The languages the Questions screen shows a question and its answer in, as English names ("Russian"); a blank
     * question language is the language of the call. They fill the instructions' placeholders.
     */
    val questionDisplayLanguage: String = "Russian",
    val answerDisplayLanguage: String = "Russian",
    /** Facts about the user the answers may draw on: background, projects, skills. Kept on the phone only. */
    val aboutUser: String = "",
    /** What the call is about, such as its main technology or programming language; code is written in it. */
    val topic: String = "",
    val system: String = QuestionPrompts.INSTRUCTIONS,
    val user: String = QuestionPrompts.MESSAGE,
    val followUpsSystem: String = AnswerExpansionPrompts.FOLLOW_UPS_INSTRUCTIONS,
    val followUpsUser: String = AnswerExpansionPrompts.FOLLOW_UPS_MESSAGE,
    val expansionSystem: String = AnswerExpansionPrompts.EXPANSION_INSTRUCTIONS,
    val expansionUser: String = AnswerExpansionPrompts.EXPANSION_MESSAGE,
)
