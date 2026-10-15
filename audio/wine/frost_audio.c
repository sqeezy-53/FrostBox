/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Narrow experimental Wine 11.19 Unix audio driver. Shared stereo 48 kHz only.
 * mmdevapi implements WASAPI/WinMM; this file implements its existing Unix contract.
 * Packaged in the default OSS slot only in the opt-in overlay, no OSS device access.
 */
#include "config.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include "ntstatus.h"
#include "winternl.h"
#include "audioclient.h"
#include "mmsystem.h"
#include "mmddk.h"
#include "wine/unixlib.h"
#include "unixlib.h"
#define MAGIC 0x31414246u
#define RATE 48000u
#define MAX_FRAMES 4800u
struct reply {uint32_t magic,status,padding,received_lo,received_hi,played_lo,played_hi,capacity;};
struct stream {
    struct stream *next;pthread_mutex_t lock;int fd;unsigned block,bits,frames,locked_frames;
    BOOL playing,failed;BYTE *buffer;float gain[2];struct reply state;
};
static pthread_mutex_t streams_lock=PTHREAD_MUTEX_INITIALIZER;
static struct stream *streams;
static int io_all(int fd,void *buf,size_t size,BOOL send_data){char *p=buf;while(size){ssize_t n=send_data?send(fd,p,size,MSG_NOSIGNAL):recv(fd,p,size,0);if(n<0&&errno==EINTR)continue;if(n<=0)return 0;p+=n;size-=n;}return 1;}
static int command(int fd,unsigned op,unsigned frames,const void *pcm,struct reply *r){uint32_t h[]={MAGIC,op,frames,op==3?frames*4:0};if(!io_all(fd,h,sizeof(h),TRUE)||(h[3]&&!io_all(fd,(void*)pcm,h[3],TRUE))||!io_all(fd,r,sizeof(*r),FALSE))return 0;return r->magic==MAGIC && !r->status && r->capacity>0 && r->capacity<=MAX_FRAMES && r->padding<=r->capacity;}
static int connect_output(struct reply *r){
 const char *prefix=getenv("WINEPREFIX"),*end,*begin;struct sockaddr_un a={.sun_family=AF_UNIX};char name[100];int fd;struct ucred peer;socklen_t n=sizeof(peer);struct timeval timeout={2,0};
 if(!prefix || !(end=strrchr(prefix,'/')))return -1;begin=end;while(begin>prefix&&begin[-1]!='/')begin--;
 if(end-begin!=36)return -1;
 snprintf(name,sizeof(name),"frost-audio-%u-%.*s",(unsigned)getuid(),36,begin);memcpy(a.sun_path+1,name,strlen(name));
 fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0)return -1;
 setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
 if(connect(fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+1+strlen(name))||getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n)||peer.uid!=getuid()||!command(fd,1,0,NULL,r)){close(fd);return -1;}return fd;
}
/* Handles are validated against live allocations; pointers from callers are never blindly cast. */
static struct stream *acquire(stream_handle h){struct stream *s;pthread_mutex_lock(&streams_lock);for(s=streams;s;s=s->next)if((UINT_PTR)s==h)break;if(s)pthread_mutex_lock(&s->lock);pthread_mutex_unlock(&streams_lock);return s;}
static NTSTATUS done(struct stream *s,HRESULT *out,HRESULT hr){*out=hr;if(s)pthread_mutex_unlock(&s->lock);return STATUS_SUCCESS;}
static HRESULT query(struct stream *s){if(s->failed||!command(s->fd,6,0,NULL,&s->state)){s->failed=TRUE;return AUDCLNT_E_DEVICE_INVALIDATED;}return S_OK;}
static BOOL format_supported(const WAVEFORMATEX *f){
 if(!f||f->nChannels!=2||f->nSamplesPerSec!=RATE||f->nBlockAlign!=2*f->wBitsPerSample/8||f->nAvgBytesPerSec!=RATE*f->nBlockAlign)return FALSE;
 if(f->wFormatTag==WAVE_FORMAT_PCM)return f->wBitsPerSample==16;
 if(f->wFormatTag==WAVE_FORMAT_IEEE_FLOAT)return f->wBitsPerSample==32;
 if(f->wFormatTag==WAVE_FORMAT_EXTENSIBLE&&f->cbSize>=22){const WAVEFORMATEXTENSIBLE *e=(const void*)f;return e->Samples.wValidBitsPerSample==f->wBitsPerSample && (e->dwChannelMask==0||e->dwChannelMask==3) && ((f->wBitsPerSample==16&&e->SubFormat.Data1==1)||(f->wBitsPerSample==32&&e->SubFormat.Data1==3)) && e->SubFormat.Data2==0 && e->SubFormat.Data3==0x10 && !memcmp(e->SubFormat.Data4,"\x80\0\0\xaa\0\x38\x9b\x71",8);}
 return FALSE;
}
static NTSTATUS attach(void *a){return STATUS_SUCCESS;}
static NTSTATUS detach(void *a){/* mmdevapi releases streams before unloading the Unix module */return streams?STATUS_UNSUCCESSFUL:STATUS_SUCCESS;}
/* Unix call dispatch must succeed even for unsupported optional features:
 * mmdevapi asserts on a nonzero NTSTATUS. Feature errors belong in params.
 * No MIDI endpoints are advertised; the notify thread exits immediately. */
