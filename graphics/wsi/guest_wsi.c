// SPDX-License-Identifier: MIT
// Experimental guest-local WSI. Vulkan commands stay in Box64's native wrapper.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include "guest_protocol.h"
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <time.h>
#include <fcntl.h>
#include <sys/syscall.h>

#define DEADLINE 5000000000ull
#define MAX_IMAGES FROST_GUEST_MAX_IMAGES
static PFN_vkGetInstanceProcAddr host_gipa;
static PFN_vkGetDeviceProcAddr host_gdpa;
static void *loader;
static VkInstance instance;
static VkPhysicalDevice physical;
static VkDevice device;
static VkQueue queue;
static uint32_t family;
static int channel=-1;
static struct FrostGuestReply hello;
static uint32_t generation;
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t queue_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t image_available=PTHREAD_COND_INITIALIZER;
#define I(n) ((PFN_##n)host_gipa(instance,#n))
#define D(n) ((PFN_##n)host_gdpa(device,#n))
#define CHECK(call) do { VkResult r_=(call); if(r_!=VK_SUCCESS){fprintf(stderr,"FROST_GUEST_FAIL %s=%d\n",#call,r_);result=r_;goto fail;} } while(0)
struct Surface { uint64_t magic; };
static struct Surface *surface;
struct Swapchain {
    uint64_t magic;
    uint32_t count,width,height,next,generation,frame;
    VkImage images[MAX_IMAGES]; VkDeviceMemory memory[MAX_IMAGES];
    struct FrostWsiImage metadata[MAX_IMAGES];
    unsigned acquired[MAX_IMAGES],external[MAX_IMAGES],sent[MAX_IMAGES];
    VkCommandPool pool; VkCommandBuffer command; VkFence fence; VkSemaphore ready;
    int pending,broken;
};
static struct Swapchain *current;
/* Explicit QA only: bounded last-stage files survive stderr clipping. No GPU
 * synchronization or timeout behavior is changed. One small file per thread. */
