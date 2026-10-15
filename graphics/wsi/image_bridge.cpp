// SPDX-License-Identifier: MIT
#include "image_bridge.hpp"
#include "protocol.h"
#include "guest_protocol.h"
#include <thread>
#include <mutex>
#include "../vulkan/gpu.hpp"
#include "../../runtime/cpu/box64_backend.hpp"
#include <array>
#include <atomic>
#include <cstring>
#include <filesystem>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
namespace frostbox::wsi {
using gpu::check;
namespace {
constexpr uint64_t deadline=5000000000ull;
struct Fd {int value=-1;~Fd(){if(value>=0)close(value);}int take(){int v=value;value=-1;return v;}};
struct Socket : Fd {std::string path;~Socket(){if(!path.empty()&&path[0]!='@')unlink(path.c_str());}};
VkImageCreateInfo imageInfo(uint32_t width,uint32_t height,const void* next) {
    VkImageCreateInfo i{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};i.pNext=next;i.imageType=VK_IMAGE_TYPE_2D;i.format=VK_FORMAT_R8G8B8A8_UNORM;i.extent={width,height,1};i.mipLevels=1;i.arrayLayers=1;i.samples=VK_SAMPLE_COUNT_1_BIT;i.tiling=VK_IMAGE_TILING_OPTIMAL;i.usage=FROST_WSI_USAGE;return i;
}
void require(bool ok,const char* text) {if(!ok)throw std::runtime_error(text);}
}
struct ImageBridge::State {
    std::unique_ptr<gpu::Instance> instance;
    VkPhysicalDevice physical{};VkDevice device{};VkQueue queue{};VkSurfaceKHR surface{};VkSwapchainKHR swapchain{};
    VkExtent2D extent{};uint32_t family=0;std::array<uint8_t,16> uuid{},driver_uuid{};
    VkQueryPool timestamps{};uint32_t timestamp_bits=0;float timestamp_period=0;double copy_gpu_ns=0;
    uint64_t copy_measured=0;double acquire_cpu_ns=0,submit_wait_cpu_ns=0,present_wait_cpu_ns=0,present_call_cpu_ns=0;
    VkCommandPool pool{};VkCommandBuffer command{};VkFence submitted{},presented{};VkSemaphore acquired{},finished{},guest_ready{};
    std::vector<VkImage> images;uint64_t frames=0;
    ANativeWindow* owned_window=nullptr;
    bool submitted_pending=false,present_pending=false,poisoned=false;
    std::vector<std::pair<VkImage,VkDeviceMemory>> retained;
    std::array<std::pair<VkImage,VkDeviceMemory>,4> imported{};
    std::shared_ptr<Process> child;
    Fd channel;
    unsigned session_frame=0,session_total=0;
    uint64_t total_imports=0,sync_imports=0,sync_real_fds=0;bool sync_supported=false;PFN_vkImportSemaphoreFdKHR import_semaphore=nullptr;
    std::array<FrostWsiImage,4> imported_info{};
    std::thread worker; std::atomic<bool> stopping{false};
    std::mutex status_lock; std::string guest_status="{}";
    int server_fd=-1,peer_fd=-1;
    void stopGuest() {
        stopping=true;
        {std::lock_guard guard(status_lock);if(server_fd>=0)shutdown(server_fd,SHUT_RDWR);if(peer_fd>=0)shutdown(peer_fd,SHUT_RDWR);}
        if(worker.joinable())worker.join();
    }
    void clearSession() {
        if(child) {child->cancel();child.reset();}
        if(channel.value>=0){close(channel.take());}
        require(drain(),"Session GPU still pending; recover runtime");
        for(auto& slot:imported) {if(slot.first)vkDestroyImage(device,slot.first,nullptr);if(slot.second)vkFreeMemory(device,slot.second,nullptr);slot={};}
        session_frame=session_total=0;
    }
    bool drain() noexcept {
        if(submitted_pending) {auto r=vkWaitForFences(device,1,&submitted,VK_TRUE,deadline);if(r!=VK_SUCCESS&&r!=VK_ERROR_DEVICE_LOST)return false;submitted_pending=false;}
        if(present_pending) {auto r=vkWaitForFences(device,1,&presented,VK_TRUE,deadline);if(r!=VK_SUCCESS&&r!=VK_ERROR_DEVICE_LOST)return false;present_pending=false;}
        return true;
    }
    ~State() {
        stopGuest();
        if(child){child->cancel();child.reset();}
        if(device) {
            // Bound retirement even on a driver stall. Never destroy resources still in use.
            // If the driver does not retire, only process recovery may reclaim this context.
            if(!drain()) {instance.release();return;}
            for(auto [image,memory]:imported) {if(image)vkDestroyImage(device,image,nullptr);if(memory)vkFreeMemory(device,memory,nullptr);}
            for(auto [image,memory]:retained) {vkDestroyImage(device,image,nullptr);vkFreeMemory(device,memory,nullptr);}
            if(timestamps)vkDestroyQueryPool(device,timestamps,nullptr);
            if(pool)vkDestroyCommandPool(device,pool,nullptr);
            if(submitted)vkDestroyFence(device,submitted,nullptr);if(presented)vkDestroyFence(device,presented,nullptr);
            if(guest_ready)vkDestroySemaphore(device,guest_ready,nullptr);
            if(acquired)vkDestroySemaphore(device,acquired,nullptr);if(finished)vkDestroySemaphore(device,finished,nullptr);
            if(swapchain)vkDestroySwapchainKHR(device,swapchain,nullptr);vkDestroyDevice(device,nullptr);
        }
        if(surface&&instance)vkDestroySurfaceKHR(instance->handle,surface,nullptr);
        if(owned_window)ANativeWindow_release(owned_window);
    }
    void init(ANativeWindow* window) {
        retained.reserve(1);ANativeWindow_acquire(window);owned_window=window;
        instance=std::make_unique<gpu::Instance>(true);
        VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};sci.window=window;check(vkCreateAndroidSurfaceKHR(instance->handle,&sci,nullptr,&surface),"host Surface");
        uint32_t n=0;check(vkEnumeratePhysicalDevices(instance->handle,&n,nullptr),"host devices");std::vector<VkPhysicalDevice> ds(n);check(vkEnumeratePhysicalDevices(instance->handle,&n,ds.data()),"host devices");
        for(auto d:ds) {
            VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(d,&p);if(p.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU)continue;
            auto ext=gpu::extensions(d);if(!gpu::has(ext,VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)||!gpu::has(ext,VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME))continue;
            uint32_t qn=0;vkGetPhysicalDeviceQueueFamilyProperties(d,&qn,nullptr);std::vector<VkQueueFamilyProperties> qs(qn);vkGetPhysicalDeviceQueueFamilyProperties(d,&qn,qs.data());
            for(uint32_t j=0;j<qn;j++){VkBool32 support=0;check(vkGetPhysicalDeviceSurfaceSupportKHR(d,j,surface,&support),"host queue support");if(qs[j].queueCount&&(qs[j].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&support){physical=d;family=j;timestamp_bits=qs[j].timestampValidBits;timestamp_period=p.limits.timestampPeriod;break;}}if(physical)break;
        }require(physical,"No hardware GPU with external FD and present maintenance");
        VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};VkPhysicalDeviceProperties2 p{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};p.pNext=&id;vkGetPhysicalDeviceProperties2(physical,&p);std::memcpy(uuid.data(),id.deviceUUID,16);std::memcpy(driver_uuid.data(),id.driverUUID,16);
        VkPhysicalDeviceExternalImageFormatInfo ei{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};ei.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        VkPhysicalDeviceImageFormatInfo2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};query.pNext=&ei;query.format=VK_FORMAT_R8G8B8A8_UNORM;query.type=VK_IMAGE_TYPE_2D;query.tiling=VK_IMAGE_TILING_OPTIMAL;query.usage=FROST_WSI_USAGE;
        VkExternalImageFormatProperties ex{VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};VkImageFormatProperties2 fp{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};fp.pNext=&ex;check(vkGetPhysicalDeviceImageFormatProperties2(physical,&query,&fp),"host opaque image query");
        require((ex.externalMemoryProperties.externalMemoryFeatures&VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT)!=0,"Image FD not importable");
        VkPhysicalDeviceSwapchainMaintenance1FeaturesEXT maintenance{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SWAPCHAIN_MAINTENANCE_1_FEATURES_EXT};VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};features.pNext=&maintenance;vkGetPhysicalDeviceFeatures2(physical,&features);require(maintenance.swapchainMaintenance1,"Present retirement unavailable");
        VkPhysicalDeviceExternalSemaphoreInfo sq{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO};sq.handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        VkExternalSemaphoreProperties sp{VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};vkGetPhysicalDeviceExternalSemaphoreProperties(physical,&sq,&sp);
        sync_supported=(sp.externalSemaphoreFeatures&VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT)&&gpu::has(gpu::extensions(physical),VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
        float priority=1;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=family;qi.queueCount=1;qi.pQueuePriorities=&priority;
        const char* extensions[]={VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,VK_KHR_SWAPCHAIN_EXTENSION_NAME,VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME,VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME};VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.pNext=&maintenance;di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;di.enabledExtensionCount=sync_supported?4:3;di.ppEnabledExtensionNames=extensions;
        check(vkCreateDevice(physical,&di,nullptr,&device),"host device");vkGetDeviceQueue(device,family,0,&queue);if(sync_supported){import_semaphore=reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(vkGetDeviceProcAddr(device,"vkImportSemaphoreFdKHR"));require(import_semaphore,"SYNC_FD function unavailable");}
        VkSurfaceCapabilitiesKHR caps{};check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,&caps),"host capabilities");require((caps.supportedUsageFlags&VK_IMAGE_USAGE_TRANSFER_DST_BIT)!=0,"Swapchain lacks transfer destination");
        extent=caps.currentExtent;if(extent.width==UINT32_MAX)extent={static_cast<uint32_t>(ANativeWindow_getWidth(window)),static_cast<uint32_t>(ANativeWindow_getHeight(window))};require(extent.width&&extent.height,"Empty surface");
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&n,nullptr),"host formats");std::vector<VkSurfaceFormatKHR> fs(n);check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&n,fs.data()),"host formats");bool found=false;for(auto f:fs)if(f.format==VK_FORMAT_R8G8B8A8_UNORM&&f.colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)found=true;require(found,"Diagnostic requires RGBA8 swapchain");
        VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};ci.surface=surface;ci.minImageCount=caps.minImageCount+1;if(caps.maxImageCount)ci.minImageCount=std::min(ci.minImageCount,caps.maxImageCount);ci.imageFormat=VK_FORMAT_R8G8B8A8_UNORM;ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;ci.imageExtent=extent;ci.imageArrayLayers=1;ci.imageUsage=VK_IMAGE_USAGE_TRANSFER_DST_BIT;ci.preTransform=(caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)?VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:caps.currentTransform;ci.presentMode=VK_PRESENT_MODE_FIFO_KHR;ci.clipped=VK_TRUE;
        for(auto alpha:{VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR})if(caps.supportedCompositeAlpha&alpha){ci.compositeAlpha=alpha;break;}require(ci.compositeAlpha!=0,"No supported alpha mode");
        check(vkCreateSwapchainKHR(device,&ci,nullptr,&swapchain),"host swapchain");check(vkGetSwapchainImagesKHR(device,swapchain,&n,nullptr),"host images");images.resize(n);check(vkGetSwapchainImagesKHR(device,swapchain,&n,images.data()),"host images");
        if(timestamp_bits){VkQueryPoolCreateInfo qp{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};qp.queryType=VK_QUERY_TYPE_TIMESTAMP;qp.queryCount=2;check(vkCreateQueryPool(device,&qp,nullptr,&timestamps),"host timestamp pool");}
        VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pc.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pc.queueFamilyIndex=family;check(vkCreateCommandPool(device,&pc,nullptr,&pool),"host pool");VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;check(vkAllocateCommandBuffers(device,&ca,&command),"host commands");
        VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(device,&fc,nullptr,&submitted),"host fence");check(vkCreateFence(device,&fc,nullptr,&presented),"host present fence");VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};check(vkCreateSemaphore(device,&sc,nullptr,&acquired),"host acquire semaphore");check(vkCreateSemaphore(device,&sc,nullptr,&finished),"host present semaphore");if(sync_supported)check(vkCreateSemaphore(device,&sc,nullptr,&guest_ready),"host guest-ready semaphore");
    }
    void copy(FrostWsiImage info,Fd& fd,bool persistent=false,Fd* synchronization=nullptr,int slot=-1) {
        require(info.magic==FROST_WSI_MAGIC&&(slot>=0?(info.width>0&&info.height>0&&info.width<=4096&&info.height<=4096):(info.width==extent.width&&info.height==extent.height))&&info.format==VK_FORMAT_R8G8B8A8_UNORM,"Invalid external image dimensions/protocol");
        require(info.frame<1000000&&info.memory_type<32&&info.allocation_size>0&&info.allocation_size<=512ull*1024*1024,"Invalid memory metadata");require(!std::memcmp(info.device_uuid,uuid.data(),16)&&!std::memcmp(info.driver_uuid,driver_uuid.data(),16),"Different physical device/driver UUID");
        unsigned index_slot=slot>=0?static_cast<unsigned>(slot):info.frame%2;
        require(index_slot<imported.size(),"Invalid guest image slot");
        auto& cached=imported[index_slot];
        VkImage image=persistent?cached.first:VkImage{};VkDeviceMemory memory=persistent?cached.second:VkDeviceMemory{};
        require((image&&fd.value<0)||(!image&&fd.value>=0),"Pool descriptor reuse mismatch");
        if(image)require(info.memory_type==imported_info[index_slot].memory_type&&info.allocation_size==imported_info[index_slot].allocation_size,"Pool metadata changed");
        struct Retire {State& s;VkImage& i;VkDeviceMemory& m;bool pooled;~Retire(){
            if(!s.drain()){s.poisoned=true;if(!pooled)s.retained.emplace_back(i,m);return;}
            if(pooled)return;
            if(i)vkDestroyImage(s.device,i,nullptr);if(m)vkFreeMemory(s.device,m,nullptr);
        }} retire{*this,image,memory,persistent};
        if(!image) {
        VkExternalMemoryImageCreateInfo ex{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};ex.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;auto ci=imageInfo(info.width,info.height,&ex);check(vkCreateImage(device,&ci,nullptr,&image),"host import image");if(persistent)cached.first=image;VkMemoryRequirements req{};vkGetImageMemoryRequirements(device,image,&req);require(req.size==info.allocation_size&&(req.memoryTypeBits&(1u<<info.memory_type)),"Opaque FD allocation mismatch");
        VkImportMemoryFdInfoKHR import{VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR};import.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;import.fd=fd.value;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};dedicated.pNext=&import;dedicated.image=image;VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};alloc.pNext=&dedicated;alloc.allocationSize=info.allocation_size;alloc.memoryTypeIndex=info.memory_type;
        check(vkAllocateMemory(device,&alloc,nullptr,&memory),"host import opaque FD");fd.take();if(persistent)cached.second=memory;check(vkBindImageMemory(device,image,memory,0),"host bind imported image");total_imports++;if(persistent)imported_info[index_slot]=info;
        }
        require(info.reserved==0&&info.sync_fd<=2,"Unsupported synchronization protocol");
        if(info.sync_fd){
            require(persistent&&sync_supported&&synchronization,"SYNC_FD import unsupported");
            require(info.sync_fd==2?synchronization->value<0:synchronization->value>=0,"Missing sync FD");
            VkImportSemaphoreFdInfoKHR sf{VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR};sf.semaphore=guest_ready;sf.flags=VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;sf.handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;sf.fd=synchronization->value;
            check(import_semaphore(device,&sf),"host SYNC_FD import");if(synchronization->value>=0){synchronization->take();sync_real_fds++;}sync_imports++;
        }
        auto clock=[](){return std::chrono::steady_clock::now();};auto ns=[](auto a,auto b){return std::chrono::duration<double,std::nano>(b-a).count();};
        auto started=clock();uint32_t index=0;auto r=vkAcquireNextImageKHR(device,swapchain,deadline,acquired,nullptr,&index);if(r!=VK_SUBOPTIMAL_KHR)check(r,"host acquire");acquire_cpu_ns+=ns(started,clock());
        check(vkResetCommandBuffer(command,0),"host reset commands");VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};check(vkBeginCommandBuffer(command,&bi),"host begin");
        if(timestamps){vkCmdResetQueryPool(command,timestamps,0,2);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,timestamps,0);}
        VkImageMemoryBarrier barriers[2]{};for(auto& b:barriers){b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};}
        auto& source=barriers[0];source.image=image;source.oldLayout=VK_IMAGE_LAYOUT_GENERAL;source.newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;source.srcQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;source.dstQueueFamilyIndex=family;source.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
        auto& target=barriers[1];target.image=images[index];target.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;target.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;target.srcQueueFamilyIndex=target.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;target.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,2,barriers);
        VkImageCopy region{};region.srcSubresource=region.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};region.extent={info.width,info.height,1};if(info.width==extent.width&&info.height==extent.height) {
            vkCmdCopyImage(command,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,images[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&region);
        } else {
            VkFormatProperties format{};vkGetPhysicalDeviceFormatProperties(physical,VK_FORMAT_R8G8B8A8_UNORM,&format);
            require((format.optimalTilingFeatures&(VK_FORMAT_FEATURE_BLIT_SRC_BIT|VK_FORMAT_FEATURE_BLIT_DST_BIT))==(VK_FORMAT_FEATURE_BLIT_SRC_BIT|VK_FORMAT_FEATURE_BLIT_DST_BIT),"RGBA blit unsupported");
            VkImageBlit blit{};blit.srcSubresource=blit.dstSubresource=region.srcSubresource;
            blit.srcOffsets[1]={static_cast<int>(info.width),static_cast<int>(info.height),1};blit.dstOffsets[1]={static_cast<int>(extent.width),static_cast<int>(extent.height),1};
            vkCmdBlitImage(command,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,images[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&blit,VK_FILTER_NEAREST);
        }
        source.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;source.newLayout=VK_IMAGE_LAYOUT_GENERAL;source.srcQueueFamilyIndex=family;source.dstQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;source.srcAccessMask=VK_ACCESS_TRANSFER_READ_BIT;source.dstAccessMask=0;
        target.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;target.newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;target.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;target.dstAccessMask=0;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,nullptr,0,nullptr,2,barriers);if(timestamps)vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,timestamps,1);check(vkEndCommandBuffer(command),"host end");
        check(vkResetFences(device,1,&submitted),"host reset submit fence");check(vkResetFences(device,1,&presented),"host reset present fence");VkPipelineStageFlags stages[]={VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT};VkSemaphore waits[]={acquired,guest_ready};
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=info.sync_fd?2:1;si.pWaitSemaphores=waits;si.pWaitDstStageMask=stages;si.commandBufferCount=1;si.pCommandBuffers=&command;si.signalSemaphoreCount=1;si.pSignalSemaphores=&finished;check(vkQueueSubmit(queue,1,&si,submitted),"host image copy submit");submitted_pending=true;
        VkSwapchainPresentFenceInfoEXT fence{VK_STRUCTURE_TYPE_SWAPCHAIN_PRESENT_FENCE_INFO_EXT};fence.swapchainCount=1;fence.pFences=&presented;VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.pNext=&fence;pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&finished;pi.swapchainCount=1;pi.pSwapchains=&swapchain;pi.pImageIndices=&index;
        started=clock();r=vkQueuePresentKHR(queue,&pi);present_call_cpu_ns+=ns(started,clock());if(r!=VK_SUBOPTIMAL_KHR)check(r,"host GPU present");present_pending=true;started=clock();check(vkWaitForFences(device,1,&submitted,VK_TRUE,deadline),"host copy completion");submitted_pending=false;submit_wait_cpu_ns+=ns(started,clock());started=clock();check(vkWaitForFences(device,1,&presented,VK_TRUE,deadline),"host present retirement");present_pending=false;present_wait_cpu_ns+=ns(started,clock());
        if(timestamps){uint64_t values[2]{};check(vkGetQueryPoolResults(device,timestamps,0,2,sizeof(values),values,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT),"host copy timestamps");uint64_t delta=values[1]-values[0];if(timestamp_bits<64)delta&=(1ull<<timestamp_bits)-1;copy_gpu_ns+=delta*static_cast<double>(timestamp_period);copy_measured++;}frames++;
    }
};
ImageBridge::ImageBridge(ANativeWindow* window):state(std::make_unique<State>()){state->init(window);}
ImageBridge::~ImageBridge()=default;

