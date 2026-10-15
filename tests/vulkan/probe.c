// SPDX-License-Identifier: MIT
// The same shader/resources run as SysV x64/Bionic ELF or Win64 PE. No ARM64 rendering shortcut.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stddef.h>
#ifdef _WIN32
// Freestanding PE startup: no CRT floating-point initialization is needed.
int _fltused=0;
__declspec(dllimport) int printf(const char*,...);
__declspec(dllimport) int fflush(void*);
__declspec(dllimport) void* malloc(size_t);
__declspec(dllimport) void free(void*);
__declspec(dllimport) void* memcpy(void*,const void*,size_t);
__declspec(dllimport) void* memset(void*,int,size_t);
__declspec(dllimport) int strcmp(const char*,const char*);
__declspec(dllimport) char* strstr(const char*,const char*);
__declspec(dllimport) void* __stdcall LoadLibraryA(const char*);
__declspec(dllimport) void* __stdcall GetProcAddress(void*,const char*);
__declspec(dllimport) int __stdcall FreeLibrary(void*);
__declspec(dllimport) uint32_t __stdcall GetCurrentProcessId(void);
__declspec(dllimport) __declspec(noreturn) void __stdcall ExitProcess(uint32_t);
#define load_library() LoadLibraryA("vulkan-1.dll")
#define symbol(lib,name) GetProcAddress(lib,name)
#define close_library(lib) FreeLibrary(lib)
#ifndef NULL
#define NULL ((void*)0)
#endif
#else
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#define load_library() dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL)
#define symbol(lib,name) dlsym(lib,name)
#define close_library(lib) dlclose(lib)
#endif
#include "compute_spv.h"
#ifdef FROST_PROBE_X86
_Static_assert(sizeof(void*)==4 && sizeof(uintptr_t)==4,"The x86 probe requires 32-bit pointers");
#else
_Static_assert(sizeof(void*)==8 && sizeof(uintptr_t)==8,"The probe requires correct x64 pointer types");
#endif
#define FUNCTIONS(X) \
 X(vkGetInstanceProcAddr) X(vkEnumerateInstanceExtensionProperties) X(vkCreateInstance) \
 X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) \
 X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceMemoryProperties) \
 X(vkCreateDevice) X(vkGetDeviceProcAddr) X(vkDestroyDevice) X(vkGetDeviceQueue) \
 X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkAllocateMemory) \
 X(vkFreeMemory) X(vkBindBufferMemory) X(vkMapMemory) X(vkUnmapMemory) \
 X(vkFlushMappedMemoryRanges) X(vkInvalidateMappedMemoryRanges) \
 X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) \
 X(vkCreateDescriptorPool) X(vkDestroyDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
 X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) X(vkCreateShaderModule) X(vkDestroyShaderModule) \
 X(vkCreateComputePipelines) X(vkDestroyPipeline) X(vkCreateCommandPool) X(vkDestroyCommandPool) \
 X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) X(vkEndCommandBuffer) X(vkCmdBindPipeline) \
 X(vkCmdBindDescriptorSets) X(vkCmdDispatch) X(vkCmdPipelineBarrier) \
 X(vkCreateFence) X(vkDestroyFence) X(vkQueueSubmit) X(vkWaitForFences) X(vkDeviceWaitIdle) \
 X(vkCreateDebugUtilsMessengerEXT) X(vkSubmitDebugUtilsMessageEXT) X(vkDestroyDebugUtilsMessengerEXT)
