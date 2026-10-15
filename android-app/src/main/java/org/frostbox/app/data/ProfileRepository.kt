package org.frostbox.app.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.frostbox.core.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

private val Context.profileStore by preferencesDataStore("profiles")
@Serializable data class ProfileArchive(val schema: Int=1,val profiles: List<GameProfile> = emptyList())
class ProfileRepository(private val context: Context, private val profileDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = context.profileStore) {
    private val key=stringPreferencesKey("archive")
    private val json=Json { ignoreUnknownKeys=true }
    private fun decode(raw: String?)=raw?.let { json.decodeFromString<ProfileArchive>(it).also { a -> require(a.schema==1);a.profiles.forEach(GameProfile::validate) } } ?: ProfileArchive()
    val profiles=profileDataStore.data.map { decode(it[key]).profiles }
    private fun home(id: String): File { require(UUID.fromString(id).toString()==id);val root=File(context.filesDir,"games");ManagedFiles.noLink(root);check(root.isDirectory || root.mkdirs());return File(root,id).also { ManagedFiles.noLink(it) } }
    private suspend fun store(p: GameProfile) { profileDataStore.edit { it[key]=json.encodeToString(decode(it[key]).let { a -> a.copy(profiles=a.profiles.filterNot { old -> old.id==p.id }+p) }) } }
    private suspend fun sha(file: File): String=file.inputStream().use { input ->
        val d=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536)
        while(true) { currentCoroutineContext().ensureActive();val n=input.read(bytes);if(n<0) break;d.update(bytes,0,n) }
        d.digest().joinToString("") { "%02x".format(it) }
    }
    private fun persist(p: GameProfile) {
        val directory=home(p.id);val exe=ManagedFiles.resolve(directory,p.executable)
        if(!exe.isFile) return // Legacy editable records without imported files are not launchable.
        ManagedFiles.write(File(directory,"launch.json"),JSONObject().put("schema",1).put("exeSha256",p.sha256)
            .put("executable",p.executable).put("arguments",JSONArray(p.arguments)).put("workingDirectory",p.workingDirectory)
            .put("architecture",p.architecture).put("runtimeVariant",p.runtimeVariant).put("graphicsApi",p.graphicsApi).put("graphicsBackend",p.graphics.name).put("winePrefixId",p.winePrefixId ?: p.id).put("environment",JSONObject(parseLaunchEnvironment(p.environment))))
    }
    suspend fun save(profile: GameProfile) = withContext(Dispatchers.IO) {
        profile.validate()
        val directory=home(profile.id)
        if(!directory.isDirectory) { store(profile);return@withContext }
        ManagedFiles.locked(listOf(directory)) { persist(profile);store(profile) }
    }
    suspend fun saveEdited(profile: GameProfile) = withContext(Dispatchers.IO) {
        profile.validate();recoverImport(profile.id)
        ManagedFiles.locked(listOf(home(profile.id))) {
            val exe=ManagedFiles.resolve(home(profile.id),profile.executable)
            check(exe.isFile) { "EXE not found" }
            check(ManagedFiles.resolve(home(profile.id),profile.workingDirectory).isDirectory) { "Working directory not found" }
            val p=profile.copy(sha256=sha(exe),architecture=PeInspector.inspect(exe)).also {it.validate()}
            persist(p);store(p)
        }
    }
    suspend fun importCover(id: String,uri: Uri)=withContext(Dispatchers.IO) {
        val dir=home(id);check(dir.isDirectory)
        val temp=File.createTempFile("cover-import-",".part",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use {input -> temp.outputStream().use {out ->
                val buffer=ByteArray(65536);var count=0
                while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=8*1024*1024){"Image exceeds 8 MiB"};out.write(buffer,0,n)}
            }} ?: error("Image is unavailable")
            val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(temp.path,options)
            require(options.outWidth in 1..16384 && options.outHeight in 1..16384){"Unsupported image dimensions"}
            options.inJustDecodeBounds=false;options.inSampleSize=1
            while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>768)options.inSampleSize*=2
            val bitmap=android.graphics.BitmapFactory.decodeFile(temp.path,options) ?: error("Cannot decode image")
            try {
                ManagedFiles.locked(listOf(dir)) {
                    val current=profiles.first().single {it.id==id}
                    val name="cover-${UUID.randomUUID()}.jpg"
                    File(dir,name).outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it))}
                    store(current.copy(coverFile=name).also {it.validate()})
                }
            } finally {bitmap.recycle()}
        } finally {temp.delete()} // Only the newly created cache temporary, never SAF/game/prefix data.
    }
    /** Atomic record-only update during a running game. Never touches its locked content/prefix.
     * The allowlist prevents accidental replacement of executable/runtime identity. */
    suspend fun updateGameSettings(id: String, change: (GameProfile)->GameProfile) = withContext(Dispatchers.IO) {
        profileDataStore.edit { prefs ->
            val archive=decode(prefs[key]);val old=archive.profiles.single {it.id==id}
            val updated=change(old).also {it.validate()}
            check(updated.copy(experience=old.experience,controllerMapping=old.controllerMapping,
                keyboardController=old.keyboardController,touchProfile=old.touchProfile,
                width=old.width,height=old.height)==old) { "Only in-game settings may change during a session" }
            prefs[key]=json.encodeToString(archive.copy(profiles=archive.profiles.map {if(it.id==id)updated else it}))
        }
    }
    suspend fun markStarted(id: String,time: Long) { profileDataStore.edit { prefs ->
        val a=decode(prefs[key]);prefs[key]=json.encodeToString(a.copy(profiles=a.profiles.map { if(it.id==id) it.copy(lastLaunch=time) else it }))
    } }
    // Record-only edits: no game-directory lock, prefix/cache access or file deletion.
    // The guard is rechecked inside the atomic DataStore transaction.
    suspend fun removeEntry(id: String, canModify: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        profileDataStore.edit { prefs ->
            check(canModify()) { "Library changes are blocked during an active session" }
            val a=decode(prefs[key])
            prefs[key]=json.encodeToString(a.copy(profiles=a.profiles.filterNot { it.id==id }))
        }
    }
    suspend fun clearEntries(canModify: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        profileDataStore.edit { prefs ->
            check(canModify()) { "Library changes are blocked during an active session" }
            prefs[key]=json.encodeToString(decode(prefs[key]).copy(profiles=emptyList()))
        }
    }
    suspend fun importExe(uri: Uri,progress: (TreeImporter.Progress)->Unit = {}): GameProfile=withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString();val dir=home(id)
        check(dir.mkdirs()) { "Cannot create game directory" }
        var committed=false
        try {
            var name="Imported game";var size: Long?=null
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
                if(c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it>=0 }?.let { name=c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it>=0 && !c.isNull(it) }?.let { size=c.getLong(it).takeIf { n -> n>=0 } }
                }
            }
            val reserve=64L*1024*1024
            require(size==null || size!!<=512L*1024*1024) { "EXE exceeds 512 MiB import limit" }
            check(StatFs(dir.path).availableBytes>=(size ?: 0)+reserve) { "Not enough storage for EXE" }
            val temp=File(dir,"import.part");var total=0L;var last=0L
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output ->
                val buffer=ByteArray(65536)
                while(true) {
                    currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0) break
                    total+=n;require(total<=512L*1024*1024) { "EXE exceeds 512 MiB import limit" }
                    if(total-last>=1024*1024 || total==n.toLong()) {
                        check(StatFs(dir.path).availableBytes>=reserve+n) { "Storage reserve exhausted" }
                        progress(TreeImporter.Progress(name,total,size,0,1));last=total
                    }
                    output.write(buffer,0,n)
                };output.fd.sync()
            } } ?: error("Provider returned no readable file")
            if(size!=null) check(total==size) { "EXE changed or truncated during import" }
            val arch=PeInspector.inspect(temp);val digest=sha(temp);check(temp.renameTo(File(dir,"game.exe")))
            val existing=if(arch=="x86-64") profiles.first().firstOrNull { it.architecture=="x86-64" && home(it.id).isDirectory } else null
            val p=GameProfile(id,name.replace('\\','/').substringAfterLast('/').removeSuffix(".exe").filter { it>=' ' }.take(100).ifBlank { "Imported game" },architecture=arch,sha256=digest,addedAt=System.currentTimeMillis(),winePrefixId=existing?.let { it.winePrefixId ?: it.id })
            withContext(NonCancellable) { save(p);committed=true }
            progress(TreeImporter.Progress(name,total,size,1,1));p
        } finally { if(!committed) ManagedFiles.removeTree(dir) }
    }
    suspend fun recoverImport(id: String)=withContext(Dispatchers.IO) {
        val dir=home(id)
        ManagedFiles.locked(listOf(dir)) { reconcileImport(dir) }
    }
    private suspend fun reconcileImport(dir: File) {
        val journal=ManagedFiles.read(File(dir,"import-journal.json")) ?: return
        check(journal.getString("profileId")==dir.name) { "Import ownership mismatch" }
        val current=profiles.first().firstOrNull { it.id==dir.name } ?: error("Missing import profile")
        val content=ManagedFiles.resolve(dir,"content");val backup=ManagedFiles.resolve(dir,"content.previous");val stage=ManagedFiles.resolve(dir,"content.new")
        if(current.contentGeneration==journal.optString("generation") && content.isDirectory) {
            check(File(content,".frostbox-import-generation").readText()==current.contentGeneration)
            ManagedFiles.removeTree(backup);ManagedFiles.removeTree(stage)
        } else {
            if(backup.exists()) { ManagedFiles.removeTree(content);check(backup.renameTo(content)) }
            else if(content.isDirectory && File(content,".frostbox-import-generation").takeIf { it.isFile }?.readText()==journal.optString("generation")) ManagedFiles.removeTree(content)
            ManagedFiles.removeTree(stage);persist(current)
        }
        check(File(dir,"import-journal.json").delete())
    }
    suspend fun importTree(profile: GameProfile,uri: Uri,progress: (TreeImporter.Progress)->Unit): GameProfile=withContext(Dispatchers.IO) {
        val dir=home(profile.id)
        ManagedFiles.locked(listOf(dir)) {
            reconcileImport(dir)
            val current=profiles.first().single { it.id==profile.id }
            val stage=ManagedFiles.resolve(dir,"content.new");check(!stage.exists());check(stage.mkdir())
            val generation=UUID.randomUUID().toString()
            ManagedFiles.write(File(dir,"import-journal.json"),JSONObject().put("profileId",profile.id).put("generation",generation))
            try {
                val entries=TreeImporter(context).copy(uri,stage,progress)
                // A matching copy relocates the EXE next to its DLLs while preserving every relative path.
                val matches=entries.filter { it.relative.endsWith(".exe",true) }.filter { sha(ManagedFiles.resolve(stage,it.relative))==current.sha256 }
                val selected=matches.singleOrNull()?.relative
                if(selected==null && current.executable.startsWith("content/")) {
                    val oldRelative=current.executable.removePrefix("content/")
                    val retained=ManagedFiles.resolve(stage,oldRelative)
                    check(retained.isFile && sha(retained)==current.sha256) { "Replacement folder does not contain the configured EXE; previous import retained" }
                }
                val exe=selected?.let { "content/$it" } ?: current.executable
                val wd=selected?.substringBeforeLast('/',"")?.let { if(it.isEmpty()) "content" else "content/$it" } ?: "content"
                val p=current.copy(executable=exe,workingDirectory=wd,contentGeneration=generation)
                File(stage,".frostbox-import-generation").writeText(generation)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val content=ManagedFiles.resolve(dir,"content");val backup=ManagedFiles.resolve(dir,"content.previous")
                    if(content.exists()) check(content.renameTo(backup))
                    check(stage.renameTo(content));persist(p);store(p);reconcileImport(dir)
                };p
            } catch(e: Throwable) { withContext(NonCancellable) { reconcileImport(dir) };throw e }
        }
    }
    suspend fun listExecutables(id: String): List<String> = withContext(Dispatchers.IO) {
        val dir=home(id);val content=ManagedFiles.resolve(dir,"content")
        val files=mutableListOf("game.exe")
        if(content.isDirectory) java.nio.file.Files.walk(content.toPath(),32).use { paths -> paths.filter { java.nio.file.Files.isRegularFile(it,java.nio.file.LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".exe",true) }.limit(1000).forEach { files+=dir.toPath().relativize(it).toString() } }
        files
    }
    suspend fun prepareLaunch(profile: GameProfile)=withContext(Dispatchers.IO) {
        recoverImport(profile.id);save(profile) // Shared lock also prevents importing while launch is active.
        val dir=home(profile.id);val exe=ManagedFiles.resolve(dir,profile.executable)
        check(exe.isFile && PeInspector.inspect(exe)==profile.architecture && sha(exe)==profile.sha256) { "Missing, changed or unsupported EXE" }
        check(ManagedFiles.resolve(dir,profile.workingDirectory).isDirectory) { "Missing working directory" }
    }
    fun isGui(profile: GameProfile)=PeInspector.isGui(ManagedFiles.resolve(home(profile.id),profile.executable))
    fun readRun(id: String)=ManagedFiles.read(File(home(id),"launcher-result.json"))
    fun writeRun(id: String,result: JSONObject)=ManagedFiles.write(File(home(id),"launcher-result.json"),result)
    suspend fun export(uri: Uri)=withContext(Dispatchers.IO) {
        val archive=profileDataStore.data.first()[key] ?: json.encodeToString(ProfileArchive())
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use { it.write(archive) } ?: error("Cannot open export destination")
    }
}
