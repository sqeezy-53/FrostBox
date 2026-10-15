// SPDX-License-Identifier: MIT
// Genuine Windows D3D11 Draw/Present, compiled against pinned Wine SDK headers.
#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
__declspec(dllimport) int printf(const char*,...);
__declspec(dllimport) int fflush(void*);
#include <string.h>
int _fltused=0;
#ifndef FROSTBOX_CUBE_FRAMES
#define FROSTBOX_CUBE_FRAMES 120
#endif
static int quit;
static LRESULT CALLBACK proc(HWND w,UINT message,WPARAM a,LPARAM b){
#ifdef FROSTBOX_PANEL_PROBE
    if(message==WM_LBUTTONDOWN) {printf("PANEL_CLICK=%d,%d\n",(short)LOWORD(b),(short)HIWORD(b));fflush(NULL);}
    if(message==WM_KEYDOWN || message==WM_KEYUP){printf("PANEL_KEY=%u,%u\n",(unsigned)a,(unsigned)message);fflush(NULL);}
#endif
if(message==WM_CLOSE){DestroyWindow(w);return 0;}if(message==WM_DESTROY){quit=1;PostQuitMessage(0);return 0;}return DefWindowProcW(w,message,a,b);}
static const GUID texture_iid={0x6f15aaf2,0xd208,0x4e89,{0x9a,0xb4,0x48,0x95,0x35,0xd3,0x4f,0x9c}};
static const char shader[]=
"cbuffer Frame:register(b0){float time;float aspect;float2 pad;}"
"struct Vertex{float3 position:POSITION;float3 color:COLOR;};"
"struct Pixel{float4 position:SV_POSITION;float3 color:COLOR;};"
"Pixel vs(Vertex v){Pixel p;float s=sin(time),c=cos(time);float3 r=float3(v.position.x*c+v.position.z*s,v.position.y,-v.position.x*s+v.position.z*c);"
"float t=0.4;float3 q=float3(r.x,r.y*cos(t)-r.z*sin(t),r.y*sin(t)+r.z*cos(t)+4);p.position=float4(q.x/aspect,q.y,q.z*1.010101-0.10101,q.z);p.color=v.color;return p;}"
"float4 ps(Pixel p):SV_TARGET{return float4(p.color,1);}";
// Six cube faces, independent colored vertices; depth testing proves actual 3D occlusion.
#define V(x,y,z,r,g,b) {x,y,z,r,g,b}
static const float vertices[36][6]={
V(-1,-1,-1,1,0,0),V(-1,1,-1,1,0,0),V(1,1,-1,1,0,0), V(-1,-1,-1,1,0,0),V(1,1,-1,1,0,0),V(1,-1,-1,1,0,0),
V(-1,-1,1,0,1,0),V(1,1,1,0,1,0),V(-1,1,1,0,1,0), V(-1,-1,1,0,1,0),V(1,-1,1,0,1,0),V(1,1,1,0,1,0),
V(-1,-1,-1,0,0,1),V(-1,-1,1,0,0,1),V(-1,1,1,0,0,1), V(-1,-1,-1,0,0,1),V(-1,1,1,0,0,1),V(-1,1,-1,0,0,1),
V(1,-1,-1,1,1,0),V(1,1,1,1,1,0),V(1,-1,1,1,1,0), V(1,-1,-1,1,1,0),V(1,1,-1,1,1,0),V(1,1,1,1,1,0),
V(-1,1,-1,1,0,1),V(-1,1,1,1,0,1),V(1,1,1,1,0,1), V(-1,1,-1,1,0,1),V(1,1,1,1,0,1),V(1,1,-1,1,0,1),
V(-1,-1,-1,0,1,1),V(1,-1,1,0,1,1),V(-1,-1,1,0,1,1), V(-1,-1,-1,0,1,1),V(1,-1,-1,0,1,1),V(1,-1,1,0,1,1)};
#define HR(call) do{HRESULT h_=(call);if(FAILED(h_)){printf("D3D11_FAIL %s HRESULT=%08lx\n",#call,(unsigned long)h_);goto done;}}while(0)
#define RELEASE(p) do{if(p){IUnknown_Release((IUnknown*)(p));(p)=NULL;}}while(0)
void mainCRTStartup(void){
    int result=11;unsigned frames=0;ID3D11Device *device=NULL;ID3D11DeviceContext *context=NULL;IDXGISwapChain *swap=NULL;ID3D11Texture2D *back=NULL,*depth=NULL;ID3D11RenderTargetView *rtv=NULL;ID3D11DepthStencilView *dsv=NULL;ID3D11VertexShader *vs=NULL;ID3D11PixelShader *ps=NULL;ID3D11InputLayout *layout=NULL;ID3D11Buffer *vb=NULL,*cb=NULL;ID3DBlob *vcode=NULL,*pcode=NULL,*errors=NULL;ID3D11RasterizerState *raster=NULL;
    printf("FROSTBOX_D3D11_CUBE BEGIN\n");
    WNDCLASSEXW cls={0};cls.cbSize=sizeof(cls);cls.lpfnWndProc=proc;cls.hInstance=GetModuleHandleW(NULL);cls.lpszClassName=L"FrostD3D11Cube";
    if(!RegisterClassExW(&cls)){printf("REGISTER_FAILED=%lu\n",GetLastError());ExitProcess(11);}
#ifdef FROSTBOX_PANEL_PROBE
    HWND window=CreateWindowExW(0,cls.lpszClassName,cls.lpszClassName,WS_POPUP,0,0,800,600,NULL,NULL,cls.hInstance,NULL);if(!window)ExitProcess(11);ShowWindow(window,SW_SHOW);
#else
    HWND window=CreateWindowExW(0,cls.lpszClassName,cls.lpszClassName,WS_OVERLAPPEDWINDOW,0,0,400,300,NULL,NULL,cls.hInstance,NULL);if(!window)ExitProcess(11);ShowWindow(window,SW_SHOW);
#endif
    RECT area={0};if(!GetClientRect(window,&area))goto done;UINT width=area.right,height=area.bottom;if(!width||!height||width>4096||height>4096){printf("BAD_CLIENT=%ux%u\n",width,height);goto done;}
    DXGI_SWAP_CHAIN_DESC desc={0};desc.BufferDesc.Width=width;desc.BufferDesc.Height=height;desc.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;desc.SampleDesc.Count=1;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.OutputWindow=window;desc.Windowed=TRUE;desc.SwapEffect=DXGI_SWAP_EFFECT_DISCARD;
    D3D_FEATURE_LEVEL wanted=D3D_FEATURE_LEVEL_11_0,actual=0;
    HR(D3D11CreateDeviceAndSwapChain(NULL,D3D_DRIVER_TYPE_HARDWARE,NULL,0,&wanted,1,D3D11_SDK_VERSION,&desc,&swap,&device,&actual,&context));printf("D3D11_DEVICE FEATURE=%04x SIZE=%ux%u\n",actual,width,height);
    HR(IDXGISwapChain_GetBuffer(swap,0,&texture_iid,(void**)&back));HR(ID3D11Device_CreateRenderTargetView(device,(ID3D11Resource*)back,NULL,&rtv));
    D3D11_TEXTURE2D_DESC td={0};td.Width=width;td.Height=height;td.MipLevels=1;td.ArraySize=1;td.Format=DXGI_FORMAT_D32_FLOAT;td.SampleDesc.Count=1;td.BindFlags=D3D11_BIND_DEPTH_STENCIL;
    HR(ID3D11Device_CreateTexture2D(device,&td,NULL,&depth));HR(ID3D11Device_CreateDepthStencilView(device,(ID3D11Resource*)depth,NULL,&dsv));
    HR(D3DCompile(shader,strlen(shader),"cube.hlsl",NULL,NULL,"vs","vs_5_0",0,0,&vcode,&errors));RELEASE(errors);
    HR(D3DCompile(shader,strlen(shader),"cube.hlsl",NULL,NULL,"ps","ps_5_0",0,0,&pcode,&errors));RELEASE(errors);
    HR(ID3D11Device_CreateVertexShader(device,ID3D10Blob_GetBufferPointer(vcode),ID3D10Blob_GetBufferSize(vcode),NULL,&vs));HR(ID3D11Device_CreatePixelShader(device,ID3D10Blob_GetBufferPointer(pcode),ID3D10Blob_GetBufferSize(pcode),NULL,&ps));
    D3D11_INPUT_ELEMENT_DESC inputs[]={{"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"COLOR",0,DXGI_FORMAT_R32G32B32_FLOAT,0,12,D3D11_INPUT_PER_VERTEX_DATA,0}};
    HR(ID3D11Device_CreateInputLayout(device,inputs,2,ID3D10Blob_GetBufferPointer(vcode),ID3D10Blob_GetBufferSize(vcode),&layout));
    D3D11_BUFFER_DESC bd={0};bd.ByteWidth=sizeof(vertices);bd.Usage=D3D11_USAGE_IMMUTABLE;bd.BindFlags=D3D11_BIND_VERTEX_BUFFER;D3D11_SUBRESOURCE_DATA data={vertices,0,0};HR(ID3D11Device_CreateBuffer(device,&bd,&data,&vb));
    bd.ByteWidth=16;bd.Usage=D3D11_USAGE_DEFAULT;bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;HR(ID3D11Device_CreateBuffer(device,&bd,NULL,&cb));
    D3D11_RASTERIZER_DESC rd={0};rd.FillMode=D3D11_FILL_SOLID;rd.CullMode=D3D11_CULL_NONE;rd.DepthClipEnable=TRUE;HR(ID3D11Device_CreateRasterizerState(device,&rd,&raster));ID3D11DeviceContext_RSSetState(context,raster);
    ID3D11DeviceContext_OMSetRenderTargets(context,1,&rtv,dsv);D3D11_VIEWPORT viewport={0,0,(float)width,(float)height,0,1};ID3D11DeviceContext_RSSetViewports(context,1,&viewport);
    UINT stride=24,offset=0;ID3D11DeviceContext_IASetVertexBuffers(context,0,1,&vb,&stride,&offset);ID3D11DeviceContext_IASetInputLayout(context,layout);ID3D11DeviceContext_IASetPrimitiveTopology(context,D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);ID3D11DeviceContext_VSSetShader(context,vs,NULL,0);ID3D11DeviceContext_PSSetShader(context,ps,NULL,0);ID3D11DeviceContext_VSSetConstantBuffers(context,0,1,&cb);
    for(frames=0;frames<FROSTBOX_CUBE_FRAMES&&!quit;frames++){
        MSG msg;while(PeekMessageW(&msg,NULL,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}if(quit)break;
        float frame[]={frames*0.04f,(float)width/height,0,0};ID3D11DeviceContext_UpdateSubresource(context,(ID3D11Resource*)cb,0,NULL,frame,0,0);
        const float clear[]={0.02f,0.025f,0.06f,1};ID3D11DeviceContext_ClearRenderTargetView(context,rtv,clear);ID3D11DeviceContext_ClearDepthStencilView(context,dsv,D3D11_CLEAR_DEPTH,1,0);
        ID3D11DeviceContext_Draw(context,36,0);HR(IDXGISwapChain_Present(swap,1,0));printf("FROSTBOX_D3D11_DRAW_PRESENT FRAME=%u\n",frames+1);fflush(NULL);Sleep(15);
    }
    if(frames==FROSTBOX_CUBE_FRAMES)result=42;
done:
    if(errors)printf("HLSL_ERROR=%s\n",(char*)ID3D10Blob_GetBufferPointer(errors));
    if(context){ID3D11DeviceContext_ClearState(context);ID3D11DeviceContext_Flush(context);}
    RELEASE(raster);RELEASE(cb);RELEASE(vb);RELEASE(layout);RELEASE(ps);RELEASE(vs);RELEASE(pcode);RELEASE(vcode);RELEASE(errors);RELEASE(dsv);RELEASE(depth);RELEASE(rtv);RELEASE(back);RELEASE(swap);RELEASE(context);RELEASE(device);
    if(!quit)DestroyWindow(window);printf("FROSTBOX_D3D11_EXIT=%d DRAWS=%u\n",result,frames);fflush(NULL);ExitProcess(result);
}
