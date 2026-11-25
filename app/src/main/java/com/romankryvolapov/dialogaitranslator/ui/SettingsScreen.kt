package com.romankryvolapov.dialogaitranslator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.romankryvolapov.dialogaitranslator.DialogApp
import com.romankryvolapov.dialogaitranslator.MainActivity
import com.romankryvolapov.dialogaitranslator.capture.shizukuProblem
import com.romankryvolapov.dialogaitranslator.core.ApplicationInfo
import com.romankryvolapov.dialogaitranslator.core.Hearing
import com.romankryvolapov.dialogaitranslator.core.LastRequests
import com.romankryvolapov.dialogaitranslator.core.LlmModel
import com.romankryvolapov.dialogaitranslator.core.ModelParameters
import com.romankryvolapov.dialogaitranslator.core.PromptPart
import com.romankryvolapov.dialogaitranslator.core.PromptSettings
import com.romankryvolapov.dialogaitranslator.core.QuestionPrompts
import com.romankryvolapov.dialogaitranslator.core.QuestionsSettings
import com.romankryvolapov.dialogaitranslator.core.SummarySettings
import com.romankryvolapov.dialogaitranslator.core.TextPrompt
import com.romankryvolapov.dialogaitranslator.core.TranslationMethod
import com.romankryvolapov.dialogaitranslator.core.VoicePrompt
import com.romankryvolapov.dialogaitranslator.core.allHearings
import com.romankryvolapov.dialogaitranslator.core.allLlmModels
import com.romankryvolapov.dialogaitranslator.core.effectiveParameters
import com.romankryvolapov.dialogaitranslator.core.findLlmModelById
import com.romankryvolapov.dialogaitranslator.core.hearing
import com.romankryvolapov.dialogaitranslator.core.promptProblem
import com.romankryvolapov.dialogaitranslator.core.textModels
import com.romankryvolapov.dialogaitranslator.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class SettingsPage(val title: String, val note: String) {
    GENERAL("General", "Translation methods, language, calls"),
    PHRASES("Phrases", "Where speech is cut into phrases"),
    MODELS("LLM", "Each model's request parameters"),
    KEYS("API keys", "Google, OpenAI, Claude"),
    MAIN_PROMPT("Main prompt", "Translation of recognised speech, summary"),
    VOICE_PROMPT("Voice prompt", "Models that hear the audio themselves"),
    QUESTIONS("Questions", "Suggested answers"),
    CONSOLE("Console", "What the app logged"),
}

private val LANGUAGES = listOf(
    "en-US", "en-GB", "ru-RU", "uk-UA", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-PT", "pl-PL", "bg-BG", "tr-TR", "ja-JP", "ko-KR",
)

@Composable
fun SettingsScreen(
    app: DialogApp,
    activity: MainActivity,
    info: ApplicationInfo,
    page: SettingsPage?,
    open: (SettingsPage?) -> Unit,
    padding: PaddingValues,
) {
    val update = app.settings::update
    if (page == null) {
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SettingsPage.entries.forEach {
                Column(Modifier.fillMaxWidth().clickable { open(it) }.padding(20.dp, 14.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleMedium)
                    Note(it.note)
                }
                HorizontalDivider()
            }
        }
        return
    }
    if (page == SettingsPage.CONSOLE) return Console(padding)
    Column(
        Modifier.padding(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(page.title, style = MaterialTheme.typography.headlineSmall)
        when (page) {
            SettingsPage.GENERAL -> General(activity, info, update)
            SettingsPage.PHRASES -> Phrases(info, update)
            SettingsPage.MODELS -> Models(info, update)
            SettingsPage.KEYS -> Keys(activity, info, update)
            SettingsPage.MAIN_PROMPT -> MainPrompt(app, info, update)
            SettingsPage.VOICE_PROMPT -> VoicePromptPage(app, info, update)
            SettingsPage.QUESTIONS -> QuestionsPage(app, info, update)
            SettingsPage.CONSOLE -> Unit
        }
    }
}

