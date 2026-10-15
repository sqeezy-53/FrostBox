// SPDX-License-Identifier: MIT
// Real Windows XInput polling fixture; all input must originate from a human controller.
typedef unsigned long DWORD;
typedef unsigned short WORD;
typedef unsigned char BYTE;
typedef void *HANDLE;
__declspec(dllimport) HANDLE GetStdHandle(DWORD);
__declspec(dllimport) int WriteFile(HANDLE,const void*,DWORD,DWORD*,void*);
__declspec(dllimport) HANDLE LoadLibraryA(const char*);
__declspec(dllimport) void *GetProcAddress(HANDLE,const char*);
__declspec(dllimport) void Sleep(DWORD);
__declspec(dllimport) unsigned long long GetTickCount64(void);
__declspec(dllimport) __declspec(noreturn) void ExitProcess(unsigned);
typedef struct {WORD buttons;BYTE lt,rt;short lx,ly,rx,ry;} PAD;
typedef struct {DWORD packet;PAD pad;} STATE;
static void text(const char *s){DWORD n=0,w;while(s[n])n++;WriteFile(GetStdHandle((DWORD)-11),s,n,&w,0);}
static void number(long n){char b[16];unsigned c=0;DWORD w;if(n<0){text("-");n=-n;}do{b[c++]='0'+n%10;n/=10;}while(n);while(c)WriteFile(GetStdHandle((DWORD)-11),&b[--c],1,&w,0);}
void mainCRTStartup(void) {
    HANDLE dll=LoadLibraryA("xinput1_3.dll");
    DWORD (*get)(DWORD,STATE*)=dll?(void*)GetProcAddress(dll,"XInputGetState"):0;
    if(!get){text("FROSTBOX_XINPUT_LOAD_FAILED\n");ExitProcess(3);}
    text("FROSTBOX_XINPUT_DLL_LOADED=1\n");
    HANDLE audio=LoadLibraryA("winmm.dll");
    unsigned (*waveCount)(void)=audio?(void*)GetProcAddress(audio,"waveOutGetNumDevs"):0;
    text("FROSTBOX_WAVEOUT_DEVICES=");number(waveCount?(long)waveCount():-1);text("\n");
    unsigned polls=0,changes=0,seen=0;unsigned lx=0,ly=0,rx=0,ry=0,lt=0,rt=0,stage=0,printed=0;DWORD last=~0UL;int connected=0;STATE s;
    for(unsigned i=0;i<30000;i++){
        DWORD err=get(0,&s);polls++;
        if(!err){
            if(!connected){text("FROSTBOX_XINPUT_CONNECTED=1\n");connected=1;}
            if(s.packet!=last){last=s.packet;changes++;seen|=s.pad.buttons;
                if(printed++<160){text("PAD ");number(s.packet);text(" ");number(s.pad.buttons);text(" ");number(s.pad.lx);text(" ");number(s.pad.ly);text(" ");number(s.pad.rx);text(" ");number(s.pad.ry);text(" ");number(s.pad.lt);text(" ");number(s.pad.rt);text("\n");}
            }
            #define MAXABS(dst,v) do {unsigned a=(v)<0?-(v):(v);if(a>dst)dst=a;}while(0)
            MAXABS(lx,s.pad.lx);MAXABS(ly,s.pad.ly);MAXABS(rx,s.pad.rx);MAXABS(ry,s.pad.ry);MAXABS(lt,s.pad.lt);MAXABS(rt,s.pad.rt);
            if(!stage && (seen&0xf00f)==0xf00f && lx>20000 && ly>20000 && rx>20000 && ry>20000 && lt>180 && rt>180){
                stage=1;text("FROSTBOX_XINPUT_CONTROLS_PASSED=1\nFROSTBOX_XINPUT_WAIT_DISCONNECT=1\n");
                struct {BYTE type,subtype;WORD flags;PAD gamepad;WORD left,right;} caps;
                DWORD (*cap)(DWORD,DWORD,void*)=(void*)GetProcAddress(dll,"XInputGetCapabilities");
                DWORD errcap=cap?cap(0,0,&caps):999;
                text("FROSTBOX_XINPUT_CAPABILITIES=");number(errcap);text("\n");if(errcap)ExitProcess(4);
                const char *names[]={"xinput1_4.dll","xinput9_1_0.dll"};
                for(unsigned d=0;d<2;d++) {HANDLE other=LoadLibraryA(names[d]);DWORD(*f)(DWORD,STATE*)=other?(void*)GetProcAddress(other,"XInputGetState"):0;DWORD e=f?f(0,&s):999;text(names[d]);text(" STATE=");number(e);text("\n");if(e)ExitProcess(5);}
                unsigned long long start=GetTickCount64();for(unsigned k=0;k<1000;k++)if(get(0,&s))ExitProcess(6);
                text("FROSTBOX_XINPUT_1000_POLLS_MS=");number((long)(GetTickCount64()-start));text("\n");
            }
            if(stage==2){text("FROSTBOX_XINPUT_RECONNECTED=1\nFROSTBOX_XINPUT_RESULT=PASS\n");ExitProcess(42);}
        }else if(connected){text("FROSTBOX_XINPUT_DISCONNECTED=");number(err);text("\n");connected=0;if(stage==1 && err==1167){stage=2;text("FROSTBOX_XINPUT_WAIT_RECONNECT=1\n");}}
        if(i%1000==0){text("FROSTBOX_XINPUT_ERROR=");number(err);text(" COVERAGE ");number(seen);text(" ");number(lx);text(" ");number(ly);text(" ");number(rx);text(" ");number(ry);text(" ");number(lt);text(" ");number(rt);text("\n");}
        Sleep(10);
    }
    text("FROSTBOX_XINPUT_TIMEOUT\n");ExitProcess(2);
}
