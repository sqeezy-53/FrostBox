/* SPDX-License-Identifier: LGPL-2.1-or-later
 * FrostBox experimental Android controller transport for Wine 11.19 winebus.
 * Included only in the no-SDL build. Existing Wine HID / XInput owns Windows ABI.
 * Wire: little endian, magic FBP1 + four {connected,buttons,lx,ly,rx,ry,lt,rt} int32.
 */
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

struct android_pad { struct unix_device unix_device; BOOL started, sent; };
static struct android_pad *android_pads[4];
static struct list android_events = LIST_INIT(android_events);
static pthread_mutex_t android_lock = PTHREAD_MUTEX_INITIALIZER;
static int android_socket = -1, android_wake[2] = {-1,-1};
static char android_endpoint[100];
static void android_destroy(struct unix_device *iface) { }
static NTSTATUS android_start(struct unix_device *iface)
{
    pthread_mutex_lock(&android_lock); ((struct android_pad *)iface)->started = TRUE;
    pthread_mutex_unlock(&android_lock); return STATUS_SUCCESS;
}
static void android_stop(struct unix_device *iface)
{
    unsigned i;
    pthread_mutex_lock(&android_lock);
    for (i=0;i<4;i++) if (android_pads[i] == (struct android_pad *)iface) android_pads[i]=NULL;
    pthread_mutex_unlock(&android_lock);
}
static NTSTATUS android_no_haptics(struct unix_device *iface) { return STATUS_NOT_SUPPORTED; }
static NTSTATUS android_haptics(struct unix_device *iface,UINT ms,USHORT a,USHORT b,USHORT c,USHORT d) { return STATUS_NOT_SUPPORTED; }
static const struct hid_device_vtbl android_vtbl = {
    .destroy=android_destroy, .start=android_start, .stop=android_stop,
    .haptics_start=android_haptics, .haptics_stop=android_no_haptics,
};
static void android_disconnect(void)
{
    unsigned i;
    for(i=0;i<4;i++) if(android_pads[i]) {
        bus_event_queue_device_removed(&android_events,&android_pads[i]->unix_device);
        android_pads[i]=NULL;
    }
}
static BOOL android_packet(const int32_t *packet)
{
    static const unsigned masks[]={4096,8192,16384,32768,256,512,32,16,64,128,1,8,2,4};
    const USAGE_AND_PAGE usage={.UsagePage=HID_USAGE_PAGE_GENERIC,.Usage=HID_USAGE_GENERIC_GAMEPAD};
    unsigned i,j;
    if((uint32_t)packet[0]!=0x31504246) return FALSE;
    for(i=0;i<4;i++) {
        const int32_t *p=packet+1+i*8;struct unix_device *iface;
        if(p[0]!=0 && p[0]!=1)return FALSE;
        for(j=2;j<6;j++)if(p[j]<-32768 || p[j]>32767)return FALSE;
        if(p[6]<0 || p[6]>32767 || p[7]<0 || p[7]>32767)return FALSE;
        if(!p[0]) {if(android_pads[i]){bus_event_queue_device_removed(&android_events,&android_pads[i]->unix_device);android_pads[i]=NULL;}continue;}
        if(!android_pads[i]) {
            struct device_desc desc={.vid=0x045e,.pid=0x028e,.version=1,.is_gamepad=TRUE,
                .interface=-1,.bus_id=-1,.manufacturer={'F','r','o','s','t','B','o','x',0},
                .product={'A','n','d','r','o','i','d',' ','G','a','m','e','p','a','d',0},.serialnumber={'0',0}};
            struct android_pad *pad=hid_device_create(&android_vtbl,sizeof(*pad));
            if(!pad)return FALSE;
            iface=&pad->unix_device;desc.serialnumber[0]='0'+i;
            if(!hid_device_begin_report_descriptor(iface,&usage)||!hid_device_add_gamepad(iface)||!hid_device_end_report_descriptor(iface)) {
                iface->vtbl->destroy(iface);free(iface);return FALSE;
            }
            if(!bus_event_queue_device_created(&android_events,iface,&desc)){iface->vtbl->destroy(iface);free(iface);return FALSE;}
            android_pads[i]=pad;
            fprintf(stderr,"FROSTBOX_HID_CREATED=%u\n",i);
        }
        iface=&android_pads[i]->unix_device;
        for(j=0;j<6;j++)hid_device_set_abs_axis(iface,j,p[j+2]);
        for(j=0;j<14;j++)hid_device_set_button(iface,j,!!(p[1]&masks[j]));
        hid_device_set_hatswitch_x(iface,0,!!(p[1]&8)-!!(p[1]&4));
        hid_device_set_hatswitch_y(iface,0,!!(p[1]&2)-!!(p[1]&1));
        if(android_pads[i]->started && (!android_pads[i]->sent || memcmp(iface->hid_device_state.report_buf,iface->hid_device_state.last_report_buf,iface->hid_device_state.report_len))) {
            if(!bus_event_queue_input_report(&android_events,iface,iface->hid_device_state.report_buf,iface->hid_device_state.report_len))return FALSE;
            hid_device_sync_report(iface);android_pads[i]->sent=TRUE;
        }
    }
    return TRUE;
}
NTSTATUS sdl_bus_init(void *args)
{
    const char *prefix=getenv("WINEPREFIX"),*end,*begin;size_t n;
    if(!prefix || !(end=strrchr(prefix,'/')))return STATUS_NOT_SUPPORTED;
    begin=end;while(begin>prefix && begin[-1]!='/')begin--;n=end-begin;
    if(n!=36)return STATUS_NOT_SUPPORTED;
    snprintf(android_endpoint,sizeof(android_endpoint),"frost-pad-%u-%.*s",(unsigned)getuid(),(int)n,begin);
    if(pipe(android_wake))return STATUS_UNSUCCESSFUL;
    return STATUS_SUCCESS;
}
NTSTATUS sdl_bus_wait(void *args)
{
    struct bus_event *result=args;int32_t packet[33];size_t received=0;
    bus_event_cleanup(result);
    for(;;) {
        pthread_mutex_lock(&android_lock);
        if(bus_event_queue_pop(&android_events,result)){pthread_mutex_unlock(&android_lock);return STATUS_PENDING;}
        pthread_mutex_unlock(&android_lock);
        if(android_socket<0) {
            struct sockaddr_un addr={.sun_family=AF_UNIX};struct ucred peer;socklen_t len=sizeof(peer);
            android_socket=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
            memcpy(addr.sun_path+1,android_endpoint,strlen(android_endpoint));
            if(android_socket<0 || connect(android_socket,(struct sockaddr *)&addr,offsetof(struct sockaddr_un,sun_path)+1+strlen(android_endpoint)) ||
                getsockopt(android_socket,SOL_SOCKET,SO_PEERCRED,&peer,&len) || peer.uid!=getuid()) {
                if(android_socket>=0)close(android_socket);android_socket=-1;
                struct pollfd wake={android_wake[0],POLLIN,0};if(poll(&wake,1,100)>0)break;continue;
            }
            fprintf(stderr,"FROSTBOX_HID_CONNECTED=1\n");
        }
        struct pollfd fds[2]={{android_wake[0],POLLIN,0},{android_socket,POLLIN,0}};
        if(poll(fds,2,100)<0){if(errno==EINTR)continue;break;}
        if(fds[0].revents)break;
        if(fds[1].revents) {
            ssize_t n=recv(android_socket,(char *)packet+received,sizeof(packet)-received,0);
            if(n>0) {
                received+=n;if(received<sizeof(packet))continue;
                received=0;pthread_mutex_lock(&android_lock);BOOL valid=android_packet(packet);pthread_mutex_unlock(&android_lock);
                if(valid)continue;
            }
            close(android_socket);android_socket=-1;received=0;
            pthread_mutex_lock(&android_lock);android_disconnect();pthread_mutex_unlock(&android_lock);
        }
    }
    if(android_socket>=0)close(android_socket);android_socket=-1;
    pthread_mutex_lock(&android_lock);android_disconnect();bus_event_queue_destroy(&android_events);pthread_mutex_unlock(&android_lock);
    close(android_wake[0]);close(android_wake[1]);android_wake[0]=android_wake[1]=-1;
    return STATUS_SUCCESS;
}
NTSTATUS sdl_bus_stop(void *args) {if(android_wake[1]>=0){char stop=1;write(android_wake[1],&stop,1);}return STATUS_SUCCESS;}