private typealias Update = ((ApplicationInfo) -> ApplicationInfo) -> Unit

@Composable
private fun General(activity: MainActivity, info: ApplicationInfo, update: Update) {
    Text("Translation methods", style = MaterialTheme.typography.titleMedium)
    Note("A dialog starts on method 1; when it fails, the next one takes over until the dialog is started again. First choose how the speech is heard, then, for a recogniser, the model that translates.")
    val chain = info.translationMethods
    for (position in 0 until TranslationMethod.MAX_CHAIN) {
        if (position > chain.size) break
        val method = chain.getOrNull(position)
        fun set(changed: TranslationMethod?) = update { old ->
            val methods = old.translationMethods.toMutableList()
            if (changed == null) methods.removeAt(position)
            else if (position < methods.size) methods[position] = changed
            else methods.add(changed)
            old.copy(methodKeys = methods.map { it.key }.distinct())
        }
        Text(if (position == 0) "Method 1" else "Method ${position + 1} (reserve)")
        val none: List<Hearing?> = if (position > 0) listOf(null) else emptyList()
        Picker("How the speech is heard", method?.hearing, none + allHearings(), { it?.title ?: "None" }) { hearing ->
            when (hearing) {
                null -> if (method != null) set(null)
                is Hearing.Voice -> set(TranslationMethod(hearing.model, null))
                is Hearing.Recognition -> {
                    val taken = chain.filterIndexed { i, m -> i != position && m.recognizer == hearing.recognizer }.map { it.model.id }
                    val model = method?.model?.takeIf { it.readsText && it.id !in taken } ?: textModels().firstOrNull { it.id !in taken }
                    model?.let { set(TranslationMethod(it, hearing.recognizer)) }
                }
            }
        }
        val recognizer = method?.recognizer
        if (recognizer != null) {
            val free = textModels().filter { m -> chain.none { it !== method && it.recognizer == recognizer && it.model.id == m.id } }
            Picker("Translated by", method.model, free, { it.title }) { set(TranslationMethod(it, recognizer)) }
        }
    }
    HorizontalDivider()
    Picker("Language of the conversation", info.speechLanguage, LANGUAGES, { it }) { language -> update { it.copy(speechLanguage = language) } }
    Note("The built-in prompts translate from English into Russian; for another pair edit the prompts too.")
    Toggle("Show the answer as it is written", info.useStreamingResponse) { on -> update { it.copy(useStreamingResponse = on) } }
    HorizontalDivider()
    Text("Calls and sound of this phone", style = MaterialTheme.typography.titleMedium)
    var shizuku by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            shizuku = withContext(Dispatchers.IO) { shizukuProblem() }?.plus(".") ?: "Shizuku is ready."
            delay(2_000)
        }
    }
    Note("Heard through Shizuku, which must be started again after the phone restarts. $shizuku")
    Toggle("In a call, hear me as well", info.hearBothSides) { on -> update { it.copy(hearBothSides = on) } }
    Note("Takes effect when a dialog starts.")
    OutlinedButton(onClick = activity::shareDialog) { Text("Share the last dialog") }
}

@Composable
private fun Phrases(info: ApplicationInfo, update: Update) {
    val now = info.phrases
    NumberField("Silence threshold, %", now.silenceThresholdPercents.toString(), "Sound below it counts as silence") { text ->
        text.toIntOrNull()?.takeIf { it in 0..99 }?.let { v -> update { it.copy(phrases = it.phrases.copy(silenceThresholdPercents = v)) } } != null
    }
    NumberField("Pause that ends a phrase, ms", now.maxSilenceMillis.toString(), "100 or more") { text ->
        text.toLongOrNull()?.takeIf { it in 100..10_000 }?.let { v -> update { it.copy(phrases = it.phrases.copy(maxSilenceMillis = v)) } } != null
    }
    NumberField("Longest phrase, ms", now.maxPhraseMillis.toString(), "For models that hear the audio; 1000 or more") { text ->
        text.toLongOrNull()?.takeIf { it in 1_000..60_000 }?.let { v -> update { it.copy(phrases = it.phrases.copy(maxPhraseMillis = v)) } } != null
    }
    Note("Android speech recognition finds the pauses itself.")
}

