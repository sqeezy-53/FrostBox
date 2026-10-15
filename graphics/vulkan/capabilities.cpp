// SPDX-License-Identifier: MIT
#include "gpu.hpp"
#include <cstring>
#include <unistd.h>
namespace frostbox::gpu {
bool has(const std::vector<VkExtensionProperties>& list,const char* name) {
    for(const auto& e:list)if(!strcmp(e.extensionName,name))return true;
    return false;
}
std::vector<VkExtensionProperties> extensions(VkPhysicalDevice physical) {
    uint32_t count=0;check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&count,nullptr),"device extension count");
    std::vector<VkExtensionProperties> list(count);check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&count,list.data()),"device extensions");list.resize(count);return list;
}
Instance::Instance(bool wsi) {
    uint32_t loader=VK_API_VERSION_1_0;
    auto getVersion=reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(nullptr,"vkEnumerateInstanceVersion"));
    if(getVersion)check(getVersion(&loader),"loader version");
    api=std::min(loader,VK_API_VERSION_1_1);
    uint32_t count=0;check(vkEnumerateInstanceExtensionProperties(nullptr,&count,nullptr),"instance extension count");
    std::vector<VkExtensionProperties> list(count);check(vkEnumerateInstanceExtensionProperties(nullptr,&count,list.data()),"instance extensions");
    const char* names[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME,VK_EXT_SURFACE_MAINTENANCE_1_EXTENSION_NAME};
    if(wsi)for(auto name:names)if(!has(list,name))throw std::runtime_error(std::string("Missing instance extension: ")+name);
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="FrostBox Vulkan Foundation";app.apiVersion=api;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;
    ci.enabledExtensionCount=wsi?4:0;ci.ppEnabledExtensionNames=wsi?names:nullptr;
    check(vkCreateInstance(&ci,nullptr,&handle),"vkCreateInstance");
}
std::string capabilities(VkInstance instance,uint32_t api,VkSurfaceKHR surface) {
    uint32_t n=0;check(vkEnumeratePhysicalDevices(instance,&n,nullptr),"physical device count");
    if(!n)throw std::runtime_error("No Vulkan physical device");
    std::vector<VkPhysicalDevice> devices(n);check(vkEnumeratePhysicalDevices(instance,&n,devices.data()),"physical devices");
    std::ostringstream out;out<<"{\"schema\":1,\"uid\":"<<getuid()<<",\"pid\":"<<getpid()<<",\"instanceApi\":"<<quote(version(api))<<",\"surfaceAvailable\":"<<(surface?"true":"false")<<",\"devices\":[";
    for(uint32_t i=0;i<n;i++) {
        if(i)out<<',';auto d=devices[i];VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(d,&p);
        auto ex=extensions(d);
        out<<"{\"name\":"<<quote(p.deviceName)<<",\"vendorId\":"<<p.vendorID<<",\"deviceId\":"<<p.deviceID<<",\"deviceType\":"<<p.deviceType<<",\"apiVersion\":"<<quote(version(p.apiVersion))<<",\"apiVersionRaw\":"<<p.apiVersion<<",\"driverVersionRaw\":"<<p.driverVersion;
        if(api>=VK_API_VERSION_1_1 && (p.apiVersion>=VK_API_VERSION_1_2 || has(ex,VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME))) {
            VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
            VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};props.pNext=&driver;
            vkGetPhysicalDeviceProperties2(d,&props);
            out<<",\"driverId\":"<<driver.driverID<<",\"driverName\":"<<quote(driver.driverName)<<",\"driverInfo\":"<<quote(driver.driverInfo);
        } else out<<",\"driverProperties\":null";
        out<<",\"swapchain\":"<<(has(ex,VK_KHR_SWAPCHAIN_EXTENSION_NAME)?"true":"false")<<",\"androidHardwareBuffer\":"<<(has(ex,VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)?"true":"false")<<",\"timestampPeriodNs\":"<<p.limits.timestampPeriod<<",\"extensions\":[";
        for(size_t j=0;j<ex.size();j++) {if(j)out<<',';out<<"{\"name\":"<<quote(ex[j].extensionName)<<",\"specVersion\":"<<ex[j].specVersion<<'}';}out<<"]";
        uint32_t qn=0;vkGetPhysicalDeviceQueueFamilyProperties(d,&qn,nullptr);std::vector<VkQueueFamilyProperties> queues(qn);vkGetPhysicalDeviceQueueFamilyProperties(d,&qn,queues.data());
        out<<",\"queueFamilies\":[";
        for(uint32_t q=0;q<qn;q++) {
            if(q)out<<',';auto f=queues[q];out<<"{\"index\":"<<q<<",\"flags\":"<<f.queueFlags<<",\"count\":"<<f.queueCount<<",\"graphics\":"<<((f.queueFlags&VK_QUEUE_GRAPHICS_BIT)?"true":"false")<<",\"compute\":"<<((f.queueFlags&VK_QUEUE_COMPUTE_BIT)?"true":"false")<<",\"transfer\":"<<((f.queueFlags&VK_QUEUE_TRANSFER_BIT)?"true":"false")<<",\"timestampValidBits\":"<<f.timestampValidBits<<",\"present\":";
            if(surface) {VkBool32 present=0;check(vkGetPhysicalDeviceSurfaceSupportKHR(d,q,surface,&present),"present support");out<<(present?"true":"false");}else out<<"null";out<<'}';
        }out<<"]";
        VkPhysicalDeviceMemoryProperties memory{};vkGetPhysicalDeviceMemoryProperties(d,&memory);out<<",\"memoryHeaps\":[";
        for(uint32_t j=0;j<memory.memoryHeapCount;j++) {if(j)out<<',';out<<"{\"sizeBytes\":"<<memory.memoryHeaps[j].size<<",\"flags\":"<<memory.memoryHeaps[j].flags<<'}';}out<<"],\"memoryTypes\":[";
        for(uint32_t j=0;j<memory.memoryTypeCount;j++) {if(j)out<<',';out<<"{\"heapIndex\":"<<memory.memoryTypes[j].heapIndex<<",\"propertyFlags\":"<<memory.memoryTypes[j].propertyFlags<<'}';}out<<"]";
        out<<",\"imageFormats\":[";const VkFormat formats[]={VK_FORMAT_R8G8B8A8_UNORM,VK_FORMAT_B8G8R8A8_UNORM,VK_FORMAT_D32_SFLOAT};
        for(size_t j=0;j<3;j++) {
            if(j)out<<',';VkFormatProperties f{};vkGetPhysicalDeviceFormatProperties(d,formats[j],&f);VkImageFormatProperties image{};
            auto r=vkGetPhysicalDeviceImageFormatProperties(d,formats[j],VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,j==2?VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT:VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,0,&image);
            out<<"{\"format\":"<<formats[j]<<",\"linearFeatures\":"<<f.linearTilingFeatures<<",\"optimalFeatures\":"<<f.optimalTilingFeatures<<",\"bufferFeatures\":"<<f.bufferFeatures<<",\"imageResult\":"<<r<<",\"maxWidth\":"<<image.maxExtent.width<<",\"maxHeight\":"<<image.maxExtent.height<<'}';
        }out<<"]";
        out<<",\"externalAhbImage\":";
        if(api>=VK_API_VERSION_1_1 && has(ex,VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) {
            VkPhysicalDeviceExternalImageFormatInfo external{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};external.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
            VkPhysicalDeviceImageFormatInfo2 ci{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};ci.pNext=&external;ci.format=VK_FORMAT_R8G8B8A8_UNORM;ci.type=VK_IMAGE_TYPE_2D;ci.tiling=VK_IMAGE_TILING_OPTIMAL;ci.usage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
            VkExternalImageFormatProperties ext{VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};VkImageFormatProperties2 props{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};props.pNext=&ext;
            auto r=vkGetPhysicalDeviceImageFormatProperties2(d,&ci,&props);out<<"{\"result\":"<<r<<",\"externalMemoryFeatures\":"<<ext.externalMemoryProperties.externalMemoryFeatures<<",\"compatibleHandleTypes\":"<<ext.externalMemoryProperties.compatibleHandleTypes<<'}';
        } else out<<"null";
        out<<",\"surfaceFormats\":";
        if(surface) {
            uint32_t count=0;check(vkGetPhysicalDeviceSurfaceFormatsKHR(d,surface,&count,nullptr),"surface format count");std::vector<VkSurfaceFormatKHR> f(count);check(vkGetPhysicalDeviceSurfaceFormatsKHR(d,surface,&count,f.data()),"surface formats");out<<'[';
            for(uint32_t j=0;j<count;j++){if(j)out<<',';out<<"{\"format\":"<<f[j].format<<",\"colorSpace\":"<<f[j].colorSpace<<'}';}out<<']';
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(d,surface,&count,nullptr),"present mode count");std::vector<VkPresentModeKHR> modes(count);check(vkGetPhysicalDeviceSurfacePresentModesKHR(d,surface,&count,modes.data()),"present modes");out<<",\"presentModes\":[";
            for(uint32_t j=0;j<count;j++){if(j)out<<',';out<<modes[j];}out<<']';
            VkSurfaceCapabilitiesKHR c{};check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(d,surface,&c),"surface capabilities");out<<",\"surfaceCapabilities\":{\"minImageCount\":"<<c.minImageCount<<",\"maxImageCount\":"<<c.maxImageCount<<",\"width\":"<<c.currentExtent.width<<",\"height\":"<<c.currentExtent.height<<",\"currentTransform\":"<<c.currentTransform<<",\"supportedUsageFlags\":"<<c.supportedUsageFlags<<'}';
        } else out<<"null,\"presentModes\":null,\"surfaceReason\":\"No Android Surface in headless probe; Start renderer to query WSI\"";
        out<<'}';
    }out<<"]}";return out.str();
}
std::string probe() {try {Instance instance(false);return capabilities(instance.handle,instance.api);}catch(const std::exception& e){return "{\"schema\":1,\"error\":"+quote(e.what())+'}';}}
}
