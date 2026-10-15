// SPDX-License-Identifier: MIT
// Real adjacent-DLL and working-directory fixture; no Wine or display mock.
__declspec(dllimport) unsigned int FrostSum(void);
__declspec(dllimport) void* GetStdHandle(unsigned long);
__declspec(dllimport) int WriteFile(void*,const void*,unsigned long,unsigned long*,void*);
__declspec(dllimport) unsigned long GetFileAttributesA(const char*);
__declspec(dllimport) __declspec(noreturn) void ExitProcess(unsigned int);
void mainCRTStartup(void) {
    if(GetFileAttributesA("data/resource.txt")==0xffffffffUL) ExitProcess(5);
    if(FrostSum()!=5050) ExitProcess(6);
    const char text[]="FROSTBOX_DLL_RESULT=5050\n";unsigned long written=0;
    if(!WriteFile(GetStdHandle((unsigned long)-11),text,sizeof(text)-1,&written,0)) ExitProcess(7);
    ExitProcess(42);
}
