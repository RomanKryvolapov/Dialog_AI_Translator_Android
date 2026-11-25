package com.romankryvolapov.dialogaitranslator.data

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What was said and its translations, one private file per day. */
class DialogLog(filesDir: File) {
    private val folder = File(filesDir, "dialogs")
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val log = LoggerFactory.getLogger("DialogLog")

    @Synchronized
    fun write(line: String) {
        try {
            folder.mkdirs()
            File(folder, "${LocalDate.now()}.txt").appendText("${LocalTime.now().format(time)} $line\n")
        } catch (e: IOException) {
            log.error("The dialog log was not written: {}", e.message)
        }
    }

    /** The end of the newest day's log, as much as another app can be handed; null when there is none. */
    @Synchronized
    fun latest(): String? = try {
        folder.listFiles()?.maxByOrNull { it.name }?.readText()?.takeLast(SHARED_CHARS)
    } catch (e: IOException) {
        log.error("The dialog log was not read: {}", e.message)
        null
    }

    private companion object {
        const val SHARED_CHARS = 100_000
    }
}
