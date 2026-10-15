package org.frostbox.app.runtime

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.frostbox.app.data.ManagedFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Reconciles identical immutable payloads without deleting a runtime root or changing a byte.
 * No games, diagnostic run-* directories, Wine prefixes, metadata or symlinks are eligible. */
internal object RuntimeFileSharing {
    data class Identity(val device: Long, val inode: Long, val mode: Int, val size: Long, val modified: Long)
    data class Payload(val file: File, val relative: String, val identity: Identity, val blocks: Long, val links: Long)
    data class Replacement(val source: Payload, val target: Payload, val sha256: String)
    data class Plan(val privateRoot: File, val roots: Int, val examined: Int,
                    val expectedReclaim: Long, val replacements: List<Replacement>, val referenceTemps: List<java.nio.file.Path>) {
        fun json() = JSONObject().put("schema",1).put("runtimeRoots",roots).put("examinedFiles",examined)
            .put("replacementPaths",replacements.size).put("expectedReclaimedAllocatedBytes",expectedReclaim)
            .put("unpublishedReferenceTemps",JSONArray(referenceTemps.map {it.toFile().relativeTo(privateRoot).path}))
            .put("policy","verified private PE/font objects; same bytes/mode/mtime; no game or prefix deletion")
            .put("entries",JSONArray().apply { replacements.forEach { r -> put(JSONObject()
                .put("path",r.target.file.relativeTo(privateRoot).path)
                .put("sharedWith",r.source.file.relativeTo(privateRoot).path)
                .put("bytes",r.target.identity.size).put("sha256",r.sha256)
                .put("category","immutable-runtime").put("rebuildable",true)
                .put("whySafe","content verified identical; original paths retained; idle runtime lease")) } })
    }

