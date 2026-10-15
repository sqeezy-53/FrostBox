package org.frostbox.core
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioWire {
 const val MAGIC=0x31414246;const val RATE=48000;const val CHANNELS=2;const val MAX_FRAMES=4800
 data class Header(val operation: Int,val frames: Int,val bytes: Int)
 fun header(bytes: ByteArray): Header {
  require(bytes.size==16);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);require(b.int==MAGIC)
  val h=Header(b.int,b.int,b.int);require(h.operation in 1..6)
  if(h.operation==3)require(h.frames in 1..MAX_FRAMES && h.bytes==h.frames*4)else require(h.frames==0 && h.bytes==0)
  return h
 }
 fun reply(status: Int,padding: Int,received: Long,played: Long,capacity: Int)=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
  putInt(MAGIC);putInt(status);putInt(padding);putInt(received.toInt());putInt((received ushr 32).toInt());putInt(played.toInt());putInt((played ushr 32).toInt());putInt(capacity)
 }.array()
}
