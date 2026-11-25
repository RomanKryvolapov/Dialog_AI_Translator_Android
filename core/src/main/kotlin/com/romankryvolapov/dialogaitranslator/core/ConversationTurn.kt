package com.romankryvolapov.dialogaitranslator.core

/**
 * One phrase of the live conversation and, once the model has answered it with a prompt that uses
 * the conversation, its translation. In voice mode the phrase is audio, so [source] stays empty until
 * the model's transcript arrives. Written on background threads, read when later prompts are built.
 */
class ConversationTurn(source: String) {
    @Volatile
    var source: String = source

    @Volatile
    var translation: String? = null

    /** Who said it, when the recogniser tells speakers apart ("S1", "S2"…); no recogniser here does yet. */
    @Volatile
    var speaker: String? = null
}
