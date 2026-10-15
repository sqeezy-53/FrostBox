package org.frostbox.app.runtime

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Wow64DeviceTest {
 @Test fun isolatedX86ConsoleAndX64Regression()=runBlocking {validate(19)}
 @Test fun x86VulkanCompute()=runBlocking {validate(20)}
 @Test fun cancelX86AndTerminateServer()=runBlocking {
    val inst=InstrumentationRegistry.getInstrumentation();val client=RuntimeProbeClient(inst.targetContext)
    try {
        withContext(Dispatchers.Main){client.start(23)}
        withTimeout(180000){client.state.first {it.report.contains("FROSTBOX_SLEEP_READY")}}
        withContext(Dispatchers.Main){client.cancel()}
        val final=withTimeout(30000){client.state.first {!it.active}};val r=JSONObject(final.report.substringAfter('\n',"{}"))
        inst.sendStatus(2,Bundle().apply{putString("frostbox.wow64.cancel",r.toString())})
        assertTrue(r.toString(),r.getBoolean("cancelled"));assertTrue(r.getJSONArray("runs").getJSONObject(0).getBoolean("cancelled"))
        assertEquals(0,r.getJSONObject("kill").getInt("exitCode"));assertEquals(0,r.getJSONObject("wait").getInt("exitCode"))
    } finally {withContext(Dispatchers.Main){client.cancel();client.close()}}
 }
 @Test fun nativePlacedMemoryControl()=runBlocking {
    val inst=InstrumentationRegistry.getInstrumentation();val client=RuntimeProbeClient(inst.targetContext)
    try {
        withContext(Dispatchers.Main){client.start(22)}
        val final=withTimeout(30000){client.state.first {!it.active}};val r=JSONObject(final.report.substringAfter('\n',"{}"))
        inst.sendStatus(2,Bundle().apply {putString("frostbox.map.control",r.toString())})
        assertEquals(Process.myUid(),r.getInt("runtimeUid"));assertNotEquals(Process.myPid(),r.getInt("runtimePid"))
        assertTrue(r.toString(),r.optString("error").isEmpty());assertEquals(4,r.getJSONArray("samples").length())
        for(i in 0..3)assertEquals(0,r.getJSONArray("samples").getJSONObject(i).getInt("normalMapResult"))
    } finally {withContext(Dispatchers.Main){client.close()}}
 }
 private suspend fun validate(mode: Int) {
    val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
    org.junit.Assume.assumeTrue("Build optional WoW64 overlay first",runCatching {context.assets.open("wine-wow64/manifest.json").close()}.isSuccess)
    val client=RuntimeProbeClient(context)
    try {
        withContext(Dispatchers.Main){client.start(mode)}
        val final=withTimeout(360000){client.state.first {!it.active}}
        val text=final.report.substringAfter('\n',"{}");val r=JSONObject(text)
        inst.sendStatus(2,Bundle().apply {putString("frostbox.wow64.result",r.toString())})
        assertEquals(Process.myUid(),r.getInt("runtimeUid"));assertNotEquals(Process.myPid(),r.getInt("runtimePid"))
        assertTrue(r.toString(),r.getBoolean("wow64Validated"))
        assertEquals(42,r.getJSONObject("x64Regression").getInt("exitCode"))
        assertEquals(0,r.getJSONObject("wait").getInt("exitCode"));assertTrue(r.getBoolean("prefixRegistryPresent"))
    } finally {withContext(Dispatchers.Main){client.cancel();client.close()}}
 }
}
