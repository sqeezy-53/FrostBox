package org.frostbox.app.runtime

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.frostbox.app.data.ProfileRepository
import org.frostbox.core.GameProfile
import org.json.JSONObject

/** UI-facing lifecycle; one IPC client, existing native execution and prefix recovery underneath. */
class LibraryLauncher(private val context: Context,private val repository: ProfileRepository,private val scope: CoroutineScope,val display: org.frostbox.app.display.DisplaySession,private val controllers: org.frostbox.app.input.ControllerMonitor?=null) {
    enum class Phase { Ready,Preparing,Starting,Running,Stopping,Finished,Failed }
    data class Session(val phase: Phase=Phase.Ready,val result: String="{}",val operation: String="launch")
    private val client=WineRuntimeClient(context)
    private val mutable=MutableStateFlow<Map<String,Session>>(emptyMap())
    val sessions=mutable.asStateFlow()
    val guestWsi=MutableStateFlow<org.frostbox.app.display.GuestWsiSession?>(null)
    val activeId=MutableStateFlow<String?>(null)
    private var audioHub: org.frostbox.app.audio.AudioHub?=null
    private var audioReport: JSONObject?=null
    private var controllerInputReport: JSONObject?=null
    private var keyboardBridge: org.frostbox.app.input.ControllerKeyboardBridge?=null
    private var controllerHub: org.frostbox.app.input.ControllerHub?=null
    private var operation="launch"
    private var runId=""
    private var started=false
    private var stopping=false
    init {
        scope.launch(Dispatchers.IO) {
            repository.profiles.collect { profiles ->
                profiles.forEach { p ->
                    if(p.id !in mutable.value) {
                        val saved=runCatching { repository.readRun(p.id) }.getOrNull()
                        val oldPhase=saved?.optString("launcherPhase")
                        val phase=runCatching { Phase.valueOf(oldPhase ?: "Ready") }.getOrDefault(Phase.Failed)
                        val interrupted=phase in listOf(Phase.Preparing,Phase.Starting,Phase.Running,Phase.Stopping)
                        val result=saved ?: JSONObject()
                        if(interrupted) result.put("error","App restarted during execution. Play/recover will reconcile the runtime; no automatic relaunch.")
                        mutable.update { map -> if(p.id in map) map else map+(p.id to Session(if(interrupted) Phase.Failed else phase,result.toString())) }
                    }
                }
            }
        }
        scope.launch {
            client.state.collect { status ->
                val id=activeId.value ?: return@collect
                if(status.result=="{}" || status.token!=runId) return@collect
                val result=JSONObject(status.result).put("runId",runId)
                if(!status.active && display.status.value.active && display.status.value.error.isNotEmpty()) result.put("error",display.status.value.error)
                var phase=if(status.active) when(result.optString("phase")) {
                    "launch" -> if(result.optBoolean("running")) Phase.Running else Phase.Stopping
                    "PROVISIONING","initialize" -> Phase.Preparing
                    else -> Phase.Starting
                } else terminal(result)
                if(stopping && status.active) phase=Phase.Stopping
                if(operation=="launch" && result.optString("phase")=="launch" && result.optBoolean("running") && !started) {
                    started=true
                    if(display.status.value.active && guestWsi.value==null) {
                        val expected=runId
                        scope.launch {
                            delay(15000)
                            if(runId==expected && activeId.value==id && display.status.value.active && display.status.value.frames==0L) {
                                display.timedOut();stop()
                            }
                        }
                    }
                    runCatching { withContext(Dispatchers.IO) { repository.markStarted(id,System.currentTimeMillis());repository.writeRun(id,JSONObject(result.toString()).put("launcherPhase",phase.name)) } }
                        .onFailure { result.put("storageError",it.toString()) }
                }
                if(!status.active) {
                    audioHub?.let {it.close();withTimeoutOrNull(2000){it.awaitClosed()};audioReport=JSONObject(it.report.value)};audioHub=null;audioReport?.let {result.put("audioBridge",it)}
                    keyboardBridge?.let {controllerInputReport=it.report();it.close()};keyboardBridge=null;controllerInputReport?.let {result.put("controllerKeyboard",it)}
                    guestWsi.value?.let { result.put("gpuFrames",it.frames.value).put("gpuError",it.error.value);if(it.error.value.isNotEmpty()){result.put("error",it.error.value);phase=Phase.Failed};it.close() };guestWsi.value=null
                    if(display.status.value.active) { result.put("display",display.report());display.stop() }
                    runCatching { withContext(Dispatchers.IO) { repository.writeRun(id,JSONObject(result.toString()).put("launcherPhase",phase.name)) } }.onFailure { result.put("storageError",it.toString()) }
                }
                // Publish the complete terminal report before exposing Play again. Consumers
                // must not see activeId=null alongside the preceding partial process snapshot.
                mutable.update { it+(id to Session(phase,result.toString(),operation)) }
                if(!status.active) {controllerHub?.let {it.close();withTimeoutOrNull(2000){it.awaitClosed()}};controllerHub=null;activeId.value=null}
            }
        }
    }
    private fun terminal(result: JSONObject): Phase {
        val error=result.optString("error")
        if(error.isNotEmpty() || result.optBoolean("timedOut") || result.optInt("execErrno")!=0 ||
            (result.optInt("signal")!=0 && !result.optBoolean("cancelled")) ||
            // Optional Wine registration helpers may log import_dll and still let the EXE run.
            // loader_init identifies a PE loader failure, preserving missing application DLL errors.
            result.optString("stderr").contains("err:module:loader_init") ||
            result.optJSONObject("prefixValidation")?.optBoolean("valid")==false || result.optBoolean("valid",true)==false) return Phase.Failed
        return if(operation=="launch") Phase.Finished else Phase.Ready
    }
    fun play(profile: GameProfile) = request(profile,"launch")
    fun request(profile: GameProfile,op: String) {
        check(activeId.value==null) { "Another runtime operation is active" }
        audioReport=null;controllerInputReport=null;activeId.value=profile.id;operation=op;started=false;stopping=false;runId=java.util.UUID.randomUUID().toString()
        val phase=if(op=="launch") Phase.Preparing else Phase.Starting
        mutable.update { it+(profile.id to Session(phase,operation=op)) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(op=="launch") repository.prepareLaunch(profile)
                    repository.writeRun(profile.id,JSONObject().put("launcherPhase",phase.name).put("operation",op).put("runId",runId))
                }
                if(stopping) {
                    val stopped=JSONObject().put("runId",runId).put("operation",op).put("cancelled",true)
                        .put("notStarted",true).put("launcherPhase",if(op=="launch") "Finished" else "Ready")
                    withContext(Dispatchers.IO) { repository.writeRun(profile.id,stopped) }
                    mutable.update { it+(profile.id to Session(if(op=="launch") Phase.Finished else Phase.Ready,stopped.toString(),op)) }
                    activeId.value=null
                } else {
                    val audio=op=="launch" && profile.audioEnabled
                    if(audio){check(runCatching{context.assets.open("wine-audio/manifest.json").close()}.isSuccess){"Experimental audio driver not bundled"};audioHub=org.frostbox.app.audio.AudioHub(context,profile.winePrefixId ?: profile.id,scope)}
                    val input=op=="launch" && profile.controllerMode=="xinput" && controllers!=null && runCatching {context.assets.open("wine-input/manifest.json").close()}.isSuccess
                    if(input)controllerHub=org.frostbox.app.input.ControllerHub(profile.winePrefixId ?: profile.id,controllers!!,scope)
                    val gpu=op=="launch" && profile.graphics==org.frostbox.core.GraphicsBackend.DXVK
                    val gui=gpu || (op=="launch" && profile.controllerMode=="keyboard") || (op=="launch" && withContext(Dispatchers.IO) { repository.isGui(profile) })
                    if(gpu) {
                        check(runCatching { context.assets.open("dxvk/manifest.json").use { };context.assets.open("wine-wsi/manifest.json").use { } }.isSuccess){"Experimental DXVK package is not bundled"}
                        guestWsi.value=org.frostbox.app.display.GuestWsiSession(context,profile.width,profile.height){ reason ->
                            mutable.update { it+(profile.id to (it[profile.id] ?: Session()).copy(result=JSONObject().put("error",reason).toString())) };stop()
                        }
                    }
                    if(gui) check(runCatching { context.assets.open("display/manifest.json").use { } }.isSuccess) {
                        "Experimental Windows display is not bundled. Build with withWindowsDisplay=true; console execution remains available."
                    }
                    val endpoint=if(gui) display.start() else ""
                    val gpuEndpoint=guestWsi.value?.awaitEndpoint() ?: ""
                    val keyboard=op=="launch" && profile.controllerMode=="keyboard" && controllers!=null
                    if(keyboard){check(runCatching{context.assets.open("wine-keyboard/manifest.json").close()}.isSuccess){"Keyboard/mouse controller driver is not bundled"};keyboardBridge=org.frostbox.app.input.ControllerKeyboardBridge(controllers!!,display,profile.keyboardController,scope)}
                    client.request(op,profile.id) { putBoolean("audioBridge",audio);putBoolean("controllerKeyboard",keyboard);putBoolean("controllerBridge",input);putString("launcherRunId",runId);putString("displayEndpoint",endpoint);putString("wsiEndpoint",gpuEndpoint) }
                }
            } catch(e: Exception) {
                audioHub?.close();audioHub=null;keyboardBridge?.close();keyboardBridge=null;controllerHub?.close();controllerHub=null
                guestWsi.value?.close();guestWsi.value=null
                if(display.status.value.active) display.stop()
                val result=JSONObject().put("runId",runId).put("error",e.message ?: e.toString()).put("launcherPhase","Failed")
                mutable.update { it+(profile.id to Session(Phase.Failed,result.toString(),op)) }
                withContext(Dispatchers.IO) { runCatching { repository.writeRun(profile.id,result) } };activeId.value=null
            }
        }
    }
    fun stop() { audioHub?.close();keyboardBridge?.let {controllerInputReport=it.report();it.close()};keyboardBridge=null;controllerHub?.close();controllerHub=null;display.stopInput();stopping=true;activeId.value?.let { id -> mutable.update { it+(id to (it[id] ?: Session()).copy(phase=Phase.Stopping)) } };client.stopProcess() }
    fun close() { audioHub?.close();audioHub=null;keyboardBridge?.close();keyboardBridge=null;controllerHub?.close();controllerHub=null;guestWsi.value?.close();guestWsi.value=null;client.close();display.dispose() }
}