#define DECLARE(name) static PFN_##name name;
FUNCTIONS(DECLARE)
#undef DECLARE
#define GLOBAL(name) do { name=(PFN_##name)vkGetInstanceProcAddr(VK_NULL_HANDLE,#name);if(!name){printf("MISSING_GLOBAL=%s\n",#name);goto cleanup;}printf("GLOBAL=%s\n",#name); }while(0)
#define INSTANCE(name) do { name=(PFN_##name)vkGetInstanceProcAddr(instance,#name);if(!name){printf("MISSING_INSTANCE=%s\n",#name);goto cleanup;}printf("INSTANCE=%s\n",#name); }while(0)
#define DEVICE(name) do { name=(PFN_##name)vkGetDeviceProcAddr(device,#name);if(!name){printf("MISSING_DEVICE=%s\n",#name);goto cleanup;}printf("DEVICE=%s\n",#name); }while(0)
#define CHECK(call) do { VkResult r=(call);printf("CALL=%s RESULT=%d\n",#call,(int)r);fflush(NULL);if(r!=VK_SUCCESS)goto cleanup;}while(0)
typedef struct { unsigned allocations,frees,debug; } Callbacks;
typedef struct { void* base;size_t size; } Header;
static void* VKAPI_PTR allocate(void* user,size_t size,size_t alignment,VkSystemAllocationScope scope) {
    (void)scope;if(alignment<sizeof(Header))alignment=sizeof(Header);
    if(!size || size>SIZE_MAX-alignment-sizeof(Header))return NULL;
    void* base=malloc(size+alignment+sizeof(Header));if(!base)return NULL;
    uintptr_t aligned=((uintptr_t)base+sizeof(Header)+alignment-1)&~(uintptr_t)(alignment-1);
    Header* h=(Header*)aligned-1;h->base=base;h->size=size;
    __atomic_fetch_add(&((Callbacks*)user)->allocations,1,__ATOMIC_RELAXED);return (void*)aligned;
}
static void VKAPI_PTR deallocate(void* user,void* ptr) {
    if(ptr){free(((Header*)ptr-1)->base);__atomic_fetch_add(&((Callbacks*)user)->frees,1,__ATOMIC_RELAXED);}
}
static void* VKAPI_PTR reallocate(void* user,void* old,size_t size,size_t alignment,VkSystemAllocationScope scope) {
    if(!size){deallocate(user,old);return NULL;}
    void* next=allocate(user,size,alignment,scope);
    if(next && old){size_t before=((Header*)old-1)->size;memcpy(next,old,before<size?before:size);deallocate(user,old);}return next;
}
static VkBool32 VKAPI_PTR debug_callback(VkDebugUtilsMessageSeverityFlagBitsEXT severity,VkDebugUtilsMessageTypeFlagsEXT type,const VkDebugUtilsMessengerCallbackDataEXT* data,void* user) {
    (void)severity;(void)type;
    if(data && data->pMessage && strstr(data->pMessage,"FROSTBOX_CALLBACK"))__atomic_fetch_add(&((Callbacks*)user)->debug,1,__ATOMIC_RELAXED);
    return VK_FALSE;
}
static int probe(int skip) {
    int result=11,verified=0;void* lib=NULL;uint32_t* values=NULL;
    VkInstance instance=VK_NULL_HANDLE;VkPhysicalDevice physical=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;VkQueue queue=VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger=VK_NULL_HANDLE;VkBuffer buffer=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout=VK_NULL_HANDLE;VkDescriptorPool descriptorPool=VK_NULL_HANDLE;VkPipelineLayout layout=VK_NULL_HANDLE;
    VkShaderModule shader=VK_NULL_HANDLE;VkPipeline pipeline=VK_NULL_HANDLE;VkCommandPool pool=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;
    Callbacks callbacks={0};VkAllocationCallbacks allocator={&callbacks,allocate,reallocate,deallocate,NULL,NULL};
#ifdef _WIN32
    printf("FROSTBOX_PLATFORM=WINDOWS_X64 PROCESS_ID=%u\n",GetCurrentProcessId());
#else
    printf("FROSTBOX_PLATFORM=BIONIC_X86_64 UID=%u PID=%u\n",(unsigned)getuid(),(unsigned)getpid());
#endif
    lib=load_library();if(!lib){printf("FROSTBOX_LOADER_FAILED\n");
#ifndef _WIN32
        printf("DLERROR=%s\n",dlerror());
#endif
        goto cleanup;}
    printf("FROSTBOX_LOADER_OPENED\n");
    vkGetInstanceProcAddr=(PFN_vkGetInstanceProcAddr)symbol(lib,"vkGetInstanceProcAddr");if(!vkGetInstanceProcAddr)goto cleanup;
    GLOBAL(vkEnumerateInstanceExtensionProperties);GLOBAL(vkCreateInstance);
    static VkExtensionProperties exts[128];uint32_t count=128;CHECK(vkEnumerateInstanceExtensionProperties(NULL,&count,exts));
    int debug=0;for(uint32_t i=0;i<count;i++)if(!strcmp(exts[i].extensionName,"VK_EXT_debug_utils"))debug=1;
    printf("DEBUG_UTILS_AVAILABLE=%d\n",debug);
    const char* extNames[]={"VK_EXT_debug_utils"};
    VkApplicationInfo app={VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="FrostBox x64 compute";app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&app;ici.enabledExtensionCount=debug?1:0;ici.ppEnabledExtensionNames=debug?extNames:NULL;
    CHECK(vkCreateInstance(&ici,&allocator,&instance));printf("FROSTBOX_INSTANCE_CREATED\n");
    INSTANCE(vkDestroyInstance);INSTANCE(vkEnumeratePhysicalDevices);INSTANCE(vkGetPhysicalDeviceProperties);
    INSTANCE(vkGetPhysicalDeviceQueueFamilyProperties);INSTANCE(vkGetPhysicalDeviceMemoryProperties);INSTANCE(vkCreateDevice);INSTANCE(vkGetDeviceProcAddr);
    if(debug) {
        INSTANCE(vkCreateDebugUtilsMessengerEXT);INSTANCE(vkSubmitDebugUtilsMessageEXT);INSTANCE(vkDestroyDebugUtilsMessengerEXT);
        VkDebugUtilsMessengerCreateInfoEXT m={VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};m.messageSeverity=VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;m.messageType=VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;m.pfnUserCallback=debug_callback;m.pUserData=&callbacks;
        CHECK(vkCreateDebugUtilsMessengerEXT(instance,&m,&allocator,&messenger));
        VkDebugUtilsMessengerCallbackDataEXT data={VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CALLBACK_DATA_EXT};data.pMessage="FROSTBOX_CALLBACK";
        vkSubmitDebugUtilsMessageEXT(instance,VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT,VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT,&data);
        printf("FROSTBOX_DEBUG_CALLBACKS=%u\n",callbacks.debug);if(!callbacks.debug)goto cleanup;
    }
    VkPhysicalDevice devices[8];count=8;CHECK(vkEnumeratePhysicalDevices(instance,&count,devices));
    uint32_t family=UINT32_MAX;
    for(uint32_t i=0;i<count;i++) {
        VkPhysicalDeviceProperties props;vkGetPhysicalDeviceProperties(devices[i],&props);
        printf("GPU=%s VENDOR=%u TYPE=%u API=%u.%u.%u\n",props.deviceName,props.vendorID,props.deviceType,VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion));
        if(props.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU)continue;
        VkQueueFamilyProperties families[16];uint32_t n=16;vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&n,families);if(n>16)goto cleanup;
        for(uint32_t j=0;j<n;j++) {printf("QUEUE=%u FLAGS=%u COUNT=%u\n",j,families[j].queueFlags,families[j].queueCount);if(family==UINT32_MAX && families[j].queueCount && (families[j].queueFlags&VK_QUEUE_COMPUTE_BIT)) {family=j;physical=devices[i];}}
        if(physical)break;
    }
    if(!physical)goto cleanup;
    float priority=1;VkDeviceQueueCreateInfo qi={VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=family;qi.queueCount=1;qi.pQueuePriorities=&priority;
    VkDeviceCreateInfo di={VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;
    CHECK(vkCreateDevice(physical,&di,&allocator,&device));printf("FROSTBOX_DEVICE_CREATED\n");
    DEVICE(vkDestroyDevice);DEVICE(vkGetDeviceQueue);DEVICE(vkCreateBuffer);DEVICE(vkDestroyBuffer);DEVICE(vkGetBufferMemoryRequirements);
    DEVICE(vkAllocateMemory);DEVICE(vkFreeMemory);DEVICE(vkBindBufferMemory);DEVICE(vkMapMemory);DEVICE(vkUnmapMemory);
    DEVICE(vkFlushMappedMemoryRanges);DEVICE(vkInvalidateMappedMemoryRanges);DEVICE(vkCreateDescriptorSetLayout);DEVICE(vkDestroyDescriptorSetLayout);
    DEVICE(vkCreateDescriptorPool);DEVICE(vkDestroyDescriptorPool);DEVICE(vkAllocateDescriptorSets);DEVICE(vkUpdateDescriptorSets);
    DEVICE(vkCreatePipelineLayout);DEVICE(vkDestroyPipelineLayout);DEVICE(vkCreateShaderModule);DEVICE(vkDestroyShaderModule);
    DEVICE(vkCreateComputePipelines);DEVICE(vkDestroyPipeline);DEVICE(vkCreateCommandPool);DEVICE(vkDestroyCommandPool);
    DEVICE(vkAllocateCommandBuffers);DEVICE(vkBeginCommandBuffer);DEVICE(vkEndCommandBuffer);DEVICE(vkCmdBindPipeline);
    DEVICE(vkCmdBindDescriptorSets);DEVICE(vkCmdDispatch);DEVICE(vkCmdPipelineBarrier);DEVICE(vkCreateFence);DEVICE(vkDestroyFence);
    DEVICE(vkQueueSubmit);DEVICE(vkWaitForFences);DEVICE(vkDeviceWaitIdle);
    vkGetDeviceQueue(device,family,0,&queue);if(!queue)goto cleanup;
    VkBufferCreateInfo bc={VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bc.size=100*sizeof(uint32_t);bc.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;bc.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    CHECK(vkCreateBuffer(device,&bc,&allocator,&buffer));VkMemoryRequirements requirements;vkGetBufferMemoryRequirements(device,buffer,&requirements);
    VkPhysicalDeviceMemoryProperties mem;vkGetPhysicalDeviceMemoryProperties(physical,&mem);uint32_t mt=UINT32_MAX;
    for(uint32_t i=0;i<mem.memoryTypeCount;i++)if((requirements.memoryTypeBits&(1u<<i)) && (mem.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)){mt=i;if(mem.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)break;}
    if(mt==UINT32_MAX)goto cleanup;
    VkMemoryAllocateInfo ma={VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ma.allocationSize=requirements.size;ma.memoryTypeIndex=mt;
    CHECK(vkAllocateMemory(device,&ma,&allocator,&memory));CHECK(vkBindBufferMemory(device,buffer,memory,0));CHECK(vkMapMemory(device,memory,0,VK_WHOLE_SIZE,0,(void**)&values));
    for(uint32_t i=0;i<100;i++)values[i]=i+1;
    int coherent=(mem.memoryTypes[mt].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0;
    VkMappedMemoryRange range={VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};range.memory=memory;range.size=VK_WHOLE_SIZE;
    if(!coherent)CHECK(vkFlushMappedMemoryRanges(device,1,&range));
    VkDescriptorSetLayoutBinding binding={0,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,NULL};
    VkDescriptorSetLayoutCreateInfo sl={VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};sl.bindingCount=1;sl.pBindings=&binding;CHECK(vkCreateDescriptorSetLayout(device,&sl,&allocator,&setLayout));
    VkDescriptorPoolSize poolSize={VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1};VkDescriptorPoolCreateInfo dp={VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dp.maxSets=1;dp.poolSizeCount=1;dp.pPoolSizes=&poolSize;CHECK(vkCreateDescriptorPool(device,&dp,&allocator,&descriptorPool));
    VkDescriptorSet set;VkDescriptorSetAllocateInfo da={VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};da.descriptorPool=descriptorPool;da.descriptorSetCount=1;da.pSetLayouts=&setLayout;CHECK(vkAllocateDescriptorSets(device,&da,&set));
    VkDescriptorBufferInfo db={buffer,0,100*sizeof(uint32_t)};VkWriteDescriptorSet write={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};write.dstSet=set;write.descriptorCount=1;write.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;write.pBufferInfo=&db;vkUpdateDescriptorSets(device,1,&write,0,NULL);
    VkPipelineLayoutCreateInfo pl={VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pl.setLayoutCount=1;pl.pSetLayouts=&setLayout;CHECK(vkCreatePipelineLayout(device,&pl,&allocator,&layout));
    VkShaderModuleCreateInfo sm={VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sm.codeSize=sizeof(compute_spv);sm.pCode=compute_spv;CHECK(vkCreateShaderModule(device,&sm,&allocator,&shader));
    VkComputePipelineCreateInfo cp={VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};cp.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;cp.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;cp.stage.module=shader;cp.stage.pName="main";cp.layout=layout;
    CHECK(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&cp,&allocator,&pipeline));
    VkCommandPoolCreateInfo pc={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pc.queueFamilyIndex=family;CHECK(vkCreateCommandPool(device,&pc,&allocator,&pool));
    VkCommandBuffer command;VkCommandBufferAllocateInfo ca={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;CHECK(vkAllocateCommandBuffers(device,&ca,&command));
    VkCommandBufferBeginInfo begin={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;CHECK(vkBeginCommandBuffer(command,&begin));
    vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,NULL);
    if(!skip)vkCmdDispatch(command,2,1,1);
    VkMemoryBarrier barrier={VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,NULL,0,NULL);CHECK(vkEndCommandBuffer(command));
    VkFenceCreateInfo fc={VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};CHECK(vkCreateFence(device,&fc,&allocator,&fence));
    VkSubmitInfo si={VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&command;
    CHECK(vkQueueSubmit(queue,1,&si,fence));CHECK(vkWaitForFences(device,1,&fence,VK_TRUE,10000000000ull));
    printf("FROSTBOX_GPU_SUBMISSION_COMPLETED SKIP_DISPATCH=%d\n",skip);
    if(!coherent)CHECK(vkInvalidateMappedMemoryRanges(device,1,&range));
    uint32_t sum=0;for(uint32_t i=0;i<100;i++){sum+=values[i];if(values[i]!=2*(i+1)+7){printf("MISMATCH INDEX=%u ACTUAL=%u EXPECTED=%u\n",i,values[i],2*(i+1)+7);result=skip?43:12;goto cleanup;}}
    printf("FROSTBOX_VULKAN_RESULT=%u\n",sum);verified=sum==10800;
cleanup:
    if(device && vkDeviceWaitIdle)vkDeviceWaitIdle(device);
    if(device && fence && vkDestroyFence)vkDestroyFence(device,fence,&allocator);
    if(device && pool && vkDestroyCommandPool)vkDestroyCommandPool(device,pool,&allocator);
    if(device && pipeline && vkDestroyPipeline)vkDestroyPipeline(device,pipeline,&allocator);
    if(device && shader && vkDestroyShaderModule)vkDestroyShaderModule(device,shader,&allocator);
    if(device && layout && vkDestroyPipelineLayout)vkDestroyPipelineLayout(device,layout,&allocator);
    if(device && descriptorPool && vkDestroyDescriptorPool)vkDestroyDescriptorPool(device,descriptorPool,&allocator);
    if(device && setLayout && vkDestroyDescriptorSetLayout)vkDestroyDescriptorSetLayout(device,setLayout,&allocator);
    if(device && values && vkUnmapMemory)vkUnmapMemory(device,memory);
    if(device && buffer && vkDestroyBuffer)vkDestroyBuffer(device,buffer,&allocator);
    if(device && memory && vkFreeMemory)vkFreeMemory(device,memory,&allocator);
    if(device && vkDestroyDevice)vkDestroyDevice(device,&allocator);
    if(instance && messenger && vkDestroyDebugUtilsMessengerEXT)vkDestroyDebugUtilsMessengerEXT(instance,messenger,&allocator);
    if(instance && vkDestroyInstance)vkDestroyInstance(instance,&allocator);
    if(lib)close_library(lib);
    printf("FROSTBOX_ALLOCATIONS=%u FREES=%u DEBUG_CALLBACKS=%u\n",callbacks.allocations,callbacks.frees,callbacks.debug);
#ifdef _WIN32
    // Wine 11.19 explicitly passes NULL host allocators in its Vulkan thunks.
    // Compute/readback remains mandatory; allocator support is a separate ABI status.
    if(!callbacks.allocations)printf("FROSTBOX_WINE_ALLOCATION_CALLBACKS_NOT_EXERCISED\n");
    if(verified && callbacks.allocations==callbacks.frees)result=42;
#else
    if(verified && callbacks.allocations && callbacks.allocations==callbacks.frees)result=42;
#endif
    printf("FROSTBOX_CLEANUP_COMPLETE EXIT=%d\n",result);fflush(NULL);return result;
}
#ifdef _WIN32
void mainCRTStartup(void){ExitProcess((uint32_t)probe(0));}
#else
int main(int argc,char** argv){return probe(argc>1 && !strcmp(argv[1],"--skip-dispatch"));}
#endif
