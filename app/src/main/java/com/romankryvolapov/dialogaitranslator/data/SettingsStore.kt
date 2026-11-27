package com.romankryvolapov.dialogaitranslator.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.romankryvolapov.dialogaitranslator.core.ApplicationInfo
import com.romankryvolapov.dialogaitranslator.core.EnvFile
import com.romankryvolapov.dialogaitranslator.core.PersonalTexts
import com.romankryvolapov.dialogaitranslator.core.PreferencesRepository
import com.romankryvolapov.dialogaitranslator.core.backgroundThreadScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The settings, the keys among them, in one file encrypted with a key the Android Keystore holds. */
class SettingsStore(context: Context) : PreferencesRepository {

    private val log = LoggerFactory.getLogger("Settings")

    private val store: DataStore<ApplicationInfo> = DataStoreFactory.create(
        serializer = AeadSerializer(aead(context), InfoSerializer, FILE.toByteArray()),
        corruptionHandler = ReplaceFileCorruptionHandler {
            log.error("The saved settings cannot be read, starting with the defaults", it)
            ApplicationInfo()
        },
        produceFile = { context.dataStoreFile(FILE) },
    )

    // Read once at start, a few kilobytes: everything after it needs the settings at hand.
    private val state = MutableStateFlow(runBlocking { store.data.first() })
    private val changed = Channel<Unit>(Channel.CONFLATED)

    override val appInfoFlow: StateFlow<ApplicationInfo> = state
    override fun getAppInfo(): ApplicationInfo = state.value

    init {
        backgroundThreadScope.launch {
            for (change in changed) {
                try {
                    store.updateData { state.value }
                } catch (e: Exception) {
                    log.error("The settings were not saved: {}", e.message, e)
                }
            }
        }
        importLeftFile(File(context.filesDir, IMPORT_FILE))
        importPersonal(File(context.filesDir, PERSONAL_DIR))
    }

    override fun update(change: (ApplicationInfo) -> ApplicationInfo) {
        state.update(change)
        changed.trySend(Unit)
    }

    /** Keys left in the app's private folder over a cable are taken in once and the file is deleted. */
    private fun importLeftFile(file: File) {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrElse { return log.warn("The keys file could not be read: {}", it.message) }
        update { EnvFile.apply(text, it) }
        if (!file.delete()) log.warn("The imported keys file could not be deleted")
        val info = getAppInfo()
        log.info("Keys imported, lengths: Google {}, OpenAI {}, Claude {}", info.googleCloudToken.length, info.openAiToken.length, info.claudeToken.length)
    }

    /** The user's own texts left over a cable ([PersonalTexts]): taken in once, and the folder deleted. */
    private fun importPersonal(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val texts = files.mapNotNull { file ->
            runCatching { file.name to file.readText() }.onFailure { log.warn("{} could not be read: {}", file.name, it.message) }.getOrNull()
        }.toMap()
        val result = PersonalTexts.apply(texts, getAppInfo())
        update { result.info }
        if (!dir.deleteRecursively()) log.warn("The imported personal texts could not be deleted")
        log.info("Personal texts taken in: {}", result.applied.ifEmpty { listOf("none") }.joinToString())
        result.skipped.forEach { (name, why) -> log.warn("Personal text {} left out: {}", name, why) }
    }

    private companion object {
        const val FILE = "settings.bin"
        const val PERSONAL_DIR = "import-personal"
        const val KEYSET = "settings_keyset"
        const val IMPORT_FILE = "import.env"

        fun aead(context: Context): Aead {
            AeadConfig.register()
            return try {
                openKeyset(context)
            } catch (e: Exception) {
                // The Keystore lost its key (a reset lock screen, say): what it encrypted cannot be read any more.
                LoggerFactory.getLogger("Settings").error("The settings key is lost, starting with the defaults", e)
                context.deleteSharedPreferences(KEYSET)
                context.dataStoreFile(FILE).delete()
                openKeyset(context)
            }
        }

        private fun openKeyset(context: Context): Aead =
            AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET, KEYSET)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://settings_master_key")
                .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private object InfoSerializer : Serializer<ApplicationInfo> {
        /** Defaults are not written: a field never changed follows the built-in value of the next version. */
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }
        override val defaultValue = ApplicationInfo()

        override suspend fun readFrom(input: InputStream): ApplicationInfo = try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("settings are not readable", e)
        }

        override suspend fun writeTo(t: ApplicationInfo, output: OutputStream) =
            output.write(json.encodeToString(ApplicationInfo.serializer(), t).encodeToByteArray())
    }
}
