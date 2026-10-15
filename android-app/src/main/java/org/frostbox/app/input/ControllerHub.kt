package org.frostbox.app.input

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.frostbox.core.GamepadSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental Wine HID transport. One authenticated reader, bounded latest-state packets.
 * No Binder call in Windows XInputGetState; Wine maintains its normal HID state cache.
 */
class ControllerHub(prefixId: String,private val monitor: ControllerMonitor,scope: CoroutineScope): AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val server: LocalServerSocket
    @Volatile private var peer: LocalSocket?=null
    private val mutable=MutableStateFlow("Waiting for Wine HID");val status=mutable.asStateFlow()
    private val job: Job
    init {
        require(UUID.fromString(prefixId).toString()==prefixId)
        server=LocalServerSocket("frost-pad-${Process.myUid()}-$prefixId")
        job=scope.launch(Dispatchers.IO) {
            try {while(isActive && !closed.get()) {
                val client=server.accept();peer=client
                try {
                    check(client.peerCredentials.uid==Process.myUid()) {"Foreign input peer"}
                    client.sendBufferSize=4096;client.soTimeout=1000
                    mutable.value="Wine HID connected"
                    val out=client.outputStream
                    while(isActive && !closed.get()) {
                        val bytes=ByteBuffer.allocate(132).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31504246)
                        val pads=monitor.runtimePads()
                        for(slot in 0..3) {
                            val p=pads.firstOrNull {it.slot==slot} ?: GamepadSnapshot(slot,"")
                            bytes.putInt(if(p.connected)1 else 0).putInt(p.buttons)
                            fun axis(v: Float)= (v.coerceIn(-1f,1f)*32767).toInt()
                            bytes.putInt(axis(p.leftX)).putInt(axis(-p.leftY)).putInt(axis(p.rightX)).putInt(axis(-p.rightY))
                            bytes.putInt(axis(p.leftTrigger)).putInt(axis(p.rightTrigger))
                        }
                        out.write(bytes.array());delay(16)
                    }
                } catch(e: Exception) {if(!closed.get())mutable.value="Wine HID disconnected: ${e.message}"}
                finally {runCatching {client.close()};peer=null}
            }}catch(e: Exception){if(!closed.get())mutable.value="Input transport error: ${e.message}"}
        }
    }
    suspend fun awaitClosed() {job.join()}
    override fun close() {if(closed.compareAndSet(false,true)){
        // close(fd) alone can leave Linux accept() holding the abstract socket alive.
        // shutdown wakes accept/write before descriptor close and coroutine cancellation.
        runCatching {peer?.fileDescriptor?.let {android.system.Os.shutdown(it,android.system.OsConstants.SHUT_RDWR)}}
        runCatching {android.system.Os.shutdown(server.fileDescriptor,android.system.OsConstants.SHUT_RDWR)}
        runCatching{peer?.close()};runCatching{server.close()};job.cancel();mutable.value="Closed"
    }}
}
