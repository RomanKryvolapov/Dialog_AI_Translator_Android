package com.romankryvolapov.dialogaitranslator.core

enum class LlmModelEngine(
    override val type: String,
) : TypeEnum {
    GOOGLE(type = "Google"),
    OPENAI(type = "OpenAI"),
    CLAUDE(type = "Claude"),
}