@Composable
private fun Models(info: ApplicationInfo, update: Update) {
    var model by remember { mutableStateOf(allLlmModels().first()) }
    Picker("Model", model, allLlmModels(), { it.title }) { model = it }
    val support = model.parameterSupport
    val chosen = info.modelParameters[model.id] ?: ModelParameters()
    fun set(changed: ModelParameters) = update {
        it.copy(modelParameters = if (changed == ModelParameters()) it.modelParameters - model.id else it.modelParameters + (model.id to changed))
    }
    androidx.compose.runtime.key(model.id) {
        Decimal("Temperature", support.temperature, chosen.temperature, 0.0..2.0) { set(chosen.copy(temperature = it)) }
        Decimal("Top P", support.topP, chosen.topP, 0.0..1.0) { set(chosen.copy(topP = it)) }
        if (support.topK) Whole("Top K", chosen.topK) { set(chosen.copy(topK = it)) }
        Whole("Max output tokens", chosen.maxOutputTokens) { set(chosen.copy(maxOutputTokens = it)) }
        if (support.thinkingLevels.isNotEmpty()) {
            Picker("Thinking", chosen.thinking, listOf<String?>(null) + support.thinkingLevels, { it ?: "Default" }) { set(chosen.copy(thinking = it)) }
        }
        if (support.verbosityLevels.isNotEmpty()) {
            Picker("Verbosity", chosen.verbosity, listOf<String?>(null) + support.verbosityLevels, { it ?: "Default" }) { set(chosen.copy(verbosity = it)) }
        }
    }
    val sent = effectiveParameters(support, chosen)
    Note(
        "Each request carries: " + listOfNotNull(
            sent.temperature?.let { "temperature $it" }, sent.topP?.let { "top P $it" }, sent.topK?.let { "top K $it" },
            sent.maxOutputTokens?.let { "max tokens $it" }, sent.thinking?.let { "thinking $it" }, sent.verbosity?.let { "verbosity $it" },
        ).joinToString().ifEmpty { "no parameters" },
    )
}

@Composable
private fun Decimal(label: String, offered: Boolean, value: Double?, range: ClosedRange<Double>, set: (Double?) -> Unit) {
    if (!offered) return
    NumberField(label, value?.toString().orEmpty(), "${range.start}–${range.endInclusive}; empty: the default") { text ->
        if (text.isBlank()) {
            set(null)
            true
        } else {
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it in range }?.let { set(it) } != null
        }
    }
}

@Composable
private fun Whole(label: String, value: Int?, set: (Int?) -> Unit) {
    NumberField(label, value?.toString().orEmpty(), "Empty: the default") { text ->
        if (text.isBlank()) {
            set(null)
            true
        } else {
            text.toIntOrNull()?.takeIf { it > 0 }?.let { set(it) } != null
        }
    }
}

@Composable
private fun Keys(activity: MainActivity, info: ApplicationInfo, update: Update) {
    Note("Kept encrypted on this phone.")
    KeyField("Google", info.googleCloudToken) { key -> update { it.copy(googleCloudToken = key) } }
    KeyField("OpenAI", info.openAiToken) { key -> update { it.copy(openAiToken = key) } }
    KeyField("Claude", info.claudeToken) { key -> update { it.copy(claudeToken = key) } }
    OutlinedButton(onClick = activity::importKeys) { Text("Import from a .env file") }
}

/**
 * A saved key never reaches the screen, only its length: what a field holds, hidden or not, accessibility services
 * can read. A new key is typed over it.
 */
