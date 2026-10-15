// SPDX-License-Identifier: MIT
// x86-64 guest creates and clears the GPU image; Android host only imports and blits it.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/un.h>
#include "protocol.h"
#define FUNCS(X) X(vkCreateInstance) X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) \
 X(vkGetPhysicalDeviceProperties2) X(vkGetPhysicalDeviceImageFormatProperties2) \
 X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceMemoryProperties) \
 X(vkCreateDevice) X(vkGetDeviceProcAddr) X(vkGetDeviceQueue) X(vkDestroyDevice) \
 X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkAllocateMemory) \
 X(vkFreeMemory) X(vkBindImageMemory) X(vkGetMemoryFdKHR) X(vkCreateCommandPool) \
 X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) \
 X(vkEndCommandBuffer) X(vkCmdPipelineBarrier) X(vkCmdClearColorImage) \
 X(vkCreateFence) X(vkDestroyFence) X(vkQueueSubmit) X(vkWaitForFences) X(vkResetFences) X(vkResetCommandBuffer) X(vkGetPhysicalDeviceExternalSemaphoreProperties) X(vkCreateSemaphore) X(vkDestroySemaphore) X(vkGetSemaphoreFdKHR) X(vkCreateQueryPool) X(vkDestroyQueryPool) X(vkCmdResetQueryPool) X(vkCmdWriteTimestamp) X(vkGetQueryPoolResults)
