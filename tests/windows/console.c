// SPDX-License-Identifier: MIT
// Freestanding Win32 API fixture; no CRT, game files or third-party headers.
typedef void* HANDLE;
typedef unsigned long DWORD;
#if defined(_M_IX86)
#define WINAPI __stdcall
#else
#define WINAPI
#endif
__declspec(dllimport) HANDLE WINAPI GetStdHandle(DWORD);
__declspec(dllimport) int WINAPI WriteFile(HANDLE,const void*,DWORD,DWORD*,void*);
__declspec(dllimport) __declspec(noreturn) void WINAPI ExitProcess(unsigned int);
void mainCRTStartup(void) {
    volatile unsigned int sum=0;
    for(unsigned int i=1;i<=100;++i) sum+=i;
    char output[]="FROSTBOX_PE_RESULT=";
    char digits[16];unsigned int n=sum,count=0;
    do {digits[count++]=(char)('0'+n%10);n/=10;}while(n);
    HANDLE out=GetStdHandle((DWORD)-11);DWORD written=0;
    if(!WriteFile(out,output,sizeof(output)-1,&written,0)) ExitProcess(2);
    while(count) if(!WriteFile(out,&digits[--count],1,&written,0)) ExitProcess(3);
    if(!WriteFile(out,"\n",1,&written,0)) ExitProcess(4);
    ExitProcess(sum==5050?42:1);
}