std::string ImageBridge::startGuest(const std::string& endpoint) {
    require(!state->worker.joinable()&&!state->child,"Bridge session already active");
    require(endpoint.size()>1&&endpoint[0]=='@'&&endpoint.size()<sizeof(sockaddr_un::sun_path),"Invalid private endpoint");
    Fd server;server.value=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);require(server.value>=0,"Guest socket failed");
    sockaddr_un address{};address.sun_family=AF_UNIX;std::strcpy(address.sun_path,endpoint.c_str());address.sun_path[0]=0;
    require(!bind(server.value,reinterpret_cast<sockaddr*>(&address),offsetof(sockaddr_un,sun_path)+endpoint.size())&&!listen(server.value,1),"Guest endpoint bind failed");
    state->server_fd=server.take();
    state->worker=std::thread([s=state.get()] {
        Fd peer;uint32_t generation=0,next_frame=0;pid_t producer=0;
        auto status=[&](const std::string& error){
            std::lock_guard guard(s->status_lock);
            // Error messages here are fixed native labels, no untrusted guest strings.
            s->guest_status="{\"frames\":"+std::to_string(s->frames)+",\"generation\":"+std::to_string(generation)+",\"guestPid\":"+std::to_string(producer)+",\"uid\":"+std::to_string(getuid())+",\"imports\":"+std::to_string(s->total_imports)+",\"syncFds\":"+std::to_string(s->sync_real_fds)+",\"error\":\""+error+"\"}";
        };
        try {
          while(!s->stopping) {
            pollfd poller{s->server_fd,POLLIN,0};while(!s->stopping&&poll(&poller,1,100)==0){}
            require(!s->stopping,"Guest session revoked");peer.value=accept4(s->server_fd,nullptr,nullptr,SOCK_CLOEXEC);require(peer.value>=0,"Guest accept failed");
            {std::lock_guard guard(s->status_lock);s->peer_fd=peer.value;}
            ucred credentials{};socklen_t length=sizeof(credentials);require(!getsockopt(peer.value,SOL_SOCKET,SO_PEERCRED,&credentials,&length)&&credentials.uid==getuid(),"Guest UID mismatch");producer=credentials.pid;
            timeval timeout{10,0};setsockopt(peer.value,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(peer.value,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
            bool initialized=false;
            while(!s->stopping) {
                FrostGuestRequest request{};iovec iov{&request,sizeof(request)};alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int)*4)]{};msghdr msg{};msg.msg_iov=&iov;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
                auto bytes=recvmsg(peer.value,&msg,MSG_WAITALL|MSG_CMSG_CLOEXEC);if(bytes==0)break;
                std::array<Fd,4> descriptors;unsigned count=0;
                for(auto*c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c))if(c->cmsg_level==SOL_SOCKET&&c->cmsg_type==SCM_RIGHTS){auto n=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);auto*fds=reinterpret_cast<int*>(CMSG_DATA(c));for(size_t j=0;j<n;j++){if(count<descriptors.size())descriptors[count].value=fds[j];else close(fds[j]);count++;}}
                require(bytes==sizeof(request)&&!(msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC))&&count<=2&&request.version==FROST_GUEST_VERSION,"Malformed guest packet");
                FrostGuestReply reply{};reply.version=FROST_GUEST_VERSION;reply.generation=request.generation;reply.frame=request.image.frame;reply.width=s->extent.width;reply.height=s->extent.height;reply.max_images=4;reply.sync_supported=s->sync_supported;std::memcpy(reply.device_uuid,s->uuid.data(),16);std::memcpy(reply.driver_uuid,s->driver_uuid.data(),16);
                if(request.operation==FROST_GUEST_HELLO){require(!initialized&&!count,"Invalid guest hello");initialized=true;}
                else if(request.operation==FROST_GUEST_RESET){require(initialized&&!count&&request.generation==generation+1,"Stale guest generation");s->clearSession();generation=request.generation;next_frame=0;}
                else {
                    require(initialized&&request.operation==FROST_GUEST_PRESENT&&request.generation==generation&&generation&&request.slot<4&&request.image.frame==next_frame,"Invalid guest frame order");
                    bool first=!s->imported[request.slot].first;require(request.image.sync_fd==1||request.image.sync_fd==2,"Guest synchronization required");require(count==(first?1u:0u)+(request.image.sync_fd==1?1u:0u),"Guest FD count mismatch");
                    Fd memory,sync;if(first)memory.value=descriptors[0].take();if(request.image.sync_fd==1)sync.value=descriptors[first?1:0].take();
                    s->copy(request.image,memory,true,&sync,request.slot);next_frame++;
                }
                status("");require(send(peer.value,&reply,sizeof(reply),MSG_NOSIGNAL)==sizeof(reply),"Guest retirement ACK failed");
            }
            status("");
            {std::lock_guard guard(s->status_lock);s->peer_fd=-1;}
            close(peer.take());
            if(generation)break; // A completed producer requires a fresh owner session.
          }
        } catch(const std::exception& e){status(e.what());}
        {std::lock_guard guard(s->status_lock);s->peer_fd=-1;if(s->server_fd>=0){close(s->server_fd);s->server_fd=-1;}}
    });
    return endpoint;
}
std::string ImageBridge::guestStatus(){std::lock_guard guard(state->status_lock);return state->guest_status;}
std::string ImageBridge::frame(const std::string& native_dir,const std::string& work,unsigned color) {
    require(color<=1,"Invalid diagnostic color");require(!state->poisoned,"GPU retirement failed; restart runtime before another image diagnostic");
    Socket server;server.path=work+"/frost-wsi.sock";require(server.path.size()<sizeof(sockaddr_un::sun_path),"Socket path too long");
    // Never unlink an existing endpoint owned by another run.
    server.value=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);require(server.value>=0,"Create image socket failed");sockaddr_un address{};address.sun_family=AF_UNIX;std::strcpy(address.sun_path,server.path.c_str());
    if(bind(server.value,reinterpret_cast<sockaddr*>(&address),sizeof(address))){server.path.clear();throw std::runtime_error("Image socket bind failed");}require(!listen(server.value,1),"Image socket listen failed");
    auto cmd=Box64Backend(std::filesystem::path(native_dir)/"libbox64_vulkan.so").diagnostic(std::filesystem::path(work)/"wsi-export-x64.elf",work,true);
    cmd.arguments.insert(cmd.arguments.end(),{server.path,std::to_string(state->extent.width),std::to_string(state->extent.height),std::to_string(color)});cmd.timeout=std::chrono::seconds(20);cmd.environment["BOX64_LOG"]="1";cmd.environment["BOX64_DYNAREC_DUMP"]="0";
    auto process=Process::spawn(std::move(cmd));
    try {
        pollfd p{server.value,POLLIN,0};
        const auto connect_end=std::chrono::steady_clock::now()+std::chrono::seconds(10);
        while(poll(&p,1,100)==0&&process->poll().running&&std::chrono::steady_clock::now()<connect_end){}
        require((p.revents&POLLIN)!=0,"Guest did not send image; inspect child log");
        Fd socket;socket.value=accept4(server.value,nullptr,nullptr,SOCK_CLOEXEC);require(socket.value>=0,"Image accept failed");timeval timeout{5,0};setsockopt(socket.value,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(socket.value,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
        ucred peer{};socklen_t length=sizeof(peer);require(!getsockopt(socket.value,SOL_SOCKET,SO_PEERCRED,&peer,&length)&&peer.uid==getuid()&&peer.pid==process->poll().pid,"Unexpected image producer PID/UID");
        FrostWsiImage info{};iovec iov{&info,sizeof(info)};alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int)*4)]{};msghdr msg{};msg.msg_iov=&iov;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
        auto size=recvmsg(socket.value,&msg,MSG_WAITALL|MSG_CMSG_CLOEXEC);Fd memory;unsigned descriptors=0;
        for(auto* c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c))if(c->cmsg_level==SOL_SOCKET&&c->cmsg_type==SCM_RIGHTS){auto count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);auto* fds=reinterpret_cast<int*>(CMSG_DATA(c));for(size_t i=0;i<count;i++){if(!descriptors)memory.value=fds[i];else close(fds[i]);descriptors++;}}
        require(size==sizeof(info)&&!(msg.msg_flags&(MSG_CTRUNC|MSG_TRUNC))&&descriptors==1&&memory.value>=0,"Invalid image packet/FD count");require(info.frame==color,"Wrong diagnostic frame");
        state->copy(info,memory);const unsigned char ack=42;require(send(socket.value,&ack,1,MSG_NOSIGNAL)==1,"Image acknowledgement failed");
        ProcessSnapshot result;const auto end=std::chrono::steady_clock::now()+std::chrono::seconds(5);do {result=process->poll();if(!result.running)break;poll(nullptr,0,10);}while(std::chrono::steady_clock::now()<end);
        require(!result.running&&result.exit_code==42&&!result.signal&&!result.exec_errno&&!result.timed_out,"Image guest failed after present");
        return "{\"windowsPresentation\":false,\"guest\":\"linux-x86-64-box64\",\"hostPid\":"+std::to_string(getpid())+",\"uid\":"+std::to_string(getuid())+",\"presentedFrames\":"+std::to_string(state->frames)+",\"frame\":"+std::to_string(color)+",\"memoryBytes\":"+std::to_string(info.allocation_size)+",\"sameDeviceUuid\":true,\"child\":"+result.json()+"}";
    } catch(const std::exception& e) {process->cancel();auto result=process->poll();throw std::runtime_error(std::string(e.what())+" CHILD="+result.json());}
}