static NTSTATUS midi_driver(void *a){((WCHAR *)a)[0]=0;return STATUS_SUCCESS;}
static NTSTATUS midi_init_empty(void *a){struct midi_init_params *p=a;*p->err=DRV_SUCCESS;return STATUS_SUCCESS;}
static NTSTATUS midi_out_empty(void *a){struct midi_out_message_params *p=a;memset(p->notify,0,sizeof(*p->notify));*p->err=p->msg==MODM_GETNUMDEVS?0:MMSYSERR_NOTSUPPORTED;return STATUS_SUCCESS;}
static NTSTATUS midi_in_empty(void *a){struct midi_in_message_params *p=a;memset(p->notify,0,sizeof(*p->notify));*p->err=p->msg==MIDM_GETNUMDEVS?0:MMSYSERR_NOTSUPPORTED;return STATUS_SUCCESS;}
static NTSTATUS midi_wait_empty(void *a){struct midi_notify_wait_params *p=a;*p->quit=TRUE;memset(p->notify,0,sizeof(*p->notify));return STATUS_SUCCESS;}
static NTSTATUS aux_empty(void *a){struct aux_message_params *p=a;*p->err=p->msg==AUXDM_GETNUMDEVS?0:MMSYSERR_NOTSUPPORTED;return STATUS_SUCCESS;}
static NTSTATUS connect_test(void *a){struct test_connect_params *p=a;struct reply r;int fd=connect_output(&r);p->priority=fd<0?Priority_Unavailable:Priority_Preferred;if(fd>=0)close(fd);return STATUS_SUCCESS;}
static NTSTATUS endpoints(void *a){
 struct get_endpoint_ids_params *p=a;static const WCHAR name[]={'F','r','o','s','t','B','o','x',' ','A','n','d','r','o','i','d',' ','P','C','M',0};static const char device[]="android-pcm";unsigned needed=sizeof(struct endpoint)+sizeof(name)+sizeof(device);struct reply r;int fd;
 p->num=0;p->default_idx=0;p->result=S_OK;if(p->flow!=eRender)return STATUS_SUCCESS;
 fd=connect_output(&r);if(fd<0){p->result=AUDCLNT_E_DEVICE_INVALIDATED;return STATUS_SUCCESS;}close(fd);
 p->num=1;if(p->size<needed){p->size=needed;p->result=HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);return STATUS_SUCCESS;}
 p->endpoints[0].name=sizeof(struct endpoint);p->endpoints[0].device=sizeof(struct endpoint)+sizeof(name);memcpy((char*)p->endpoints+sizeof(struct endpoint),name,sizeof(name));memcpy((char*)p->endpoints+p->endpoints[0].device,device,sizeof(device));return STATUS_SUCCESS;
}
static NTSTATUS create(void *a){
 struct create_stream_params *p=a;struct stream *s;p->result=AUDCLNT_E_UNSUPPORTED_FORMAT;
 fprintf(stderr,"FROST_AUDIO_CREATE flow=%u share=%u flags=%08x rate=%u channels=%u bits=%u\n",p->flow,p->share,(unsigned)p->flags,p->fmt?p->fmt->nSamplesPerSec:0,p->fmt?p->fmt->nChannels:0,p->fmt?p->fmt->wBitsPerSample:0);
 if(p->flow!=eRender||p->share!=AUDCLNT_SHAREMODE_SHARED||p->flags||!format_supported(p->fmt))return STATUS_SUCCESS;
 s=calloc(1,sizeof(*s));if(!s){p->result=E_OUTOFMEMORY;return STATUS_SUCCESS;}s->fd=connect_output(&s->state);
 if(s->fd<0){free(s);p->result=AUDCLNT_E_DEVICE_INVALIDATED;return STATUS_SUCCESS;}
 s->frames=s->state.capacity;s->block=p->fmt->nBlockAlign;s->bits=p->fmt->wBitsPerSample;s->gain[0]=s->gain[1]=1;
 s->buffer=calloc(s->frames,s->block);if(!s->buffer){close(s->fd);free(s);p->result=E_OUTOFMEMORY;return STATUS_SUCCESS;}
 pthread_mutex_init(&s->lock,NULL);pthread_mutex_lock(&streams_lock);s->next=streams;streams=s;pthread_mutex_unlock(&streams_lock);
 *p->channel_count=2;*p->stream=(UINT_PTR)s;p->result=S_OK;return STATUS_SUCCESS;
}
static NTSTATUS release(void *a){struct release_stream_params *p=a;struct stream **link,*s;pthread_mutex_lock(&streams_lock);for(link=&streams;*link&&((UINT_PTR)*link)!=p->stream;link=&(*link)->next) { }s=*link;if(!s){pthread_mutex_unlock(&streams_lock);p->result=AUDCLNT_E_DEVICE_INVALIDATED;return STATUS_SUCCESS;}pthread_mutex_lock(&s->lock);*link=s->next;pthread_mutex_unlock(&streams_lock);shutdown(s->fd,SHUT_RDWR);close(s->fd);free(s->buffer);pthread_mutex_unlock(&s->lock);pthread_mutex_destroy(&s->lock);free(s);p->result=S_OK;return STATUS_SUCCESS;}
static NTSTATUS start_stream(void *a){struct start_params *p=a;struct stream *s=acquire(p->stream);if(!s)return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);if(s->playing)return done(s,&p->result,AUDCLNT_E_NOT_STOPPED);if(!command(s->fd,2,0,NULL,&s->state)){s->failed=TRUE;return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);}s->playing=TRUE;return done(s,&p->result,S_OK);}
static NTSTATUS stop_stream(void *a){struct stop_params *p=a;struct stream *s=acquire(p->stream);if(!s)return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);if(!s->playing)return done(s,&p->result,S_FALSE);s->playing=FALSE;if(!command(s->fd,4,0,NULL,&s->state)){s->failed=TRUE;return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);}return done(s,&p->result,S_OK);}
static NTSTATUS reset_stream(void *a){struct reset_params *p=a;struct stream *s=acquire(p->stream);if(!s)return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);if(s->playing)return done(s,&p->result,AUDCLNT_E_NOT_STOPPED);if(s->locked_frames)return done(s,&p->result,AUDCLNT_E_BUFFER_OPERATION_PENDING);return done(s,&p->result,command(s->fd,5,0,NULL,&s->state)?S_OK:AUDCLNT_E_DEVICE_INVALIDATED);}
static NTSTATUS render_get(void *a){struct get_render_buffer_params *p=a;struct stream *s=acquire(p->stream);HRESULT hr;if(!s)return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);if(s->locked_frames)return done(s,&p->result,AUDCLNT_E_OUT_OF_ORDER);if(!p->frames){*p->data=NULL;return done(s,&p->result,S_OK);}if(FAILED(hr=query(s)))return done(s,&p->result,hr);if(p->frames>s->frames-s->state.padding)return done(s,&p->result,AUDCLNT_E_BUFFER_TOO_LARGE);s->locked_frames=p->frames;memset(s->buffer,0,p->frames*s->block);*p->data=s->buffer;return done(s,&p->result,S_OK);}
static NTSTATUS render_release(void *a){struct release_render_buffer_params *p=a;struct stream *s=acquire(p->stream);short pcm[MAX_FRAMES*2];unsigned i;if(!s)return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);if(p->written_frames>s->locked_frames)return done(s,&p->result,AUDCLNT_E_INVALID_SIZE);if(!s->locked_frames)return done(s,&p->result,p->written_frames?AUDCLNT_E_OUT_OF_ORDER:S_OK);if(p->flags & ~AUDCLNT_BUFFERFLAGS_SILENT)return done(s,&p->result,E_INVALIDARG);
 for(i=0;i<p->written_frames*2;i++){float f=(p->flags&AUDCLNT_BUFFERFLAGS_SILENT)?0:s->bits==32?((float*)s->buffer)[i]:((short*)s->buffer)[i]/32768.f;f*=s->gain[i&1];if(f!=f)f=0;if(f>1)f=1;if(f<-1)f=-1;float scaled=f*32768;pcm[i]=scaled>=32767?32767:scaled<=-32768?-32768:(short)scaled;}
 s->locked_frames=0;if(p->written_frames&&!command(s->fd,3,p->written_frames,pcm,&s->state)){s->failed=TRUE;return done(s,&p->result,AUDCLNT_E_DEVICE_INVALIDATED);}return done(s,&p->result,S_OK);
}
static NTSTATUS supported(void *a){struct is_format_supported_params *p=a;p->result=p->flow==eRender&&p->share==AUDCLNT_SHAREMODE_SHARED&&format_supported(p->fmt_in)?S_OK:AUDCLNT_E_UNSUPPORTED_FORMAT;return STATUS_SUCCESS;}
static NTSTATUS mix(void *a){struct get_mix_format_params *p=a;static const GUID subtype={3,0,0x10,{0x80,0,0,0xaa,0,0x38,0x9b,0x71}};memset(p->fmt,0,sizeof(*p->fmt));p->fmt->Format=(WAVEFORMATEX){WAVE_FORMAT_EXTENSIBLE,2,RATE,RATE*8,8,32,22};p->fmt->Samples.wValidBitsPerSample=32;p->fmt->dwChannelMask=3;p->fmt->SubFormat=subtype;p->result=p->flow==eRender?S_OK:AUDCLNT_E_WRONG_ENDPOINT_TYPE;return STATUS_SUCCESS;}
static NTSTATUS period(void *a){struct get_device_period_params *p=a;if(p->def_period)*p->def_period=100000;if(p->min_period)*p->min_period=100000;p->result=S_OK;return STATUS_SUCCESS;}
static NTSTATUS size_get(void *a){struct get_buffer_size_params *p=a;struct stream *s=acquire(p->stream);if(s)*p->frames=s->frames;return done(s,&p->result,s?S_OK:AUDCLNT_E_DEVICE_INVALIDATED);}
static NTSTATUS latency(void *a){struct get_latency_params *p=a;struct stream *s=acquire(p->stream);if(s)*p->latency=(REFERENCE_TIME)s->frames*10000000/RATE;return done(s,&p->result,s?S_OK:AUDCLNT_E_DEVICE_INVALIDATED);}
static NTSTATUS padding(void *a){struct get_current_padding_params *p=a;struct stream *s=acquire(p->stream);HRESULT hr=s?query(s):AUDCLNT_E_DEVICE_INVALIDATED;if(s&&SUCCEEDED(hr))*p->padding=s->state.padding;return done(s,&p->result,hr);}
static NTSTATUS frequency(void *a){struct get_frequency_params *p=a;struct stream *s=acquire(p->stream);if(s)*p->freq=RATE*s->block;return done(s,&p->result,s?S_OK:AUDCLNT_E_DEVICE_INVALIDATED);}
static NTSTATUS position(void *a){struct get_position_params *p=a;struct stream *s=acquire(p->stream);LARGE_INTEGER now,freq;HRESULT hr=s?query(s):AUDCLNT_E_DEVICE_INVALIDATED;if(s&&SUCCEEDED(hr)){*p->pos=(UINT64)s->state.played_lo|((UINT64)s->state.played_hi<<32);if(!p->device)*p->pos*=s->block;NtQueryPerformanceCounter(&now,&freq);if(p->qpctime)*p->qpctime=(now.QuadPart/freq.QuadPart)*10000000+(now.QuadPart%freq.QuadPart)*10000000/freq.QuadPart;}return done(s,&p->result,hr);}
static NTSTATUS volumes(void *a){struct set_volumes_params *p=a;struct stream *s=acquire(p->stream);if(s){for(unsigned i=0;i<2;i++){float v=p->master_volume*(p->volumes?p->volumes[i]:1)*(p->session_volumes?p->session_volumes[i]:1);s->gain[i]=v>=0&&v<=1?v:0;}pthread_mutex_unlock(&s->lock);}return STATUS_SUCCESS;}
static NTSTATUS event(void *a){struct set_event_handle_params *p=a;p->result=AUDCLNT_E_EVENTHANDLE_NOT_EXPECTED;return STATUS_SUCCESS;}
static NTSTATUS started(void *a){struct is_started_params *p=a;struct stream *s=acquire(p->stream);p->result=s&&s->playing?S_OK:S_FALSE;if(s)pthread_mutex_unlock(&s->lock);return STATUS_SUCCESS;}
static NTSTATUS prop(void *a){struct get_prop_value_params *p=a;p->result=E_NOTIMPL;return STATUS_SUCCESS;}
static NTSTATUS capture_get(void *a){struct get_capture_buffer_params *p=a;p->result=AUDCLNT_E_WRONG_ENDPOINT_TYPE;return STATUS_SUCCESS;}
static NTSTATUS capture_release(void *a){struct release_capture_buffer_params *p=a;p->result=AUDCLNT_E_WRONG_ENDPOINT_TYPE;return STATUS_SUCCESS;}
static NTSTATUS loopback(void *a){struct get_loopback_capture_device_params *p=a;p->result=E_NOTIMPL;return STATUS_SUCCESS;}
static NTSTATUS next_packet(void *a){struct get_next_packet_size_params *p=a;p->result=AUDCLNT_E_WRONG_ENDPOINT_TYPE;return STATUS_SUCCESS;}
static NTSTATUS sample_rate(void *a){struct set_sample_rate_params *p=a;p->result=E_NOTIMPL;return STATUS_SUCCESS;}
const unixlib_entry_t __wine_unix_call_funcs[funcs_count]={attach,detach,attach,attach,endpoints,create,release,start_stream,stop_stream,reset_stream,render_get,render_release,capture_get,capture_release,supported,loopback,mix,period,size_get,latency,padding,next_packet,frequency,position,volumes,event,sample_rate,connect_test,started,prop,midi_driver,midi_init_empty,attach,midi_out_empty,midi_in_empty,midi_wait_empty,aux_empty};
C_ASSERT(ARRAYSIZE(__wine_unix_call_funcs)==funcs_count);
