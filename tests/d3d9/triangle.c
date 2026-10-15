// SPDX-License-Identifier: MIT
// Real x86 Direct3D9 fixed-function DrawPrimitiveUP/Present, 120 GPU frames.
#define COBJMACROS
#include <windows.h>
#include <d3d9.h>
__declspec(dllimport) int __cdecl printf(const char*,...);
__declspec(dllimport) int __cdecl fflush(void*);
int _fltused;
typedef struct {float x,y,z,rhw;DWORD color;} Vertex;
static LRESULT CALLBACK wndproc(HWND w,UINT m,WPARAM p,LPARAM l){return DefWindowProcW(w,m,p,l);}
void mainCRTStartup(void) {
    HWND w=NULL;IDirect3D9 *api=NULL;IDirect3DDevice9 *device=NULL;int code=77;
    WNDCLASSW wc={0};wc.lpfnWndProc=wndproc;wc.hInstance=GetModuleHandleW(NULL);wc.lpszClassName=L"FrostBoxD3D9X86";
    if(!RegisterClassW(&wc))goto done;
    w=CreateWindowExW(0,wc.lpszClassName,L"FrostBox x86 D3D9",WS_POPUP|WS_VISIBLE,0,0,640,360,NULL,NULL,wc.hInstance,NULL);
    if(!w)goto done;RECT rect={0};if(!GetClientRect(w,&rect))goto done;
    printf("FROSTBOX_D3D9_X86 HWND=%p CLIENT=%ldx%ld\n",w,rect.right-rect.left,rect.bottom-rect.top);fflush(NULL);
    api=Direct3DCreate9(D3D_SDK_VERSION);if(!api)goto done;
    D3DADAPTER_IDENTIFIER9 id={0};if(SUCCEEDED(IDirect3D9_GetAdapterIdentifier(api,0,0,&id)))printf("GPU=%s DRIVER=%s\n",id.Description,id.Driver);
    D3DPRESENT_PARAMETERS pp={0};pp.BackBufferWidth=640;pp.BackBufferHeight=360;pp.BackBufferFormat=D3DFMT_X8R8G8B8;pp.BackBufferCount=1;pp.SwapEffect=D3DSWAPEFFECT_DISCARD;pp.hDeviceWindow=w;pp.Windowed=TRUE;pp.PresentationInterval=D3DPRESENT_INTERVAL_ONE;
    HRESULT hr=IDirect3D9_CreateDevice(api,0,D3DDEVTYPE_HAL,w,D3DCREATE_SOFTWARE_VERTEXPROCESSING,&pp,&device);
    printf("CREATE_DEVICE=0x%08lx\n",hr);fflush(NULL);if(FAILED(hr))goto done;
    IDirect3DDevice9_SetRenderState(device,D3DRS_LIGHTING,FALSE);IDirect3DDevice9_SetRenderState(device,D3DRS_CULLMODE,D3DCULL_NONE);
    if(FAILED(IDirect3DDevice9_SetFVF(device,D3DFVF_XYZRHW|D3DFVF_DIFFUSE)))goto done;
    for(unsigned f=0;f<120;f++) {
        MSG msg;while(PeekMessageW(&msg,NULL,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}
        DWORD red=(f*7)&255;Vertex v[]={{320,40,0.5f,1,0xff000000|(red<<16)|0x00aa},{80,310,0.5f,1,0xff00dd33},{560,310,0.5f,1,0xff3377ee}};
        if(FAILED(hr=IDirect3DDevice9_Clear(device,0,NULL,D3DCLEAR_TARGET,0xff121326,1,0)))goto fail;
        if(FAILED(hr=IDirect3DDevice9_BeginScene(device)))goto fail;
        hr=IDirect3DDevice9_DrawPrimitiveUP(device,D3DPT_TRIANGLELIST,1,v,sizeof(Vertex));
        if(FAILED(hr))goto fail;
        if(FAILED(hr=IDirect3DDevice9_EndScene(device)))goto fail;
        if(FAILED(hr=IDirect3DDevice9_Present(device,NULL,NULL,NULL,NULL)))goto fail;
        Sleep(8);
    }
    printf("FROSTBOX_D3D9_X86_DRAWS=120 PRESENTS=120\n");code=42;goto done;
fail:printf("D3D9_FAIL=0x%08lx\n",hr);
done:if(device)IDirect3DDevice9_Release(device);if(api)IDirect3D9_Release(api);if(w)DestroyWindow(w);fflush(NULL);ExitProcess(code);
}
