package com.romankryvolapov.dialogaitranslator.core

/** Reads the keys of a `.env` file (the PC app's format); every other line is ignored, so one file serves both. */
object EnvFile {

    fun apply(text: String, info: ApplicationInfo): ApplicationInfo {
        val values = text.lineSequence().mapNotNull(::entry).toMap()
        fun key(name: String, current: String) = values[name]?.takeIf { it.isNotBlank() } ?: current
        return info.copy(
            googleCloudToken = key("DIALOG_GOOGLE_API_KEY", info.googleCloudToken),
            openAiToken = key("DIALOG_OPENAI_API_KEY", info.openAiToken),
            claudeToken = key("DIALOG_CLAUDE_API_KEY", info.claudeToken),
        )
    }

    private fun entry(raw: String): Pair<String, String>? {
        val line = raw.trim().removePrefix("\uFEFF").removePrefix("export ").trim()
        val separator = line.indexOf('=')
        if (line.startsWith("#") || separator <= 0) return null
        val value = line.substring(separator + 1).trim()
        val quote = value.firstOrNull()
        val closing = if (quote == '"' || quote == '\'') value.indexOf(quote, 1) else -1
        val unquoted = if (closing > 0) value.substring(1, closing) else value.substringBefore(" #").trim()
        return line.substring(0, separator).trim() to unquoted
    }
}