static pthread_once_t trace_once=PTHREAD_ONCE_INIT;
static int trace_enabled;
static void trace_init(void) { const char *debug=getenv("WINEDEBUG");trace_enabled=debug&&strstr(debug,"+frostwsi"); }
static void trace_stage(const char *stage,const struct Swapchain *s,int detail) {
    pthread_once(&trace_once,trace_init);if(!trace_enabled)return;
    const char *directory=getenv("TMPDIR");if(!directory)return;
    char path[4096],text[384];long tid=syscall(SYS_gettid);struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);
    int p=snprintf(path,sizeof(path),"%s/frost-wsi-%d-%ld.state",directory,getpid(),tid);if(p<0||(size_t)p>=sizeof(path))return;
    int n=snprintf(text,sizeof(text),"stage=%s pid=%d tid=%ld uid=%d time=%lld.%09ld generation=%u frame=%u detail=%d\n",stage,getpid(),tid,getuid(),(long long)now.tv_sec,now.tv_nsec,s?s->generation:0,s?s->frame:0,detail);
    int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600);if(fd<0)return;
    if(n>0&&(size_t)n<sizeof(text)) { ssize_t ignored=write(fd,text,(size_t)n);(void)ignored; }close(fd);
}
static int init_loader(void) {
    if(host_gipa)return 1;
    loader=dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);
    if(!loader)return 0;
    host_gipa=(PFN_vkGetInstanceProcAddr)dlsym(loader,"vkGetInstanceProcAddr");
    host_gdpa=(PFN_vkGetDeviceProcAddr)dlsym(loader,"vkGetDeviceProcAddr");
    return host_gipa&&host_gdpa;
}
static void disconnect_host(void) { if(channel>=0)close(channel);channel=-1; }
static int exchange(struct FrostGuestRequest *request,int memory_fd,int sync_fd,struct FrostGuestReply *reply) {
    int fds[2],count=0;if(memory_fd>=0)fds[count++]=memory_fd;if(sync_fd>=0)fds[count++]=sync_fd;
    struct iovec iov={request,sizeof(*request)};
    char control[CMSG_SPACE(sizeof(fds))];memset(control,0,sizeof(control));
    struct msghdr msg={0};msg.msg_iov=&iov;msg.msg_iovlen=1;
    if(count){msg.msg_control=control;msg.msg_controllen=CMSG_SPACE(count*sizeof(int));struct cmsghdr *c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;c->cmsg_len=CMSG_LEN(count*sizeof(int));memcpy(CMSG_DATA(c),fds,count*sizeof(int));}
    request->version=FROST_GUEST_VERSION;
    if(channel<0||sendmsg(channel,&msg,MSG_NOSIGNAL)!=sizeof(*request)||recv(channel,reply,sizeof(*reply),MSG_WAITALL)!=sizeof(*reply)||reply->version!=FROST_GUEST_VERSION){disconnect_host();return 0;}
    return 1;
}
static int connect_host(void) {
    if(channel>=0)return 1;
    const char *name=getenv("FROSTBOX_WSI");if(!name||name[0]!='@'||strlen(name)>=sizeof(((struct sockaddr_un*)0)->sun_path))return 0;
    int fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0)return 0;
    struct sockaddr_un addr={.sun_family=AF_UNIX};strcpy(addr.sun_path,name);addr.sun_path[0]=0;
    struct timeval timeout={5,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    struct ucred peer; socklen_t length=sizeof(peer);
    if(connect(fd,(void*)&addr,offsetof(struct sockaddr_un,sun_path)+strlen(name))||getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&length)||peer.uid!=getuid()){close(fd);return 0;}
    channel=fd;struct FrostGuestRequest request={.operation=FROST_GUEST_HELLO};
    if(!exchange(&request,-1,-1,&hello)||hello.result||!hello.sync_supported){disconnect_host();return 0;}
    fprintf(stderr,"FROST_GUEST_HOST pid=%d uid=%d extent=%ux%u\n",peer.pid,peer.uid,hello.width,hello.height);return 1;
}
static int valid_surface(VkSurfaceKHR s) {return surface&&(uintptr_t)s==(uintptr_t)surface;}
static int valid_swap(VkSwapchainKHR s) {return current&&(uintptr_t)s==(uintptr_t)current&&!current->broken;}
static VkImageLayout layout(VkImageLayout l) {return l==VK_IMAGE_LAYOUT_PRESENT_SRC_KHR?VK_IMAGE_LAYOUT_GENERAL:l;}
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char *layer,uint32_t *count,VkExtensionProperties *out) {
    if(!init_loader())return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkEnumerateInstanceExtensionProperties f=(void*)host_gipa(VK_NULL_HANDLE,"vkEnumerateInstanceExtensionProperties");
    if(layer)return f(layer,count,out);
    uint32_t n=0;VkResult r=f(NULL,&n,NULL);if(r)return r;
    VkExtensionProperties *items=calloc(n+1,sizeof(*items));if(!items)return VK_ERROR_OUT_OF_HOST_MEMORY;
    r=f(NULL,&n,items);if(!r){
        uint32_t kept=0;
        for(uint32_t j=0;j<n;j++) {
            const char *name=items[j].extensionName;
            if(strstr(name,"surface")&&strcmp(name,"VK_KHR_surface"))continue;
            if(!strcmp(name,"VK_EXT_swapchain_colorspace"))continue;
            items[kept++]=items[j];
        }
        n=kept;
        if(connect_host()){strcpy(items[n].extensionName,"VK_KHR_win32_surface");items[n++].specVersion=6;if(!surface)disconnect_host();}
    }
    if(!r){if(out){uint32_t copied=*count<n?*count:n;memcpy(out,items,copied*sizeof(*out));*count=copied;if(copied<n)r=VK_INCOMPLETE;}else *count=n;}free(items);return r;
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo *info,const VkAllocationCallbacks *allocator,VkInstance *out) {
    if(!init_loader()||instance)return VK_ERROR_INITIALIZATION_FAILED;
    const char **extensions=calloc(info->enabledExtensionCount+1,sizeof(char*));if(!extensions)return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkInstanceCreateInfo copy=*info;copy.ppEnabledExtensionNames=extensions;copy.enabledExtensionCount=0;
    for(uint32_t j=0;j<info->enabledExtensionCount;j++){const char *s=info->ppEnabledExtensionNames[j];if(!strcmp(s,"VK_KHR_win32_surface")){if(!connect_host()){free(extensions);return VK_ERROR_EXTENSION_NOT_PRESENT;}}else extensions[copy.enabledExtensionCount++]=s;}
    VkResult r=((PFN_vkCreateInstance)host_gipa(NULL,"vkCreateInstance"))(&copy,allocator,out);free(extensions);if(!r)instance=*out;return r;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyInstance(VkInstance i,const VkAllocationCallbacks *a) {I(vkDestroyInstance)(i,a);instance=VK_NULL_HANDLE;disconnect_host();}
// Called by Wine interface48, after Wine has created a real HWND/client_surface.
__attribute__((visibility("default"))) VkResult frostCreateSurface(VkInstance i,VkSurfaceKHR *out) {
    if(i!=instance||!connect_host())return VK_ERROR_SURFACE_LOST_KHR;
    if(surface)return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
    surface=calloc(1,sizeof(*surface));if(!surface)return VK_ERROR_OUT_OF_HOST_MEMORY;
    surface->magic=0x46524f5354535552ull;*out=(VkSurfaceKHR)(uintptr_t)surface;
    fprintf(stderr,"FROST_GUEST_SURFACE created\n");return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkDestroySurfaceKHR(VkInstance i,VkSurfaceKHR s,const VkAllocationCallbacks *a) {(void)i;(void)a;if(valid_surface(s)){free(surface);surface=NULL;}}
VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceSupportKHR(VkPhysicalDevice p,uint32_t q,VkSurfaceKHR s,VkBool32 *out) {
    if(!valid_surface(s))return VK_ERROR_SURFACE_LOST_KHR;
    uint32_t n=0;I(vkGetPhysicalDeviceQueueFamilyProperties)(p,&n,NULL);VkQueueFamilyProperties *qs=calloc(n,sizeof(*qs));if(!qs)return VK_ERROR_OUT_OF_HOST_MEMORY;I(vkGetPhysicalDeviceQueueFamilyProperties)(p,&n,qs);
    VkPhysicalDeviceIDProperties id={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};VkPhysicalDeviceProperties2 properties={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,&id};I(vkGetPhysicalDeviceProperties2)(p,&properties);
    *out=q<n&&(qs[q].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&!memcmp(id.deviceUUID,hello.device_uuid,16)&&!memcmp(id.driverUUID,hello.driver_uuid,16);free(qs);return VK_SUCCESS;
}
__attribute__((visibility("default"))) VkBool32 frostPresentationSupport(VkPhysicalDevice p,uint32_t q) {
    if(!connect_host())return VK_FALSE;
    uint32_t n=0;I(vkGetPhysicalDeviceQueueFamilyProperties)(p,&n,NULL);if(q>=n)return VK_FALSE;
    VkQueueFamilyProperties *qs=calloc(n,sizeof(*qs));if(!qs)return VK_FALSE;I(vkGetPhysicalDeviceQueueFamilyProperties)(p,&n,qs);VkBool32 supported=(qs[q].queueFlags&VK_QUEUE_GRAPHICS_BIT)!=0;free(qs);return supported;
}
VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice p,VkSurfaceKHR s,VkSurfaceCapabilitiesKHR *c) {
    if(!valid_surface(s))return VK_ERROR_SURFACE_LOST_KHR;
    VkPhysicalDeviceProperties props;I(vkGetPhysicalDeviceProperties)(p,&props);
    memset(c,0,sizeof(*c));c->minImageCount=2;c->maxImageCount=MAX_IMAGES;c->currentExtent=(VkExtent2D){UINT32_MAX,UINT32_MAX};c->minImageExtent=(VkExtent2D){1,1};uint32_t max=props.limits.maxImageDimension2D;if(max>4096)max=4096;c->maxImageExtent=(VkExtent2D){max,max};c->maxImageArrayLayers=1;c->supportedTransforms=c->currentTransform=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;c->supportedCompositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;c->supportedUsageFlags=FROST_WSI_USAGE;return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice p,VkSurfaceKHR s,uint32_t *n,VkSurfaceFormatKHR *out) {(void)p;if(!valid_surface(s))return VK_ERROR_SURFACE_LOST_KHR;if(!out){*n=1;return VK_SUCCESS;}if(!*n)return VK_INCOMPLETE;*n=1;*out=(VkSurfaceFormatKHR){VK_FORMAT_R8G8B8A8_UNORM,VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};return VK_SUCCESS;}
VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfacePresentModesKHR(VkPhysicalDevice p,VkSurfaceKHR s,uint32_t *n,VkPresentModeKHR *out) {(void)p;if(!valid_surface(s))return VK_ERROR_SURFACE_LOST_KHR;if(!out){*n=1;return VK_SUCCESS;}if(!*n)return VK_INCOMPLETE;*n=1;*out=VK_PRESENT_MODE_FIFO_KHR;return VK_SUCCESS;}
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceExtensionProperties(VkPhysicalDevice p,const char *layer,uint32_t *count,VkExtensionProperties *out) {
    PFN_vkEnumerateDeviceExtensionProperties f=I(vkEnumerateDeviceExtensionProperties);if(layer)return f(p,layer,count,out);
    uint32_t n=0;VkResult r=f(p,NULL,&n,NULL);if(r)return r;
    VkExtensionProperties *items=calloc(n+1,sizeof(*items));if(!items)return VK_ERROR_OUT_OF_HOST_MEMORY;r=f(p,NULL,&n,items);
    if(!r){uint32_t kept=0;for(unsigned j=0;j<n;j++){const char *name=items[j].extensionName;
        if((strstr(name,"swapchain")&&strcmp(name,VK_KHR_SWAPCHAIN_EXTENSION_NAME))||strstr(name,"present")||strstr(name,"display_timing")||strstr(name,"hdr_metadata")||strstr(name,"full_screen")||strstr(name,"low_latency"))continue;
        items[kept++]=items[j];}n=kept;
        if(out){uint32_t copied=*count<n?*count:n;memcpy(out,items,copied*sizeof(*out));*count=copied;if(copied<n)r=VK_INCOMPLETE;}else *count=n;
    }free(items);return r;
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice p,const VkDeviceCreateInfo *info,const VkAllocationCallbacks *a,VkDevice *out) {
    if(device)return VK_ERROR_TOO_MANY_OBJECTS;
    const char **extensions=calloc(info->enabledExtensionCount+2,sizeof(char*));if(!extensions)return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkDeviceCreateInfo copy=*info;copy.ppEnabledExtensionNames=extensions;copy.enabledExtensionCount=0;
    int wsi=0;for(uint32_t j=0;j<info->enabledExtensionCount;j++){const char *s=info->ppEnabledExtensionNames[j];if(!strcmp(s,VK_KHR_SWAPCHAIN_EXTENSION_NAME))wsi=1;else extensions[copy.enabledExtensionCount++]=s;}
    if(wsi){const char *extra[]={VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME};for(unsigned j=0;j<2;j++){int found=0;for(unsigned k=0;k<copy.enabledExtensionCount;k++)if(!strcmp(extensions[k],extra[j]))found=1;if(!found)extensions[copy.enabledExtensionCount++]=extra[j];}}
    VkResult r=I(vkCreateDevice)(p,&copy,a,out);free(extensions);if(r)return r;
    device=*out;physical=p;family=info->pQueueCreateInfos[0].queueFamilyIndex;D(vkGetDeviceQueue)(device,family,0,&queue);return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyDevice(VkDevice d,const VkAllocationCallbacks *a) {D(vkDestroyDevice)(d,a);device=NULL;queue=NULL;physical=NULL;}
static void destroy_swap(struct Swapchain *s) {
    if(!s)return;
    if(s->pending&&D(vkWaitForFences)(device,1,&s->fence,VK_TRUE,DEADLINE)!=VK_SUCCESS){s->broken=1;return;}
    if(s->pool)D(vkDestroyCommandPool)(device,s->pool,NULL);if(s->fence)D(vkDestroyFence)(device,s->fence,NULL);if(s->ready)D(vkDestroySemaphore)(device,s->ready,NULL);
    for(unsigned j=0;j<MAX_IMAGES;j++){if(s->images[j])D(vkDestroyImage)(device,s->images[j],NULL);if(s->memory[j])D(vkFreeMemory)(device,s->memory[j],NULL);}free(s);
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateSwapchainKHR(VkDevice d,const VkSwapchainCreateInfoKHR *ci,const VkAllocationCallbacks *a,VkSwapchainKHR *out) {
    (void)a;if(d!=device||!valid_surface(ci->surface))return VK_ERROR_SURFACE_LOST_KHR;
    if(current)return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
    if(ci->pNext||ci->flags||ci->minImageCount<2||ci->minImageCount>MAX_IMAGES||ci->imageFormat!=VK_FORMAT_R8G8B8A8_UNORM||ci->imageColorSpace!=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR||ci->imageArrayLayers!=1||ci->imageSharingMode!=VK_SHARING_MODE_EXCLUSIVE||ci->presentMode!=VK_PRESENT_MODE_FIFO_KHR||ci->preTransform!=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR||ci->compositeAlpha!=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR||(ci->imageUsage&~FROST_WSI_USAGE)||!ci->imageExtent.width||!ci->imageExtent.height||ci->imageExtent.width>4096||ci->imageExtent.height>4096)return VK_ERROR_INITIALIZATION_FAILED;
    struct Swapchain *s=calloc(1,sizeof(*s));if(!s)return VK_ERROR_OUT_OF_HOST_MEMORY;VkResult result=VK_SUCCESS;
    s->count=ci->minImageCount;s->width=ci->imageExtent.width;s->height=ci->imageExtent.height;s->generation=++generation;
    struct FrostGuestRequest reset={.operation=FROST_GUEST_RESET,.generation=s->generation};struct FrostGuestReply reply;
    if(!exchange(&reset,-1,-1,&reply)||reply.result){result=VK_ERROR_SURFACE_LOST_KHR;goto fail;}
    VkPhysicalDeviceMemoryProperties mp;I(vkGetPhysicalDeviceMemoryProperties)(physical,&mp);
    for(unsigned j=0;j<s->count;j++){
        VkExternalMemoryImageCreateInfo ex={VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,NULL,VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
        VkImageCreateInfo image={VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,&ex,0,VK_IMAGE_TYPE_2D,VK_FORMAT_R8G8B8A8_UNORM,{s->width,s->height,1},1,1,VK_SAMPLE_COUNT_1_BIT,VK_IMAGE_TILING_OPTIMAL,FROST_WSI_USAGE,VK_SHARING_MODE_EXCLUSIVE,0,NULL,VK_IMAGE_LAYOUT_UNDEFINED};
        CHECK(D(vkCreateImage)(device,&image,NULL,&s->images[j]));VkMemoryRequirements req;D(vkGetImageMemoryRequirements)(device,s->images[j],&req);
        uint32_t type=0;while(type<mp.memoryTypeCount&&!(req.memoryTypeBits&(1u<<type)))type++;if(type==mp.memoryTypeCount){result=VK_ERROR_FEATURE_NOT_PRESENT;goto fail;}
        VkExportMemoryAllocateInfo export={VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO,NULL,VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};VkMemoryDedicatedAllocateInfo dedicated={VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,&export,s->images[j],VK_NULL_HANDLE};VkMemoryAllocateInfo allocation={VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,&dedicated,req.size,type};
        CHECK(D(vkAllocateMemory)(device,&allocation,NULL,&s->memory[j]));CHECK(D(vkBindImageMemory)(device,s->images[j],s->memory[j],0));
        s->metadata[j]=(struct FrostWsiImage){.magic=FROST_WSI_MAGIC,.width=s->width,.height=s->height,.format=VK_FORMAT_R8G8B8A8_UNORM,.memory_type=type,.allocation_size=req.size};memcpy(s->metadata[j].device_uuid,hello.device_uuid,16);memcpy(s->metadata[j].driver_uuid,hello.driver_uuid,16);
    }
    VkCommandPoolCreateInfo pool={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,NULL,VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,family};CHECK(D(vkCreateCommandPool)(device,&pool,NULL,&s->pool));VkCommandBufferAllocateInfo command={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,NULL,s->pool,VK_COMMAND_BUFFER_LEVEL_PRIMARY,1};CHECK(D(vkAllocateCommandBuffers)(device,&command,&s->command));
    VkFenceCreateInfo fence={VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};CHECK(D(vkCreateFence)(device,&fence,NULL,&s->fence));VkExportSemaphoreCreateInfo export={VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,NULL,VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};VkSemaphoreCreateInfo sem={VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,&export};CHECK(D(vkCreateSemaphore)(device,&sem,NULL,&s->ready));
    current=s;*out=(VkSwapchainKHR)(uintptr_t)s;fprintf(stderr,"FROST_GUEST_SWAPCHAIN generation=%u images=%u extent=%ux%u\n",s->generation,s->count,s->width,s->height);return VK_SUCCESS;
fail: destroy_swap(s);return result;
}
VKAPI_ATTR void VKAPI_CALL vkDestroySwapchainKHR(VkDevice d,VkSwapchainKHR s,const VkAllocationCallbacks *a) {(void)d;(void)a;if(current&&(uintptr_t)s==(uintptr_t)current){struct Swapchain *old=current;current=NULL;destroy_swap(old);}}
VKAPI_ATTR VkResult VKAPI_CALL vkGetSwapchainImagesKHR(VkDevice d,VkSwapchainKHR handle,uint32_t *n,VkImage *out) {(void)d;if(!valid_swap(handle))return VK_ERROR_OUT_OF_DATE_KHR;if(!out){*n=current->count;return VK_SUCCESS;}uint32_t count=*n<current->count?*n:current->count;memcpy(out,current->images,count*sizeof(*out));*n=count;return count<current->count?VK_INCOMPLETE:VK_SUCCESS;}
static VkResult begin(struct Swapchain *s) {
    if(s->pending){trace_stage("reuse-fence-wait",s,0);VkResult r=D(vkWaitForFences)(device,1,&s->fence,VK_TRUE,DEADLINE);trace_stage("reuse-fence-done",s,r);if(r)return r;s->pending=0;}
    VkResult r=D(vkResetCommandBuffer)(s->command,0);if(r)return r;VkCommandBufferBeginInfo bi={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};return D(vkBeginCommandBuffer)(s->command,&bi);
}
static VkResult submit_queue(VkQueue q,uint32_t count,const VkSubmitInfo *info,VkFence fence) {
    trace_stage("queue-mutex-wait",NULL,count);pthread_mutex_lock(&queue_mutex);trace_stage("queue-submit",NULL,count);VkResult r=D(vkQueueSubmit)(q,count,info,fence);trace_stage("queue-submit-done",NULL,r);pthread_mutex_unlock(&queue_mutex);return r;
}
VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit(VkQueue q,uint32_t count,const VkSubmitInfo *info,VkFence fence){return submit_queue(q,count,info,fence);}
VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit2(VkQueue q,uint32_t count,const VkSubmitInfo2 *info,VkFence fence) {
    pthread_mutex_lock(&queue_mutex);VkResult r=D(vkQueueSubmit2)(q,count,info,fence);pthread_mutex_unlock(&queue_mutex);return r;
}
VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit2KHR(VkQueue q,uint32_t count,const VkSubmitInfo2 *info,VkFence fence){return vkQueueSubmit2(q,count,info,fence);}
VKAPI_ATTR VkResult VKAPI_CALL vkQueueWaitIdle(VkQueue q) {trace_stage("idle-queue-mutex",NULL,0);pthread_mutex_lock(&queue_mutex);trace_stage("queue-idle-wait",NULL,0);VkResult r=D(vkQueueWaitIdle)(q);trace_stage("queue-idle-done",NULL,r);pthread_mutex_unlock(&queue_mutex);return r;}
VKAPI_ATTR VkResult VKAPI_CALL vkDeviceWaitIdle(VkDevice d) {trace_stage("idle-device-mutex",NULL,0);pthread_mutex_lock(&queue_mutex);trace_stage("device-idle-wait",NULL,0);VkResult r=D(vkDeviceWaitIdle)(d);trace_stage("device-idle-done",NULL,r);pthread_mutex_unlock(&queue_mutex);return r;}
VKAPI_ATTR VkResult VKAPI_CALL vkAcquireNextImageKHR(VkDevice d,VkSwapchainKHR handle,uint64_t timeout,VkSemaphore semaphore,VkFence fence,uint32_t *index) {
    (void)d;if(!valid_swap(handle))return VK_ERROR_OUT_OF_DATE_KHR;
    trace_stage("acquire-mutex-wait",NULL,0);pthread_mutex_lock(&mutex);struct Swapchain *s=current;trace_stage("acquire-enter",s,0);VkResult result=VK_SUCCESS;uint32_t slot=s->next;
    struct timespec end;clock_gettime(CLOCK_REALTIME,&end);
    if(timeout!=UINT64_MAX){end.tv_sec+=timeout/1000000000ull;end.tv_nsec+=timeout%1000000000ull;if(end.tv_nsec>=1000000000){end.tv_sec++;end.tv_nsec-=1000000000;}}
    for(;;){unsigned n=0;while(n<s->count&&s->acquired[slot]){slot=(slot+1)%s->count;n++;}if(n<s->count)break;
        if(!timeout){result=VK_NOT_READY;goto fail;}
        trace_stage("acquire-image-wait",s,0);int wait=timeout==UINT64_MAX?pthread_cond_wait(&image_available,&mutex):pthread_cond_timedwait(&image_available,&mutex,&end);
        if(wait==ETIMEDOUT){result=VK_TIMEOUT;goto fail;}
        if(wait||s->broken){result=VK_ERROR_OUT_OF_DATE_KHR;goto fail;}
    }
    CHECK(begin(s));VkImageMemoryBarrier barrier={VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,NULL,0,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,s->external[slot]?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,s->external[slot]?VK_QUEUE_FAMILY_EXTERNAL:VK_QUEUE_FAMILY_IGNORED,s->external[slot]?family:VK_QUEUE_FAMILY_IGNORED,s->images[slot],{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};
    D(vkCmdPipelineBarrier)(s->command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,0,NULL,0,NULL,1,&barrier);CHECK(D(vkEndCommandBuffer)(s->command));
    // Signal application synchronization objects through actual queue work.
    VkSubmitInfo submit={VK_STRUCTURE_TYPE_SUBMIT_INFO,NULL,0,NULL,NULL,1,&s->command,semaphore?1u:0u,semaphore?&semaphore:NULL};CHECK(submit_queue(queue,1,&submit,fence));
    // Internal fence submission protects the reusable command buffer, independent of app fence ownership.
    CHECK(D(vkResetFences)(device,1,&s->fence));CHECK(submit_queue(queue,0,NULL,s->fence));s->pending=1;s->acquired[slot]=1;s->next=(slot+1)%s->count;*index=slot;
fail:trace_stage("acquire-return",s,result);pthread_mutex_unlock(&mutex);return result;
}
VKAPI_ATTR VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue q,const VkPresentInfoKHR *info) {
    if(q!=queue||info->swapchainCount!=1||info->pNext||!valid_swap(info->pSwapchains[0]))return VK_ERROR_OUT_OF_DATE_KHR;
    trace_stage("present-mutex-wait",NULL,0);pthread_mutex_lock(&mutex);struct Swapchain *s=current;uint32_t slot=info->pImageIndices[0];trace_stage("present-enter",s,slot);VkResult result=VK_SUCCESS;int memory_fd=-1,sync_fd=-1;VkPipelineStageFlags *stages=NULL;
    if(slot>=s->count||!s->acquired[slot]){result=VK_ERROR_OUT_OF_DATE_KHR;goto fail;}
    CHECK(begin(s));VkImageMemoryBarrier barrier={VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,NULL,VK_ACCESS_MEMORY_WRITE_BIT,0,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,family,VK_QUEUE_FAMILY_EXTERNAL,s->images[slot],{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};D(vkCmdPipelineBarrier)(s->command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,NULL,0,NULL,1,&barrier);CHECK(D(vkEndCommandBuffer)(s->command));CHECK(D(vkResetFences)(device,1,&s->fence));
    stages=calloc(info->waitSemaphoreCount+1,sizeof(*stages));if(!stages){result=VK_ERROR_OUT_OF_HOST_MEMORY;goto fail;}for(unsigned j=0;j<info->waitSemaphoreCount;j++)stages[j]=VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit={VK_STRUCTURE_TYPE_SUBMIT_INFO,NULL,info->waitSemaphoreCount,info->pWaitSemaphores,stages,1,&s->command,1,&s->ready};CHECK(submit_queue(queue,1,&submit,s->fence));s->pending=1;
    VkSemaphoreGetFdInfoKHR sf={VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,NULL,s->ready,VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};CHECK(D(vkGetSemaphoreFdKHR)(device,&sf,&sync_fd));
    if(!s->sent[slot]){VkMemoryGetFdInfoKHR mf={VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR,NULL,s->memory[slot],VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};CHECK(D(vkGetMemoryFdKHR)(device,&mf,&memory_fd));}
    struct FrostGuestRequest request={.operation=FROST_GUEST_PRESENT,.generation=s->generation,.slot=slot,.image=s->metadata[slot]};request.image.frame=s->frame;request.image.sync_fd=sync_fd<0?2:1;struct FrostGuestReply reply;
    trace_stage("host-ack-wait",s,slot);if(!exchange(&request,memory_fd,sync_fd,&reply)||reply.result||reply.generation!=s->generation||reply.frame!=s->frame){result=VK_ERROR_SURFACE_LOST_KHR;goto fail;}
    trace_stage("present-fence-wait",s,slot);CHECK(D(vkWaitForFences)(device,1,&s->fence,VK_TRUE,DEADLINE));s->pending=0;s->sent[slot]=s->external[slot]=1;s->acquired[slot]=0;s->frame++;pthread_cond_broadcast(&image_available);
    fprintf(stderr,"FROST_GUEST_PRESENT generation=%u frame=%u slot=%u\n",s->generation,s->frame,slot);
fail:trace_stage("present-return",s,result);if(result)s->broken=1;if(memory_fd>=0)close(memory_fd);if(sync_fd>=0)close(sync_fd);free(stages);if(info->pResults)info->pResults[0]=result;pthread_mutex_unlock(&mutex);return result;
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateRenderPass(VkDevice d,const VkRenderPassCreateInfo *info,const VkAllocationCallbacks *a,VkRenderPass *out) {
    VkAttachmentDescription *attachments=calloc(info->attachmentCount+1,sizeof(*attachments));if(!attachments)return VK_ERROR_OUT_OF_HOST_MEMORY;
    memcpy(attachments,info->pAttachments,info->attachmentCount*sizeof(*attachments));for(unsigned j=0;j<info->attachmentCount;j++){attachments[j].initialLayout=layout(attachments[j].initialLayout);attachments[j].finalLayout=layout(attachments[j].finalLayout);}VkRenderPassCreateInfo copy=*info;copy.pAttachments=attachments;VkResult r=D(vkCreateRenderPass)(d,&copy,a,out);free(attachments);return r;
}
VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier(VkCommandBuffer cmd,VkPipelineStageFlags src,VkPipelineStageFlags dst,VkDependencyFlags dep,uint32_t nm,const VkMemoryBarrier *m,uint32_t nb,const VkBufferMemoryBarrier *b,uint32_t ni,const VkImageMemoryBarrier *i) {
    if(!ni){D(vkCmdPipelineBarrier)(cmd,src,dst,dep,nm,m,nb,b,ni,i);return;}
    VkImageMemoryBarrier copy[ni];memcpy(copy,i,ni*sizeof(*i));for(unsigned j=0;j<ni;j++){copy[j].oldLayout=layout(copy[j].oldLayout);copy[j].newLayout=layout(copy[j].newLayout);}D(vkCmdPipelineBarrier)(cmd,src,dst,dep,nm,m,nb,b,ni,copy);
}
VKAPI_ATTR VkResult VKAPI_CALL vkAcquireNextImage2KHR(VkDevice d,const VkAcquireNextImageInfoKHR *info,uint32_t *index) {
    if(info->pNext||info->deviceMask!=1)return VK_ERROR_FEATURE_NOT_PRESENT;
    return vkAcquireNextImageKHR(d,info->swapchain,info->timeout,info->semaphore,info->fence,index);
}
VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier2(VkCommandBuffer cmd,const VkDependencyInfo *info) {
    if(!info->imageMemoryBarrierCount){D(vkCmdPipelineBarrier2)(cmd,info);return;}
    VkImageMemoryBarrier2 *barriers=malloc(info->imageMemoryBarrierCount*sizeof(*barriers));
    if(!barriers)abort(); // Void Vulkan commands cannot return an allocation error; never forward a wrong layout.
    memcpy(barriers,info->pImageMemoryBarriers,info->imageMemoryBarrierCount*sizeof(*barriers));
    for(unsigned j=0;j<info->imageMemoryBarrierCount;j++){barriers[j].oldLayout=layout(barriers[j].oldLayout);barriers[j].newLayout=layout(barriers[j].newLayout);}
    VkDependencyInfo copy=*info;copy.pImageMemoryBarriers=barriers;D(vkCmdPipelineBarrier2)(cmd,&copy);free(barriers);
}
VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier2KHR(VkCommandBuffer cmd,const VkDependencyInfo *info){vkCmdPipelineBarrier2(cmd,info);}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateRenderPass2(VkDevice d,const VkRenderPassCreateInfo2 *info,const VkAllocationCallbacks *a,VkRenderPass *out) {
    VkAttachmentDescription2 *attachments=calloc(info->attachmentCount+1,sizeof(*attachments));if(!attachments)return VK_ERROR_OUT_OF_HOST_MEMORY;
    memcpy(attachments,info->pAttachments,info->attachmentCount*sizeof(*attachments));for(unsigned j=0;j<info->attachmentCount;j++){attachments[j].initialLayout=layout(attachments[j].initialLayout);attachments[j].finalLayout=layout(attachments[j].finalLayout);}VkRenderPassCreateInfo2 copy=*info;copy.pAttachments=attachments;VkResult r=D(vkCreateRenderPass2)(d,&copy,a,out);free(attachments);return r;
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateRenderPass2KHR(VkDevice d,const VkRenderPassCreateInfo2 *i,const VkAllocationCallbacks *a,VkRenderPass *out){return vkCreateRenderPass2(d,i,a,out);}
VKAPI_ATTR void VKAPI_CALL vkCmdBeginRendering(VkCommandBuffer cmd,const VkRenderingInfo *info) {
    VkRenderingAttachmentInfo *colors=calloc(info->colorAttachmentCount+1,sizeof(*colors));if(!colors)abort();
    memcpy(colors,info->pColorAttachments,info->colorAttachmentCount*sizeof(*colors));for(unsigned j=0;j<info->colorAttachmentCount;j++){colors[j].imageLayout=layout(colors[j].imageLayout);colors[j].resolveImageLayout=layout(colors[j].resolveImageLayout);}
    VkRenderingInfo copy=*info;copy.pColorAttachments=colors;D(vkCmdBeginRendering)(cmd,&copy);free(colors);
}
VKAPI_ATTR void VKAPI_CALL vkCmdBeginRenderingKHR(VkCommandBuffer cmd,const VkRenderingInfo *i){vkCmdBeginRendering(cmd,i);}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance,const char*);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice,const char*);
static PFN_vkVoidFunction hook(const char *name) {
#define H(n) if(!strcmp(name,#n))return (PFN_vkVoidFunction)n
    H(vkGetInstanceProcAddr);H(vkGetDeviceProcAddr);H(vkEnumerateInstanceExtensionProperties);H(vkCreateInstance);H(vkEnumerateDeviceExtensionProperties);H(vkDestroyInstance);H(vkCreateDevice);H(vkDestroyDevice);H(vkDestroySurfaceKHR);H(vkGetPhysicalDeviceSurfaceSupportKHR);H(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);H(vkGetPhysicalDeviceSurfaceFormatsKHR);H(vkGetPhysicalDeviceSurfacePresentModesKHR);H(vkCreateSwapchainKHR);H(vkDestroySwapchainKHR);H(vkGetSwapchainImagesKHR);H(vkAcquireNextImageKHR);H(vkQueuePresentKHR);H(vkCreateRenderPass);H(vkCmdPipelineBarrier);H(vkAcquireNextImage2KHR);H(vkCmdPipelineBarrier2);H(vkCmdPipelineBarrier2KHR);H(vkCreateRenderPass2);H(vkCreateRenderPass2KHR);H(vkCmdBeginRendering);H(vkCmdBeginRenderingKHR);H(vkQueueSubmit);H(vkQueueSubmit2);H(vkQueueSubmit2KHR);H(vkQueueWaitIdle);H(vkDeviceWaitIdle);
#undef H
    return NULL;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance i,const char *name) {if(!init_loader())return NULL;PFN_vkVoidFunction f=hook(name);return f?f:host_gipa(i,name);}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice d,const char *name) {if(!init_loader())return NULL;PFN_vkVoidFunction f=hook(name);return f?f:host_gdpa(d,name);}
