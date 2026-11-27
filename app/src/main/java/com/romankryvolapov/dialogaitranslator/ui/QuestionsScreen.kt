package com.romankryvolapov.dialogaitranslator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.romankryvolapov.dialogaitranslator.core.splitCodeBlocks
import com.romankryvolapov.dialogaitranslator.data.Answer
import com.romankryvolapov.dialogaitranslator.data.AnswersList
import com.romankryvolapov.dialogaitranslator.data.FollowUps

private val Warning = Color(0xFFE0B060)
private val BLANK_LINES = Regex("""\n\s*\n""")

/**
 * One answer on the Questions screen: the question as the model restated it, in the language chosen for it, the
 * answer, and the fuller answers asked for under it. A tap opens, under it, the follow-up questions the other side may
 * ask next; picking one writes a fuller answer that covers it. They open inside the card rather than in a pop-up, which
 * Android puts over the text when the card reaches the bottom of the screen.
 */
@Composable
fun AnswerCard(answers: AnswersList, row: Answer) {
    var open by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable {
            open = !open
            if (open) answers.askFollowUps(row.id)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(row.question, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA0A0A0))
                Text(
                    "✕",
                    Modifier.clip(CircleShape).clickable { answers.dismiss(row.id) }.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFA0A0A0),
                )
            }
            RichText(row.answer)
            row.expansions.forEach { expansion ->
                HorizontalDivider()
                if (expansion.text.isBlank() && !expansion.done) Note("Writing a fuller answer…")
                RichText(expansion.text)
                expansion.failure?.let { Text("Stopped: $it", style = MaterialTheme.typography.bodySmall, color = Warning) }
            }
            if (open) FollowUpList(row.followUps, retry = { answers.askFollowUps(row.id) }) { question ->
                open = false
                answers.expand(row.id, question)
            }
        }
    }
}

/** The follow-up questions under an answer, one per line with a rule between them. */
@Composable
private fun FollowUpList(followUps: FollowUps, retry: () -> Unit, pick: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
        Column {
            when (followUps) {
                FollowUps.NotAsked, FollowUps.Loading -> FollowUpLine("Finding follow-up questions…", enabled = false) {}
                is FollowUps.Failed -> FollowUpLine("No follow-up questions: ${followUps.reason}. Tap to try again.", onClick = retry)
                is FollowUps.Ready -> followUps.questions.forEachIndexed { i, question ->
                    if (i > 0) HorizontalDivider()
                    FollowUpLine(question) { pick(question) }
                }
            }
        }
    }
}

@Composable
private fun FollowUpLine(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        withInlineCode(text),
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A model's text: prose as it is, fenced code in a box of its own that scrolls sideways rather than wrapping. */
@Composable
fun RichText(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitCodeBlocks(text).forEach { part ->
            if (part.code) {
                Surface(Modifier.fillMaxWidth(), color = Color(0xFF141414), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        part.text,
                        Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            } else {
                // An empty line between the gist and its points only takes room on a small screen.
                Text(withInlineCode(part.text.replace(BLANK_LINES, "\n")), style = style)
            }
        }
    }
}

/** `code` inside a sentence in monospace, its backticks dropped. */
private fun withInlineCode(text: String): AnnotatedString = buildAnnotatedString {
    text.split('`').forEachIndexed { i, piece ->
        if (i % 2 == 1) withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF141414))) { append(piece) }
        else append(piece)
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
