package com.romankryvolapov.dialogaitranslator.data

import com.romankryvolapov.dialogaitranslator.core.AnswerExpander
import com.romankryvolapov.dialogaitranslator.core.QuestionsDisplay
import com.romankryvolapov.dialogaitranslator.core.backgroundThreadScope
import com.romankryvolapov.dialogaitranslator.core.classifyFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/** The follow-up questions offered for an answer: asked for when its menu is first opened. */
sealed interface FollowUps {
    data object NotAsked : FollowUps
    data object Loading : FollowUps
    data class Ready(val questions: List<String>) : FollowUps
    data class Failed(val reason: String) : FollowUps
}

/** A fuller answer to one follow-up, as it is written; [failure] set when it stopped short. */
data class Expansion(val id: Long, val followUp: String, val text: String, val done: Boolean = false, val failure: String? = null)

/** One row of the Questions screen; [id] stays with the row, unlike its place in the list. */
data class Answer(
    val id: Long,
    val question: String,
    val answer: String,
    val followUps: FollowUps = FollowUps.NotAsked,
    /** The answer text [followUps] were asked for: a tap while the answer was still being written asked for less. */
    val followUpsFor: String? = null,
    val expansions: List<Expansion> = emptyList(),
    /**
     * Taken off the screen by the user. The row stays in the list, hidden, since the Questions mode addresses rows by
     * their place in it; the mode still remembers the question, so it is not answered again.
     */
    val dismissed: Boolean = false,
)

/**
 * The Questions screen's rows, and the requests a row's menu makes: the follow-up questions it offers, and the fuller
 * answer to the one picked, which grows under the row's answer. Clear drops the rows and what is still on its way.
 */
class AnswersList(private val expander: AnswerExpander) : QuestionsDisplay {
    private val log = LoggerFactory.getLogger("Answers")
    private val state = MutableStateFlow<List<Answer>>(emptyList())
    private val ids = AtomicLong()
    private val jobs = ArrayList<Job>()
    val rows: StateFlow<List<Answer>> = state

    override fun add(question: String, answer: String): Int = synchronized(this) {
        state.update { it + Answer(ids.incrementAndGet(), question, answer) }
        state.value.lastIndex
    }

    /** The question asked again in full: what was asked for the old wording no longer fits, so it goes. */
    override fun update(row: Int, question: String, answer: String) = synchronized(this) {
        state.update { rows ->
            rows.mapIndexed { i, old ->
                when {
                    i != row -> old
                    old.question == question -> old.copy(answer = answer)
                    else -> Answer(old.id, question, answer, dismissed = old.dismissed)
                }
            }
        }
    }

    /** The follow-ups are asked for as soon as an answer is complete, so its menu opens with them ready. */
    override fun settled(row: Int) {
        state.value.getOrNull(row)?.let { askFollowUps(it.id) }
    }

    override fun clear() = synchronized(this) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        state.value = emptyList()
    }

    /** Takes row [id] off the screen. */
    fun dismiss(id: Long) = change(id) { it.copy(dismissed = true) }

    /** Asks for the follow-up questions of row [id], unless those for its answer as it stands are there or on their way. */
    fun askFollowUps(id: Long) {
        val row = synchronized(this) {
            val row = state.value.firstOrNull { it.id == id } ?: return
            val current = row.followUpsFor == row.answer
            if (current && (row.followUps is FollowUps.Loading || row.followUps is FollowUps.Ready)) return
            change(id) { it.copy(followUps = FollowUps.Loading, followUpsFor = row.answer) }
            row
        }
        launch {
            val result = try {
                FollowUps.Ready(expander.followUps(row.question, row.answer))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Follow-up questions not made: {}", reason(e))
                FollowUps.Failed(reason(e))
            }
            // A later request for a fuller text of the answer owns the row now.
            change(id) { if (it.followUpsFor == row.answer) it.copy(followUps = result) else it }
        }
    }

    /** Writes the fuller answer to [followUp] under row [id]. */
    fun expand(id: Long, followUp: String) {
        val mine = ids.incrementAndGet()
        val row = synchronized(this) {
            val row = state.value.firstOrNull { it.id == id } ?: return
            change(id) { it.copy(expansions = it.expansions + Expansion(mine, followUp, "")) }
            row
        }
        fun write(how: (Expansion) -> Expansion) = change(id) { answer ->
            answer.copy(expansions = answer.expansions.map { if (it.id == mine) how(it) else it })
        }
        launch {
            try {
                expander.expand(row.question, row.answer, followUp).collect { text -> write { it.copy(text = text) } }
                write { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Fuller answer not written: {}", reason(e))
                write { it.copy(done = true, failure = reason(e)) }
            }
        }
    }

    private fun change(id: Long, how: (Answer) -> Answer) = synchronized(this) {
        state.update { rows -> rows.map { if (it.id == id) how(it) else it } }
    }

    private fun launch(work: suspend () -> Unit) = synchronized(this) {
        jobs.removeAll { !it.isActive }
        jobs += backgroundThreadScope.launch { work() }
    }

    private fun reason(e: Throwable): String =
        if (e is IllegalStateException) e.message.orEmpty() else classifyFailure(e).kind.reason
}
