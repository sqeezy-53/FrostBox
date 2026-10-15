/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Private WoW64 extension of FrostBox's existing PCM driver.
 * Parameter thunk bodies below are adapted from Wine 11.19 wineoss.drv/oss.c
 * (Wine project contributors, LGPL-2.1-or-later). No OSS device access.
 * Qualified x64 frost_audio.c/module stays unchanged.
 */
#include "config.h"
#include <stdint.h>
#include <stdlib.h>
#include "ntstatus.h"
#include "winternl.h"
static ULONG_PTR zero_bits;
/* Only this module's stream allocations use the Wine low-address allocator.
 * The pointer returned to a 32-bit GetBuffer caller must not be truncated. */
static void *wow_calloc(size_t n,size_t size) {
    void *base=NULL;SIZE_T bytes;
    if(!n || size>(SIZE_MAX-16)/n)return NULL;
    bytes=n*size+16;
    if(NtAllocateVirtualMemory(NtCurrentProcess(),&base,zero_bits,&bytes,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE))return NULL;
    *(SIZE_T *)base=bytes;
    return (char *)base+16;
}
static void wow_free(void *ptr) {
    SIZE_T bytes=0;void *base;
    if(!ptr)return;
    base=(char *)ptr-16;NtFreeVirtualMemory(NtCurrentProcess(),&base,&bytes,MEM_RELEASE);
}
#define calloc wow_calloc
#define free wow_free
#include "frost_audio.c"
#undef calloc
#undef free
#define FIXME(...) ((void)0)
typedef UINT PTR32;

