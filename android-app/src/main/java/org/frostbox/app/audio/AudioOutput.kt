package org.frostbox.app.audio
import android.content.Context
import android.media.*
import android.os.*
import org.frostbox.core.AudioWire

interface PcmOutput: AutoCloseable {
 val kind: String get()="test-output"
 val appliedGain: Float? get()=null
 val capacity: Int
 val played: Long
 fun gain(value: Float) {}
 fun start()
 fun pause()
 fun reset()
 fun write(bytes: ByteArray)
}
/** A real Android output must initialize and obtain audio focus before HELLO acknowledges it. */
class AndroidPcmOutput(context: Context): PcmOutput {
 private val manager=context.getSystemService(AudioManager::class.java)
 private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
 @Volatile private var focusLost=false
 private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(false)
  .setOnAudioFocusChangeListener({change->if(change<0){focusLost=true;runCatching{track?.pause()}}},Handler(Looper.getMainLooper())).build()
 private var track: AudioTrack?=null
 private var previous=0L;private var wraps=0L
 private val bytes: Int
 @Volatile override var appliedGain: Float=1f
  private set
 override val kind="android-audiotrack"
 override val capacity: Int
 init {
  val min=AudioTrack.getMinBufferSize(AudioWire.RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT)
  require(min>0 && min<=AudioWire.MAX_FRAMES*4){"Unsupported Android audio buffer"}
  bytes=AudioWire.MAX_FRAMES*4
  check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Android audio focus unavailable"}
  try {track=create();capacity=track!!.bufferSizeInFrames.coerceAtMost(AudioWire.MAX_FRAMES)}
  catch(e: Exception){track?.release();manager.abandonAudioFocusRequest(focus);throw e}
 }
 private fun create()=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(AudioWire.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
  .setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build().also {if(it.state!=AudioTrack.STATE_INITIALIZED){it.release();error("Android output did not initialize")}}
 private fun active(): AudioTrack {check(!focusLost){"Android audio focus lost"};return checkNotNull(track){"Output released"}}
 override val played: Long get(){val raw=active().playbackHeadPosition.toLong() and 0xffffffffL;if(raw<previous)wraps+=1L shl 32;previous=raw;return wraps+raw}
 override fun gain(value: Float){require(value.isFinite() && value in 0f..1f);check(active().setVolume(value)==AudioTrack.SUCCESS);appliedGain=value}
 override fun start(){active().play()}
 override fun pause(){active().pause()}
 override fun reset(){active().stop();track?.release();track=create();appliedGain=1f;previous=0;wraps=0}
 override fun write(bytes: ByteArray){
  var offset=0;val deadline=SystemClock.elapsedRealtime()+1500
  while(offset<bytes.size){val n=active().write(bytes,offset,bytes.size-offset,AudioTrack.WRITE_NON_BLOCKING);check(n>=0){"AudioTrack write error $n"};offset+=n
   if(n==0){check(SystemClock.elapsedRealtime()<deadline){"Android audio buffer did not drain"};Thread.sleep(2)}
  }
 }
 override fun close(){val old=track;track=null;runCatching{old?.pause()};old?.release();manager.abandonAudioFocusRequest(focus)}
}