@Composable
private fun KeyField(label: String, saved: String, save: (String) -> Unit) {
    val state = remember(saved) { TextFieldState() }
    OutlinedSecureTextField(
        state, Modifier.fillMaxWidth(), label = { Text(if (saved.isEmpty()) label else "$label — a new key") },
        supportingText = { Text(if (saved.isEmpty()) "Not set" else "Set, ${saved.length} characters") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.text.isNotBlank()) Button({ save(state.text.toString().trim()) }) { Text("Save") }
        if (saved.isNotEmpty()) OutlinedButton({ save("") }) { Text("Remove") }
    }
}

@Composable
private fun MainPrompt(app: DialogApp, info: ApplicationInfo, update: Update) {
    val prompts = info.prompts
    fun change(how: (PromptSettings) -> PromptSettings) = update { it.copy(prompts = how(it.prompts)) }
    NumberField("Previous phrases in each request", prompts.previousPhrases.toString(), "0–20") { text ->
        text.toIntOrNull()?.takeIf { it in 0..20 }?.let { v -> change { it.copy(previousPhrases = v) } } != null
    }
    PromptEditor("Instructions", PromptPart.INSTRUCTIONS, prompts.text.system, TextPrompt().system) { v -> change { it.copy(text = it.text.copy(system = v)) } }
    PromptEditor("Message", PromptPart.TEXT_MESSAGE, prompts.text.user, TextPrompt().user) { v -> change { it.copy(text = it.text.copy(user = v)) } }
    PromptEditor("Previous phrase format", PromptPart.PREVIOUS_PHRASE, prompts.text.previousPhrase, TextPrompt().previousPhrase) { v ->
        change { it.copy(text = it.text.copy(previousPhrase = v)) }
    }
    HorizontalDivider()
    Toggle("Conversation summary", prompts.summary.enabled) { on -> change { it.copy(summary = it.summary.copy(enabled = on)) } }
    NumberField("Rewrite the summary every … phrases", prompts.summary.everyPhrases.toString(), "1–100") { text ->
        text.toIntOrNull()?.takeIf { it in 1..100 }?.let { v -> change { it.copy(summary = it.summary.copy(everyPhrases = v)) } } != null
    }
    PromptEditor("Summary instructions", PromptPart.INSTRUCTIONS, prompts.summary.system, SummarySettings().system) { v ->
        change { it.copy(summary = it.summary.copy(system = v)) }
    }
    PromptEditor("Summary message", PromptPart.SUMMARY_MESSAGE, prompts.summary.user, SummarySettings().user) { v ->
        change { it.copy(summary = it.summary.copy(user = v)) }
    }
    val text by app.lastRequests.text.collectAsState()
    val summary by app.lastRequests.summary.collectAsState()
    val summaryRequest by app.lastRequests.summaryRequest.collectAsState()
    Sent("Last request", text)
    Sent("Last summary request", summaryRequest)
    if (summary.isNotBlank()) Shown("The summary now", summary)
}

@Composable
private fun VoicePromptPage(app: DialogApp, info: ApplicationInfo, update: Update) {
    val voice = info.prompts.voice
    fun change(how: (VoicePrompt) -> VoicePrompt) = update { it.copy(prompts = it.prompts.copy(voice = how(it.prompts.voice))) }
    PromptEditor("Instructions", PromptPart.INSTRUCTIONS, voice.system, VoicePrompt().system) { v -> change { it.copy(system = v) } }
    PromptEditor("Message", PromptPart.VOICE_MESSAGE, voice.user, VoicePrompt().user) { v -> change { it.copy(user = v) } }
    PromptEditor("Previous phrase format", PromptPart.PREVIOUS_PHRASE, voice.previousPhrase, VoicePrompt().previousPhrase) { v ->
        change { it.copy(previousPhrase = v) }
    }
    val sent by app.lastRequests.voice.collectAsState()
    Sent("Last request", sent)
}

