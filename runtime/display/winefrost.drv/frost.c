/* SPDX-License-Identifier: LGPL-2.1-or-later
 * FrostBox software display transport, 2026.
 * Surface/device/event registration adapted from Wine wineandroid.drv:
 * Copyright 1996, 2013, 2017 Alexandre Julliard; Wine contributors.
 * No Android ABI pointers cross the x64/ARM64 boundary. No GPU implementation.
 */
#if 0
#pragma makedep unix
#endif
#include "config.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include "ntstatus.h"
#include "windef.h"
#include "winbase.h"
#include "ntuser.h"
#include "wine/gdi_driver.h"
#include "wine/server.h"
#include "wine/unixlib.h"
#include "wine/debug.h"
WINE_DEFAULT_DEBUG_CHANNEL(frost);
#define MAGIC 0x44584246u
#define WIDTH 800
#define HEIGHT 600
/* All fields little-endian uint32; both qualified ABIs are little-endian. */
struct packet { uint32_t magic, type, hwnd; int32_t x,y,w,h; uint32_t size; };
static int display_fd = -1;
static pthread_mutex_t send_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t read_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_key_t queue_key;
static pthread_once_t queue_once = PTHREAD_ONCE_INIT;
static int queue_key_ready;
static int queue_token;

static void init_queue_key(void)
{
    queue_key_ready = pthread_key_create(&queue_key, NULL) == 0;
}

static BOOL is_queue_set(void)
{
    if (pthread_once(&queue_once, init_queue_key) != 0 || !queue_key_ready)
        return FALSE;
    return pthread_getspecific(queue_key) != NULL;
}

static void mark_queue_set(void)
{
    if (queue_key_ready && pthread_setspecific(queue_key, &queue_token) != 0)
        WARN("Failed to store thread queue state\n");
}
static struct packet input_packet;
static size_t input_used;
/* Hardware touch deltas are independent of Wine cursor clipping/recentering. */
static HWND pointer_window;
static POINT pointer_position;
static BOOL pointer_valid;

