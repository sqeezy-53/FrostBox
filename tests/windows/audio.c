// SPDX-License-Identifier: MIT
// Real WinMM PCM output when a Wine audio endpoint exists; exit 77 means unavailable.
typedef unsigned long DWORD;typedef unsigned short WORD;typedef unsigned long long UPTR;typedef void* HANDLE;
__declspec(dllimport) HANDLE GetStdHandle(DWORD);__declspec(dllimport) int WriteFile(HANDLE,const void*,DWORD,DWORD*,void*);
__declspec(dllimport) HANDLE LoadLibraryA(const char*);__declspec(dllimport) void* GetProcAddress(HANDLE,const char*);
__declspec(dllimport) int FreeLibrary(HANDLE);
__declspec(dllimport) void Sleep(DWORD);__declspec(dllimport) __declspec(noreturn) void ExitProcess(unsigned);
struct FORMAT {WORD tag,channels;DWORD rate,bytes;WORD align,bits,extra;};
struct HEADER {char *data;DWORD length,recorded;UPTR user;DWORD flags,loops;struct HEADER *next;UPTR reserved;};
static short pcm[22050];static struct HEADER header;static struct FORMAT format={1,1,22050,44100,2,16,0};
static void text(const char*s){DWORD n=0,w;while(s[n])n++;WriteFile(GetStdHandle((DWORD)-11),s,n,&w,0);}
void mainCRTStartup(void){
 HANDLE dll=LoadLibraryA("winmm.dll"),out=0;
 unsigned (*count)(void)=dll?(void*)GetProcAddress(dll,"waveOutGetNumDevs"):0;
 unsigned (*open)(HANDLE*,unsigned,void*,UPTR,UPTR,DWORD)=dll?(void*)GetProcAddress(dll,"waveOutOpen"):0;
 unsigned (*prepare)(HANDLE,void*,unsigned)=dll?(void*)GetProcAddress(dll,"waveOutPrepareHeader"):0;
 unsigned (*write)(HANDLE,void*,unsigned)=dll?(void*)GetProcAddress(dll,"waveOutWrite"):0;
 unsigned (*reset)(HANDLE)=dll?(void*)GetProcAddress(dll,"waveOutReset"):0;
 unsigned (*unprepare)(HANDLE,void*,unsigned)=dll?(void*)GetProcAddress(dll,"waveOutUnprepareHeader"):0;
 unsigned (*close)(HANDLE)=dll?(void*)GetProcAddress(dll,"waveOutClose"):0;
 if(!count||!open||!prepare||!write||!reset||!unprepare||!close){text("AUDIO_API_MISSING\n");ExitProcess(3);}
 if(!count()){text("AUDIO_NO_ENDPOINT\n");FreeLibrary(dll);ExitProcess(77);}
 for(unsigned i=0;i<22050;i++)pcm[i]=(i%50<25)?2000:-2000;
 for(unsigned round=0;round<2;round++){
  if(open(&out,~0U,&format,0,0,0)){text("AUDIO_OPEN_FAILED\n");ExitProcess(4);}
  header.data=(char*)pcm;header.length=sizeof(pcm);header.flags=0;
  if(prepare(out,&header,sizeof(header))){close(out);ExitProcess(5);}
  if(write(out,&header,sizeof(header))){unprepare(out,&header,sizeof(header));close(out);ExitProcess(6);}
  text("AUDIO_PCM_SUBMITTED=44100\n");unsigned t=0;while(!(header.flags&1) && t++<300)Sleep(10);
  reset(out);unprepare(out,&header,sizeof(header));close(out);
  if(t>=300){text("AUDIO_COMPLETION_TIMEOUT\n");ExitProcess(7);}text("AUDIO_BUFFER_DONE\n");
 }
 text("AUDIO_SOFTWARE_PASS_AUDIBILITY_UNCONFIRMED\n");ExitProcess(42);
}
