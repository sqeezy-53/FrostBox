// SPDX-License-Identifier: MIT
// Real Wine/Win32 Raw Input probe. Synthetic selftest is separately labelled.
typedef void* H;typedef unsigned long D;typedef unsigned int U;typedef unsigned short W;typedef unsigned long long P;typedef long long L;typedef int B;
typedef struct {long x,y;} POINT;
typedef struct {H hwnd;U message;P wp;L lp;D time;POINT pt;D private_;} MSG;
typedef struct {U size,style;L(*proc)(H,U,P,L);int ce,we;H inst,icon,cursor,brush;const unsigned short*menu,*name;H small;} CLASS;
typedef struct {W page,usage;D flags;H target;} RID;
typedef struct {D type,size;H device;P wp;} RH;
typedef struct {W scan,flags,reserved,vkey;U message;D extra;} RK;
typedef struct {W flags;union {D buttons;struct{W buttonFlags,buttonData;};};D rawButtons;long dx,dy;D extra;} RM;
typedef struct {RH header;union {RK key;RM mouse;} data;} RAW;
typedef struct {long x,y;D data,flags,time;P extra;} MI;
typedef struct {W vk,scan;D flags,time;P extra;} KI;
typedef struct {D type;union {MI mouse;KI key;};} IN;
_Static_assert(sizeof(RID)==16,"RAWINPUTDEVICE ABI");_Static_assert(sizeof(RH)==24,"RAWINPUTHEADER ABI");
_Static_assert(sizeof(RK)==16,"RAWKEYBOARD ABI");_Static_assert(sizeof(RM)==24,"RAWMOUSE ABI");_Static_assert(sizeof(IN)==40,"INPUT ABI");
#define API __declspec(dllimport)
API W RegisterClassExW(const CLASS*);API H CreateWindowExW(D,const unsigned short*,const unsigned short*,D,int,int,int,int,H,H,H,void*);
API B ShowWindow(H,int);API B UpdateWindow(H);API L DefWindowProcW(H,U,P,L);API B DestroyWindow(H);API void PostQuitMessage(int);
API B GetMessageW(MSG*,H,U,U);API B TranslateMessage(const MSG*);API L DispatchMessageW(const MSG*);
API B RegisterRawInputDevices(const RID*,U,U);API U GetRawInputData(H,U,void*,U*,U);API U GetRawInputDeviceList(void*,U*,U);API U GetRawInputDeviceInfoW(H,U,void*,U*);
API H GetForegroundWindow(void);API H GetFocus(void);API H GetActiveWindow(void);API B SetForegroundWindow(H);API H SetFocus(H);
API short GetAsyncKeyState(int);API short GetKeyState(int);API B GetKeyboardState(unsigned char*);
API U SendInput(U,IN*,int);API U MapVirtualKeyW(U,U);API P SetTimer(H,P,U,void*);API B KillTimer(H,P);
API H GetStdHandle(D);API B WriteFile(H,const void*,D,D*,void*);API const char*GetCommandLineA(void);API D GetLastError(void);API void ExitProcess(U);
static H window;static U stage,selftest,rawkeys,rawmouse,legacy,zeroScan,validScan,asyncKeys,bytesErrors,deviceErrors;static long sumx,sumy;static unsigned char state[256];
static void text(const char*s){D n=0,w;while(s[n])n++;WriteFile(GetStdHandle((D)-11),s,n,&w,0);}
static void num(L n){char b[32];U c=0;D w;if(n<0){text("-");n=-n;}do{b[c++]='0'+n%10;n/=10;}while(n);while(c)WriteFile(GetStdHandle((D)-11),&b[--c],1,&w,0);}
static void line(const char*s,L n){text(s);num(n);text("\n");}
static void focus(void){text("FOCUS hwnd=");num((L)window);text(" fg=");num((L)GetForegroundWindow());text(" focus=");num((L)GetFocus());text(" active=");num((L)GetActiveWindow());text("\n");}
static void inject(U scan,B up){IN i={0};i.type=1;i.key.vk='W';i.key.scan=scan;i.key.flags=up?2:0;line("SYNTH_SEND_KEY=",SendInput(1,&i,sizeof(i)));}
static L proc(H h,U m,P wp,L lp){
 if(m==0x6||m==0x7||m==0x8||m==0x1c){line("ACTIVATION_MESSAGE=",m);focus();}
 if(m==0xff){
  U size=0;RAW r={0};U n=GetRawInputData((H)lp,0x10000003,0,&size,sizeof(RH));
  if(n!=0||size>sizeof(r)||size<sizeof(RH)){bytesErrors++;line("RAW_SIZE_ERROR=",size);return 0;}
  U got=GetRawInputData((H)lp,0x10000003,&r,&size,sizeof(RH));
  if(got!=size||r.header.size!=size){bytesErrors++;line("RAW_READ_ERROR=",got);return 0;}
  text("WM_INPUT type=");num(r.header.type);text(" size=");num(size);text(" device=");num((L)r.header.device);text("\n");
  if(r.header.type==1){rawkeys++;if(r.data.key.scan)validScan++;else zeroScan++;text("RAW_KEY vk=");num(r.data.key.vkey);text(" scan=");num(r.data.key.scan);text(" flags=");num(r.data.key.flags);text("\n");}
  if(r.header.type==0){rawmouse++;sumx+=r.data.mouse.dx;sumy+=r.data.mouse.dy;text("RAW_MOUSE dx=");num(r.data.mouse.dx);text(" dy=");num(r.data.mouse.dy);text(" buttons=");num(r.data.mouse.buttonFlags);text("\n");}
  U infoSize=0;U info=GetRawInputDeviceInfoW(r.header.device,0x20000007,0,&infoSize);if(info==(U)-1)deviceErrors++;line("RAW_DEVICE_INFO_QUERY=",info);line("RAW_DEVICE_INFO_BYTES=",infoSize);
  return DefWindowProcW(h,m,wp,lp);
 }
 if(m==0x100||m==0x101){legacy++;GetKeyboardState(state);text("KEY_MESSAGE vk=");num(wp);text(" scan=");num((lp>>16)&255);text(" async=");num(!!(GetAsyncKeyState(wp)&0x8000));text(" state=");num(!!(GetKeyState(wp)&0x8000));text("\n");if(GetAsyncKeyState(wp)&0x8000)asyncKeys++;}
 if(m>=0x200&&m<=0x205){line("MOUSE_MESSAGE=",m);}
 if(m==0x113&&selftest){
  switch(stage++){
   case 0:inject(0,0);break;case 1:inject(0,1);break;
   case 2:inject(MapVirtualKeyW('W',4)&255,0);break;case 3:inject(MapVirtualKeyW('W',4)&255,1);break;
   case 4:{IN i={0};i.mouse.x=37;i.mouse.y=-19;i.mouse.flags=1;line("SYNTH_SEND_MOUSE=",SendInput(1,&i,sizeof(i)));break;}
   case 5:{IN i={0};i.mouse.flags=2;SendInput(1,&i,sizeof(i));break;}
   case 6:{IN i={0};i.mouse.flags=4;SendInput(1,&i,sizeof(i));break;}
   case 9:KillTimer(h,1);DestroyWindow(h);break;
  }
 }
 if(m==0x10){DestroyWindow(h);return 0;}
 if(m==2){line("RAW_KEY_COUNT=",rawkeys);line("RAW_ZERO_SCAN_COUNT=",zeroScan);line("RAW_VALID_SCAN_COUNT=",validScan);line("RAW_MOUSE_COUNT=",rawmouse);line("RAW_DX_SUM=",sumx);line("RAW_DY_SUM=",sumy);line("ASYNC_DOWN_COUNT=",asyncKeys);line("RAW_BUFFER_ERRORS=",bytesErrors);line("RAW_DEVICE_INFO_ERRORS=",deviceErrors);PostQuitMessage(selftest?(rawkeys>=4&&zeroScan>=2&&validScan>=2&&rawmouse>0&&!bytesErrors?42:1):42);return 0;}
 return DefWindowProcW(h,m,wp,lp);
}
void mainCRTStartup(void){
 const char*cmd=GetCommandLineA();for(U i=0;cmd[i];i++)if(cmd[i]=='s'&&cmd[i+1]=='e'&&cmd[i+2]=='l'&&cmd[i+3]=='f')selftest=1;
 line("SYNTHETIC_SELFTEST=",selftest);CLASS c={sizeof(c),3,proc,0,0,0,0,0,0,0,u"FrostBox Raw Probe",0};
 if(!RegisterClassExW(&c))ExitProcess(2);
 window=CreateWindowExW(0,c.name,c.name,0x10cf0000,80,80,640,400,0,0,0,0);if(!window)ExitProcess(3);
 ShowWindow(window,5);UpdateWindow(window);SetForegroundWindow(window);SetFocus(window);focus();
 RID devices[2]={{1,6,0,window},{1,2,0,window}};B registered=RegisterRawInputDevices(devices,2,sizeof(RID));line("RAW_REGISTER=",registered);line("RAW_REGISTER_ERROR=",registered?0:GetLastError());
 U count=0;line("RAW_DEVICE_LIST_QUERY=",GetRawInputDeviceList(0,&count,16));line("RAW_DEVICE_COUNT=",count);
 text("FROSTBOX_RAW_READY\n");if(selftest)SetTimer(window,1,120,0);
 MSG m;while(GetMessageW(&m,0,0,0)>0){TranslateMessage(&m);DispatchMessageW(&m);}ExitProcess((U)m.wp);
}
