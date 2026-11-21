package com.plaintext.audible

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.plaintext.audible.data.BackupManager
import com.plaintext.audible.data.ReleaseRepository
import com.plaintext.audible.ui.TrackerScreen
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var repo: ReleaseRepository
    private var sharedImportPayload by mutableStateOf<String?>(null)
    private var externalStatusBanner by mutableStateOf<Pair<String, Boolean>?>(null)

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or declined */ }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val json = BackupManager.exportJson(repo)
                        contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open destination file")
                    }
                }
                externalStatusBanner = if (result.isSuccess) {
                    "OK: BACKUP EXPORTED (.JSON)" to false
                } else {
                    "WARN: BACKUP EXPORT FAILED" to true
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Unable to read backup file")
                        require(raw.isNotBlank()) { "Backup file is empty" }
                        BackupManager.importJson(repo, raw)
                        val importedPrefs = repo.getPrefs()
                        ReleaseCheckWorker.scheduleDailyCheck(
                            this@MainActivity,
                            importedPrefs.notifyHour,
                            importedPrefs.notifyMinute
                        )
                        repo.syncCatalog(forceRefresh = false)
                    }
                }
                externalStatusBanner = if (result.isSuccess) {
                    "OK: BACKUP RESTORED (.JSON)" to false
                } else {
                    "WARN: INVALID OR UNREADABLE BACKUP FILE" to true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repo = ReleaseRepository(applicationContext)

        extractSharedTextFromIntent(intent)?.let { sharedImportPayload = it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = repo.getPrefs()
        ReleaseCheckWorker.scheduleDailyCheck(this, prefs.notifyHour, prefs.notifyMinute)

        setContent {
            TrackerScreen(
                repo = repo,
                sharedImportText = sharedImportPayload,
                onConsumeSharedImport = { sharedImportPayload = null },
                externalStatusMessage = externalStatusBanner?.first,
                externalStatusIsWarn = externalStatusBanner?.second ?: false,
                onConsumeExternalStatus = { externalStatusBanner = null },
                onExportBackup = { exportLauncher.launch("nocturnetxt-backup.json") },
                onImportBackup = { importLauncher.launch(arrayOf("application/json", "*/*")) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedTextFromIntent(intent)?.let { sharedImportPayload = it }
    }

    private fun extractSharedTextFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotBlank() }
                } else null
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.trim()?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }
}
