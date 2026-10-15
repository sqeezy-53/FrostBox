// SPDX-License-Identifier: MIT
// A negative capability probe, NOT a Windows Vulkan presentation fixture.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <stdint.h>
__declspec(dllimport) int printf(const char*, ...);
__declspec(dllimport) int fflush(void*);
__declspec(dllimport) int strcmp(const char*, const char*);
__declspec(dllimport) void* LoadLibraryA(const char*);
__declspec(dllimport) void* GetProcAddress(void*, const char*);
__declspec(dllimport) int FreeLibrary(void*);
__declspec(dllimport) uint32_t GetCurrentProcessId(void);
__declspec(dllimport) __declspec(noreturn) void ExitProcess(uint32_t);
static VkExtensionProperties extensions[512];
static int probe(void) {
    printf("FROSTBOX_WSI_CAPABILITY_WINDOWS_PE PID=%u\n", GetCurrentProcessId());
    void* library = LoadLibraryA("vulkan-1.dll");
    if (!library) return 11;
    PFN_vkGetInstanceProcAddr get = (PFN_vkGetInstanceProcAddr)GetProcAddress(library, "vkGetInstanceProcAddr");
    if (!get) { FreeLibrary(library); return 12; }
    PFN_vkEnumerateInstanceExtensionProperties enumerate = (PFN_vkEnumerateInstanceExtensionProperties)get(0, "vkEnumerateInstanceExtensionProperties");
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)get(0, "vkCreateInstance");
    PFN_vkDestroyInstance destroy;
    if (!enumerate || !create) { FreeLibrary(library); return 13; }
    uint32_t count = 512; VkResult r = enumerate(0, &count, extensions);
    printf("FROSTBOX_WSI_ENUMERATE RESULT=%d COUNT=%u\n", r, count);
    if (r != VK_SUCCESS) { FreeLibrary(library); return 14; }
    int win32 = 0, android = 0, headless = 0;
    for (uint32_t i = 0; i < count; ++i) {
        if (!strcmp(extensions[i].extensionName, "VK_KHR_win32_surface")) win32 = 1;
        if (!strcmp(extensions[i].extensionName, "VK_KHR_android_surface")) android = 1;
        if (!strcmp(extensions[i].extensionName, "VK_EXT_headless_surface")) headless = 1;
        printf("FROSTBOX_WSI_EXTENSION=%s\n", extensions[i].extensionName);
    }
    printf("FROSTBOX_WSI_AVAILABLE WIN32=%d ANDROID=%d HEADLESS=%d\n", win32, android, headless);
    const char* names[] = { "VK_KHR_surface", "VK_KHR_win32_surface" };
    VkInstanceCreateInfo info = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    info.enabledExtensionCount = 2; info.ppEnabledExtensionNames = names;
    VkInstance instance = 0; r = create(&info, 0, &instance);
    printf("FROSTBOX_WSI_CREATE_INSTANCE RESULT=%d\n", r);
    if (r == VK_SUCCESS) {
        destroy = (PFN_vkDestroyInstance)get(instance, "vkDestroyInstance");
        if (!destroy) { FreeLibrary(library); return 15; }
        destroy(instance, 0);
    }
    FreeLibrary(library); fflush(0);
    // 44 means the expected current missing-driver gate was reproduced; never success 42.
    return !win32 && r == VK_ERROR_EXTENSION_NOT_PRESENT ? 44 : 45;
}
void mainCRTStartup(void) { ExitProcess((uint32_t)probe()); }
