package com.notlexi.nlfileeditor.tmx

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class TmxFullUi(
    val installed: Boolean = false,
    val release: String? = null,
    val installing: Boolean = false,
    val phase: InstallPhase = InstallPhase.IDLE,
    val fraction: Float = 0f,
    val detail: String = "",
    val error: String? = null,
    val compatibilityMode: Boolean = false,
    val unsupported: String? = null
)

/** State of the NL TMX Full environment. Installation runs in the ViewModel scope, so leaving the screen does not stop it. */
class TmxFullModel(private val app: Application, private val scope: CoroutineScope) {
    private val installer = RootfsInstaller(app)
    private val prefs = app.getSharedPreferences("nl_tmx", Context.MODE_PRIVATE)
    private val cancel = AtomicBoolean(false)
    private var job: Job? = null

    private val _ui = MutableStateFlow(
        TmxFullUi(
            installed = installer.isInstalled(),
            release = installer.installedRelease(),
            compatibilityMode = prefs.getBoolean("compat", false),
            unsupported = TmxSessions.fullEntryPossible(app)
        )
    )
    val ui: StateFlow<TmxFullUi> = _ui.asStateFlow()

    fun download() {
        if (job?.isActive == true) return
        cancel.set(false)
        _ui.update { it.copy(installing = true, error = null, phase = InstallPhase.FINDING, fraction = 0f, detail = "Starting…") }
        job = scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    installer.install({ cancel.get() }) { p ->
                        _ui.update { it.copy(phase = p.phase, fraction = p.fraction, detail = p.detail) }
                    }
                    null
                } catch (e: InstallException) {
                    e.message
                } catch (e: Exception) {
                    "Installation failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            val ok = error == null && installer.isInstalled()
            _ui.update {
                it.copy(
                    installing = false, installed = ok, release = if (ok) installer.installedRelease() else null,
                    error = error, phase = if (ok) InstallPhase.DONE else InstallPhase.IDLE
                )
            }
        }
    }

    fun cancelDownload() { cancel.set(true) }

    fun uninstall() {
        if (_ui.value.installing) return
        TmxSessions.full?.finishIfRunning()
        TmxSessions.full = null
        scope.launch {
            withContext(Dispatchers.IO) { installer.uninstall() }
            _ui.update { it.copy(installed = false, release = null, error = null, phase = InstallPhase.IDLE, fraction = 0f) }
        }
    }

    fun setCompatibilityMode(on: Boolean) {
        prefs.edit().putBoolean("compat", on).apply()
        _ui.update { it.copy(compatibilityMode = on) }
    }
}
