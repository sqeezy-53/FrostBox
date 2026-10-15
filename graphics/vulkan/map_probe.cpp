// SPDX-License-Identifier: MIT
// Native control for WoW64 mapping failure. Does not render or replace guest GPU tests.
#include "gpu.hpp"
#include "map_compat.hpp"
#include <sys/mman.h>
#include <unistd.h>
#include <cstdint>
namespace frostbox::gpu {
std::string map_probe() {
    Instance instance(false);VkDevice device=nullptr;VkBuffer buffer=0;VkDeviceMemory memory=0;
    std::ostringstream report;
    try {
        uint32_t count=0;check(vkEnumeratePhysicalDevices(instance.handle,&count,nullptr),"devices");
        std::vector<VkPhysicalDevice> devices(count);check(vkEnumeratePhysicalDevices(instance.handle,&count,devices.data()),"devices");
        if(devices.empty())throw std::runtime_error("No GPU");auto physical=devices.front();
        VkPhysicalDeviceProperties properties{};vkGetPhysicalDeviceProperties(physical,&properties);
        if(!has(extensions(physical),VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME))throw std::runtime_error("No placed mapping extension");
        VkPhysicalDeviceMapMemoryPlacedFeaturesEXT features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
        VkPhysicalDeviceFeatures2 f{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,&features};vkGetPhysicalDeviceFeatures2(physical,&f);
        if(!features.memoryMapPlaced)throw std::runtime_error("Placed mapping feature false");
        VkPhysicalDeviceMapMemoryPlacedPropertiesEXT placed{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_PROPERTIES_EXT};
        VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,&placed};vkGetPhysicalDeviceProperties2(physical,&props);
        uint64_t alignment=std::max<uint64_t>(placed.minPlacedMemoryMapAlignment,sysconf(_SC_PAGESIZE));
        if(alignment>16*1024*1024 || (alignment&(alignment-1)))throw std::runtime_error("Unexpected placed alignment");
        vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,nullptr);std::vector<VkQueueFamilyProperties> qs(count);vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,qs.data());
        uint32_t family=0;while(family<count&&!qs[family].queueCount)family++;if(family==count)throw std::runtime_error("No queue");
        float priority=1;VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};q.queueFamilyIndex=family;q.queueCount=1;q.pQueuePriorities=&priority;
        const char* exts[]={VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME,VK_KHR_MAP_MEMORY_2_EXTENSION_NAME};
        VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dc.pNext=&features;dc.queueCreateInfoCount=1;dc.pQueueCreateInfos=&q;dc.enabledExtensionCount=2;dc.ppEnabledExtensionNames=exts;
        check(vkCreateDevice(physical,&dc,nullptr,&device),"device");
        auto map2=reinterpret_cast<PFN_vkMapMemory2KHR>(vkGetDeviceProcAddr(device,"vkMapMemory2KHR"));if(!map2)throw std::runtime_error("No map2 function");
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bc.size=128;bc.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        check(vkCreateBuffer(device,&bc,nullptr,&buffer),"buffer");VkMemoryRequirements requirements{};vkGetBufferMemoryRequirements(device,buffer,&requirements);
        VkPhysicalDeviceMemoryProperties types{};vkGetPhysicalDeviceMemoryProperties(physical,&types);uint32_t type=0;
        while(type<types.memoryTypeCount&&(!(requirements.memoryTypeBits&(1u<<type))||!(types.memoryTypes[type].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)))type++;
        if(type==types.memoryTypeCount)throw std::runtime_error("No CPU visible memory");
        report<<"{\"nativeControl\":true,\"pid\":"<<getpid()<<",\"uid\":"<<getuid()<<",\"gpu\":"<<quote(properties.deviceName)<<",\"alignment\":"<<alignment<<",\"requiredBytes\":"<<requirements.size<<",\"samples\":[";
        bool first=true;
        for(bool padded:{false,true}) {
            VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};alloc.allocationSize=padded?(requirements.size+alignment-1)&~(alignment-1):requirements.size;alloc.memoryTypeIndex=type;
            check(vkAllocateMemory(device,&alloc,nullptr,&memory),"memory");void* normal=nullptr;VkResult basic=vkMapMemory(device,memory,0,VK_WHOLE_SIZE,0,&normal);if(basic==VK_SUCCESS)vkUnmapMemory(device,memory);
            for(bool low:{false,true}) {
                size_t length=static_cast<size_t>((alloc.allocationSize+alignment-1)&~(alignment-1));
                void *reservation=MAP_FAILED;size_t reserved=length+static_cast<size_t>(alignment);
                if(low) {
                    reserved=length;
                    for(uintptr_t address:{0x10000000u,0x20000000u,0x30000000u}) {
                        reservation=mmap(reinterpret_cast<void*>(address),reserved,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS|0x100000,-1,0); // Linux MAP_FIXED_NOREPLACE
                        if(reservation!=MAP_FAILED && reinterpret_cast<uintptr_t>(reservation)!=address){munmap(reservation,reserved);reservation=MAP_FAILED;}
                        if(reservation!=MAP_FAILED)break;
                    }
                } else reservation=mmap(nullptr,reserved,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
                uintptr_t address=reservation==MAP_FAILED?0:(reinterpret_cast<uintptr_t>(reservation)+alignment-1)&~(alignment-1);
                VkResult result=VK_ERROR_OUT_OF_HOST_MEMORY;void *mapped=nullptr;
                if(reservation!=MAP_FAILED) {
                    VkMemoryMapPlacedInfoEXT pi{VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT,nullptr,reinterpret_cast<void*>(address)};
                    VkMemoryMapInfoKHR mi{VK_STRUCTURE_TYPE_MEMORY_MAP_INFO_KHR,&pi,VK_MEMORY_MAP_PLACED_BIT_EXT,memory,0,VK_WHOLE_SIZE};result=map2(device,&mi,&mapped);
                    if(result==VK_SUCCESS)vkUnmapMemory(device,memory);munmap(reservation,reserved);
                }
                if(!first)report<<',';first=false;
                report<<"{\"low32\":"<<(low?"true":"false")<<",\"allocationBytes\":"<<alloc.allocationSize<<",\"normalMapResult\":"<<basic<<",\"placedAddress\":"<<address<<",\"placedMapResult\":"<<result<<",\"mappedAddress\":"<<reinterpret_cast<uintptr_t>(mapped)<<"}";
            }
            vkFreeMemory(device,memory,nullptr);memory=0;
        }
        report<<"]}";
    } catch(const std::exception& e) {
        if(memory)vkFreeMemory(device,memory,nullptr);if(buffer)vkDestroyBuffer(device,buffer,nullptr);if(device)vkDestroyDevice(device,nullptr);
        return "{\"nativeControl\":true,\"error\":"+quote(e.what())+"}";
    }
    vkDestroyBuffer(device,buffer,nullptr);vkDestroyDevice(device,nullptr);return report.str();
}
}
