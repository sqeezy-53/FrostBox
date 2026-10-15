package org.frostbox.app.audio
import android.content.Context
import android.net.*
import android.os.Process
import android.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.frostbox.core.AudioWire
import org.json.JSONObject
import java.io.DataInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** One stream at a time, fixed-size PCM packets; no background queue or UI-thread audio IO. */
class AudioHub(prefixId: String,scope: CoroutineScope,private val factory: ()->PcmOutput): AutoCloseable {
 constructor(context: Context,prefixId: String,scope: CoroutineScope):this(prefixId,scope,{AndroidPcmOutput(context)})
 private val closed=AtomicBoolean(false);private val server: LocalServerSocket
 @Volatile private var peer: LocalSocket?=null
 private val mutable=MutableStateFlow(JSONObject().put("deviceTested",false).put("audibilityVerified",false).toString())
 val report=mutable.asStateFlow()
 @Volatile private var gain=1f
 fun setGain(value: Float){require(value.isFinite() && value in 0f..1f);gain=value}
 private val job: Job
 init {
  require(UUID.fromString(prefixId).toString()==prefixId);server=LocalServerSocket("frost-audio-${Process.myUid()}-$prefixId")
  job=scope.launch(Dispatchers.IO){try{while(isActive&&!closed.get()){
   val client=server.accept();peer=client
   var output: PcmOutput?=null;var received=0L;var played=0L;var totalReceived=0L;var totalPlayed=0L;var previousPlayed=0L;var starts=0;var stops=0;var capacity=0;val crc=CRC32();var error=""
   try {
    check(client.peerCredentials.uid==Process.myUid()){ "Foreign audio peer" }
    val input=DataInputStream(client.inputStream);val reply=client.outputStream;val header=ByteArray(16)
    while(isActive&&!closed.get()){
     // EOF at a packet boundary is normal stream release, not a transport failure.
     val first=input.read();if(first<0)break;header[0]=first.toByte();input.readFully(header,1,15);val h=AudioWire.header(header)
     if(output==null){check(h.operation==1){"HELLO required"};output=factory();capacity=output.capacity;require(capacity in 1..AudioWire.MAX_FRAMES)}
     val sink=checkNotNull(output);sink.gain(gain)
     when(h.operation){
      1->Unit
      2->{sink.start();starts++}
      3->{val pcm=ByteArray(h.bytes);input.readFully(pcm);played=sink.played.coerceAtMost(received);check(h.frames<=capacity-(received-played)){"PCM capacity exceeded"};sink.write(pcm);received+=h.frames;totalReceived+=h.frames;crc.update(pcm)}
      4->{sink.pause();stops++}
      5->{val current=sink.played.coerceAtMost(received);totalPlayed+=(current-previousPlayed).coerceAtLeast(0);sink.reset();received=0;played=0;previousPlayed=0}
      6->Unit
     }
     played=sink.played.coerceAtMost(received);totalPlayed+=(played-previousPlayed).coerceAtLeast(0);previousPlayed=played;val padding=(received-played).coerceIn(0,capacity.toLong()).toInt()
     reply.write(AudioWire.reply(0,padding,received,played,capacity))
     mutable.value=JSONObject().put("outputInitialized",true).put("outputKind",sink.kind).put("sampleRate",AudioWire.RATE).put("channels",2).put("format","PCM16").put("totalReceivedFrames",totalReceived).put("totalConsumedFrames",totalPlayed).put("receivedFrames",received).put("consumedFrames",played).put("capacityFrames",capacity).put("crc32",crc.value).put("starts",starts).put("stops",stops).put("gain",gain).put("outputGain",sink.appliedGain).put("cleanup",false).put("audibilityVerified",false).toString()
    }
   }catch(e: Exception){if(!closed.get())error=e.message ?: e.toString();runCatching {client.outputStream.write(AudioWire.reply(1,0,received,played,capacity))}}
   finally {
    runCatching {output?.close()};runCatching {client.close()};peer=null
    mutable.value=JSONObject().put("outputInitialized",output!=null).put("outputKind",output?.kind ?: "none").put("sampleRate",AudioWire.RATE).put("channels",2).put("format","PCM16").put("totalReceivedFrames",totalReceived).put("totalConsumedFrames",totalPlayed).put("receivedFrames",received).put("consumedFrames",played).put("crc32",crc.value).put("starts",starts).put("stops",stops).put("cleanup",true).put("error",error).put("audibilityVerified",false).toString()
   }
  }}catch(e: Exception){if(!closed.get())mutable.value=JSONObject().put("error",e.toString()).toString()}}
 }
 suspend fun awaitClosed(){job.join()}
 override fun close(){if(closed.compareAndSet(false,true)){
  runCatching{peer?.fileDescriptor?.let {Os.shutdown(it,OsConstants.SHUT_RDWR)}};runCatching{Os.shutdown(server.fileDescriptor,OsConstants.SHUT_RDWR)}
  runCatching{peer?.close()};runCatching{server.close()};job.cancel()
 }}
}
