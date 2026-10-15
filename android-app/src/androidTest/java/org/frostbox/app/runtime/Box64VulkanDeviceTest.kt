package org.frostbox.app.runtime

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Box64VulkanDeviceTest {
    private fun execute(mode: Int,label: String)=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();lateinit var client: RuntimeProbeClient
        inst.runOnMainSync {client=RuntimeProbeClient(inst.targetContext);client.start(mode)}
        try {
            val done=withTimeout(90000){client.state.first{!it.active}}
            val payload=done.report.substringAfter('\n')
            val parsed=runCatching {JSONObject(payload)}.getOrElse {JSONObject().put("error",done.report)}
            val r=parsed.put("label",label).put("testUid",Process.myUid()).put("testPid",Process.myPid())
            inst.sendStatus(2,Bundle().apply {putString("frostbox.bridge.result",r.toString())})
            val dir=File(inst.targetContext.filesDir,"vulkan-bridge-evidence").apply {mkdirs()};File(dir,"$label.json").writeText(r.toString(2))
            assertEquals(r.toString(),"",r.optString("error"))
            assertEquals(Process.myUid(),r.getInt("runtimeUid"));assertNotEquals(Process.myPid(),r.getInt("runtimePid"))
            assertFalse(r.toString(),r.getBoolean("running"));assertFalse(r.toString(),r.getBoolean("timedOut"));assertEquals(0,r.getInt("execErrno"));assertEquals(0,r.getInt("signal"))
            assertFalse("Child reaping",File("/proc/${r.getInt("pid")}").exists())
            val output=r.getString("stdout").replace("\r\n","\n")
            if(mode<=13)assertTrue(output,output.contains("UID=${Process.myUid()} PID=${r.getInt("pid")}"))
            if(mode==10 || mode==14) {assertEquals(11,r.getInt("exitCode"));if(mode==10)assertTrue(output,output.contains("FROSTBOX_LOADER_FAILED"));else assertTrue(r.toString(),r.getString("stderr").contains("Wine was built without Vulkan support"))}
            else {
                if(mode<=13)assertTrue(output,output.contains("UID=${Process.myUid()}"))
                assertTrue(output,output.contains("GPU=Adreno (TM) 840"));assertTrue(output,output.contains("FROSTBOX_INSTANCE_CREATED"));assertTrue(output,output.contains("FROSTBOX_DEVICE_CREATED"));assertTrue(output,output.contains("FROSTBOX_GPU_SUBMISSION_COMPLETED"))
                if(mode<=13 || output.contains("DEBUG_UTILS_AVAILABLE=1"))assertTrue(output,output.contains("FROSTBOX_DEBUG_CALLBACKS=1"));assertTrue(output,output.contains("FROSTBOX_CLEANUP_COMPLETE"))
                assertEquals(if(mode==13)43 else 42,r.getInt("exitCode"))
                if(mode==13) {assertTrue(output,output.contains("MISMATCH INDEX=0 ACTUAL=1 EXPECTED=9"));assertFalse(output,output.contains("FROSTBOX_VULKAN_RESULT="))}
                else assertTrue(output,output.contains("FROSTBOX_VULKAN_RESULT=10800\n"))
            }
            r
        } finally {inst.runOnMainSync {client.close()}}
    }
    private fun prepareWindowsPrefix() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val record=File(context.filesDir,"wine-vulkan-profile-id.txt")
        if(!record.exists()) {
            val previous=File(context.filesDir,"prefix-validation-id.txt")
            val id=if(previous.isFile)previous.readText().trim() else java.util.UUID.randomUUID().toString()
            if(!previous.isFile)PrefixDeviceTest().operation(id,"fixture")
            PrefixDeviceTest().operation(id,"initialize")
            record.writeText(id)
        }
        assertTrue(PrefixDeviceTest().operation(record.readText().trim(),"validate").getBoolean("valid"))
    }
    @Test fun windowsWithoutVulkanControl(){prepareWindowsPrefix();execute(14,"windows-without-vulkan-control")}
    @Test fun windowsCompute(){prepareWindowsPrefix();val r=execute(15,"windows-compute");assertFalse(r.getBoolean("bootstrap"));assertEquals(0,r.getJSONObject("wineShutdown").getJSONObject("wait").getInt("exitCode"))}
    @Test fun originalLoaderControl(){execute(10,"original-loader-control")}
    @Test fun computeDynarec(){execute(11,"compute-dynarec")}
    @Test fun computeInterpreter(){execute(12,"compute-interpreter")}
    @Test fun dispatchNegativeControl(){execute(13,"dispatch-negative-control")}
    @Test fun repeatedCompute(){repeat(10){execute(11,"repeat-$it")}}
}