    private fun payload(file: File, relative: String): Payload {
        ManagedFiles.noLink(file)
        val s = Os.lstat(file.path)
        check(OsConstants.S_ISREG(s.st_mode) && s.st_uid == android.os.Process.myUid())
        return Payload(file,relative,Identity(s.st_dev,s.st_ino,s.st_mode,s.st_size,s.st_mtime),s.st_blocks*512,s.st_nlink)
    }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest=MessageDigest.getInstance("SHA-256");val block=ByteArray(262144)
        while(true) { val n=input.read(block);if(n<0)break;digest.update(block,0,n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun verifyReferenceTemp(privateRoot: File,path: java.nio.file.Path,relative: String) {
        check(Files.isSymbolicLink(path) && Os.lstat(path.toString()).st_uid==android.os.Process.myUid())
        val extension=Files.readSymbolicLink(path).toFile().extension
        val proof=relative.substringBeforeLast('/')+"/owned-reference.$extension"
        check(ImmutableRuntimeFiles.shareable(proof))
        ImmutableRuntimeFiles.resolve(privateRoot,path,proof)
    }
    private fun roots(context: Context): List<File> {
        val roots=mutableListOf<File>()
        val base=ManagedFiles.resolve(context.filesDir,"runtime-tests/wine-userspace")
        val baseMarker=File(base,".package-sha256");ManagedFiles.noLink(baseMarker)
        if(baseMarker.isFile && baseMarker.readText().matches(Regex("[0-9a-f]{64}")))roots+=base.canonicalFile
        for(parentName in listOf("display","wine-vulkan","wine-wsi","wine-input","wine-keyboard","wine-audio","wine-audio-wow64","horizon-wow64")) {
            val parent=ManagedFiles.resolve(context.filesDir,parentName)
            for(root in parent.listFiles().orEmpty().sortedBy { it.name }) {
                if(!root.name.matches(Regex("[0-9a-f]{64}(-v3)?")))continue
                ManagedFiles.noLink(root)
                val marker=File(root,if(parentName=="horizon-wow64")"wow64-ready.json" else "ready")
                ManagedFiles.noLink(marker)
                if(root.isDirectory && marker.isFile)roots+=root.canonicalFile
            }
        }
        return roots
    }
    suspend fun inspect(context: Context): Plan = withContext(Dispatchers.IO) {
        RuntimeStorageLease.acquire(context,maintenance=true).use {
            val runtimeRoots=roots(context);val files=mutableListOf<Payload>();val referenceTemps=mutableListOf<java.nio.file.Path>()
            for(root in runtimeRoots) {
                Files.walk(root.toPath()).use { paths -> paths.forEach { path ->
                    val relative=root.toPath().relativize(path).toString()
                    if(path.fileName.toString().matches(Regex("\\.frost-share-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) {
                        // Dry-run only records verified unpublished references; apply retires them.
                        verifyReferenceTemp(context.filesDir,path,relative)
                        referenceTemps.add(path)
                    } else {
                        ImmutableRuntimeFiles.resolve(context.filesDir,path,relative)
                        if(!Files.isSymbolicLink(path) && Files.isRegularFile(path) && ImmutableRuntimeFiles.shareable(relative)) files+=payload(path.toFile(),relative)
                    }
                } }
            }
            // Existing links to an unexamined location (possibly mutable) are never used as sources.
            val inodeCounts=files.groupingBy { it.identity.device to it.identity.inode }.eachCount()
            val safe=files.filter { it.links == inodeCounts[it.identity.device to it.identity.inode]?.toLong() }
            val replacements=mutableListOf<Replacement>();var expected=0L
            for(group in safe.groupBy { listOf(it.relative,it.identity.device,it.identity.mode,it.identity.size,it.identity.modified) }.values) {
                currentCoroutineContext().ensureActive()
                if(group.map { it.identity.device to it.identity.inode }.distinct().size<2)continue
                val hashes=group.groupBy { p ->
                    val digest=hash(p.file)
                    check(payload(p.file,p.relative).identity==p.identity) { "Runtime changed during analysis" }
                    digest
                }
                for((digest,identical) in hashes) {
                    val canonical=identical.first()
                    if(identical.size<2)continue
                    val objectPath=ImmutableRuntimeFiles.objectFile(context.filesDir,canonical.file,digest,canonical.identity.mode,canonical.identity.modified)
                    expected+=identical.distinctBy {it.identity.device to it.identity.inode}.sumOf {it.blocks} -
                        (if(objectPath.exists())0L else canonical.blocks)
                    identical.forEach { replacements+=Replacement(canonical,it,digest) }
                }
            }
            Plan(context.filesDir.canonicalFile,runtimeRoots.size,files.size,expected,replacements,referenceTemps)
        }
    }
    suspend fun apply(context: Context, plan: Plan): JSONObject = withContext(Dispatchers.IO) {
        RuntimeStorageLease.acquire(context,maintenance=true).use {
            check(context.filesDir.canonicalFile==plan.privateRoot)
            val currentRoots=roots(context).map { it.canonicalFile }.toSet()
            val verifiedSources=mutableSetOf<File>();var linked=0
            // Preflight the entire plan before changing anything. Stale plans fail closed.
            for(r in plan.replacements) {
                currentCoroutineContext().ensureActive()
                for(p in listOf(r.source,r.target)) {
                    check(currentRoots.any { root -> p.file.toPath().startsWith(root.toPath()) })
                    check(ImmutableRuntimeFiles.shareable(p.relative))
                    check(ManagedFiles.resolve(plan.privateRoot,p.file.relativeTo(plan.privateRoot).path)==p.file)
                    check(payload(p.file,p.relative).identity==p.identity) { "Stale runtime sharing plan; analyze again" }
                }
                if(verifiedSources.add(r.source.file)) check(hash(r.source.file)==r.sha256)
                check(hash(r.target.file)==r.sha256)
            }
            for(path in plan.referenceTemps) {
                val root=currentRoots.firstOrNull { path.startsWith(it.toPath()) } ?: error("Stale reference-temp root")
                verifyReferenceTemp(plan.privateRoot,path,root.toPath().relativize(path).toString())
            }
            for(path in plan.referenceTemps)Files.delete(path) // unlink only; verified object remains
            val objects=plan.replacements.distinctBy { it.source.file }.associate { r ->
                r.source.file to ImmutableRuntimeFiles.ensureObject(plan.privateRoot,r.source.file.toPath(),r.source.relative,
                    r.sha256,r.source.identity.mode,r.source.identity.modified)
            }
            for(r in plan.replacements) {
                currentCoroutineContext().ensureActive()
                ImmutableRuntimeFiles.replaceWithReference(r.target.file.toPath(),objects.getValue(r.source.file))
                check(ImmutableRuntimeFiles.resolve(plan.privateRoot,r.target.file.toPath(),r.target.relative)==objects.getValue(r.source.file).toPath())
                linked++
            }
            JSONObject().put("linkedPaths",linked).put("retiredUnpublishedReferences",plan.referenceTemps.size).put("expectedReclaimedAllocatedBytes",plan.expectedReclaim)
                .put("deletedRuntimeRoots",0).put("deletedPrefixes",0).put("deletedGameFiles",0)
        }
    }
}