@Composable
private fun QuestionsPage(app: DialogApp, info: ApplicationInfo, update: Update) {
    val now = info.questions
    fun change(how: (QuestionsSettings) -> QuestionsSettings) = update { it.copy(questions = how(it.questions)) }
    Toggle("Suggest answers to the questions heard", now.enabled) { on -> change { it.copy(enabled = on) } }
    Picker<LlmModel?>("Model", findLlmModelById(now.modelId), textModels(), { it?.title ?: now.modelId }) { m -> m?.let { model -> change { it.copy(modelId = model.id) } } }
    Picker("Show the question in", now.questionDisplayLanguage, listOf("") + QuestionPrompts.DISPLAY_LANGUAGES, { it.ifEmpty { "The language of the call" } }) { v ->
        change { it.copy(questionDisplayLanguage = v) }
    }
    Picker("Show the answer in", now.answerDisplayLanguage, QuestionPrompts.DISPLAY_LANGUAGES, { it }) { v -> change { it.copy(answerDisplayLanguage = v) } }
    NumberField("Phrases in each request", now.phrases.toString(), "1–20") { text ->
        text.toIntOrNull()?.takeIf { it in 1..20 }?.let { v -> change { it.copy(phrases = v) } } != null
    }
    NumberField("Answers kept in full", now.fullAnswers.toString(), "0–20") { text ->
        text.toIntOrNull()?.takeIf { it in 0..20 }?.let { v -> change { it.copy(fullAnswers = v) } } != null
    }
    PromptEditor("Instructions", PromptPart.QUESTIONS_INSTRUCTIONS, now.system, QuestionPrompts.INSTRUCTIONS) { v -> change { it.copy(system = v) } }
    PromptEditor("Message", PromptPart.QUESTIONS_MESSAGE, now.user, QuestionPrompts.MESSAGE) { v -> change { it.copy(user = v) } }
    val sent by app.lastRequests.questions.collectAsState()
    Sent("Last request", sent)
}

@Composable
private fun Console(padding: PaddingValues) {
    val lines by AppLog.lines.collectAsState()
    Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
        OutlinedButton(onClick = AppLog::clear) { Text("Clear") }
        SelectionContainer {
            LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                items(lines.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ---------- Pieces ----------

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, set)
    }
}

@Composable
private fun <T> Picker(label: String, value: T, options: List<T>, title: (T) -> String, pick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Note(label)
        Box {
            OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(title(value), Modifier.weight(1f)) }
            DropdownMenu(open, { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem({ Text(title(option)) }, {
                        open = false
                        if (option != value) pick(option)
                    })
                }
            }
        }
    }
}

/** [save] returns whether the text was acceptable. */
@Composable
private fun NumberField(label: String, saved: String, note: String, save: (String) -> Boolean) {
    var text by remember { mutableStateOf(saved) }
    var wrong by remember { mutableStateOf(false) }
    // What is typed stays as typed ("0." on the way to "0.7"); only a value changed elsewhere replaces it.
    LaunchedEffect(saved) {
        if (!wrong && text.replace(',', '.').toDoubleOrNull() != saved.toDoubleOrNull()) text = saved
    }
    OutlinedTextField(
        text, { text = it; wrong = !save(it.trim()) }, Modifier.fillMaxWidth(), singleLine = true, isError = wrong,
        label = { Text(label) }, supportingText = { Text(if (wrong) "Not saved. $note" else note) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun PromptEditor(label: String, part: PromptPart, saved: String, builtIn: String, save: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    val problem = promptProblem(part, text)
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 14, isError = problem != null,
        label = { Text(label) }, supportingText = problem?.let { { Text(it) } },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Saved blank, a part goes back to the built-in text.
        if (text != saved) Button({ save(text.ifBlank { builtIn }) }, enabled = problem == null) { Text("Save") }
        if (saved != builtIn) OutlinedButton({ save(builtIn) }) { Text("Reset to default") }
    }
}

@Composable
private fun Sent(label: String, sent: LastRequests.Sent?) {
    if (sent != null) Shown("$label — ${sent.model}", "[system]\n${sent.prompt.system}\n\n[user]\n${sent.prompt.user}")
}

@Composable
private fun Shown(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
}