static BOOL write_all(const void *data, size_t size)
{
    const char *p=data;
    while(size) { ssize_t n=send(display_fd,p,size,MSG_NOSIGNAL); if(n<0 && errno==EINTR) continue;
        if(n<=0) return FALSE; p+=n;size-=n; }
    return TRUE;
}
static BOOL send_packet(struct packet *p, const void *bits)
{
    BOOL ok;
    pthread_mutex_lock(&send_mutex);
    ok=write_all(p,sizeof(*p)) && (!p->size || write_all(bits,p->size));
    pthread_mutex_unlock(&send_mutex);
    if(!ok) WARN("display transport failed: %s\n",strerror(errno));
    return ok;
}
static void set_queue(void)
{
    HANDLE handle; NTSTATUS status;
    if(is_queue_set()) return;
    if(!queue_key_ready) { WARN("Cannot create thread queue key\n");return; }
    if(wine_server_fd_to_handle(display_fd,GENERIC_READ|SYNCHRONIZE,0,&handle)) return;
    SERVER_START_REQ(set_queue_fd) {
        req->handle=wine_server_obj_handle(handle);status=wine_server_call(req);
    } SERVER_END_REQ;
    NtClose(handle);if(!status) mark_queue_set();
}
static HWND keyboard_target(HWND root)
{
    GUITHREADINFO info={.cbSize=sizeof(info)};
    DWORD pid=0,tid=NtUserGetWindowThread(root,&pid);
    BOOL ok=NtUserGetGUIThreadInfo(tid,&info);
    HWND ancestor=info.hwndFocus?NtUserGetAncestor(info.hwndFocus,GA_ROOT):NULL;
    TRACE("route root=%p owner_tid=%lu pid=%lu current_tid=%lu gui=%d active=%p focus=%p ancestor=%p foreground=%p\n",
          root,(unsigned long)tid,(unsigned long)pid,(unsigned long)GetCurrentThreadId(),ok,
          info.hwndActive,info.hwndFocus,ancestor,NtUserGetForegroundWindow());
    if(ok && info.hwndFocus && ancestor==root) return info.hwndFocus;
    return root;
}
static BOOL process_events(DWORD mask)
{
    struct packet events[64]; unsigned int count=0,i;
    BOOL mouse_frame=FALSE;
    if(!mask) return FALSE;
    set_queue();
    pthread_mutex_lock(&read_mutex);
    while(count<64) {
        ssize_t n=recv(display_fd,(char*)&input_packet+input_used,sizeof(input_packet)-input_used,MSG_DONTWAIT);
        if(n<0 && errno==EINTR) continue;
        if(n==0 || (n<0 && errno!=EAGAIN && errno!=EWOULDBLOCK)) {
            pthread_mutex_unlock(&read_mutex);
            NtTerminateProcess(NtCurrentProcess(),STATUS_CONNECTION_DISCONNECTED);
            return FALSE;
        }
        if(n<0) break;
        input_used+=n;
        if(input_used==sizeof(input_packet)) { events[count++]=input_packet;input_used=0; }
    }
    pthread_mutex_unlock(&read_mutex);
    for(i=0;i<count;i++) {
        struct packet *p=&events[i]; INPUT input={0}; HWND hwnd=ULongToHandle(p->hwnd);
        if(p->magic!=MAGIC || p->size || !NtUserIsWindow(hwnd)) continue;
        switch(p->type) {
        case 3:
            mouse_frame=TRUE;
            if(p->w & (MOUSEEVENTF_LEFTDOWN|MOUSEEVENTF_RIGHTDOWN)) NtUserSetForegroundWindowInternal(hwnd);
            input.type=INPUT_MOUSE; input.mi.dx=p->x;input.mi.dy=p->y;
            input.mi.dwFlags=MOUSEEVENTF_ABSOLUTE|MOUSEEVENTF_MOVE|(p->w & 0x1e);
            {
                struct raw_mouse raw={.count=1};
                pthread_mutex_lock(&read_mutex);
                if(pointer_valid && pointer_window==hwnd) {
                    raw.data[0].x=p->x-pointer_position.x;raw.data[0].y=p->y-pointer_position.y;
                }
                pointer_window=hwnd;pointer_position=(POINT){p->x,p->y};pointer_valid=TRUE;
                pthread_mutex_unlock(&read_mutex);
                NtUserSendHardwareInput(hwnd,SEND_HWMSG_RAWINPUT,&input,(LPARAM)&raw);
            }break;
        case 8:
            mouse_frame=TRUE;
            if(p->w & (MOUSEEVENTF_LEFTDOWN|MOUSEEVENTF_RIGHTDOWN)) NtUserSetForegroundWindowInternal(hwnd);
            input.type=INPUT_MOUSE;input.mi.dx=p->x;input.mi.dy=p->y;
            input.mi.dwFlags=MOUSEEVENTF_MOVE|(p->w & 0x1e);
            {
                struct raw_mouse raw={.count=1,.data={{p->x,p->y}}};
                NtUserSendHardwareInput(hwnd,SEND_HWMSG_RAWINPUT,&input,(LPARAM)&raw);
            }break;
        case 4:
            {
                UINT scan=p->y?p->y:NtUserMapVirtualKeyEx(p->x,MAPVK_VK_TO_VSC_EX,NtUserGetKeyboardLayout(0));
                input.type=INPUT_KEYBOARD;input.ki.wVk=p->x;input.ki.wScan=scan&0xff;
                input.ki.dwFlags=(p->w?KEYEVENTF_KEYUP:0)|((scan&0xff00)==0xe000?KEYEVENTF_EXTENDEDKEY:0);
                NtUserSendHardwareInput(keyboard_target(hwnd),0,&input,0);
            }break;
        case 7:
            if(p->x>=0 && p->x<=0xffff && !p->w) {
                HWND target=keyboard_target(hwnd);
                BOOL posted=NtUserPostMessage(target,WM_CHAR,p->x,1);
                TRACE("text corr=%d unit=%04x root=%p target=%p posted=%d error=%lu\n",p->h,p->x,hwnd,target,posted,(unsigned long)RtlGetLastWin32Error());
            }
            break;
        case 5: pthread_mutex_lock(&read_mutex);if(pointer_window==hwnd)pointer_valid=FALSE;pthread_mutex_unlock(&read_mutex);NtUserPostMessage(hwnd,WM_CLOSE,0,0);break;
        case 6: if(p->x) NtUserSetForegroundWindowInternal(hwnd);break;
        }
    }
    /* Wine accumulates motion until a host input frame completes. Without this,
     * movement alone waits for a later button or the accumulation limit. Same
     * public user-driver contract as winewayland.drv pointer_handle_frame(). */
    if(mouse_frame) {
        static const INPUT flush={.type=INPUT_MOUSE,.mi.dwFlags=MOUSEEVENTF_MOVE_NOCOALESCE};
        NtUserSendHardwareInput(NULL,SEND_HWMSG_RAWINPUT,&flush,0);
    }
    return count!=0;
}
static BOOL create_window(HWND hwnd) { set_queue();return TRUE; }
static BOOL create_desktop(const WCHAR *name,UINT w,UINT h) { set_queue();return TRUE; }
static BOOL window_changing(HWND hwnd,UINT flags,BOOL shaped,const struct window_rects *rects)
{ set_queue();return NtUserGetAncestor(hwnd,GA_PARENT)==NtUserGetDesktopWindow(); }
static void destroy_window(HWND hwnd)
{
    pthread_mutex_lock(&read_mutex);if(pointer_window==hwnd)pointer_valid=FALSE;pthread_mutex_unlock(&read_mutex);
    struct packet p={MAGIC,2,HandleToULong(hwnd),0,0,0,0,0};send_packet(&p,NULL);
}
static void window_changed(HWND hwnd,HWND after,HWND owner,UINT flags,const struct window_rects *rects,struct window_surface *surface)
{
    if((flags & SWP_SHOWWINDOW) && hwnd!=NtUserGetDesktopWindow() && !(NtUserGetWindowLongW(hwnd,GWL_EXSTYLE)&WS_EX_NOACTIVATE))
        NtUserSetForegroundWindowInternal(hwnd);
}
static void set_clip(struct window_surface *s,const RECT *rects,UINT count) { /* one foreground opaque window */ }
static void surface_destroy(struct window_surface *s) { }
static BOOL flush(struct window_surface *s,const RECT *rect,const RECT *dirty,const BITMAPINFO *info,
                  const void *bits,BOOL shape_changed,const BITMAPINFO *shape_info,const void *shape_bits)
{
    RECT window, visible;
    int w=info->bmiHeader.biWidth,h=abs(info->bmiHeader.biHeight),row;
    unsigned char *cropped;
    BOOL ok;
    struct packet p={MAGIC,1,HandleToULong(s->hwnd),0,0,0,0,0};
    if(w<=0 || h<=0 || w>2048 || h>2048 || info->bmiHeader.biBitCount!=32) return FALSE;
    if(s->hwnd==NtUserGetDesktopWindow() || !(NtUserGetWindowLongW(s->hwnd,GWL_STYLE)&WS_VISIBLE)) return TRUE;
    if(!NtUserGetWindowRect(s->hwnd,&window,96)) return FALSE;
    /* Wine rounds backing surfaces to 128 pixels. Allocation padding is not window
     * content; intersect in window-relative coordinates and preserve the source stride. */
    visible.left=max(rect->left,0);visible.top=max(rect->top,0);
    visible.right=min(rect->right,window.right-window.left);
    visible.bottom=min(rect->bottom,window.bottom-window.top);
    p.w=visible.right-visible.left;p.h=visible.bottom-visible.top;
    if(p.w<=0 || p.h<=0) return TRUE;
    if(visible.left-rect->left+p.w>w || visible.top-rect->top+p.h>h) return FALSE;
    p.x=window.left+visible.left;p.y=window.top+visible.top;p.size=p.w*p.h*4;
    if(p.w==w && p.h==h && info->bmiHeader.biHeight<0) return send_packet(&p,bits);
    if(!(cropped=malloc(p.size))) return FALSE;
    for(row=0;row<p.h;row++) {
        int source_row=visible.top-rect->top+row;
        if(info->bmiHeader.biHeight>0) source_row=h-1-source_row;
        memcpy(cropped+row*p.w*4,(const unsigned char *)bits+
               (source_row*w+visible.left-rect->left)*4,p.w*4);
    }
    ok=send_packet(&p,cropped);free(cropped);return ok;
}
static const struct window_surface_funcs surface_funcs={sizeof(struct window_surface),set_clip,flush,surface_destroy};
static BOOL create_surface(HWND hwnd,BOOL layered,const RECT *rect,struct window_surface **surface)
{
    BITMAPINFO info={0};
    if(layered) return FALSE;
    if(*surface && (*surface)->funcs==&surface_funcs) return TRUE;
    if(*surface) window_surface_release(*surface);
    info.bmiHeader.biSize=sizeof(info.bmiHeader);
    info.bmiHeader.biWidth=rect->right-rect->left;info.bmiHeader.biHeight=-(rect->bottom-rect->top);
    info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;
    *surface=window_surface_create(&surface_funcs,hwnd,rect,&info,0);return *surface!=NULL;
}
static UINT update_devices(const struct gdi_device_manager *manager,void *param)
{
    struct pci_id pci={0};struct gdi_monitor monitor={.rc_monitor={0,0,WIDTH,HEIGHT},.rc_work={0,0,WIDTH,HEIGHT}};
    DEVMODEW mode={.dmSize=sizeof(mode),.dmFields=DM_POSITION|DM_PELSWIDTH|DM_PELSHEIGHT|DM_BITSPERPEL|DM_DISPLAYFREQUENCY,
        .dmBitsPerPel=32,.dmPelsWidth=WIDTH,.dmPelsHeight=HEIGHT,.dmDisplayFrequency=60};
    manager->add_gpu(NULL,&pci,NULL,param);
    manager->add_source("FrostBox software",DISPLAY_DEVICE_ATTACHED_TO_DESKTOP|DISPLAY_DEVICE_PRIMARY_DEVICE,96,param);
    manager->add_monitor(&monitor,param);manager->add_modes(&mode,1,&mode,param);return STATUS_SUCCESS;
}
static const struct user_driver_funcs funcs={
    .pCreateDesktop=create_desktop,.pCreateWindow=create_window,.pProcessEvents=process_events,
    .pDestroyWindow=destroy_window,.pWindowPosChanging=window_changing,.pWindowPosChanged=window_changed,
    .pCreateWindowSurface=create_surface,.pUpdateDisplayDevices=update_devices
};
NTSTATUS __wine_unix_lib_init(void)
{
    const char *name=getenv("FROSTBOX_DISPLAY");struct sockaddr_un addr={.sun_family=AF_UNIX};
    struct timeval timeout={5,0};struct ucred peer;socklen_t len=sizeof(peer);
    if(!name || !*name || strlen(name)>90) return STATUS_UNSUCCESSFUL;
    display_fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(display_fd<0) return STATUS_UNSUCCESSFUL;
    memcpy(addr.sun_path+1,name,strlen(name));
    setsockopt(display_fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    if(connect(display_fd,(void*)&addr,offsetof(struct sockaddr_un,sun_path)+1+strlen(name)) ||
       getsockopt(display_fd,SOL_SOCKET,SO_PEERCRED,&peer,&len) || peer.uid!=getuid()) {
        close(display_fd);display_fd=-1;return STATUS_UNSUCCESSFUL;
    }
    __wine_set_user_driver(&funcs,WINE_GDI_DRIVER_VERSION);
    TRACE("connected to FrostBox software surface\n");return STATUS_SUCCESS;
}