std::string ImageBridge::persistentFrame(const std::string& native_dir,const std::string& work,unsigned total) {
    require(total>=2&&total<=1000,"Invalid bounded session length");require(!state->poisoned,"GPU context requires process recovery");
    try {
        if(!state->child||state->session_frame==state->session_total) {
            state->clearSession();
            Socket server;static std::atomic<unsigned> nonce{0};
            // Abstract names disappear with the socket, including SIGKILL: no stale filesystem node.
            server.path="@frost-wsi-"+std::to_string(getuid())+"-"+std::to_string(getpid())+"-"+std::to_string(++nonce);
            server.value=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);require(server.value>=0,"Session socket failed");
            sockaddr_un address{};address.sun_family=AF_UNIX;std::strcpy(address.sun_path,server.path.c_str());address.sun_path[0]=0;
            auto length=static_cast<socklen_t>(offsetof(sockaddr_un,sun_path)+server.path.size());
            require(!bind(server.value,reinterpret_cast<sockaddr*>(&address),length)&&!listen(server.value,1),"Session bind/listen failed");
            auto cmd=Box64Backend(std::filesystem::path(native_dir)/"libbox64_vulkan.so").diagnostic(std::filesystem::path(work)/"wsi-export-x64.elf",work,true);
            cmd.arguments.insert(cmd.arguments.end(),{server.path,std::to_string(state->extent.width),std::to_string(state->extent.height),"0",std::to_string(total)});
            cmd.timeout=std::chrono::seconds(120);cmd.output_limit=262144;cmd.environment["BOX64_LOG"]="1";cmd.environment["BOX64_DYNAREC_DUMP"]="0";
            state->child=Process::spawn(std::move(cmd));state->session_total=total;
            pollfd p{server.value,POLLIN,0};auto end=std::chrono::steady_clock::now()+std::chrono::seconds(10);
            while(poll(&p,1,100)==0&&state->child->poll().running&&std::chrono::steady_clock::now()<end){}
            require((p.revents&POLLIN)!=0,"Persistent guest failed to connect");
            state->channel.value=accept4(server.value,nullptr,nullptr,SOCK_CLOEXEC);require(state->channel.value>=0,"Session accept failed");
            timeval timeout{5,0};setsockopt(state->channel.value,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(state->channel.value,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
            ucred peer{};socklen_t size=sizeof(peer);require(!getsockopt(state->channel.value,SOL_SOCKET,SO_PEERCRED,&peer,&size)&&peer.uid==getuid()&&peer.pid==state->child->poll().pid,"Session producer PID/UID mismatch");
        }
        require(total==state->session_total,"Session length changed");
        FrostWsiImage info{};iovec iov{&info,sizeof(info)};alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int)*4)]{};msghdr msg{};msg.msg_iov=&iov;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
        auto size=recvmsg(state->channel.value,&msg,MSG_WAITALL|MSG_CMSG_CLOEXEC);Fd memory,synchronization;unsigned descriptors=0;const bool first=state->session_frame<2;
        for(auto* c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c))if(c->cmsg_level==SOL_SOCKET&&c->cmsg_type==SCM_RIGHTS){auto count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);auto* fds=reinterpret_cast<int*>(CMSG_DATA(c));for(size_t i=0;i<count;i++){if(first&&descriptors==0)memory.value=fds[i];else if(descriptors==(first?1u:0u))synchronization.value=fds[i];else close(fds[i]);descriptors++;}}
        require(size==sizeof(info)&&!(msg.msg_flags&(MSG_CTRUNC|MSG_TRUNC))&&descriptors==((first?1u:0u)+(info.sync_fd==1?1u:0u)),"Invalid persistent packet/FD count");
        require(info.frame==state->session_frame,"Duplicate/stale/out-of-order frame");
        state->copy(info,memory,true,&synchronization);state->session_frame++;
        const unsigned char ack=42;require(send(state->channel.value,&ack,1,MSG_NOSIGNAL)==1,"Session ACK failed");
        auto result=state->child->poll();
        if(state->session_frame==total){auto end=std::chrono::steady_clock::now()+std::chrono::seconds(5);while(result.running&&std::chrono::steady_clock::now()<end){poll(nullptr,0,10);result=state->child->poll();}require(!result.running&&result.exit_code==42&&!result.signal&&!result.exec_errno&&!result.timed_out,"Persistent guest exit failure");}
        else require(result.running,"Persistent guest died before final frame");
        return "{\"windowsPresentation\":false,\"guest\":\"linux-x86-64-box64\",\"hostPid\":"+std::to_string(getpid())+",\"uid\":"+std::to_string(getuid())+",\"presentedFrames\":"+std::to_string(state->frames)+",\"sessionFrame\":"+std::to_string(state->session_frame)+",\"poolSize\":2,\"imports\":"+std::to_string(state->total_imports)+",\"memoryBytes\":"+std::to_string(info.allocation_size*2)+",\"sync\":\"sync-fd-ready-fence-ack-retirement\",\"syncImports\":"+std::to_string(state->sync_imports)+",\"realSyncFds\":"+std::to_string(state->sync_real_fds)+",\"sameDeviceUuid\":true,\"timings\":{\"copyGpuTotalNs\":"+std::to_string(state->copy_gpu_ns)+",\"gpuSamples\":"+std::to_string(state->copy_measured)+",\"acquireCpuTotalNs\":"+std::to_string(state->acquire_cpu_ns)+",\"submitWaitCpuTotalNs\":"+std::to_string(state->submit_wait_cpu_ns)+",\"presentCallCpuTotalNs\":"+std::to_string(state->present_call_cpu_ns)+",\"presentRetireCpuTotalNs\":"+std::to_string(state->present_wait_cpu_ns)+"},\"child\":"+result.json()+"}";
    }catch(const std::exception& e){state->poisoned=true;std::string report=state->child?state->child->poll().json():"{}";if(state->child)state->child->cancel();throw std::runtime_error(std::string(e.what())+" CHILD="+report);}
}
}
