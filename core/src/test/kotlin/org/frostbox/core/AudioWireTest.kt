package org.frostbox.core
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
class AudioWireTest {
 private fun packet(op: Int,n: Int,b: Int)=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(AudioWire.MAGIC).putInt(op).putInt(n).putInt(b).array()
 @Test fun acceptsOnlyBoundedStereoPackets(){assertEquals(480,AudioWire.header(packet(3,480,1920)).frames);for(p in listOf(packet(3,4801,19204),packet(3,480,960),packet(1,1,0),packet(9,0,0)))assertThrows(IllegalArgumentException::class.java){AudioWire.header(p)}}
 @Test fun preserves64BitPlaybackCounters(){val b=ByteBuffer.wrap(AudioWire.reply(0,5,(1L shl 33)+8,(1L shl 32)+3,4800)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(AudioWire.MAGIC,b.int);assertEquals(0,b.int);assertEquals(5,b.int);assertEquals(8,b.int);assertEquals(2,b.int);assertEquals(3,b.int);assertEquals(1,b.int);assertEquals(4800,b.int)}
}
