package com.romankryvolapov.dialogaitranslator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.romankryvolapov.dialogaitranslator.DialogApp
import com.romankryvolapov.dialogaitranslator.MainActivity
import com.romankryvolapov.dialogaitranslator.core.ApplicationInfo
import com.romankryvolapov.dialogaitranslator.core.TokenUsageTracker

private val colors = darkColorScheme(
    primary = Color(0xFF8AB8EE),
    onPrimary = Color(0xFF00315C),
    background = Color(0xFF1C1C1C),
    surface = Color(0xFF1C1C1C),
    surfaceContainer = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF2F2F2F),
)
val Green = Color(0xFF3C713C)
val Red = Color(0xFF8C3C3C)

private enum class Screen(val title: String) { MESSAGES("Messages"), QUESTIONS("Context"), SETTINGS("Settings") }

@Composable
fun AppScreen(app: DialogApp, activity: MainActivity) {
    MaterialTheme(colorScheme = colors) {
        var screen by rememberSaveable { mutableStateOf(Screen.MESSAGES) }
        var page by rememberSaveable { mutableStateOf(SettingsPage.entries.first()) }
        val info by app.settings.appInfoFlow.collectAsState()
        BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.MESSAGES }
        Scaffold(
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.statusBarsPadding()) {
                        PrimaryTabRow(selectedTabIndex = screen.ordinal, containerColor = Color.Transparent) {
                            Screen.entries.forEach {
                                Tab(selected = it == screen, onClick = { screen = it }, text = { Text(it.title) })
                            }
                        }
                        SoundLevel(app)
                    }
                }
            },
            bottomBar = { if (screen != Screen.SETTINGS) DialogBar(app, activity, screen) },
        ) { padding ->
            when (screen) {
                Screen.MESSAGES -> Messages(app, info, padding)
                Screen.QUESTIONS -> Questions(app, info, padding)
                Screen.SETTINGS -> SettingsScreen(app, activity, info, page, { page = it }, padding)
            }
        }
    }
}

/**
 * Only the buttons, and a line when something went wrong; the rest is in Settings → General. While a dialog runs, the
 * Questions screen adds Answer now, for a question the mode let pass.
 */
@Composable
private fun DialogBar(app: DialogApp, activity: MainActivity, screen: Screen) {
    val running by app.engine.running.collectAsState()
    val status by app.engine.status.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0B060))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = activity::toggleDialog,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (running) Red else Green, contentColor = Color.White),
                ) { Text(if (running) "Stop dialog" else "Start dialog") }
                if (screen == Screen.QUESTIONS && running) {
                    Button(
                        onClick = app.questions::answerNow,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("Answer now") }
                }
                Button(
                    onClick = { if (screen == Screen.QUESTIONS) app.questions.clear() else app.engine.clear() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444), contentColor = Color.White),
                ) { Text(if (screen == Screen.QUESTIONS) "Clear answers" else "Clear dialog") }
            }
        }
    }
}

/** How loud the sound being heard is, as a thin strip under the tabs; empty while no dialog runs. */
@Composable
private fun SoundLevel(app: DialogApp) {
    val running by app.engine.running.collectAsState()
    val level by app.engine.level.collectAsState()
    LinearProgressIndicator(
        progress = { if (running) level / 100f else 0f },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

fun tokensText(totals: TokenUsageTracker.Totals): String {
    fun short(n: Long) = if (n < 1000) "$n" else "%.1fk".format(java.util.Locale.ROOT, n / 1000.0)
    return "tokens in ${short(totals.input)} · out ${short(totals.output)}"
}

@Composable
private fun Messages(app: DialogApp, info: ApplicationInfo, padding: PaddingValues) {
    val rows by app.engine.rows.collectAsState()
    if (rows.isEmpty()) return Hint(
        if (info.googleCloudToken.isBlank() && info.openAiToken.isBlank() && info.claudeToken.isBlank())
            "Add an API key in Settings → API keys, then press Start dialog."
        else "Choose what to listen to in Settings → General, then press Start dialog.",
        padding,
    )
    Feed(rows, rows.last().let { it.original.length + it.translation.length }, { it.id }, padding) { Entry(it.original, it.translation) }
}

@Composable
private fun Questions(app: DialogApp, info: ApplicationInfo, padding: PaddingValues) {
    val all by app.answers.rows.collectAsState()
    val rows = all.filter { !it.dismissed }
    if (rows.isEmpty()) return Hint(
        if (info.questions.enabled) "Answers to the questions heard appear here; tap one for a fuller answer. Answer now answers the latest question by hand."
        else "Switch Questions on in Settings → General, or press Answer now in a dialog to answer the latest question.",
        padding,
    )
    Feed(rows, rows.last().let { last -> last.answer.length + last.expansions.sumOf { it.text.length } }, { it.id }, padding) {
        AnswerCard(app.answers, it)
    }
}

/** A list that follows its end, unless the user has scrolled away from it. */
@Composable
private fun <T> Feed(rows: List<T>, growth: Int, key: (T) -> Any?, padding: PaddingValues, entry: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    val atEnd by remember { derivedStateOf { !state.canScrollForward } }
    var seen by remember { mutableIntStateOf(0) }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state.isScrollInProgress) { if (!state.isScrollInProgress) follow = atEnd }
    LaunchedEffect(rows.size, growth) {
        if (follow || seen == 0) state.scrollToItem(rows.lastIndex, Int.MAX_VALUE / 2)
        seen = rows.size
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(12.dp, padding.calculateTopPadding() + 8.dp, 12.dp, padding.calculateBottomPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(rows, key = { i, row -> key(row) ?: i }) { _, row -> entry(row) }
    }
}

@Composable
private fun Entry(small: String, large: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        SelectionContainer {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(small, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA0A0A0))
                if (large.isNotBlank()) Text(large, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Hint(text: String, padding: PaddingValues) {
    Text(text, Modifier.padding(padding).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