static NTSTATUS oss_wow64_process_attach(void *args)
{
    SYSTEM_BASIC_INFORMATION info;

    if(NtQuerySystemInformation(SystemEmulationBasicInformation, &info, sizeof(info), NULL))return STATUS_UNSUCCESSFUL;
    zero_bits = (ULONG_PTR)info.HighestUserAddress | 0x7fffffff;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_test_connect(void *args)
{
    struct
    {
        PTR32 name;
        enum driver_priority priority;
    } *params32 = args;
    struct test_connect_params params =
    {
        .name = ULongToPtr(params32->name),
    };
    connect_test(&params);
    params32->priority = params.priority;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_endpoint_ids(void *args)
{
    struct
    {
        EDataFlow flow;
        PTR32 endpoints;
        unsigned int size;
        HRESULT result;
        unsigned int num;
        unsigned int default_idx;
    } *params32 = args;
    struct get_endpoint_ids_params params =
    {
        .flow = params32->flow,
        .endpoints = ULongToPtr(params32->endpoints),
        .size = params32->size
    };
    endpoints(&params);
    params32->size = params.size;
    params32->result = params.result;
    params32->num = params.num;
    params32->default_idx = params.default_idx;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_create_stream(void *args)
{
    struct
    {
        PTR32 name;
        PTR32 device;
        EDataFlow flow;
        AUDCLNT_SHAREMODE share;
        UINT flags;
        REFERENCE_TIME duration;
        REFERENCE_TIME period;
        PTR32 fmt;
        HRESULT result;
        PTR32 channel_count;
        PTR32 stream;
    } *params32 = args;
    struct create_stream_params params =
    {
        .name = ULongToPtr(params32->name),
        .device = ULongToPtr(params32->device),
        .flow = params32->flow,
        .share = params32->share,
        .flags = params32->flags,
        .duration = params32->duration,
        .period = params32->period,
        .fmt = ULongToPtr(params32->fmt),
        .channel_count = ULongToPtr(params32->channel_count),
        .stream = ULongToPtr(params32->stream)
    };
    create(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_release_stream(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
    } *params32 = args;
    struct release_stream_params params =
    {
        .stream = params32->stream,
    };
    release(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_render_buffer(void *args)
{
    struct
    {
        stream_handle stream;
        UINT32 frames;
        HRESULT result;
        PTR32 data;
    } *params32 = args;
    BYTE *data = NULL;
    struct get_render_buffer_params params =
    {
        .stream = params32->stream,
        .frames = params32->frames,
        .data = &data
    };
    render_get(&params);
    params32->result = params.result;
    *(unsigned int *)ULongToPtr(params32->data) = PtrToUlong(data);
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_capture_buffer(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
        PTR32 data;
        PTR32 frames;
        PTR32 flags;
        PTR32 devpos;
        PTR32 qpcpos;
    } *params32 = args;
    BYTE *data = NULL;
    struct get_capture_buffer_params params =
    {
        .stream = params32->stream,
        .data = &data,
        .frames = ULongToPtr(params32->frames),
        .flags = ULongToPtr(params32->flags),
        .devpos = ULongToPtr(params32->devpos),
        .qpcpos = ULongToPtr(params32->qpcpos)
    };
    capture_get(&params);
    params32->result = params.result;
    *(unsigned int *)ULongToPtr(params32->data) = PtrToUlong(data);
    return STATUS_SUCCESS;
};

static NTSTATUS oss_wow64_is_format_supported(void *args)
{
    struct
    {
        PTR32 device;
        EDataFlow flow;
        AUDCLNT_SHAREMODE share;
        PTR32 fmt_in;
        HRESULT result;
    } *params32 = args;
    struct is_format_supported_params params =
    {
        .device = ULongToPtr(params32->device),
        .flow = params32->flow,
        .share = params32->share,
        .fmt_in = ULongToPtr(params32->fmt_in),
    };
    supported(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_mix_format(void *args)
{
    struct
    {
        PTR32 device;
        EDataFlow flow;
        PTR32 fmt;
        HRESULT result;
    } *params32 = args;
    struct get_mix_format_params params =
    {
        .device = ULongToPtr(params32->device),
        .flow = params32->flow,
        .fmt = ULongToPtr(params32->fmt)
    };
    mix(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_device_period(void *args)
{
    struct
    {
        PTR32 device;
        EDataFlow flow;
        HRESULT result;
        PTR32 def_period;
        PTR32 min_period;
    } *params32 = args;
    struct get_device_period_params params =
    {
        .device = ULongToPtr(params32->device),
        .flow = params32->flow,
        .def_period = ULongToPtr(params32->def_period),
        .min_period = ULongToPtr(params32->min_period),
    };
    period(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_buffer_size(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
        PTR32 frames;
    } *params32 = args;
    struct get_buffer_size_params params =
    {
        .stream = params32->stream,
        .frames = ULongToPtr(params32->frames)
    };
    size_get(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_latency(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
        PTR32 latency;
    } *params32 = args;
    struct get_latency_params params =
    {
        .stream = params32->stream,
        .latency = ULongToPtr(params32->latency)
    };
    latency(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_current_padding(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
        PTR32 padding;
    } *params32 = args;
    struct get_current_padding_params params =
    {
        .stream = params32->stream,
        .padding = ULongToPtr(params32->padding)
    };
    padding(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_next_packet_size(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
        PTR32 frames;
    } *params32 = args;
    struct get_next_packet_size_params params =
    {
        .stream = params32->stream,
        .frames = ULongToPtr(params32->frames)
    };
    next_packet(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_frequency(void *args)
{
    struct
    {
        stream_handle stream;
        HRESULT result;
        PTR32 freq;
    } *params32 = args;
    struct get_frequency_params params =
    {
        .stream = params32->stream,
        .freq = ULongToPtr(params32->freq)
    };
    frequency(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_position(void *args)
{
    struct
    {
        stream_handle stream;
        BOOL device;
        HRESULT result;
        PTR32 pos;
        PTR32 qpctime;
    } *params32 = args;
    struct get_position_params params =
    {
        .stream = params32->stream,
        .device = params32->device,
        .pos = ULongToPtr(params32->pos),
        .qpctime = ULongToPtr(params32->qpctime)
    };
    position(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_set_volumes(void *args)
{
    struct
    {
        stream_handle stream;
        float master_volume;
        PTR32 volumes;
        PTR32 session_volumes;
    } *params32 = args;
    struct set_volumes_params params =
    {
        .stream = params32->stream,
        .master_volume = params32->master_volume,
        .volumes = ULongToPtr(params32->volumes),
        .session_volumes = ULongToPtr(params32->session_volumes),
    };
    return volumes(&params);
}

static NTSTATUS oss_wow64_set_event_handle(void *args)
{
    struct
    {
        stream_handle stream;
        PTR32 event;
        HRESULT result;
    } *params32 = args;
    struct set_event_handle_params params =
    {
        .stream = params32->stream,
        .event = ULongToHandle(params32->event)
    };

    event(&params);
    params32->result = params.result;
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_get_prop_value(void *args)
{
    struct propvariant32
    {
        WORD vt;
        WORD pad1, pad2, pad3;
        union
        {
            ULONG ulVal;
            PTR32 ptr;
            ULARGE_INTEGER uhVal;
        };
    } *value32;
    struct
    {
        PTR32 device;
        EDataFlow flow;
        PTR32 guid;
        PTR32 prop;
        HRESULT result;
        PTR32 value;
        PTR32 buffer; /* caller allocated buffer to hold value's strings */
        PTR32 buffer_size;
    } *params32 = args;
    PROPVARIANT value;
    struct get_prop_value_params params =
    {
        .device = ULongToPtr(params32->device),
        .flow = params32->flow,
        .guid = ULongToPtr(params32->guid),
        .prop = ULongToPtr(params32->prop),
        .value = &value,
        .buffer = ULongToPtr(params32->buffer),
        .buffer_size = ULongToPtr(params32->buffer_size)
    };
    prop(&params);
    params32->result = params.result;
    if (SUCCEEDED(params.result))
    {
        value32 = UlongToPtr(params32->value);
        value32->vt = value.vt;
        switch (value.vt)
        {
        case VT_UI4:
            value32->ulVal = value.ulVal;
            break;
        case VT_LPWSTR:
            value32->ptr = params32->buffer;
            break;
        default:
            FIXME("Unhandled vt %04x\n", value.vt);
        }
    }
    return STATUS_SUCCESS;
}

static NTSTATUS oss_wow64_aux_message(void *args)
{
    struct
    {
        UINT dev_id;
        UINT msg;
        UINT user;
        UINT param_1;
        UINT param_2;
        PTR32 err;
    } *params32 = args;
    struct aux_message_params params =
    {
        .dev_id = params32->dev_id,
        .msg = params32->msg,
        .user = params32->user,
        .param_1 = params32->param_1,
        .param_2 = params32->param_2,
        .err = ULongToPtr(params32->err),
    };
    return aux_empty(&params);
}


/* No MIDI/capture endpoints are advertised. Empty MIDI notifications use only
 * their ABI-stable first BOOL and preserve the caller's 32-bit pointer sizes. */
static NTSTATUS wow_loopback(void *args){struct{PTR32 name,device,ret_device;UINT32 ret_device_len;HRESULT result;} *p=args;p->result=E_NOTIMPL;return STATUS_SUCCESS;}
static NTSTATUS wow_sample_rate(void *args){struct{stream_handle stream;float rate;HRESULT result;} *p=args;p->result=E_NOTIMPL;return STATUS_SUCCESS;}
static NTSTATUS wow_midi_init(void *args){*(UINT *)ULongToPtr(*(PTR32 *)args)=DRV_SUCCESS;return STATUS_SUCCESS;}
static NTSTATUS wow_midi_out(void *args){struct{UINT dev_id,msg,user,p1,p2;PTR32 err,notify;} *p=args;*(BOOL *)ULongToPtr(p->notify)=FALSE;*(UINT *)ULongToPtr(p->err)=p->msg==MODM_GETNUMDEVS?0:MMSYSERR_NOTSUPPORTED;return STATUS_SUCCESS;}
static NTSTATUS wow_midi_in(void *args){struct{UINT dev_id,msg,user,p1,p2;PTR32 err,notify;} *p=args;*(BOOL *)ULongToPtr(p->notify)=FALSE;*(UINT *)ULongToPtr(p->err)=p->msg==MIDM_GETNUMDEVS?0:MMSYSERR_NOTSUPPORTED;return STATUS_SUCCESS;}
static NTSTATUS wow_midi_wait(void *args){struct{PTR32 quit,notify;} *p=args;*(BOOL *)ULongToPtr(p->notify)=FALSE;*(BOOL *)ULongToPtr(p->quit)=TRUE;return STATUS_SUCCESS;}
const unixlib_entry_t __wine_unix_call_wow64_funcs[funcs_count]={
oss_wow64_process_attach,detach,attach,attach,oss_wow64_get_endpoint_ids,oss_wow64_create_stream,oss_wow64_release_stream,start_stream,stop_stream,reset_stream,oss_wow64_get_render_buffer,render_release,oss_wow64_get_capture_buffer,capture_release,oss_wow64_is_format_supported,wow_loopback,oss_wow64_get_mix_format,oss_wow64_get_device_period,oss_wow64_get_buffer_size,oss_wow64_get_latency,oss_wow64_get_current_padding,oss_wow64_get_next_packet_size,oss_wow64_get_frequency,oss_wow64_get_position,oss_wow64_set_volumes,oss_wow64_set_event_handle,wow_sample_rate,oss_wow64_test_connect,started,oss_wow64_get_prop_value,midi_driver,wow_midi_init,attach,wow_midi_out,wow_midi_in,wow_midi_wait,oss_wow64_aux_message};
C_ASSERT(ARRAYSIZE(__wine_unix_call_wow64_funcs)==funcs_count);

/* Original Wine notice for adapted parameter thunks: */
/*
 * OSS driver (unixlib)
 *
 * Copyright 2011 Andrew Eikum for CodeWeavers
 *           2022 Huw Davies
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */
