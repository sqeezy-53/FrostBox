// SPDX-License-Identifier: MIT
// Real Win32 GUI fixture. No CRT and no Android drawing or display transport here.
typedef void *HANDLE; typedef HANDLE HWND; typedef HANDLE HDC; typedef HANDLE HBRUSH;
typedef unsigned long DWORD; typedef unsigned int UINT; typedef unsigned short WORD;
typedef unsigned short WCHAR; typedef unsigned long long WPARAM; typedef long long LPARAM;
typedef long long LRESULT; typedef int BOOL; typedef const WCHAR *LPCWSTR;
typedef struct { long x,y; } POINT;
typedef struct { long left,top,right,bottom; } RECT;
typedef struct { HDC hdc; BOOL erase; RECT paint; BOOL restore,update; unsigned char reserved[32]; } PAINTSTRUCT;
typedef struct { HWND hwnd; UINT message; WPARAM wParam; LPARAM lParam; DWORD time; POINT pt; DWORD private_; } MSG;
typedef LRESULT (*WNDPROC)(HWND,UINT,WPARAM,LPARAM);
typedef struct { UINT size,style; WNDPROC proc; int classExtra,windowExtra; HANDLE instance,icon,cursor; HBRUSH background; LPCWSTR menu,name; HANDLE smallIcon; } WNDCLASSEXW;
#define API __declspec(dllimport)
API WORD RegisterClassExW(const WNDCLASSEXW*);
API HWND CreateWindowExW(DWORD,LPCWSTR,LPCWSTR,DWORD,int,int,int,int,HWND,HANDLE,HANDLE,void*);
API BOOL ShowWindow(HWND,int); API BOOL UpdateWindow(HWND);
API BOOL GetMessageW(MSG*,HWND,UINT,UINT); API BOOL TranslateMessage(const MSG*); API LRESULT DispatchMessageW(const MSG*);
API LRESULT DefWindowProcW(HWND,UINT,WPARAM,LPARAM);
API HDC BeginPaint(HWND,PAINTSTRUCT*); API BOOL EndPaint(HWND,const PAINTSTRUCT*);
API int FillRect(HDC,const RECT*,HBRUSH); API BOOL GetClientRect(HWND,RECT*);
API BOOL InvalidateRect(HWND,const RECT*,BOOL); API BOOL DestroyWindow(HWND); API void PostQuitMessage(int);
API BOOL PostMessageW(HWND,UINT,WPARAM,LPARAM); API short GetAsyncKeyState(int);
API HBRUSH CreateSolidBrush(DWORD); API BOOL DeleteObject(HANDLE);
API DWORD SetTextColor(HDC,DWORD); API int SetBkMode(HDC,int); API BOOL TextOutW(HDC,int,int,LPCWSTR,int);
API HANDLE CreateFontW(int,int,int,int,int,DWORD,DWORD,DWORD,DWORD,DWORD,DWORD,DWORD,DWORD,LPCWSTR);
API HANDLE SelectObject(HDC,HANDLE); API DWORD GetGlyphIndicesW(HDC,LPCWSTR,int,WORD*,DWORD);
API HANDLE GetModuleHandleW(LPCWSTR); API HANDLE GetStdHandle(DWORD);
API BOOL WriteFile(HANDLE,const void*,DWORD,DWORD*,void*); API __declspec(noreturn) void ExitProcess(UINT);
static const WCHAR title[]=u"FROSTBOX GUI TEST";
static const WCHAR unicode_text[]=u"\u041f\u0440\u0438\u0432\u0435\u0442 FrostBox";
static unsigned int clicked,keyed;
static void log_text(const char *s) { DWORD n=0,len=0;while(s[len])len++;WriteFile(GetStdHandle((DWORD)-11),s,len,&n,0); }
static void fill(HDC dc,long x,long y,long w,long h,DWORD rgb) { RECT r={x,y,x+w,y+h};HBRUSH b=CreateSolidBrush(rgb);FillRect(dc,&r,b);DeleteObject(b); }
static LRESULT window_proc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
    if(message==0x000f) {
        PAINTSTRUCT ps;RECT r;HDC dc=BeginPaint(hwnd,&ps);GetClientRect(hwnd,&r);
        fill(dc,0,0,r.right,r.bottom,0x00201810);
        HANDLE font=CreateFontW(-22,0,0,0,400,0,0,0,1,0,0,3,0,u"Tahoma");
        HANDLE previous=font?SelectObject(dc,font):0;WORD glyphs[6];
        BOOL glyph_ok=font && GetGlyphIndicesW(dc,unicode_text,6,glyphs,1)!=0xffffffff;
        if(glyph_ok) for(int i=0;i<6;i++) if(glyphs[i]==0xffff) glyph_ok=0;
        SetBkMode(dc,1);SetTextColor(dc,0x00ffffff);
        if(!TextOutW(dc,32,28,title,17)) log_text("FROSTBOX_GUI_TEXT_FAILED\n");
        SetTextColor(dc,0x00ff80e0);
        if(!glyph_ok || !TextOutW(dc,32,62,unicode_text,sizeof(unicode_text)/sizeof(WCHAR)-1)) log_text("FROSTBOX_GUI_UNICODE_FAILED\n");
        else log_text("FROSTBOX_GUI_UNICODE_OK\n");
        fill(dc,40,100,130,100,clicked?0x00a0ff30:0x003030e0);
        fill(dc,200,100,130,100,keyed?0x0030e0ff:0x0030b040);
        fill(dc,360,100,130,100,0x00e07020);
        SetTextColor(dc,0x00ffffff);
        if(!TextOutW(dc,32,240,u"Touch changes red. Key changes green. Escape closes.",50)) log_text("FROSTBOX_GUI_TEXT_FAILED\n");
        if(previous) SelectObject(dc,previous);if(font) DeleteObject(font);
        EndPaint(hwnd,&ps);log_text("FROSTBOX_GUI_PAINT\n");return 0;
    }
    if(message==0x0201) { clicked^=1;log_text("FROSTBOX_GUI_MOUSE\n");InvalidateRect(hwnd,0,0);return 0; }
    if(message==0x0100) { keyed^=1;log_text("FROSTBOX_GUI_KEY\n");log_text((GetAsyncKeyState((int)wp)&0x8000)?"FROSTBOX_GUI_ASYNC_DOWN\n":"FROSTBOX_GUI_ASYNC_MISSING\n");InvalidateRect(hwnd,0,0);if(wp==27) PostMessageW(hwnd,0x0010,0,0);return 0; }
    if(message==0x0010) { log_text("FROSTBOX_GUI_CLOSE\n");DestroyWindow(hwnd);return 0; }
    if(message==0x0002) { PostQuitMessage(42);return 0; }
    return DefWindowProcW(hwnd,message,wp,lp);
}
void mainCRTStartup(void) {
    WNDCLASSEXW cls={sizeof(cls),3,window_proc,0,0,0,0,0,0,0,title,0};MSG msg;
    cls.instance=GetModuleHandleW(0);
    if(!RegisterClassExW(&cls)) { log_text("FROSTBOX_GUI_REGISTER_FAILED\n");ExitProcess(2); }
    HWND window=CreateWindowExW(0,title,title,0x00cf0000,50,50,640,400,0,0,cls.instance,0);
    if(!window) { log_text("FROSTBOX_GUI_CREATE_FAILED\n");ExitProcess(3); }
    ShowWindow(window,5);UpdateWindow(window);log_text("FROSTBOX_GUI_READY\n");
    int result;while((result=GetMessageW(&msg,0,0,0))>0) { TranslateMessage(&msg);DispatchMessageW(&msg); }
    ExitProcess(result<0?4:(UINT)msg.wParam);
}
