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
API BOOL PostMessageW(HWND,UINT,WPARAM,LPARAM);
API HBRUSH CreateSolidBrush(DWORD); API BOOL DeleteObject(HANDLE);
API DWORD SetTextColor(HDC,DWORD); API int SetBkMode(HDC,int); API BOOL TextOutW(HDC,int,int,LPCWSTR,int);
API HANDLE CreateFontW(int,int,int,int,int,DWORD,DWORD,DWORD,DWORD,DWORD,DWORD,DWORD,DWORD,LPCWSTR);
API HANDLE SelectObject(HDC,HANDLE); API DWORD GetGlyphIndicesW(HDC,LPCWSTR,int,WORD*,DWORD);
API HANDLE GetModuleHandleW(LPCWSTR); API HANDLE GetStdHandle(DWORD);
API BOOL WriteFile(HANDLE,const void*,DWORD,DWORD*,void*); API __declspec(noreturn) void ExitProcess(UINT);

API LRESULT SendMessageW(HWND,UINT,WPARAM,LPARAM);
API BOOL SetWindowTextW(HWND,LPCWSTR);
API int GetWindowTextW(HWND,WCHAR*,int);
API HWND SetFocus(HWND);
API HWND GetFocus(void);
API LRESULT CallWindowProcW(WNDPROC,HWND,UINT,WPARAM,LPARAM);
API long long SetWindowLongPtrW(HWND,int,long long);
API BOOL ClientToScreen(HWND,POINT*);
API BOOL GetWindowRect(HWND,RECT*);

typedef struct { DWORD cbSize,flags;HWND active,focus,capture,menu,movesize,caret;RECT caretRect; } GUITHREADINFO;
API DWORD GetCurrentThreadId(void);
API DWORD GetWindowThreadProcessId(HWND,DWORD*);
API HWND GetForegroundWindow(void);
API BOOL GetGUIThreadInfo(DWORD,GUITHREADINFO*);
