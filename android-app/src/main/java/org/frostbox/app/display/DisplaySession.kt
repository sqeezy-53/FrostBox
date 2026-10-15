package org.frostbox.app.display

import android.graphics.*
import org.frostbox.core.DisplayGeometry
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Same-device software surface transport. Never interprets Win32 drawing commands. */
class DisplaySession {
    data class Status(val active: Boolean=false,val frames: Long=0,val width: Int=0,val height: Int=0,
        val firstFrameMs: Long?=null,val error: String="",val connected: Int=0)
    private data class Frame(val owner: LocalSocket,val hwnd: Int,val x: Int,val y: Int,val w: Int,val h: Int,val bytes: ByteArray)
    private data class Presentation(val target: Surface?,val width: Int,val height: Int,val frame: Frame?,val revision: Long,val generation: Long)
    private val state=MutableStateFlow(Status())
    val status=state.asStateFlow()
    private val lock=Any()
    private var server: LocalServerSocket?=null
    private var workers: ExecutorService?=null
    private val render=Executors.newSingleThreadExecutor()
    private val input=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(256))
    private val peers=ConcurrentHashMap.newKeySet<LocalSocket>()
    private var surface: Surface?=null
    private var surfaceWidth=0;private var surfaceHeight=0
    private var latest: Frame?=null
    private var gpuInput: Frame?=null
    private var bitmap: Bitmap?=null
    @Volatile private var generation=0L
    private var revision=0L
    private var started=0L
    private var presented=0L
    private val queued=AtomicBoolean(false)
    @Volatile private var enabled=false
    @Volatile private var acceptingInput=false
    fun start(): String {
        check(!enabled)
        val name="frostbox-${Process.myUid()}-${java.util.UUID.randomUUID()}"
        val socket=LocalServerSocket(name)
        synchronized(lock) { generation++;latest=null;gpuInput=null;revision++;started=SystemClock.elapsedRealtime();presented=0;enabled=true;acceptingInput=true }
        state.value=Status(active=true);server=socket
        val pool=Executors.newFixedThreadPool(9);workers=pool
        val token=generation
        pool.execute {
            try {
                while(enabled && generation==token) {
                    val client=socket.accept()
                    if(client.peerCredentials.uid!=Process.myUid() || peers.size>=8) { client.close();continue }
                    Os.setsockoptTimeval(client.fileDescriptor,OsConstants.SOL_SOCKET,OsConstants.SO_SNDTIMEO,StructTimeval.fromMillis(500))
                    peers.add(client);state.value=state.value.copy(connected=peers.size)
                    pool.execute { receive(client,token) }
                }
            } catch(e: Exception) { if(enabled && token==generation) fail("Display accept: ${e.message}") }
        }
        return name
    }
    @Volatile private var requestedFocus=false
    private fun receive(client: LocalSocket,token: Long) {
        try {
            val stream=DataInputStream(client.inputStream);val header=ByteArray(32)
            while(enabled && generation==token) {
                stream.readFully(header)
                val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                check(b.int==0x44584246) { "Invalid display protocol" }
                val type=b.int;val hwnd=b.int;val x=b.int;val y=b.int;val w=b.int;val h=b.int;val size=b.int
                when(type) {
                    1 -> {
                        check(w in 1..2048 && h in 1..2048 && size==w*h*4 && x in -32768..32768 && y in -32768..32768)
                        val bytes=ByteArray(size);stream.readFully(bytes)
                        val newTarget=synchronized(lock) { if(token==generation) { val changed=latest?.hwnd!=hwnd || latest?.owner!==client;latest=Frame(client,hwnd,x,y,w,h,bytes);revision++;changed } else false }
                        if(newTarget && requestedFocus)event(6,1)
                        queueRender()
                    }
                    2 -> { check(size==0); synchronized(lock) { if(token==generation && latest?.owner===client && latest?.hwnd==hwnd) { latest=null;gpuInput=null;revision++ } };queueRender() }
                    8 -> {
                        check(size==0 && w in 1..4096 && h in 1..4096 && x in -32768..32768 && y in -32768..32768)
                        val newTarget=synchronized(lock) { if(token==generation){val changed=gpuInput?.hwnd!=hwnd || gpuInput?.owner!==client;gpuInput=Frame(client,hwnd,x,y,w,h,ByteArray(0));changed}else false }
                        if(newTarget && requestedFocus)event(6,1)
                    }
                    else -> error("Unknown display packet $type")
                }
            }
        } catch(e: Exception) {
            if(enabled && generation==token && e !is java.io.EOFException) fail("Display read: ${e.message}")
        } finally {
            peers.remove(client);runCatching { client.close() }
            if(token==generation) state.value=state.value.copy(connected=peers.size)
        }
    }
    private fun fail(message: String) { state.value=state.value.copy(error=message) }
    fun attach(value: Surface?,width: Int=0,height: Int=0) {
        if(render.isShutdown) return
        // Own a separate native reference. SurfaceHolder may release its Java Surface immediately
        // after surfaceDestroyed; our reference is retired only on the render executor.
        val owned=value?.let { original ->
            val parcel=Parcel.obtain()
            try { original.writeToParcel(parcel,0);parcel.setDataPosition(0);Surface.CREATOR.createFromParcel(parcel) }
            finally { parcel.recycle() }
        }
        val retired=synchronized(lock) {
            val old=surface;surface=owned;surfaceWidth=width;surfaceHeight=height;revision++;old
        }
        if(retired!=null) render.execute { retired.release() }
        queueRender()
    }
    private fun destination(w: Int,h: Int,sw: Int,sh: Int): RectF {
        val geometry=DisplayGeometry(w,h,sw,sh)
        return RectF(geometry.left,geometry.top,geometry.left+w*geometry.scale,geometry.top+h*geometry.scale)
    }
    private fun queueRender() {
        if(render.isShutdown || !queued.compareAndSet(false,true)) return
        render.execute {
            var observed=-1L
            var attempted: Surface?=null
            try {
                val snapshot=synchronized(lock) { Presentation(surface,surfaceWidth,surfaceHeight,latest,revision,generation) }
                observed=snapshot.revision
                val target=snapshot.target ?: return@execute
                attempted=target
                if(!target.isValid || snapshot.width<1 || snapshot.height<1) return@execute
                val frame=snapshot.frame
                // Conversion and Surface locking must never hold the UI/lifecycle state lock.
                // Bitmap access belongs exclusively to this render executor.
                if(frame!=null) {
                    if(bitmap?.width!=frame.w || bitmap?.height!=frame.h) { bitmap?.recycle();bitmap=Bitmap.createBitmap(frame.w,frame.h,Bitmap.Config.ARGB_8888) }
                    val colors=IntArray(frame.w*frame.h)
                    ByteBuffer.wrap(frame.bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(colors)
                    for(i in colors.indices) colors[i]=colors[i] or 0xff000000.toInt()
                    bitmap!!.setPixels(colors,0,frame.w,0,0,frame.w,frame.h)
                }
                if(snapshot.generation!=generation) return@execute
                val canvas=target.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    if(frame!=null) canvas.drawBitmap(bitmap!!,null,destination(frame.w,frame.h,canvas.width,canvas.height),null)
                } finally { target.unlockCanvasAndPost(canvas) }
                synchronized(lock) {
                    if(frame!=null && enabled && snapshot.generation==generation) {
                        presented++
                        state.value=state.value.copy(frames=presented,width=frame.w,height=frame.h,
                            firstFrameMs=state.value.firstFrameMs ?: (SystemClock.elapsedRealtime()-started))
                    }
                }
            } catch(e: Exception) {
                // A detached/replaced producer cannot accept a pending frame. It is not a
                // Windows runtime failure; the next attached Surface replays the latest frame.
                if(enabled && synchronized(lock) { surface===attempted && revision==observed })
                    fail("Surface presentation: ${e.message}")
            }
            finally { queued.set(false);if(synchronized(lock) { revision!=observed }) queueRender() }
        }
    }
    private val inputSequence=java.util.concurrent.atomic.AtomicInteger()
    private fun event(type: Int,a: Int=0,b: Int=0,c: Int=0,text: CharSequence?=null) {
        if(!acceptingInput) return
        val frame=synchronized(lock) { gpuInput ?: latest } ?: return
        if(!peers.contains(frame.owner)) return
        val correlation=inputSequence.incrementAndGet()
        if(type==7) android.util.Log.d("FrostBoxDisplay","text corr=$correlation hwnd=${frame.hwnd} units=${text?.length ?: 0}")
        val bytes=ByteBuffer.allocate(32*(text?.length?.times(2) ?: 1)).order(ByteOrder.LITTLE_ENDIAN).apply {
            fun packet(x: Int,y: Int,flags: Int) { putInt(0x44584246);putInt(type);putInt(frame.hwnd);putInt(x);putInt(y);putInt(flags);putInt(correlation);putInt(0) }
            if(text==null) packet(a,b,c) else text.forEach { packet(it.code,0,0);packet(it.code,0,1) }
        }.array()
        val token=generation
        try { input.execute {
            if(token==generation && enabled && acceptingInput && peers.contains(frame.owner)) runCatching { frame.owner.outputStream.write(bytes) }
                .onFailure { if(enabled && acceptingInput && token==generation && peers.contains(frame.owner)) fail("Display input: ${it.message}") }
        } } catch(e: RejectedExecutionException) { if(enabled) fail("Display input queue is full; stop and retry") }
    }
    fun inputViewport(width: Int,height: Int) { synchronized(lock) { surfaceWidth=width;surfaceHeight=height } }
    fun pointer(x: Float,y: Float,flags: Int) {
        val point=synchronized(lock) {
            val f=gpuInput ?: latest ?: return
            if(surfaceWidth<1 || surfaceHeight<1)return
            val point=if(gpuInput!=null) Pair((x*f.w/surfaceWidth).toInt().coerceIn(0,f.w-1),(y*f.h/surfaceHeight).toInt().coerceIn(0,f.h-1))
                else DisplayGeometry(f.w,f.h,surfaceWidth,surfaceHeight).map(x,y,flags and (4 or 16)!=0) ?: return
            Pair(f.x+point.first,f.y+point.second)
        }
        event(3,point.first,point.second,flags)
    }
    fun hasInputTarget()=acceptingInput && synchronized(lock){(gpuInput ?: latest)!=null}
    fun relativePointer(dx: Int,dy: Int,flags: Int=0){if(dx in -2000..2000 && dy in -2000..2000 && flags and 0x1e==flags)event(8,dx,dy,flags)}
    fun key(vk: Int,up: Boolean=false) { event(4,vk,0,if(up) 1 else 0) }
    /** IME commits UTF-16 WM_CHAR units to the owning thread focus, separately from hardware keys. */
    fun text(value: CharSequence) {
        if(value.length>2048 || value.contains('\u0000')) return
        if(value.isNotEmpty()) event(7,text=value.toString())
    }
    fun closeWindow()=event(5)
    fun focus(value: Boolean) { requestedFocus=value;event(6,if(value) 1 else 0) }
    fun timedOut()=fail("No software frame within 15 seconds of Windows process start")
    fun report()=JSONObject().put("backend","Wine software GDI / local socket").put("framesPresented",state.value.frames)
        .put("firstFrameMs",state.value.firstFrameMs).put("width",state.value.width).put("height",state.value.height)
        .put("error",state.value.error).put("connected",peers.size).put("inputEnabled",acceptingInput)
        .put("inputWindow",synchronized(lock){(gpuInput ?: latest)?.hwnd ?: 0})
        .put("inputClientWidth",synchronized(lock){(gpuInput ?: latest)?.w ?: 0}).put("inputClientHeight",synchronized(lock){(gpuInput ?: latest)?.h ?: 0})
    fun stopInput() { acceptingInput=false;input.queue.clear() }
    fun stop() {
        stopInput();enabled=false;generation++
        runCatching { server?.close() };server=null
        peers.forEach { runCatching { it.close() } };peers.clear()
        workers?.shutdownNow();workers=null
        synchronized(lock) { latest=null;gpuInput=null;revision++ }
        state.value=state.value.copy(active=false,connected=0);queueRender()
        if(!render.isShutdown) render.execute { synchronized(lock) { if(!enabled) { bitmap?.recycle();bitmap=null } } }
    }
    fun dispose() { stop();attach(null);input.shutdown();render.execute { synchronized(lock) { bitmap?.recycle();bitmap=null } };render.shutdown() }
}
