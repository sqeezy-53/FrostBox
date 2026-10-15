package org.frostbox.app

import android.app.Application
import android.app.ActivityManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.frostbox.app.data.ProfileRepository
import org.frostbox.app.diagnostics.DiagnosticLog
import org.frostbox.app.input.ControllerMonitor
import org.frostbox.app.runtime.NativeRuntime
import org.frostbox.app.runtime.RuntimeProbeClient
import org.frostbox.app.runtime.LibraryLauncher
import org.frostbox.app.data.TreeImporter
import org.frostbox.app.thermal.*
import org.frostbox.core.*

class FrostModel(app: Application): AndroidViewModel(app) {
    private val repository = ProfileRepository(app)
    val log = DiagnosticLog(app)
    val controllers = ControllerMonitor(app,viewModelScope)
    val probes = RuntimeProbeClient(app)
    val gpu=org.frostbox.app.gpu.VulkanSession(app,viewModelScope)
    val display=org.frostbox.app.display.DisplaySession()
    val launcher=LibraryLauncher(app,repository,viewModelScope,display,controllers)
    val importProgress=MutableStateFlow(TreeImporter.Progress())
    private var importJob: Job?=null
    val executables=MutableStateFlow<List<String>>(emptyList())
    val wineProbeAvailable = runCatching { app.assets.open("runtime/wine-probe.json").use { } }.isSuccess
    val wow64Available = runCatching { app.assets.open("wine-wow64/manifest.json").close() }.isSuccess
    private val runtime = NativeRuntime()
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val libraryBusy = MutableStateFlow(false)
    val nativeInfo = MutableStateFlow("Probing system Vulkan…")
    val history = MutableStateFlow<List<ThermalSample>>(emptyList())
    private var thermalJob: Job? = null
    val profiles = repository.profiles.catch { message.value="Profile storage error: ${it.message}"; log.record(message.value) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        viewModelScope.launch {launcher.activeId.filter {it==null}.collect {controllers.useGameMapping(null)}}
        viewModelScope.launch(Dispatchers.IO) {
            probes.state.drop(1).filter { !it.active }.collect { log.record(it.report) }
        }
        action {
            nativeInfo.value = runtime.diagnostic()
            log.record(nativeInfo.value)
            if (Build.VERSION.SDK_INT >= 30) {
                app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 3)
                    .forEach { log.record("Previous process exit: reason=${it.reason} status=${it.status} time=${it.timestamp}") }
            }
        }
    }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message.value=e.message ?: e.javaClass.simpleName; log.record("Error: ${message.value}") }
    }
    fun startMonitoring() {
        controllers.start()
        if (thermalJob?.isActive != true) thermalJob = viewModelScope.launch {
            ThermalMonitor(getApplication()).samples.catch { message.value="Thermal API error: ${it.message}" }
                .collect { history.value=(history.value+it).takeLast(60) }
        }
    }
    fun stopMonitoring() { controllers.stop(); thermalJob?.cancel(); thermalJob=null }
    fun importExe(uri: Uri) {
        if(busy.value || libraryBusy.value) return
        busy.value=true;importProgress.value=TreeImporter.Progress()
        importJob=action {
            try { val p=repository.importExe(uri) { importProgress.value=it };message.value="Added ${p.name}; import did not execute it" }
            finally { busy.value=false }
        }
    }
    fun importFolder(p: GameProfile,uri: Uri) {
        if(busy.value || libraryBusy.value || launcher.activeId.value!=null) return
        busy.value=true;importProgress.value=TreeImporter.Progress()
        importJob=action {
            try { repository.importTree(p,uri) { importProgress.value=it };message.value="Folder imported; relative paths preserved" }
            finally { busy.value=false }
        }
    }
    fun importCover(id: String,uri: Uri) {
        if(busy.value || libraryBusy.value || launcher.activeId.value!=null)return
        busy.value=true
        action {try {repository.importCover(id,uri);message.value=getApplication<Application>().getString(org.frostbox.app.R.string.cover_saved)}finally{busy.value=false}}
    }
    fun cancelImport() { importJob?.cancel();message.value="Import cancellation requested" }
    fun loadExecutables(p: GameProfile) = action { repository.recoverImport(p.id);executables.value=repository.listExecutables(p.id) }
    fun save(p: GameProfile) = action { repository.saveEdited(p);message.value="Profile saved" }
    private fun libraryIdle() = launcher.activeId.value==null && !probes.state.value.active
    fun remove(p: GameProfile) = changeLibrary(p.id)
    fun clearLibrary() = changeLibrary(null)
    private fun changeLibrary(id: String?) {
        // Reserve synchronously on the UI thread, before launching IO, to reject double taps
        // and prevent a new Play/import while the record transaction is pending.
        if(libraryBusy.value || busy.value) return
        if(!libraryIdle()) { message.value=getApplication<Application>().getString(org.frostbox.app.R.string.library_stop_first);return }
        libraryBusy.value=true
        viewModelScope.launch {
            try {
                if(id==null) repository.clearEntries(::libraryIdle) else repository.removeEntry(id,::libraryIdle)
                message.value=getApplication<Application>().getString(if(id==null) org.frostbox.app.R.string.library_cleared else org.frostbox.app.R.string.library_record_removed)
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                message.value=e.message ?: e.javaClass.simpleName;log.record("Library: ${message.value}")
            } finally { libraryBusy.value=false }
        }
    }
    val gameSettingsFailure=MutableStateFlow("")
    fun gameSettings(id: String,change: (GameProfile)->GameProfile) = action {
        try {repository.updateGameSettings(id,change);gameSettingsFailure.value=""}
        catch(e: CancellationException){throw e}
        catch(e: Exception){gameSettingsFailure.value=e.message ?: e.toString();throw e}
    }
    private var restartJob: Job?=null
    fun restartGame(id: String) {
        if(restartJob?.isActive==true)return
        restartJob=action {
            check(launcher.activeId.value==id)
            withContext(Dispatchers.Main.immediate){launcher.stop()}
            withTimeout(45000){launcher.activeId.first {it==null}}
            val current=repository.profiles.first().single {it.id==id}
            withContext(Dispatchers.Main.immediate){play(current)}
        }
    }
    fun play(p: GameProfile) { gameSettingsFailure.value="";if(libraryBusy.value || busy.value) return; if(!wineProbeAvailable) { message.value="This build has no Wine package";return };controllers.useGameMapping(p.controllerMapping);launcher.play(p) }
    fun exportRuntime(p: GameProfile,uri: Uri) = action {
        val result=launcher.sessions.value[p.id]?.result ?: repository.readRun(p.id)?.toString() ?: "{}"
        getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use { it.write(result) } ?: error("Cannot export report")
        message.value="Runtime report exported"
    }
    fun check(p: GameProfile) = action {
        val result=runtime.prepare(p) as LaunchResult.Unavailable
        message.value=result.reason; log.record("Runtime check: ${result.reason}")
    }
    fun exportProfiles(uri: Uri) = action { repository.export(uri); message.value="Configuration exported (game files not included)" }
    fun exportLogs(uri: Uri) = action {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
            it.write((listOf(nativeInfo.value, probes.state.value.report) + log.lines.value + history.value.map { s -> s.toString() }).joinToString("\n"))
        } ?: error("Cannot open log destination")
        message.value="Diagnostics exported"
    }
    override fun onCleared() { gpu.close();launcher.close();probes.close(); stopMonitoring(); super.onCleared() }
}
