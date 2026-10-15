// SPDX-License-Identifier: MIT
// Polling WASAPI PCM fixture; no capture, microphones or assumption of audible playback.
typedef unsigned long D;typedef int HR;typedef unsigned int U;typedef unsigned short W;typedef long long T;typedef void*H;
typedef struct{D a;W b,c;unsigned char d[8];}GUID;
typedef struct{void**v;}OBJ;
#pragma pack(push,2)
typedef struct{W tag,ch;D rate,avg;W align,bits,extra;}FMT;
#pragma pack(pop)
_Static_assert(sizeof(FMT)==18,"WAVEFORMATEX ABI");
__declspec(dllimport) HR CoInitializeEx(H,D);__declspec(dllimport) HR CoCreateInstance(const GUID*,H,D,const GUID*,H*);__declspec(dllimport) void CoUninitialize(void);
__declspec(dllimport) H GetStdHandle(D);__declspec(dllimport) int WriteFile(H,const void*,D,D*,H);__declspec(dllimport) void Sleep(D);__declspec(dllimport) void ExitProcess(U);
static const GUID cls={0xbcde0395,0xe52f,0x467c,{0x8e,0x3d,0xc4,0x57,0x92,0x91,0x69,0x2e}};
static const GUID en={0xa95664d2,0x9614,0x4f35,{0xa7,0x46,0xde,0x8d,0xb6,0x36,0x17,0xe6}};
static const GUID ac={0x1cb9ad4c,0xdbfa,0x4c32,{0xb1,0x78,0xc2,0xf5,0x68,0xa7,0x03,0xb2}};
static const GUID rc={0xf294acfc,0x3146,0x4483,{0xa7,0xbf,0xad,0xdc,0xa7,0xc2,0x60,0xe2}};
static void text(const char*s){D n=0,w;while(s[n])n++;WriteFile(GetStdHandle((D)-11),s,n,&w,0);}
static void num(const char*s,long n){char b[20];U i=0;D w;text(s);if(n<0){text("-");n=-n;}do{b[i++]='0'+n%10;n/=10;}while(n);while(i)WriteFile(GetStdHandle((D)-11),&b[--i],1,&w,0);text("\n");}
static void check(HR h,U code){if(h<0){num("AUDIO_HRESULT=",h);ExitProcess(code);}}
static void drop(OBJ*o){if(o)((U(*)(OBJ*))o->v[2])(o);}
void mainCRTStartup(void){
 OBJ *e=0,*device=0,*client=0,*render=0;FMT fmt={1,2,48000,192000,4,16,0};U size=0,total=0;
 check(CoInitializeEx(0,0),2);check(CoCreateInstance(&cls,0,1,&en,(H*)&e),3);
 HR h=((HR(*)(OBJ*,int,int,OBJ**))e->v[4])(e,0,0,&device);
 if(h<0){num("AUDIO_ENDPOINT_HRESULT=",h);text("AUDIO_NO_ENDPOINT\n");drop(e);CoUninitialize();ExitProcess(77);}
 text("AUDIO_ENDPOINT_PRESENT\n");check(((HR(*)(OBJ*,const GUID*,D,H,OBJ**))device->v[3])(device,&ac,1,0,&client),4);
 check(((HR(*)(OBJ*,int,D,T,T,const FMT*,const GUID*))client->v[3])(client,0,0,1000000,0,&fmt,0),5);
 check(((HR(*)(OBJ*,U*))client->v[4])(client,&size),6);num("AUDIO_BUFFER_FRAMES=",size);if(size<480||size>4800)ExitProcess(7);
 check(((HR(*)(OBJ*,const GUID*,OBJ**))client->v[14])(client,&rc,&render),8);
 for(U round=0;round<2;round++){
  check(((HR(*)(OBJ*))client->v[10])(client),9);
  U written=0,tries=0;
  while(written<4800 && tries++<500){U padding=0;check(((HR(*)(OBJ*,U*))client->v[6])(client,&padding),10);
   if(size-padding<480){Sleep(5);continue;}
   unsigned char *bytes=0;check(((HR(*)(OBJ*,U,unsigned char**))render->v[3])(render,480,&bytes),11);
   for(U frame=0;frame<480;frame++){short value=((written+frame)%100<50)?4096:-4096;((short*)bytes)[frame*2]=((short*)bytes)[frame*2+1]=value;}
   check(((HR(*)(OBJ*,U,D))render->v[4])(render,480,0),12);written+=480;total+=480;
  }
  if(written!=4800)ExitProcess(13);
  U padding=1;tries=0;while(padding&&tries++<500){Sleep(5);check(((HR(*)(OBJ*,U*))client->v[6])(client,&padding),14);}
  if(padding)ExitProcess(15);
  check(((HR(*)(OBJ*))client->v[11])(client),16);check(((HR(*)(OBJ*))client->v[12])(client),17);
 }
 num("AUDIO_PCM_FRAMES=",total);text("AUDIO_START_STOP_ROUNDS=2\nAUDIO_SOFTWARE_PASS_AUDIBILITY_UNCONFIRMED\n");
 drop(render);drop(client);drop(device);drop(e);CoUninitialize();ExitProcess(42);
}
