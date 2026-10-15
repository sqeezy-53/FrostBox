// SPDX-License-Identifier: MIT
#include "win32-minimal.h"
static HWND root,edit,multi,check,count_label;
static WNDPROC old_edit,old_multi;
static unsigned count;
static void log_text(const char *s) { DWORD n,len=0;while(s[len])len++;WriteFile(GetStdHandle((DWORD)-11),s,len,&n,0); }
static void log_value(const char *prefix,LPCWSTR text) {
    char bytes[2048];unsigned n=0;
    while(*prefix && n<2000)bytes[n++]=*prefix++;
    for(unsigned i=0;text[i] && n<2000;i++) {
        unsigned c=text[i];
        if(c>=0xd800 && c<=0xdbff && text[i+1]>=0xdc00 && text[i+1]<=0xdfff)c=0x10000+((c-0xd800)<<10)+(text[++i]-0xdc00);
        if(c<128)bytes[n++]=c;
        else if(c<2048){bytes[n++]=0xc0|(c>>6);bytes[n++]=0x80|(c&63);}
        else if(c<65536){bytes[n++]=0xe0|(c>>12);bytes[n++]=0x80|((c>>6)&63);bytes[n++]=0x80|(c&63);}
        else {bytes[n++]=0xf0|(c>>18);bytes[n++]=0x80|((c>>12)&63);bytes[n++]=0x80|((c>>6)&63);bytes[n++]=0x80|(c&63);}
    }
    bytes[n++]='\n';DWORD written;WriteFile(GetStdHandle((DWORD)-11),bytes,n,&written,0);
}
static void number(WCHAR *text,unsigned *n,unsigned v) { WCHAR digits[12];unsigned d=0;do {digits[d++]=u'0'+v%10;v/=10;}while(v);while(d)text[(*n)++]=digits[--d]; }
static void state(const char *prefix,HWND h,UINT message,WPARAM unit) {
    GUITHREADINFO gi={.cbSize=sizeof(gi)};DWORD tid=GetCurrentThreadId();GetGUIThreadInfo(tid,&gi);
    WCHAR text[220];unsigned n=0;
    unsigned values[]={message,(unsigned)unit,(unsigned)(unsigned long long)h,
        (unsigned)(unsigned long long)root,(unsigned)(unsigned long long)edit,(unsigned)(unsigned long long)multi,
        (unsigned)(unsigned long long)GetFocus(),(unsigned)(unsigned long long)GetForegroundWindow(),
        tid,GetWindowThreadProcessId(h,0),(unsigned)(unsigned long long)gi.active,(unsigned)(unsigned long long)gi.focus};
    for(unsigned i=0;i<12;i++){if(i)text[n++]=u',';number(text,&n,values[i]);}text[n]=0;log_value(prefix,text);
}
static LRESULT edit_proc(HWND h,UINT m,WPARAM w,LPARAM l) {
    if(m==7){log_text("LAB_EDIT_FOCUS\n");state("LAB_FOCUS_STATE=",h,m,w);}
    if(m==8)state("LAB_KILLFOCUS_STATE=",h,m,w);
    if(m==0x100)log_text("LAB_EDIT_KEY\n");
    if(m==0x102){log_text("LAB_EDIT_CHAR\n");state("LAB_CHAR_STATE=",h,m,w);}

    if(m==0x100 && w==27){PostMessageW(root,0x10,0,0);return 0;}
    if(m==0x100 && w==13)log_text("LAB_ENTER\n");
    return CallWindowProcW(h==edit?old_edit:old_multi,h,m,w,l);
}
static HWND control(HWND p,LPCWSTR cls,LPCWSTR text,DWORD style,int x,int y,int w,int h,int id) {
    return CreateWindowExW(cls[0]=='E'?0x200:0,cls,text,0x50000000|style,x,y,w,h,p,(HANDLE)(long long)id,GetModuleHandleW(0),0);
}
static LRESULT proc(HWND h,UINT m,WPARAM w,LPARAM l) {
    if(m==0x100)log_text("LAB_ROOT_KEY\n");
    if(m==0x102){log_text("LAB_ROOT_CHAR\n");state("LAB_ROOT_CHAR_STATE=",h,m,w);}
    if(m==1) {
        root=h;
        control(h,u"STATIC",u"Hello Windows",0,20,10,300,24,10);
        control(h,u"STATIC",u"Привет из FrostBox — Windows на Android",0,20,36,590,24,11);
        control(h,u"STATIC",u"Тест Unicode: ё Ё № €",0,20,62,570,24,12);
        control(h,u"BUTTON",u"Count",0,20,100,110,32,1);
        count_label=control(h,u"STATIC",u"Count: 0",0,145,104,170,24,13);
        check=control(h,u"BUTTON",u"Checkbox",3,340,100,180,32,2);
        edit=control(h,u"EDIT",u"",0x10000|0x80,20,148,440,32,3);
        multi=control(h,u"EDIT",u"",0x10000|0x4|0x40|0x1000|0x200000,20,198,440,105,4);
        control(h,u"BUTTON",u"Clear",0,480,148,110,32,5);
        HWND list=control(h,u"LISTBOX",u"",0x800000|1,480,198,120,105,6);
        SendMessageW(list,0x180,0,(LPARAM)u"One");SendMessageW(list,0x180,0,(LPARAM)u"Два");
        old_edit=(WNDPROC)SetWindowLongPtrW(edit,-4,(long long)edit_proc);
        old_multi=(WNDPROC)SetWindowLongPtrW(multi,-4,(long long)edit_proc);
        SetFocus(edit);return 0;
    }
    if(m==0x111) {
        unsigned id=w&65535,notification=(w>>16)&65535;
        if(id==1 && !notification) {
            WCHAR text[24]=u"Count: ";unsigned v=++count,n=7;WCHAR digits[12];unsigned d=0;
            do {digits[d++]=u'0'+v%10;v/=10;}while(v);while(d)text[n++]=digits[--d];text[n]=0;
            SetWindowTextW(count_label,text);log_value("LAB_COUNT=",text);
        }
        if(id==2 && !notification)log_text(SendMessageW(check,0xf0,0,0)?"LAB_CHECK=1\n":"LAB_CHECK=0\n");
        if((id==3 || id==4) && notification==0x300) { WCHAR text[512];GetWindowTextW((HWND)l,text,512);log_value(id==3?"LAB_EDIT=":"LAB_MULTI=",text); }
        if(id==5 && !notification){SetWindowTextW(edit,u"");SetWindowTextW(multi,u"");WCHAR text[512];GetWindowTextW(edit,text,512);log_value("LAB_CLEAR_EDIT=",text);GetWindowTextW(multi,text,512);log_value("LAB_CLEAR_MULTI=",text);log_text("LAB_CLEAR\n");}
        return 0;
    }
    if(m==0x100 && w==27){PostMessageW(h,0x10,0,0);return 0;}
    if(m==0x10){log_text("LAB_CLOSE\n");DestroyWindow(h);return 0;}
    if(m==2){PostQuitMessage(42);return 0;}
    return DefWindowProcW(h,m,w,l);
}
void mainCRTStartup(void) {
    WNDCLASSEXW cls={sizeof(cls),3,proc,0,0,0,0,0,(HBRUSH)6,0,u"FrostBox Compatibility Lab",0};MSG msg;
    cls.instance=GetModuleHandleW(0);if(!RegisterClassExW(&cls))ExitProcess(2);
    HWND h=CreateWindowExW(0,cls.name,cls.name,0xcf0000,50,50,640,400,0,0,cls.instance,0);
    if(!h)ExitProcess(3);ShowWindow(h,5);UpdateWindow(h);
    POINT origin={0,0};RECT wr;ClientToScreen(h,&origin);GetWindowRect(h,&wr);
    WCHAR offset[32];unsigned n=0;number(offset,&n,origin.x-wr.left);offset[n++]=u',';number(offset,&n,origin.y-wr.top);offset[n]=0;
    log_value("LAB_CLIENT_OFFSET=",offset);log_text("LAB_READY\n");
    int result;while((result=GetMessageW(&msg,0,0,0))>0){if(msg.message==0x102)state("LAB_QUEUE_CHAR=",msg.hwnd,msg.message,msg.wParam);TranslateMessage(&msg);DispatchMessageW(&msg);}
    ExitProcess(result<0?4:(UINT)msg.wParam);
}
