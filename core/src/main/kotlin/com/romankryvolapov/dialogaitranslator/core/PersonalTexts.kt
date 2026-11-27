package com.romankryvolapov.dialogaitranslator.core

/**
 * The user's own texts, kept out of the repository in a folder of their own and sent to the phone with the install:
 * facts about the user, what calls are about, and any of the Questions mode's prompts. Each file name names the
 * setting it fills; a file missing leaves its setting as it is.
 */
object PersonalTexts {

    private class Target(val part: PromptPart?, val set: (QuestionsSettings, String) -> QuestionsSettings)

    private val TARGETS = mapOf(
        "about_user.md" to Target(null) { s, v -> s.copy(aboutUser = v) },
        "topic.txt" to Target(null) { s, v -> s.copy(topic = v) },
        "questions_instructions.md" to Target(PromptPart.QUESTIONS_INSTRUCTIONS) { s, v -> s.copy(system = v) },
        "questions_message.md" to Target(PromptPart.QUESTIONS_MESSAGE) { s, v -> s.copy(user = v) },
        "follow_ups_instructions.md" to Target(PromptPart.ANSWER_LANGUAGE_INSTRUCTIONS) { s, v -> s.copy(followUpsSystem = v) },
        "follow_ups_message.md" to Target(PromptPart.FOLLOW_UPS_MESSAGE) { s, v -> s.copy(followUpsUser = v) },
        "expansion_instructions.md" to Target(PromptPart.ANSWER_LANGUAGE_INSTRUCTIONS) { s, v -> s.copy(expansionSystem = v) },
        "expansion_message.md" to Target(PromptPart.EXPANSION_MESSAGE) { s, v -> s.copy(expansionUser = v) },
    )

    val FILE_NAMES: Set<String> = TARGETS.keys

    /** What the files did: the settings with them applied, and why each file left out was left out. */
    data class Result(val info: ApplicationInfo, val applied: List<String>, val skipped: Map<String, String>)

    /** [files] by name, as read; a prompt that would not save from Settings is skipped too. */
    fun apply(files: Map<String, String>, info: ApplicationInfo): Result {
        var questions = info.questions
        val applied = ArrayList<String>()
        val skipped = LinkedHashMap<String, String>()
        for ((name, raw) in files.toSortedMap()) {
            val target = TARGETS[name]
            if (target == null) {
                skipped[name] = "not a known file"
                continue
            }
            val text = raw.removePrefix("\uFEFF").trim()
            val problem = target.part?.let { part -> if (text.isEmpty()) "empty: the prompt is left as it is" else promptProblem(part, text) }
            if (problem != null) {
                skipped[name] = problem
                continue
            }
            questions = target.set(questions, text)
            applied += name
        }
        return Result(info.copy(questions = questions), applied, skipped)
    }
}
