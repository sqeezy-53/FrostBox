"""Unit-only Wine Unix contract/PCM transport test. Collector is not an audio device."""
import ctypes as C,os,socket,struct,threading,uuid,unittest
MAGIC=0x31414246
class Format(C.Structure):
 _pack_=2
 _fields_=[('tag',C.c_ushort),('channels',C.c_ushort),('rate',C.c_uint),('avg',C.c_uint),('align',C.c_ushort),('bits',C.c_ushort),('extra',C.c_ushort)]
class Reply(C.Structure):_fields_=[('flow',C.c_int),('endpoints',C.c_void_p),('size',C.c_uint),('result',C.c_int),('num',C.c_uint),('default',C.c_uint)]
class Create(C.Structure):_fields_=[('name',C.c_void_p),('device',C.c_char_p),('flow',C.c_int),('share',C.c_int),('flags',C.c_uint),('duration',C.c_longlong),('period',C.c_longlong),('fmt',C.POINTER(Format)),('result',C.c_int),('channels',C.POINTER(C.c_uint)),('stream',C.POINTER(C.c_ulonglong))]
class Control(C.Structure):_fields_=[('stream',C.c_ulonglong),('result',C.c_int)]
class Buffer(C.Structure):_fields_=[('stream',C.c_ulonglong),('frames',C.c_uint),('result',C.c_int),('data',C.POINTER(C.c_void_p))]
class Release(C.Structure):_fields_=[('stream',C.c_ulonglong),('frames',C.c_uint),('flags',C.c_uint),('result',C.c_int)]
class AudioTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.lib=C.CDLL(os.environ['FROST_AUDIO_UNIT_LIBRARY']);cls.table=(C.c_void_p*37).in_dll(cls.lib,'__wine_unix_call_funcs');cls.calltype=C.CFUNCTYPE(C.c_int,C.c_void_p)
 def call(self,index,p):return self.calltype(self.table[index])(C.byref(p))
 def test_optional_midi_discovery_returns_dispatch_success(self):
  name=(C.c_ushort*64)(*([0xffff]*64));self.assertEqual(0,self.call(30,name));self.assertEqual(0,name[0])
  err=C.c_uint(0xdeadbeef);ptr=C.pointer(err)
  self.assertEqual(0,self.call(31,ptr));self.assertEqual(1,err.value)
 def test_absent_bridge_does_not_advertise_endpoint(self):
  os.environ['WINEPREFIX']=f'/tmp/{uuid.uuid4()}/prefix';buf=C.create_string_buffer(1024);p=Reply(0,C.cast(buf,C.c_void_p),1024,0,99,0);self.call(4,p);self.assertEqual(0,p.num);self.assertLess(p.result,0)
 def test_render_lifetime_capacity_and_float_conversion(self):
  key=str(uuid.uuid4());os.environ['WINEPREFIX']=f'/tmp/{key}/prefix';server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);server.bind('\0frost-audio-'+str(os.getuid())+'-'+key);server.listen(4);data=bytearray();errors=[]
  def exact(c,n):
   result=b''
   while len(result)<n:
    part=c.recv(n-len(result))
    if not part:raise EOFError
    result+=part
   return result
  def serve():
   try:
    # Endpoint probe then real stream connection. Both are explicitly unit mocks.
    for _ in range(2):
     c,_=server.accept();received=0
     try:
      while True:
       magic,op,frames,n=struct.unpack('<4I',exact(c,16));assert magic==MAGIC
       if n:payload=exact(c,n);data.extend(payload);received+=frames
       if op==5:received=0
       c.sendall(struct.pack('<8I',MAGIC,0,0,received,0,received,0,4800))
     except EOFError:pass
     finally:c.close()
   except Exception as e:errors.append(e)
  t=threading.Thread(target=serve,daemon=True);t.start()
  try:
   endpoints=C.create_string_buffer(1024);ep=Reply(0,C.cast(endpoints,C.c_void_p),1024,0,0,0);self.call(4,ep);self.assertEqual((0,1),(ep.result,ep.num))
   fmt=Format(3,2,48000,384000,8,32,0);ch=C.c_uint();handle=C.c_ulonglong();p=Create(None,b'android-pcm',0,0,0,1000000,0,C.pointer(fmt),0,C.pointer(ch),C.pointer(handle));self.call(5,p);self.assertEqual(0,p.result);self.assertEqual(2,ch.value)
   ctl=Control(handle.value,0);self.call(7,ctl);self.assertEqual(0,ctl.result)
   ptr=C.c_void_p();g=Buffer(handle.value,4801,0,C.pointer(ptr));self.call(10,g);self.assertLess(g.result,0)
   g.frames=480;self.call(10,g);self.assertEqual(0,g.result)
   floats=(C.c_float*960).from_address(ptr.value)
   for i in range(960):floats[i]=.125 if i%2==0 else -.125
   rel=Release(handle.value,480,0,0);self.call(11,rel);self.assertEqual(0,rel.result)
   self.call(8,ctl);self.assertEqual(0,ctl.result);self.call(9,ctl);self.assertEqual(0,ctl.result);self.call(6,ctl);self.assertEqual(0,ctl.result)
   self.call(7,ctl);self.assertLess(ctl.result,0) # invalid released handle is rejected
   t.join(3);self.assertFalse(t.is_alive());self.assertFalse(errors);self.assertEqual(480*4,len(data));self.assertEqual((4096,-4096),struct.unpack('<hh',data[:4]))
  finally:server.close()
if __name__=='__main__':unittest.main()
