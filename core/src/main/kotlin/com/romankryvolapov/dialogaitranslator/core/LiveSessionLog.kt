package com.romankryvolapov.dialogaitranslator.core

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Tells when a Gemini Live session closed by itself. The library (google-genai 2.6.0) has no callback for it and only
 * writes it to its log, so its log is listened to; drop this once the library reports it. A close the app asked for has
 * code 1000 and is not told.
 */
internal object LiveSessionLog {

    private const val LOGGER = "com.google.genai.AsyncLive"
    private const val NORMAL_CLOSE = 1000

    // Held here: java.util.logging keeps its loggers, and so their handlers, only weakly.
    private val logger: Logger = Logger.getLogger(LOGGER)

    fun onClosed(listener: (reason: String) -> Unit): AutoCloseable {
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                reasonOf(record)?.let(listener)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        return AutoCloseable { logger.removeHandler(handler) }
    }

    private fun reasonOf(record: LogRecord): String? {
        val message = record.message.orEmpty()
        if (message.startsWith("Error during live session")) return record.thrown?.message ?: "connection lost"
        if (!message.startsWith("Live session clos")) return null
        val code = record.parameters?.getOrNull(0) as? Int
        val reason = record.parameters?.getOrNull(1)?.toString().orEmpty()
        return if (code == NORMAL_CLOSE) null else reason.ifBlank { "the service closed the session ($code)" }
    }
}