#define DECL(n) static PFN_##n n;
FUNCS(DECL)
#define CALL(x) do { VkResult r=(x); printf("CALL=%s RESULT=%d\n",#x,r); fflush(NULL); if(r!=VK_SUCCESS)goto cleanup; }while(0)
#define LOAD(n) do { n=(PFN_##n)gipa(instance,#n); if(!n){printf("MISSING=%s\n",#n);goto cleanup;} }while(0)
#define DEV(n) do { n=(PFN_##n)vkGetDeviceProcAddr(device,#n); if(!n){printf("MISSING=%s\n",#n);goto cleanup;} }while(0)
int main(int argc,char** argv) {
    int result=11,fd=-1,sock=-1,syncfd=-1;int use_sync=0;VkSemaphore ready=0;VkQueryPool timestamps=0;uint32_t timestamp_bits=0;double gpu_total_ns=0;
    void* lib=NULL; VkInstance instance=0; VkDevice device=0; VkImage image=0;
    VkDeviceMemory memory=0; VkCommandPool pool=0; VkFence fence=0;
    VkImage images[2]={0};VkDeviceMemory memories[2]={0};
    int persistent=argc==6;unsigned total=persistent?(unsigned)strtoul(argv[5],NULL,10):1;
    if((argc!=5&&!persistent)||!total||total>1000)return 10;
    unsigned width=(unsigned)strtoul(argv[2],NULL,10),height=(unsigned)strtoul(argv[3],NULL,10),frame=(unsigned)strtoul(argv[4],NULL,10);
    if(!width||!height||width>16384||height>16384||frame>1)return 10;
    printf("FROST_WSI_GUEST=X86_64 PID=%d UID=%d FRAME=%u\n",getpid(),getuid(),frame);
    lib=dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);if(!lib)goto cleanup;
    PFN_vkGetInstanceProcAddr gipa=(PFN_vkGetInstanceProcAddr)dlsym(lib,"vkGetInstanceProcAddr");if(!gipa)goto cleanup;
    LOAD(vkCreateInstance);
    VkApplicationInfo app={VK_STRUCTURE_TYPE_APPLICATION_INFO};app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&app;
    CALL(vkCreateInstance(&ici,NULL,&instance));
    LOAD(vkDestroyInstance);LOAD(vkEnumeratePhysicalDevices);LOAD(vkGetPhysicalDeviceProperties2);LOAD(vkGetPhysicalDeviceImageFormatProperties2);
    LOAD(vkGetPhysicalDeviceExternalSemaphoreProperties);LOAD(vkGetPhysicalDeviceQueueFamilyProperties);LOAD(vkGetPhysicalDeviceMemoryProperties);LOAD(vkCreateDevice);LOAD(vkGetDeviceProcAddr);
    uint32_t count=8;VkPhysicalDevice physicals[8];CALL(vkEnumeratePhysicalDevices(instance,&count,physicals));if(!count||count>8)goto cleanup;
    VkPhysicalDevice physical=physicals[0];
    VkPhysicalDeviceIDProperties id={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
    VkPhysicalDeviceProperties2 props={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};props.pNext=&id;vkGetPhysicalDeviceProperties2(physical,&props);
    printf("GPU=%s TYPE=%d\n",props.properties.deviceName,props.properties.deviceType);if(props.properties.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU)goto cleanup;
    VkPhysicalDeviceExternalImageFormatInfo external={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};external.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    VkPhysicalDeviceImageFormatInfo2 query={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};query.pNext=&external;query.format=VK_FORMAT_R8G8B8A8_UNORM;query.type=VK_IMAGE_TYPE_2D;query.tiling=VK_IMAGE_TILING_OPTIMAL;query.usage=FROST_WSI_USAGE;
    VkExternalImageFormatProperties extprops={VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};VkImageFormatProperties2 fmt={VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};fmt.pNext=&extprops;
    CALL(vkGetPhysicalDeviceImageFormatProperties2(physical,&query,&fmt));
    printf("OPAQUE_FD_IMAGE_FEATURES=%u COMPATIBLE=%u\n",extprops.externalMemoryProperties.externalMemoryFeatures,extprops.externalMemoryProperties.compatibleHandleTypes);
    if((extprops.externalMemoryProperties.externalMemoryFeatures&(VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT|VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT))!=(VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT|VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT))goto cleanup;
    uint32_t n=16;VkQueueFamilyProperties families[16];vkGetPhysicalDeviceQueueFamilyProperties(physical,&n,families);if(n>16)goto cleanup;
    uint32_t family=UINT32_MAX;for(uint32_t i=0;i<n;i++)if(families[i].queueCount&&(families[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)){family=i;break;}if(family==UINT32_MAX)goto cleanup;timestamp_bits=families[family].timestampValidBits;
    float priority=1;VkDeviceQueueCreateInfo qci={VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qci.queueFamilyIndex=family;qci.queueCount=1;qci.pQueuePriorities=&priority;
    VkPhysicalDeviceExternalSemaphoreInfo semquery={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO};
    VkExternalSemaphoreProperties semprops={VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
    semquery.handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;vkGetPhysicalDeviceExternalSemaphoreProperties(physical,&semquery,&semprops);
    use_sync=persistent&&(semprops.externalSemaphoreFeatures&3)==3;
    printf("SYNC_FD_SEMAPHORE_FEATURES=%u COMPATIBLE=%u ENABLED=%d\n",semprops.externalSemaphoreFeatures,semprops.compatibleHandleTypes,use_sync);
    const char* extensions[]={"VK_KHR_external_memory_fd","VK_KHR_external_semaphore_fd"};VkDeviceCreateInfo dci={VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;dci.enabledExtensionCount=use_sync?2:1;dci.ppEnabledExtensionNames=extensions;
    CALL(vkCreateDevice(physical,&dci,NULL,&device));
    DEV(vkDestroyDevice);DEV(vkGetDeviceQueue);DEV(vkCreateImage);DEV(vkDestroyImage);DEV(vkGetImageMemoryRequirements);DEV(vkAllocateMemory);DEV(vkFreeMemory);DEV(vkBindImageMemory);DEV(vkGetMemoryFdKHR);
    DEV(vkCreateCommandPool);DEV(vkDestroyCommandPool);DEV(vkAllocateCommandBuffers);DEV(vkBeginCommandBuffer);DEV(vkEndCommandBuffer);DEV(vkCmdPipelineBarrier);DEV(vkCmdClearColorImage);DEV(vkCreateFence);DEV(vkDestroyFence);DEV(vkQueueSubmit);DEV(vkWaitForFences);DEV(vkResetFences);DEV(vkResetCommandBuffer);DEV(vkCreateSemaphore);DEV(vkDestroySemaphore);DEV(vkCreateQueryPool);DEV(vkDestroyQueryPool);DEV(vkCmdResetQueryPool);DEV(vkCmdWriteTimestamp);DEV(vkGetQueryPoolResults);
    if(persistent&&timestamp_bits){VkQueryPoolCreateInfo qp={VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};qp.queryType=VK_QUERY_TYPE_TIMESTAMP;qp.queryCount=2;CALL(vkCreateQueryPool(device,&qp,NULL,&timestamps));}if(use_sync){DEV(vkGetSemaphoreFdKHR);}
    if(use_sync){VkExportSemaphoreCreateInfo export={VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};export.handleTypes=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;VkSemaphoreCreateInfo sc={VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};sc.pNext=&export;CALL(vkCreateSemaphore(device,&sc,NULL,&ready));}
    VkQueue queue;vkGetDeviceQueue(device,family,0,&queue);
    VkExternalMemoryImageCreateInfo ec={VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};ec.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    VkImageCreateInfo ci={VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ci.pNext=&ec;ci.imageType=VK_IMAGE_TYPE_2D;ci.format=VK_FORMAT_R8G8B8A8_UNORM;ci.extent=(VkExtent3D){width,height,1};ci.mipLevels=1;ci.arrayLayers=1;ci.samples=VK_SAMPLE_COUNT_1_BIT;ci.tiling=VK_IMAGE_TILING_OPTIMAL;ci.usage=FROST_WSI_USAGE;
    VkMemoryRequirements req={0};uint32_t mt=UINT32_MAX;
    for(unsigned slot=0;slot<(persistent?2u:1u);slot++){
    CALL(vkCreateImage(device,&ci,NULL,&image));vkGetImageMemoryRequirements(device,image,&req);
    VkPhysicalDeviceMemoryProperties mem;vkGetPhysicalDeviceMemoryProperties(physical,&mem);mt=UINT32_MAX;
    for(uint32_t i=0;i<mem.memoryTypeCount;i++)if((req.memoryTypeBits&(1u<<i))&&(mem.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)){mt=i;break;}if(mt==UINT32_MAX)goto cleanup;
    VkExportMemoryAllocateInfo ex={VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};ex.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    VkMemoryDedicatedAllocateInfo dedicated={VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};dedicated.pNext=&ex;dedicated.image=image;
    VkMemoryAllocateInfo alloc={VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};alloc.pNext=&dedicated;alloc.allocationSize=req.size;alloc.memoryTypeIndex=mt;
    CALL(vkAllocateMemory(device,&alloc,NULL,&memory));CALL(vkBindImageMemory(device,image,memory,0));images[slot]=image;memories[slot]=memory;
    }
    image=0;memory=0;
    VkCommandPoolCreateInfo pc={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pc.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pc.queueFamilyIndex=family;CALL(vkCreateCommandPool(device,&pc,NULL,&pool));
    VkCommandBuffer cmd;VkCommandBufferAllocateInfo ca={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;CALL(vkAllocateCommandBuffers(device,&ca,&cmd));
    sock=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(sock<0)goto cleanup;
    struct timeval timeout={5,0};setsockopt(sock,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(sock,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    struct sockaddr_un addr={0};addr.sun_family=AF_UNIX;if(strlen(argv[1])>=sizeof(addr.sun_path))goto cleanup;strcpy(addr.sun_path,argv[1]);socklen_t address_size=sizeof(addr);
    if(argv[1][0]=='@'){addr.sun_path[0]=0;address_size=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+strlen(argv[1]));}
    if(connect(sock,(struct sockaddr*)&addr,address_size))goto cleanup;
    struct ucred peer;socklen_t plen=sizeof(peer);if(getsockopt(sock,SOL_SOCKET,SO_PEERCRED,&peer,&plen)||peer.uid!=getuid())goto cleanup;
    VkFenceCreateInfo fc={VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};CALL(vkCreateFence(device,&fc,NULL,&fence));
    for(unsigned sequence=0;sequence<total;sequence++) {
    unsigned slot=persistent?sequence%2:0;image=images[slot];memory=memories[slot];
    if(persistent)frame=sequence%2;
    CALL(vkResetCommandBuffer(cmd,0));CALL(vkResetFences(device,1,&fence));
    VkCommandBufferBeginInfo begin={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};CALL(vkBeginCommandBuffer(cmd,&begin));
    if(timestamps){vkCmdResetQueryPool(cmd,timestamps,0,2);vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,timestamps,0);}
    VkImageMemoryBarrier barrier={VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};barrier.image=image;barrier.subresourceRange=(VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;barrier.oldLayout=persistent&&sequence>=2?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED;
    if(persistent&&sequence>=2){barrier.srcQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;barrier.dstQueueFamilyIndex=family;}barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,NULL,0,NULL,1,&barrier);
    VkClearColorValue color={{frame==0?1.0f:0.0f,frame==1?1.0f:0.0f,0.0f,1.0f}};vkCmdClearColorImage(cmd,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,&color,1,&barrier.subresourceRange);
    // Release ownership and canonical GENERAL layout before sending the memory FD.
    barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=0;barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_GENERAL;barrier.srcQueueFamilyIndex=family;barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,NULL,0,NULL,1,&barrier);if(timestamps)vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,timestamps,1);CALL(vkEndCommandBuffer(cmd));
    VkSubmitInfo submit={VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&cmd;
    if(use_sync){submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&ready;}
    CALL(vkQueueSubmit(queue,1,&submit,fence));
    if(use_sync){VkSemaphoreGetFdInfoKHR sf={VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};sf.semaphore=ready;sf.handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;if(vkGetSemaphoreFdKHR(device,&sf,&syncfd)!=VK_SUCCESS)_exit(13);printf("SYNC_FD_EXPORT FRAME=%u FD_VALID=%d\n",sequence,syncfd>=0);}
    VkResult completed=vkWaitForFences(device,1,&fence,VK_TRUE,5000000000ull);
    printf("CALL=vkWaitForFences RESULT=%d\n",completed);fflush(NULL);
    // Do not free an in-flight image on timeout. The isolated guest process exits instead.
    if(completed!=VK_SUCCESS)_exit(12);
    if(timestamps){uint64_t values[2]={0};CALL(vkGetQueryPoolResults(device,timestamps,0,2,sizeof(values),values,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT));uint64_t delta=values[1]-values[0];if(timestamp_bits<64)delta&=(1ull<<timestamp_bits)-1;double ns=delta*(double)props.properties.limits.timestampPeriod;gpu_total_ns+=ns;printf("GUEST_GPU_NS FRAME=%u VALUE=%.0f\n",sequence,ns);}
    VkMemoryGetFdInfoKHR fi={VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};fi.memory=memory;fi.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;if(!persistent||sequence<2){CALL(vkGetMemoryFdKHR(device,&fi,&fd));}
    struct FrostWsiImage packet={FROST_WSI_MAGIC,width,height,VK_FORMAT_R8G8B8A8_UNORM,mt,persistent?sequence:frame,req.size,{0},{0}};packet.sync_fd=use_sync?(syncfd>=0?1:2):0;memcpy(packet.device_uuid,id.deviceUUID,16);memcpy(packet.driver_uuid,id.driverUUID,16);
    int descriptors[2];unsigned descriptor_count=0;if(fd>=0)descriptors[descriptor_count++]=fd;if(syncfd>=0)descriptors[descriptor_count++]=syncfd;
    char control[CMSG_SPACE(sizeof(descriptors))]={0};struct iovec iov={&packet,sizeof(packet)};struct msghdr msg={0};msg.msg_iov=&iov;msg.msg_iovlen=1;
    if(descriptor_count){msg.msg_control=control;msg.msg_controllen=CMSG_SPACE(sizeof(int)*descriptor_count);struct cmsghdr* c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;c->cmsg_len=CMSG_LEN(sizeof(int)*descriptor_count);memcpy(CMSG_DATA(c),descriptors,sizeof(int)*descriptor_count);}
    if(sendmsg(sock,&msg,MSG_NOSIGNAL)!=(ssize_t)sizeof(packet))goto cleanup;
    if(syncfd>=0)close(syncfd);syncfd=-1;
    if(fd>=0)close(fd);fd=-1;unsigned char ack=0;if(recv(sock,&ack,1,MSG_WAITALL)!=1||ack!=42)goto cleanup;
    printf("FROST_WSI_IMAGE_EXPORTED FRAME=%u BYTES=%llu HOST_PID=%d ACK=42\n",frame,(unsigned long long)req.size,peer.pid);fflush(NULL);image=0;memory=0;
    }
    printf("FROST_WSI_PERSISTENT_FRAMES=%u POOL=%u\n",total,persistent?2u:1u);printf("GUEST_GPU_TOTAL_NS=%.0f FRAMES=%u TIMESTAMPS=%d\n",gpu_total_ns,total,timestamps!=0);result=42;
cleanup:
    if(syncfd>=0)close(syncfd);if(fd>=0)close(fd);if(sock>=0)close(sock);
    if(device&&timestamps&&vkDestroyQueryPool)vkDestroyQueryPool(device,timestamps,NULL);
    if(device&&ready&&vkDestroySemaphore)vkDestroySemaphore(device,ready,NULL);
    if(device&&fence&&vkDestroyFence)vkDestroyFence(device,fence,NULL);
    if(device&&pool&&vkDestroyCommandPool)vkDestroyCommandPool(device,pool,NULL);
    for(unsigned i=0;i<2;i++){if(device&&images[i]&&vkDestroyImage)vkDestroyImage(device,images[i],NULL);if(device&&memories[i]&&vkFreeMemory)vkFreeMemory(device,memories[i],NULL);}
    if(device&&vkDestroyDevice)vkDestroyDevice(device,NULL);
    if(instance&&vkDestroyInstance)vkDestroyInstance(instance,NULL);
    if(lib)dlclose(lib);printf("FROST_WSI_EXIT=%d\n",result);return result;
}
