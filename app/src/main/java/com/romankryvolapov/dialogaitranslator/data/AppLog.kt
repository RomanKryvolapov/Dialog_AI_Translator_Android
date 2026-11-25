package com.romankryvolapov.dialogaitranslator.data

import android.util.Log
import com.romankryvolapov.dialogaitranslator.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** What the Console page shows: the last lines the app and its libraries logged. */
object AppLog {
    private const val KEPT = 400
    private const val MAX_LINE = 2_000
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val state = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = state

    fun write(level: Level, name: String, message: String, error: Throwable?) {
        val text = message.take(MAX_LINE)
        val tag = name.substringAfterLast('.').take(23)
        when (level) {
            Level.ERROR -> Log.e(tag, text, error)
            Level.WARN -> Log.w(tag, text, error)
            Level.INFO -> Log.i(tag, text)
            else -> Log.d(tag, text)
        }
        val line = "${LocalTime.now().format(time)} $tag: $text" + (error?.let { " (${it.javaClass.simpleName})" } ?: "")
        state.update { (it + line).takeLast(KEPT) }
    }

    fun clear() {
        state.value = emptyList()
    }
}

/** SLF4J on Android: the app's own messages in full in a debug build, the libraries' from INFO on. */
class AppLogProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory() = ILoggerFactory { name -> loggers.getOrPut(name) { AppLogger(name) } }
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion() = "2.0.99"
    override fun initialize() = Unit
}

private class AppLogger(name: String) : LegacyAbstractLogger() {
    init {
        this.name = name
    }

    /** The app's own loggers have plain names, the libraries' are class names. */
    private val detailed = BuildConfig.DEBUG && '.' !in name

    override fun isTraceEnabled() = false
    override fun isDebugEnabled() = detailed
    override fun isInfoEnabled() = true
    override fun isWarnEnabled() = true
    override fun isErrorEnabled() = true
    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, messagePattern: String?, arguments: Array<out Any?>?, throwable: Throwable?) =
        AppLog.write(level, name, MessageFormatter.basicArrayFormat(messagePattern, arguments) ?: "", throwable)
}
