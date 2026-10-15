// SPDX-License-Identifier: MIT
package org.frostbox.app.runtime

import android.content.Context
import android.os.Process
import android.os.StatFs
import kotlinx.coroutines.*
import org.frostbox.app.data.ManagedFiles
import org.frostbox.core.PeInspector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Built-in console QA only, private independent userspace and fresh prefix.
 * Never upgrades a user prefix or changes the qualified Wine archive. */
internal object WineWow64Diagnostics {
    private fun sha(f: File)=f.inputStream().use {MessageDigest.getInstance("SHA-256").let {d->
        val b=ByteArray(65536);while(true){val n=it.read(b);if(n<0)break;d.update(b,0,n)}
        d.digest().joinToString(""){"%02x".format(it)}
    }}
    suspend fun execute(context: Context,base: File,cancelled: AtomicBoolean,gpuProbe: String?=null,wsiEndpoint: String?=null,sleep: Boolean=false,update: suspend (JSONObject,Boolean)->Unit) {
        val coroutine=currentCoroutineContext()
        val m=JSONObject(context.assets.open("wine-wow64/manifest.json").bufferedReader().use {it.readText()})
        check(m.getInt("schema")==1 && m.getString("wine")=="11.19")
        check(File(base,".package-sha256").readText()==m.getString("baseSha256"))
        require(gpuProbe==null || gpuProbe in listOf("vulkan","d3d9"))
        require(gpuProbe!="d3d9" || wsiEndpoint?.matches(Regex("@frost-guest-[a-f0-9-]{36}"))==true)
        val source=if(gpuProbe!=null)WineVulkanDiagnostics.view(context,org.frostbox.app.display.DisplayPackage.prepare(context,base),wsi=gpuProbe=="d3d9") else base
        val parent=File(context.filesDir,"horizon-wow64")
        val root=WineWow64Package.prepare(context,source,cancelled,gpuProbe!=null)
        ManagedFiles.locked(listOf(parent)) {
            val work=File(parent,"run-${UUID.randomUUID()}");check(work.mkdir())
            val prefix=File(work,"prefix");check(prefix.mkdir())
            for((arch,system) in listOf("x86_64" to "system32","i386" to "syswow64")) {
                val dest=File(prefix,"drive_c/windows/$system");check(dest.mkdirs())
                File(root,"x86_64/lib/wine/$arch-windows").listFiles()!!.filter{it.isFile}.forEach {f->
                    coroutine.ensureActive();check(!cancelled.get());f.copyTo(File(dest,f.name))
                }
            }
            val drives=File(prefix,"dosdevices");check(drives.mkdir())
            android.system.Os.symlink("../drive_c",File(drives,"c:").path);android.system.Os.symlink("/",File(drives,"z:").path)
            val fixture=if(sleep)"sleep-x86.exe" else when(gpuProbe){"vulkan"->"vulkan-compute-x86.exe";"d3d9"->"d3d9-triangle-x86.exe";else->"console-x86.exe"}
            val exe=File(work,fixture);context.assets.open("wine-wow64/$fixture").use {input->exe.outputStream().use {input.copyTo(it)}}
            val expected=if(gpuProbe==null && !sleep)m.getString("fixtureSha256") else JSONObject(context.assets.open("wine-wow64/$fixture.json").bufferedReader().use{it.readText()}).getString("sha256")
            check(sha(exe)==expected && PeInspector.inspect(exe)=="x86")
                        if(gpuProbe=="d3d9") {
                val dx=JSONObject(context.assets.open("wine-wow64/dxvk-x86/manifest.json").bufferedReader().use{it.readText()}).getJSONObject("files")
                for(name in listOf("d3d9.dll","d3d11.dll","dxgi.dll")) {
                    val f=File(work,name);context.assets.open("wine-wow64/dxvk-x86/$name").use {input->f.outputStream().use{input.copyTo(it)}};check(sha(f)==dx.getString(name))
                }
            }
            val display=if(gpuProbe=="d3d9")org.frostbox.app.display.DisplaySession() else null
            val endpoint=display?.start() ?: ""
            val bridge=NativeBridge();var handle=0L
            suspend fun run(op: String,guest: File=exe,dynarec: Boolean=true): JSONObject {
                val operation=if(op=="initialize" && gpuProbe!=null)"initialize-vulkan-x86-probe" else if(op=="launch" && gpuProbe!=null && guest==exe)"launch-$gpuProbe-x86-probe" else op
                handle=bridge.startWineCommand(context.applicationInfo.nativeLibraryDir,root.path,prefix.path,work.path,operation,guest.path,emptyArray(),
                    arrayOf("BOX64_DYNAREC","WINEDEBUG"),arrayOf(if(dynarec)"1" else "0",if(gpuProbe=="vulkan")"err+all,+vulkan" else "err+all,warn+module,+wow"),endpoint,if(gpuProbe!=null)root.path else "",if(op=="launch" && gpuProbe=="d3d9")checkNotNull(wsiEndpoint) else "",if(gpuProbe=="d3d9")work.path else "")
                val deadline=android.os.SystemClock.elapsedRealtime()+120000;var stop=false
                try {while(true){
                    val cleanup=op=="kill" || op=="wait"
                    if(((!cleanup && cancelled.get())||android.os.SystemClock.elapsedRealtime()>deadline)&&!stop){bridge.cancelDiagnostic(handle);stop=true}
                    val r=JSONObject(bridge.pollDiagnostic(handle)).put("runtimeUid",Process.myUid()).put("runtimePid",Process.myPid()).put("wow64",true).put("operation",op).put("dynarec",dynarec)
                    if(!r.getBoolean("running"))return r
                    update(r,false);delay(100)
                }} finally {bridge.releaseDiagnostic(handle);handle=0L}
            }
            val report=JSONObject().put("runtimeUid",Process.myUid()).put("runtimePid",Process.myPid()).put("root",root.path).put("prefix",prefix.path).put("basePreserved",true)
            try {
                val init=run("initialize");report.put("wineboot",init)
                if(init.optInt("exitCode",-1)==0 && !init.optBoolean("cancelled")) {
                    val count=if(gpuProbe==null && !sleep)4 else 1
                    val result=when(gpuProbe){"vulkan"->"FROSTBOX_VULKAN_RESULT=10800";"d3d9"->"FROSTBOX_D3D9_X86_DRAWS=120 PRESENTS=120";else->"FROSTBOX_PE_RESULT=5050"}
                    val runs=JSONArray();for(i in 0 until count){val r=run("launch",dynarec=i!=3);runs.put(r);if(r.optInt("exitCode",-1)!=42)break}
                    report.put("gpuProbe",gpuProbe).put("runs",runs).put("wow64Validated",runs.length()==count && (0 until runs.length()).all {i->
                        val r=runs.getJSONObject(i);r.optInt("exitCode",-1)==42 && r.optString("stdout").replace("\r\n","\n").contains(result+"\n") && r.optInt("signal")==0 && !r.optBoolean("cancelled")
                    })
                    if(!cancelled.get())report.put("x64Regression",run("launch",File(root,"console-x64.exe")))
                    report.put("cancelled",cancelled.get())
                    if(gpuProbe=="d3d9") {
                        val logs=JSONObject();work.listFiles()?.filter {it.name.endsWith("_d3d9.log")}?.take(4)?.forEach {logs.put(it.name,it.readText().takeLast(32768))}
                        report.put("dxvkLogs",logs)
                    }
                } else report.put("wow64Validated",false)
            } finally {
                withContext(NonCancellable) {
                    if(handle!=0L){bridge.releaseDiagnostic(handle);handle=0L}
                    try {
                        report.put("kill",run("kill")).put("wait",run("wait"))
                        report.put("prefixRegistryPresent",listOf("system.reg","user.reg","userdef.reg").all {File(prefix,it).isFile})
                        ManagedFiles.write(File(work,"report.json"),report)
                    } finally {display?.dispose()}
                }
            }
            update(report,true)
        }
    }
}
