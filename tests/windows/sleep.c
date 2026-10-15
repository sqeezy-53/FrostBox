// SPDX-License-Identifier: MIT
// A real Windows process for cancellation and Android process-death tests.
typedef void* HANDLE;
typedef unsigned long DWORD;
__declspec(dllimport) HANDLE __stdcall GetStdHandle(DWORD);
__declspec(dllimport) int __stdcall WriteFile(HANDLE,const void*,DWORD,DWORD*,void*);
__declspec(dllimport) void __stdcall Sleep(DWORD);
__declspec(dllimport) __declspec(noreturn) void __stdcall ExitProcess(unsigned int);
void mainCRTStartup(void) {
    const char text[]="FROSTBOX_SLEEP_READY\n";
    DWORD written=0;
    if(!WriteFile(GetStdHandle((DWORD)-11),text,sizeof(text)-1,&written,0)) ExitProcess(2);
    Sleep(60000);
    ExitProcess(42);
}
