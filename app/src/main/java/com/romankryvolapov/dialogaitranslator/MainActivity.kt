package com.romankryvolapov.dialogaitranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.romankryvolapov.dialogaitranslator.core.AudioInput
import com.romankryvolapov.dialogaitranslator.core.EnvFile
import com.romankryvolapov.dialogaitranslator.service.DialogService
import com.romankryvolapov.dialogaitranslator.ui.AppScreen
import rikka.shizuku.Shizuku

/** The screens, and what only an activity can do: ask for permissions and open other apps. */
class MainActivity : ComponentActivity() {

    private companion object {
        const val SHIZUKU_REQUEST = 1
    }

    private val app get() = application as DialogApp

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Notifications may be refused: the dialog works without one.
        if (granted[Manifest.permission.RECORD_AUDIO] == true || hasMicrophone()) startDialog()
        else say("Without the microphone permission a dialog cannot start")
    }

    private val shizukuAnswer = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request != SHIZUKU_REQUEST) return@OnRequestPermissionResultListener
        runOnUiThread { if (result == PackageManager.PERMISSION_GRANTED) startDialog() else say("Shizuku permission was refused") }
    }

    private val envPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        if (text == null) {
            say("The file could not be read")
        } else {
            val before = app.settings.getAppInfo()
            app.settings.update { EnvFile.apply(text, it) }
            say(if (app.settings.getAppInfo() == before) "No new keys in this file" else "Keys imported")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The screen stays on while the app is in front: the answers are read during a call, with no hands free.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Shizuku.addRequestPermissionResultListener(shizukuAnswer)
        setContent { AppScreen(app, this) }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuAnswer)
        super.onDestroy()
    }

    fun toggleDialog() {
        if (app.engine.running.value) app.engine.stop() else startDialog()
    }

    private fun startDialog() {
        if (!hasMicrophone()) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        if (app.settings.getAppInfo().audioInput != AudioInput.MICROPHONE && !shizukuReady()) return
        DialogService.start(this)
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun shizukuReady(): Boolean {
        if (!Shizuku.pingBinder()) {
            say("Shizuku is not running: open Shizuku and start it")
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(::startActivity)
            return false
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        Shizuku.requestPermission(SHIZUKU_REQUEST)
        return false
    }

    fun importKeys() = envPicker.launch(arrayOf("*/*"))

    fun askOverlay() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    fun canOverlay(): Boolean = Settings.canDrawOverlays(this)

    fun shareDialog() {
        val text = app.dialogLog.latest() ?: return say("No dialog has been saved yet")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Dialog"))
    }

    fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
