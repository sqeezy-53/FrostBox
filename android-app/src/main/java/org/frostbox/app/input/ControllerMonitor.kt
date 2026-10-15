package org.frostbox.app.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.frostbox.core.*

private val Context.controllerStore by preferencesDataStore("controllers")
data class ControllerInfo(val id: Int,val name: String,val descriptor: String,val axes: Int,val slot: Int=0)
data class ControllerState(val devices: List<ControllerInfo> = emptyList(),val lastEvent: String="",val axes: Map<String,Float> = emptyMap(),val pads: List<GamepadSnapshot> = emptyList(),val events: Long=0)
/** Public Android InputDevice events only. No Bluetooth scan or fabricated devices. */
class ControllerMonitor(private val context: Context,private val scope: CoroutineScope): InputManager.InputDeviceListener {
    private val manager=context.getSystemService(InputManager::class.java)
    private val mutable=MutableStateFlow(ControllerState());val state=mutable.asStateFlow()
    private val touchState=MutableStateFlow<GamepadSnapshot?>(null)
    fun touch(value: GamepadSnapshot?){touchState.value=value}
    @Volatile var gameInputBlocked=false
        private set
    fun blockGameInput(blocked: Boolean) {
        if(blocked==gameInputBlocked)return
        gameInputBlocked=blocked;reset(if(blocked) "Game Panel opened" else "Game Panel resumed")
    }
    fun runtimePads(): List<GamepadSnapshot> {
        val pads=touchState.value?.let {listOf(it)} ?: state.value.pads
        return if(gameInputBlocked) pads.map {GamepadSnapshot(it.slot,it.descriptor,it.connected,it.packet+1)} else pads
    }
    var testing=false
    private var active=false
    private var saved=emptyMap<String,ControllerMapping>()
    private var mappingOverride: ControllerMapping?=null
    private val keys=mutableMapOf<Int,MutableSet<Int>>()
    private val analog=mutableMapOf<Int,Map<Int,Float>>()
    private val mappingsKey=stringPreferencesKey("mappings")
    private val json=Json {ignoreUnknownKeys=true}
    init {scope.launch {context.controllerStore.data.catch {emit(emptyPreferences())}.collect {p ->
        saved=runCatching {json.decodeFromString<Map<String,ControllerMapping>>(p[mappingsKey] ?: "{}").also {it.values.forEach(ControllerMapping::validate)}}.getOrDefault(emptyMap())
        reset("Controller settings loaded")
    }}}
    fun configuration(descriptor: String)=mappingOverride ?: saved[descriptor] ?: ControllerMapping()
    fun saveConfiguration(descriptor: String,mapping: ControllerMapping,onResult: (Result<Unit>)->Unit = {}) {
        mapping.validate()
        scope.launch {try {
            context.controllerStore.edit {p ->val old=json.decodeFromString<Map<String,ControllerMapping>>(p[mappingsKey] ?: "{}");p[mappingsKey]=json.encodeToString(old+(descriptor to mapping))}
            onResult(Result.success(Unit))
        } catch(e: CancellationException){throw e} catch(e: Exception){onResult(Result.failure(e))}}
    }
    fun useGameMapping(mapping: ControllerMapping?) {mapping?.validate();mappingOverride=mapping;reset("Input profile changed")}
    // Some OEM virtual key injectors advertise GAMEPAD without a physical controller.
    private fun gamepad(d: InputDevice)=!d.isVirtual &&
        (d.isExternal || d.supportsSource(InputDevice.SOURCE_JOYSTICK)) &&
        (d.supportsSource(InputDevice.SOURCE_GAMEPAD)||d.supportsSource(InputDevice.SOURCE_JOYSTICK))
    private fun refresh() {
        val devices=manager.inputDeviceIds.toList().mapNotNull(manager::getInputDevice).filter(::gamepad).take(4)
        val previous=mutable.value
        val used=mutableSetOf<Int>()
        val infos=devices.map {d ->
            val old=previous.pads.firstOrNull {it.descriptor==d.descriptor}?.slot
            val slot=old?.takeIf {it !in used} ?: (0..3).first {it !in used};used.add(slot)
            ControllerInfo(d.id,d.name,d.descriptor,d.motionRanges.size,slot)
        }
        val live=infos.map {it.id}.toSet();keys.keys.retainAll(live);analog.keys.retainAll(live)
        val pads=(0..3).map {slot ->
            val info=infos.firstOrNull {it.slot==slot};val old=previous.pads.firstOrNull {it.slot==slot}
            if(info==null)GamepadSnapshot(slot,old?.descriptor ?: "",packet=(old?.packet ?: 0)+1)
            else if(previous.devices.any {it.id==info.id && it.descriptor==info.descriptor})old?.copy(connected=true) ?: GamepadSnapshot(slot,info.descriptor,true)
            else GamepadSnapshot(slot,info.descriptor,true,(old?.packet ?: 0)+1)
        }
        mutable.value=previous.copy(devices=infos,pads=pads,lastEvent="Input devices: ${infos.size}")
    }
    fun start(){if(!active){active=true;manager.registerInputDeviceListener(this,Handler(Looper.getMainLooper()));refresh()}}
    fun stop(){stopVibration();if(active){manager.unregisterInputDeviceListener(this);active=false};reset("Input suspended",disconnect=true)}
    fun canVibrate(id: Int)=manager.getInputDevice(id)?.vibrator?.hasVibrator()==true
    fun vibrate(id: Int) {manager.getInputDevice(id)?.vibrator?.takeIf {it.hasVibrator()}?.vibrate(android.os.VibrationEffect.createOneShot(150,android.os.VibrationEffect.DEFAULT_AMPLITUDE))}
    fun stopVibration(){mutable.value.devices.forEach {runCatching {manager.getInputDevice(it.id)?.vibrator?.cancel()}}}
    fun reset(reason: String,disconnect: Boolean=false) {
        touchState.value=touchState.value?.let {GamepadSnapshot(0,it.descriptor,!disconnect,it.packet+1)}
        keys.clear();analog.clear();mutable.value=mutable.value.copy(axes=emptyMap(),lastEvent=reason,pads=mutable.value.pads.map {GamepadSnapshot(it.slot,it.descriptor,!disconnect&&it.connected,it.packet+1)})
    }
    override fun onInputDeviceAdded(id: Int)=refresh()
    override fun onInputDeviceRemoved(id: Int){keys.remove(id);analog.remove(id);refresh()}
    override fun onInputDeviceChanged(id: Int){keys.remove(id);analog.remove(id);refresh();reset("Input ranges changed")}
    fun key(event: KeyEvent) {
        val device=event.device ?: return;if(!gamepad(device)||event.action !in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))return
        val held=keys.getOrPut(event.deviceId){mutableSetOf()}
        if(event.action==KeyEvent.ACTION_DOWN)held.add(event.keyCode) else held.remove(event.keyCode)
        publish(device,"${KeyEvent.keyCodeToString(event.keyCode)} ${if(event.action==0)"DOWN" else "UP"}")
    }
    fun motion(event: MotionEvent) {
        val device=event.device ?: return;if(!gamepad(device)||event.actionMasked!=MotionEvent.ACTION_MOVE)return
        analog[event.deviceId]=device.motionRanges.associate {it.axis to event.getAxisValue(it.axis)}
        publish(device,"Analog input")
    }
    private fun publish(device: InputDevice,label: String) {
        if(mutable.value.devices.none {it.id==device.id})refresh()
        val info=mutable.value.devices.firstOrNull {it.id==device.id} ?: return
        val config=configuration(device.descriptor);val values=analog[device.id].orEmpty();val held=keys[device.id].orEmpty()
        fun axis(vararg ids: Int): Pair<Float,InputDevice.MotionRange>?=ids.toList().firstNotNullOfOrNull {id ->device.getMotionRange(id)?.let {(values[id] ?: if(it.min<0 && it.max>0)0f else (it.min+it.max)/2) to it}}
        fun stick(id: Int)=axis(id)?.let {stickAxis(it.first,it.second.min,it.second.max)} ?: 0f
        val right=when(config.rightAxes) {
            "rx-ry"->MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
            "z-rz"->MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
            else->if(device.getMotionRange(MotionEvent.AXIS_RX)!=null && device.getMotionRange(MotionEvent.AXIS_RY)!=null)MotionEvent.AXIS_RX to MotionEvent.AXIS_RY else MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        }
        val l=radialDeadZone(stick(MotionEvent.AXIS_X),stick(MotionEvent.AXIS_Y),config.leftDeadZone)
        val r=radialDeadZone(stick(right.first),stick(right.second),config.rightDeadZone)
        fun trigger(a: Int,b: Int,key: Int): Float {if(key in held)return 1f;val range=device.getMotionRange(a) ?: device.getMotionRange(b) ?: return 0f;return triggerAxis(values[range.axis] ?: range.min,range.min,range.max,config.triggerDeadZone)}
        val defaults=mapOf(19 to 1,20 to 2,21 to 4,22 to 8,108 to 16,109 to 32,106 to 64,107 to 128,102 to 256,103 to 512,96 to 4096,97 to 8192,99 to 16384,100 to 32768)
        val mapping=defaults+config.buttons;var bits=held.fold(0){acc,k->acc or (mapping[k] ?: 0)}
        val hx=values[MotionEvent.AXIS_HAT_X] ?: 0f;val hy=values[MotionEvent.AXIS_HAT_Y] ?: 0f
        if(hx<-.5f)bits=bits or 4;if(hx>.5f)bits=bits or 8;if(hy<-.5f)bits=bits or 1;if(hy>.5f)bits=bits or 2
        val old=mutable.value.pads.first {it.slot==info.slot}
        val next=GamepadSnapshot(info.slot,info.descriptor,true,old.packet,bits,l.first,if(config.invertLeftY)-l.second else l.second,r.first,if(config.invertRightY)-r.second else r.second,trigger(17,23,104),trigger(18,22,105))
        val changed=if(next!=old)next.copy(packet=old.packet+1) else next
        mutable.value=mutable.value.copy(lastEvent="${device.name}: $label",axes=values.mapKeys {MotionEvent.axisToString(it.key)},pads=mutable.value.pads.map {if(it.slot==info.slot)changed else it},events=mutable.value.events+1)
    }
}
