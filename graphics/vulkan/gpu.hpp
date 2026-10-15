// SPDX-License-Identifier: MIT
#pragma once
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>
#include <sstream>
#include "process.hpp"
namespace frostbox::gpu {
inline std::string quote(const std::string& s) { return json_string(s); }
inline void check(VkResult r,const char* operation) {
    if(r!=VK_SUCCESS)throw std::runtime_error(std::string(operation)+": VkResult="+std::to_string(r));
}
inline std::string version(uint32_t v) { return std::to_string(VK_VERSION_MAJOR(v))+"."+std::to_string(VK_VERSION_MINOR(v))+"."+std::to_string(VK_VERSION_PATCH(v)); }
struct Instance {
    VkInstance handle=VK_NULL_HANDLE;
    uint32_t api=VK_API_VERSION_1_0;
    explicit Instance(bool wsi);
    ~Instance() { if(handle)vkDestroyInstance(handle,nullptr); }
    Instance(const Instance&)=delete;
};
std::vector<VkExtensionProperties> extensions(VkPhysicalDevice physical);
bool has(const std::vector<VkExtensionProperties>& values,const char* name);
std::string capabilities(VkInstance instance,uint32_t api,VkSurfaceKHR surface=VK_NULL_HANDLE);
std::string probe();
}
