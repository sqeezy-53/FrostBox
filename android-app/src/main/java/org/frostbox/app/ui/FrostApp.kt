package org.frostbox.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import org.frostbox.app.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.frostbox.app.runtime.LibraryLauncher
import org.json.JSONObject
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.frostbox.app.FrostModel
import org.frostbox.app.thermal.thermalLabel
import org.frostbox.core.*

private val FrostColors = HorizonColors

@Composable fun FrostApp(model: FrostModel) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val libraryState=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val activeProfile by model.launcher.activeId.collectAsStateWithLifecycle()
    val guestWsi by model.launcher.guestWsi.collectAsStateWithLifecycle()
    val displayState by model.display.status.collectAsStateWithLifecycle()
    if(displayState.active) {
        MaterialTheme(colorScheme=FrostColors.copy(primary=Color(0xFF7BE3C8),onPrimary=Color(0xFF03251E),
            primaryContainer=Color(0xFF244B44),onPrimaryContainer=Color(0xFFB0F5DF),
            surface=Color(0xFF171F25),surfaceTint=Color(0xFF7BE3C8)),shapes=HorizonShapes) { Surface(Modifier.fillMaxSize()) {
            val p=profiles.firstOrNull {it.id==activeProfile}
            val sessions by model.launcher.sessions.collectAsStateWithLifecycle()
            val audio by model.launcher.audioState.collectAsStateWithLifecycle()
            val settingsError by model.gameSettingsFailure.collectAsStateWithLifecycle()
            org.frostbox.app.display.WindowsScreen(model.display,model.launcher::stop,guestWsi,p?.touchProfile,model.controllers,
                profile=p,phase=sessions[activeProfile]?.phase?.name ?: "Starting",audio=audio,diagnostics=sessions[activeProfile]?.result ?: "{}",settingsError=settingsError,
                change={change->p?.let {model.gameSettings(it.id,change)}},restart={p?.let {model.restartGame(it.id)}})
        } }
        return
    }
    val gpuVisible by model.gpu.visible.collectAsStateWithLifecycle()
    if(gpuVisible) {MaterialTheme(colorScheme=FrostColors,shapes=HorizonShapes) {Surface(Modifier.fillMaxSize()) {org.frostbox.app.gpu.VulkanScreen(model.gpu)}};return}
    val message by model.message.collectAsStateWithLifecycle()
    val importBusy by model.busy.collectAsStateWithLifecycle()
    val libraryBusy by model.libraryBusy.collectAsStateWithLifecycle()
    val busy=importBusy || libraryBusy
    var screen by rememberSaveable { mutableStateOf("Home") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var logsId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var coverId by rememberSaveable { mutableStateOf<String?>(null) }
    val coverPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->val id=coverId;if(uri!=null && id!=null)model.importCover(id,uri);coverId=null}
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearRequested by rememberSaveable { mutableStateOf(false) }
    var replaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val sessions by model.launcher.sessions.collectAsStateWithLifecycle()
    val activeId by model.launcher.activeId.collectAsStateWithLifecycle()
    val probe by model.probes.state.collectAsStateWithLifecycle()
    val progress by model.importProgress.collectAsStateWithLifecycle()
    val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val p=profiles.firstOrNull { it.id==folderId };if(uri!=null && p!=null) model.importFolder(p,uri);folderId=null
    }
    val runtimeExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p=profiles.firstOrNull { it.id==exportId };if(uri!=null && p!=null) model.exportRuntime(p,uri);exportId=null
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importExe) }
    val configExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportProfiles) }
    val logExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(model::exportLogs) }
    BackHandler(enabled=removeId==null && !clearRequested && (editId!=null || logsId!=null || detailsId!=null || screen!="Home")) {
        when { logsId!=null -> logsId=null;editId!=null -> editId=null;detailsId!=null -> detailsId=null;else -> screen="Home" }
    }
    MaterialTheme(colorScheme=FrostColors,shapes=HorizonShapes) {
        Scaffold(containerColor=FrostColors.background,contentWindowInsets=WindowInsets.safeDrawing,bottomBar={
            NavigationBar(containerColor=FrostColors.surface,tonalElevation=0.dp) {
                listOf("Home" to "Главная","Library" to "Библиотека","Controllers" to "Геймпады","Settings" to "Настройки").forEach {(name,label)->
                    NavigationBarItem(selected=screen==name,onClick={screen=name;editId=null;detailsId=null;logsId=null},icon={HorizonNavIcon(name)},label={Text(label)},modifier=Modifier.testTag("nav-$name"))
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).padding(horizontal=20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    HorizonMark()
                    Column(Modifier.weight(1f)){Text("FrostBox",style=MaterialTheme.typography.headlineSmall);Text("Ваша Windows-библиотека",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    TextButton(onClick={screen="Settings";editId=null;detailsId=null;logsId=null}){Text("Настройки")}
                }
                if (message.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(message, modifier=Modifier.weight(1f), color=MaterialTheme.colorScheme.secondary)
                        TextButton(onClick={model.message.value=""}) { Text("×") }
                    }
                }
                if(libraryBusy) Text(stringResource(R.string.library_updating))
                if(importBusy) {
                    Text("Importing ${progress.file} · ${progress.copiedBytes/1024} KiB · ${progress.filesDone}/${progress.filesTotal} files",modifier=Modifier.testTag("import-progress"))
                    if(progress.totalBytes!=null && progress.totalBytes!!>0) LinearProgressIndicator(progress={(progress.copiedBytes.toDouble()/progress.totalBytes!!).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick=model::cancelImport,modifier=Modifier.testTag("cancel-import")) { Text("Cancel import") }
                }
                if(removeId!=null || clearRequested) LibraryRemovalDialog(clearRequested,profiles.size,activeId!=null || probe.active,busy,
                    confirm={
                        if(clearRequested) model.clearLibrary() else profiles.firstOrNull { it.id==removeId }?.let(model::remove)
                        editId=null;detailsId=null;logsId=null;folderId=null;exportId=null;replaceId=null
                        removeId=null;clearRequested=false;screen="Library"
                    },dismiss={removeId=null;clearRequested=false})
                if(replaceId!=null) AlertDialog(onDismissRequest={replaceId=null},title={Text("Import game folder")},text={Text("Only your selected folder is copied. Its structure is preserved. A previous imported folder is replaced after successful copying; the original files are unchanged.")},confirmButton={TextButton(onClick={folderId=replaceId;replaceId=null;folderPicker.launch(null)},modifier=Modifier.testTag("choose-folder")) { Text("Choose folder") }},dismissButton={TextButton(onClick={replaceId=null}) { Text("Cancel") }})
                val pageScroll=rememberScrollState()
                val libraryPage=screen=="Library" && editId==null && logsId==null && detailsId==null
                Column(if(libraryPage)Modifier.weight(1f) else Modifier.weight(1f).verticalScroll(pageScroll), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    val editing=profiles.firstOrNull { it.id == editId }
                    val details=profiles.firstOrNull { it.id==detailsId }
                    val logging=profiles.firstOrNull { it.id==logsId }
                    if(editing!=null) GameSettings(editing,profiles,model,{editId=null})
                    else if(logging!=null) RuntimeLogs(logging,sessions[logging.id] ?: LibraryLauncher.Session(),{exportId=logging.id;runtimeExport.launch("frostbox-${logging.id}.json")},{logsId=null})
                    else if(details!=null) {
                        GameCover(details,Modifier.fillMaxWidth().height(150.dp))
                        TextButton(onClick={coverId=details.id;coverPicker.launch(arrayOf("image/*"))},enabled=!busy && activeId==null,modifier=Modifier.testTag("choose-cover")){Text(stringResource(R.string.catalog_cover))}
                        GameDetails(details,sessions[details.id] ?: LibraryLauncher.Session(),activeId,busy || probe.active,model,
                        { model.loadExecutables(details);editId=details.id },{logsId=details.id},{replaceId=details.id},{removeId=details.id},{detailsId=null})
                    }
                    else when(screen) {
                        "Home" -> HorizonHome(profiles,sessions,busy || probe.active,{screen="Library"},{screen="Add Game"},{detailsId=it}) {p,state->LaunchButtons(p,state,activeId,busy || probe.active,model)}
                        "Library" -> libraryState.SaveableStateProvider("library") {
                            Text(stringResource(R.string.nav_library),style=MaterialTheme.typography.headlineSmall)
                            LibraryListControls(profiles.size,busy || probe.active,{screen="Add Game"},{clearRequested=true})
                            LibraryCatalog(profiles,sessions,busy,{removeId=it},{detailsId=it},settings={id->profiles.firstOrNull{it.id==id}?.let {model.loadExecutables(it);editId=id}},cover={id->coverId=id;coverPicker.launch(arrayOf("image/*"))}) {p,state->LaunchButtons(p,state,activeId,busy || probe.active,model)}
                        }
                        "Storage" -> StorageScreen()
                        "Add Game" -> {
                            Text("Add a Windows app",style=MaterialTheme.typography.headlineSmall)
                            Text("Select your EXE using Android's file picker. A private copy and profile are created; nothing starts automatically. Afterward, Details → Import folder copies selected DLLs/resources. x86 uses the experimental isolated Wine WoW64 runtime when bundled.")
                            Button(onClick={importer.launch(arrayOf("*/*"))},enabled=!busy && activeId==null,modifier=Modifier.testTag("select-exe")) { Text("Select EXE") }
                            TextButton(onClick={screen="Library"},modifier=Modifier.testTag("back-library")) { Text("Back to Library") }
                        }
                        "Performance" -> Performance(model)
                        "Controllers" -> ControllerScreen(model.controllers)
                        "Runtime Diagnostics" -> {
                            val native by model.nativeInfo.collectAsStateWithLifecycle()
                            Panel("Native bridge", native)
                            val probe by model.probes.state.collectAsStateWithLifecycle()
                            Panel("Runtime process lab", "Built-in tests only. Native ARM64 test validates process execution; Box64 tests run a Linux x86-64 ELF. The optional Wine console probe runs the original Windows x64 fixture; it does not qualify games or graphics.")
                            Button(onClick={model.probes.start(0)}, enabled=!probe.active && activeId==null) { Text("Test native process") }
                            Button(onClick={model.probes.start(1)}, enabled=!probe.active && activeId==null) { Text("Test Box64 interpreter") }
                            Button(onClick={model.probes.start(2)}, enabled=!probe.active && activeId==null) { Text("Test Box64 dynarec") }
                            OutlinedButton(onClick={model.probes.start(3)}, enabled=!probe.active && activeId==null) { Text("Test cancellation (30s timeout)") }
                            if (model.wineProbeAvailable) Button(onClick={model.probes.start(7)}, enabled=!probe.active && activeId==null) { Text("Run Windows x64 console test") }
                            if(probe.active) Button(onClick={model.probes.cancel()}) { Text("Stop test") }
                            Panel("Process result", probe.report)
                            Panel("Components", "FrostBox JNI API 2 · C++20 process manager. Box64 is included only in the explicitly selected runtime build; missing packages return a visible error.")
                            Panel("Runtime scope", "DXVK 2.7.1 is available in the bundled experimental GPU profile. VKD3D / Turnip are not integrated.")
                            Panel("Compatibility gate", "Vulkan properties alone do not establish DXVK support. Turnip is disabled until exact GPU and driver compatibility are verified. System Qualcomm Vulkan is the baseline candidate.")
                        }
                        "Settings" -> {
                            Text("Настройки",style=MaterialTheme.typography.headlineSmall)
                            OutlinedButton(onClick={screen="Storage"},modifier=Modifier.testTag("nav-Storage")){Text("Хранилище и данные игр")}
                            Text(stringResource(R.string.ui_diagnostics),style=MaterialTheme.typography.titleLarge)
                            listOf("Runtime Diagnostics" to R.string.ui_runtime,"Performance" to R.string.ui_thermal,"Vulkan Diagnostics" to R.string.ui_vulkan).forEach {(name,label)->
                                OutlinedButton(onClick={if(name=="Vulkan Diagnostics")model.gpu.show() else screen=name},modifier=Modifier.testTag("nav-$name")){Text(stringResource(label))}
                            }
                            Panel("Private, local storage", "Profiles: DataStore\nEXE: files/games/<profile-id>/game.exe\nLogs: files/diagnostics.log\nNo network, advertising, analytics or root access.")
                            Button(onClick={configExport.launch("frostbox-profiles.json")}) { Text("Export configuration") }
                            Button(onClick={logExport.launch("frostbox-diagnostics.txt")}) { Text("Export diagnostics") }
                            Panel("Licenses", "FrostBox original code: MIT. Kotlin, AndroidX, Compose and coroutines: Apache-2.0. Full notices and dependency review are in third-party/. The runtime build includes Box64 (MIT). The optional Wine probe includes Wine 11.19 (LGPL-2.1-or-later) with local patches; source and notices accompany that build. No third-party GPU driver is bundled.")
                            val logs by model.log.lines.collectAsStateWithLifecycle()
                            Panel("Recent diagnostics", logs.takeLast(15).joinToString("\n").ifBlank { "No entries" })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable private fun Panel(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title, style=MaterialTheme.typography.titleMedium, color=MaterialTheme.colorScheme.primary)
        Text(text)
    } }
}

@Composable private fun Performance(model: FrostModel) {
    val history by model.history.collectAsStateWithLifecycle()
    val sample=history.lastOrNull()
    Text("Thermal diagnostics", style=MaterialTheme.typography.headlineSmall)
    Panel("Battery", sample?.batteryC?.let { "%.1f °C".format(it) } ?: "Temperature unavailable")
    Panel("Android thermal state", thermalLabel(sample?.status))
    Panel("Thermal headroom", sample?.headroom?.let { "%.2f (1.0 is severe threshold)".format(it) } ?: "Unavailable / unsupported")
    Panel("Power saving", sample?.let { if(it.powerSave) "System power saver is active" else "System power saver is off" } ?: "Waiting for sample")
    Panel("Game measurements", "FPS: —   Frametime: —   1% low: —\nLong frames: —   Game session: not running\nNo runtime-present measurement source is connected.")
    Panel("Thermal Manager", "Eco requests 30 FPS, Balanced 40, Performance 60. These are saved policies, not active limits. No frequency control or temperature guarantee. Battery temperature is not CPU/GPU temperature.")
    Panel("Recent samples · every 10 seconds", history.takeLast(12).joinToString("\n") { "t=${it.seconds}s · ${it.batteryC ?: "—"} °C · ${thermalLabel(it.status)}" }.ifBlank { "Waiting for Android APIs" })
}

@Composable private fun LaunchButtons(p: GameProfile,state: LibraryLauncher.Session,activeId: String?,busy: Boolean,model: FrostModel) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={model.play(p)},enabled=activeId==null && !busy && (p.architecture=="x86-64" || (p.architecture=="x86" && model.wow64Available)) && model.wineProbeAvailable,modifier=Modifier.testTag("play-${p.id}")) { Text(stringResource(R.string.ui_play)) }
        if(activeId==p.id) OutlinedButton(onClick=model.launcher::stop,enabled=state.phase!=LibraryLauncher.Phase.Stopping,modifier=Modifier.testTag("stop-${p.id}")) { Text(stringResource(R.string.ui_stop)) }
    }
    if(p.architecture=="x86" && !model.wow64Available) Text("Experimental WoW64 package is not bundled",color=MaterialTheme.colorScheme.secondary)
    if(!model.wineProbeAvailable) Text("This build has no Wine package",color=MaterialTheme.colorScheme.error)
}

@Composable private fun GameDetails(p: GameProfile,state: LibraryLauncher.Session,activeId: String?,busy: Boolean,model: FrostModel,settings: ()->Unit,logs: ()->Unit,folder: ()->Unit,remove: ()->Unit,back: ()->Unit) {
    Text(p.name,style=MaterialTheme.typography.headlineSmall)
    Text(stringResource(phaseLabel(state.phase)),modifier=Modifier.testTag("detail-status-${p.id}"))
    Text(runtimeCaption(p),color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(p.addedAt?.let {"Добавлено: "+DateFormat.getDateInstance().format(Date(it))} ?: "Дата добавления старого профиля неизвестна",style=MaterialTheme.typography.bodySmall)
    Text("Последний запуск: "+lastPlayed(p),style=MaterialTheme.typography.bodySmall)
    LaunchButtons(p,state,activeId,busy,model)
    val result=runCatching { JSONObject(state.result) }.getOrDefault(JSONObject())
    Panel("Совместимость","${if(p.architecture=="x86")"Экспериментальный WoW64" else "Квалифицированный x64"} runtime. ${if(result.optInt("gpuFrames")>0)"В последнем запуске представлены ${result.optInt("gpuFrames")} GPU кадров." else "3D для этой записи не подтверждена."} Длительная игровая стабильность не подтверждена.")
    var advanced by rememberSaveable(p.id) {mutableStateOf(false)}
    if(result.optString("error").isNotBlank())Panel("Ошибка запуска",result.optString("error"))
    TextButton(onClick={advanced=!advanced}){Text(if(advanced)"Скрыть параметры запуска" else "Параметры запуска и файлы")}
    if(advanced) {
    Panel("Process", "PID: ${result.optInt("pid",-1)} · exit: ${if(result.has("exitCode")) result.optInt("exitCode") else "—"} · ${result.optLong("elapsedMs")} ms\n${result.optString("error")}")
    Panel("Files", "EXE: ${p.executable}\nWorking directory: ${p.workingDirectory}\nWine prefix: games/${p.winePrefixId ?: p.id}/prefix")
    }
    Button(onClick=logs,modifier=Modifier.testTag("logs-${p.id}")) { Text("Runtime Logs") }
    OutlinedButton(onClick=settings,enabled=activeId==null && !busy,modifier=Modifier.testTag("settings-${p.id}")) { Text("Game Settings") }
    OutlinedButton(onClick=folder,enabled=activeId==null && !busy,modifier=Modifier.testTag("import-folder-${p.id}")) { Text("Import / replace game folder") }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={model.launcher.request(p,"validate")},enabled=activeId==null && !busy) { Text("Validate prefix") }
        OutlinedButton(onClick={model.launcher.request(p,"recover")},enabled=activeId==null && !busy) { Text("Recover runtime") }
    }
    GameRecordMenu(p.id,busy,remove)
    TextButton(onClick=back,modifier=Modifier.testTag("details-back")) { Text("Back to Library") }
}

@Composable private fun RuntimeLogs(p: GameProfile,state: LibraryLauncher.Session,export: ()->Unit,back: ()->Unit) {
    Text("Runtime Logs · ${p.name}",style=MaterialTheme.typography.headlineSmall)
    val result=runCatching { JSONObject(state.result) }.getOrDefault(JSONObject())
    Panel("Runtime", "${state.phase.name} · PID ${result.optInt("pid",-1)} · UID ${result.optInt("runtimeUid",-1)}\nExit ${if(result.has("exitCode")) result.optInt("exitCode") else "—"} · signal ${result.optInt("signal")} · ${result.optLong("elapsedMs")} ms\n${result.optString("error")}")
    Text(result.optString("stdout").takeLast(8192).ifBlank { "stdout: empty" },modifier=Modifier.testTag("runtime-stdout"))
    Panel("stderr · Wine / Box64 · last 8 KiB",result.optString("stderr").takeLast(8192).ifBlank { "No stderr" })
    if(result.has("gpuFrames"))Panel("Windows GPU", "${result.optString("graphicsBackend")} · observed GPU frames: ${result.optInt("gpuFrames")}\n${result.optString("gpuError")}")
    result.optJSONObject("dxvkLogs")?.let { logs -> Panel("DXVK · bounded logs",logs.toString().takeLast(8192)) }
    Panel("Prefix / cleanup",(result.optJSONObject("prefixValidation") ?: result.optJSONObject("shutdown") ?: result).toString().takeLast(8192))
    Text("Native streams are capped at 32 KiB each; dropped bytes: stdout ${result.optLong("droppedOut")}, stderr ${result.optLong("droppedErr")}. Export includes the bounded full result.")
    Button(onClick=export) { Text("Export diagnostic report") }
    TextButton(onClick=back,modifier=Modifier.testTag("logs-back")) { Text("Back") }
}

@Composable private fun GameSettings(p: GameProfile,profiles: List<GameProfile>,model: FrostModel,back: ()->Unit) {
    var experience by remember(p.id) {mutableStateOf(p.experience)}
    Text(stringResource(R.string.gx_display))
    org.frostbox.app.display.ScalingChoices(experience.scaling) {experience=experience.copy(scaling=it)}
    var audioEnabled by rememberSaveable(p.id) {mutableStateOf(p.audioEnabled)}
    var controllerMode by rememberSaveable(p.id) {mutableStateOf(p.controllerMode)}
    var keyboardConfig by remember(p.id) {mutableStateOf(p.keyboardController)}
    var touchConfig by remember(p.id) {mutableStateOf(p.touchProfile)}
    var controllerOverride by rememberSaveable(p.id) {mutableStateOf(p.controllerMapping!=null)}
    var controllerConfig by remember(p.id) {mutableStateOf(p.controllerMapping ?: ControllerMapping())}
    var width by rememberSaveable(p.id) {mutableStateOf(p.width.toString())}
    var height by rememberSaveable(p.id) {mutableStateOf(p.height.toString())}
    var name by rememberSaveable(p.id) { mutableStateOf(p.name) }
    var exe by rememberSaveable(p.id) { mutableStateOf(p.executable) }
    var work by rememberSaveable(p.id) { mutableStateOf(p.workingDirectory) }
    var args by rememberSaveable(p.id) { mutableStateOf(JSONArray(p.arguments).toString()) }
    var env by rememberSaveable(p.id) { mutableStateOf(p.environment) }
    var prefix by rememberSaveable(p.id) { mutableStateOf(p.winePrefixId ?: p.id) }
    var prefixMenu by remember { mutableStateOf(false) }
    var runtimeVariant by rememberSaveable(p.id) {mutableStateOf(p.runtimeVariant)}
    var graphicsApi by rememberSaveable(p.id) {mutableStateOf(p.graphicsApi)}
    var graphics by rememberSaveable(p.id) { mutableStateOf(p.graphics) }
    var graphicsMenu by remember { mutableStateOf(false) }
    val gpuBundled=remember { runCatching { model.getApplication<android.app.Application>().assets.open("dxvk/manifest.json").use { } }.isSuccess }
    var exeMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val executableChoices by model.executables.collectAsStateWithLifecycle()
    Text("Game Settings",style=MaterialTheme.typography.headlineSmall)
    Text("Wine runtime · PE ${p.architecture}")
    Row {
        FilterChip(runtimeVariant=="auto",{runtimeVariant="auto"},label={Text("Auto")})
        val explicit=if(p.architecture=="x86")"wow64-experimental" else "qualified-x64"
        FilterChip(runtimeVariant==explicit,{runtimeVariant=explicit},enabled=p.architecture!="x86" || model.wow64Available,label={Text(if(p.architecture=="x86")"WoW64 · эксперимент" else "x64")})
    }
    Text("Auto reads the EXE PE machine field. x86 prefixes are separate from qualified x64 prefixes.")
    if(runCatching {model.getApplication<android.app.Application>().assets.open("wine-audio/manifest.json").close()}.isSuccess)Row {Checkbox(audioEnabled,{audioEnabled=it});Text("Windows audio · экспериментальный WASAPI/PCM; игровые API не квалифицированы")}
    org.frostbox.app.input.TouchProfileEditor(touchConfig,runCatching {model.getApplication<android.app.Application>().assets.open("wine-input/manifest.json").close()}.isSuccess,{touchConfig=it})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(width,{width=it},label={Text("Width")},modifier=Modifier.weight(1f))
        OutlinedTextField(height,{height=it},label={Text("Height")},modifier=Modifier.weight(1f))
    }
    Text("Ввод физического геймпада")
    Row {FilterChip(controllerMode=="xinput",{controllerMode="xinput"},label={Text("XInput")});FilterChip(controllerMode=="keyboard",{controllerMode="keyboard"},enabled=gpuBundled,label={Text("Клавиатура/мышь · эксперимент")})}
    if(controllerMode=="keyboard") {
        Text("Левый стик/D-pad: WASD; правый: мышь; RT/LT: левая/правая кнопка мыши")
        Text("Чувствительность камеры · правый стик: ${keyboardConfig.mouseSpeed.toInt()} ед./с")
        Slider(keyboardConfig.mouseSpeed,{keyboardConfig=keyboardConfig.copy(mouseSpeed=it)},valueRange=50f..6000f,modifier=Modifier.testTag("camera-sensitivity"))
        Text("Больше значение — быстрее поворот. Сохраняется для этой игры; применяется при следующем Play.")
        for((mask,label) in listOf(4096 to "A",8192 to "B",16384 to "X",32768 to "Y",256 to "LB",512 to "RB",16 to "Start",32 to "Back")) {
            var menu by remember(mask) {mutableStateOf(false)}
            Box {OutlinedButton(onClick={menu=true}){Text("$label → VK ${keyboardConfig.buttons[mask]}")}
                DropdownMenu(menu,{menu=false}) {for((vk,name) in listOf(32 to "Space",17 to "Ctrl",16 to "Shift",69 to "E",81 to "Q",82 to "R",13 to "Enter",27 to "Esc"))DropdownMenuItem(text={Text(name)},onClick={keyboardConfig=keyboardConfig.copy(buttons=keyboardConfig.buttons+(mask to vk));menu=false})}
            }
        }
    }
    Row {Checkbox(controllerOverride,{controllerOverride=it});Text(stringResource(R.string.pad_game_override))}
    if(controllerOverride)ControllerMappingEditor(controllerConfig,{controllerConfig=it})
    OutlinedTextField(name,{name=it},label={Text("Name")},modifier=Modifier.fillMaxWidth().testTag("edit-name"))
    OutlinedTextField(exe,{exe=it},label={Text("Relative EXE path")},modifier=Modifier.fillMaxWidth().testTag("edit-exe"))
    Box {
        TextButton(onClick={exeMenu=true}) { Text("Select imported EXE") }
        DropdownMenu(exeMenu,{exeMenu=false}) { executableChoices.forEach { path -> DropdownMenuItem(text={Text(path)},onClick={exe=path;exeMenu=false;work=path.substringBeforeLast('/',".")}) } }
    }
    OutlinedTextField(work,{work=it},label={Text("Working directory · . or content/... ")},modifier=Modifier.fillMaxWidth().testTag("edit-work"))
    OutlinedTextField(args,{args=it},label={Text("Arguments · JSON array of literal strings")},modifier=Modifier.fillMaxWidth().testTag("edit-args"))
    Box {
        OutlinedButton(onClick={prefixMenu=true}) { Text("Wine prefix: ${prefix.take(8)}…") }
        DropdownMenu(prefixMenu,{prefixMenu=false}) {
            (listOf(p.id,prefix)+profiles.map { it.winePrefixId ?: it.id }).distinct().forEach { id ->
                DropdownMenuItem(text={Text(if(id==p.id) "Own prefix (x64 ~655 MiB; WoW64 ~1.2 GiB)" else "Share ${profiles.firstOrNull { it.id==id }?.name ?: id.take(8)}")},onClick={prefix=id;prefixMenu=false})
            }
        }
    }
    Text("Prefixes are reused. Shared prefixes also share registry and installed applications; execution is serialized.")
    OutlinedTextField(env,{env=it},label={Text("Environment · KEY=value, one per line")},modifier=Modifier.fillMaxWidth().testTag("edit-env"),minLines=2)
    Text("Supported: WINEDEBUG, WINEDLLOVERRIDES, BOX64_LOG, BOX64_NOBANNER, BOX64_DYNAREC. Paths and security-critical runtime variables are managed internally.")
    Box {
        OutlinedButton(onClick={graphicsMenu=true},modifier=Modifier.testTag("graphics-backend")) { Text(if(graphics==org.frostbox.core.GraphicsBackend.DXVK) "DXVK 2.7.1 · experimental" else "Wine GDI") }
        DropdownMenu(graphicsMenu,{graphicsMenu=false}) {
            DropdownMenuItem(text={Text("Wine GDI")},onClick={graphics=org.frostbox.core.GraphicsBackend.SYSTEM_VULKAN;graphicsMenu=false})
            if(model.wow64Available && p.architecture=="x86")DropdownMenuItem(text={Text("DXVK 2.7.1 · experimental D3D9")},onClick={graphics=org.frostbox.core.GraphicsBackend.DXVK;graphicsApi="d3d9";graphicsMenu=false})
            if(gpuBundled)DropdownMenuItem(text={Text("DXVK 2.7.1 · experimental D3D11")},onClick={graphics=org.frostbox.core.GraphicsBackend.DXVK;graphicsApi="d3d11";graphicsMenu=false})
        }
    }
    Text("DXVK adds verified DLLs to the private imported EXE directory and refuses conflicts. Original source files are unchanged. DXVK currently requires stop/restart after Android Surface loss. FPS control is not qualified for this application.")
    if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    Button(onClick={runCatching {
        val array=JSONArray(args);val arguments=List(array.length()) { index -> require(array.get(index) is String);array.getString(index) }
        parseLaunchEnvironment(env)
        p.copy(experience=experience,runtimeVariant=runtimeVariant,graphicsApi=graphicsApi,name=name.trim(),executable=exe,workingDirectory=work,arguments=arguments,environment=env,winePrefixId=prefix,graphics=graphics,width=width.toInt(),height=height.toInt(),controllerMapping=if(controllerOverride)controllerConfig else null,touchProfile=touchConfig,controllerMode=controllerMode,keyboardController=keyboardConfig,audioEnabled=audioEnabled).also { it.validate() }
    }.onSuccess { model.save(it);back() }.onFailure { error=it.message ?: "Invalid settings" }},modifier=Modifier.testTag("save-profile")) { Text("Save") }
    TextButton(onClick=back,modifier=Modifier.testTag("settings-back")) { Text("Back") }
}
