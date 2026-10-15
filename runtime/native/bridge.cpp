#include "contract.hpp"
#include <jni.h>
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <sstream>
#include <vector>
#include <exception>

namespace {
struct Instance {
    VkInstance value = VK_NULL_HANDLE;
    ~Instance() { if (value) vkDestroyInstance(value, nullptr); }
};
std::string probe() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "FrostBox";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    Instance instance;
    auto result = vkCreateInstance(&info, nullptr, &instance.value);
    if (result != VK_SUCCESS) return "System Vulkan instance failed: " + std::to_string(result);
    uint32_t count = 0;
    result = vkEnumeratePhysicalDevices(instance.value, &count, nullptr);
    if (result != VK_SUCCESS || count == 0) return "No Vulkan physical device; result=" + std::to_string(result);
    std::vector<VkPhysicalDevice> devices(count);
    result = vkEnumeratePhysicalDevices(instance.value, &count, devices.data());
    if (result != VK_SUCCESS) return "Vulkan enumeration failed: " + std::to_string(result);
    std::ostringstream out;
    out << "FrostBox JNI API " << frostbox::api_version << " / ARM64 / system loader\n";
    for (uint32_t i=0; i<count; ++i) {
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(devices[i], &p);
        out << p.deviceName << "\nVulkan " << VK_VERSION_MAJOR(p.apiVersion) << '.' << VK_VERSION_MINOR(p.apiVersion)
            << '.' << VK_VERSION_PATCH(p.apiVersion) << " vendor=" << p.vendorID << " device=" << p.deviceID
            << " driverRaw=" << p.driverVersion << "\n";
    }
    out << "Feature/extension qualification for DXVK is pending.";
    return out.str();
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_runtime_NativeBridge_readiness(JNIEnv* env, jobject) {
    return env->NewStringUTF(frostbox::unavailable.data());
}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_runtime_NativeBridge_probe(JNIEnv* env, jobject) {
    try {
        const auto text = probe();
        __android_log_print(ANDROID_LOG_INFO, "FrostBoxNative", "%s", text.c_str());
        return env->NewStringUTF(text.c_str());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "FrostBoxNative", "%s", error.what());
        return env->NewStringUTF("Native Vulkan probe failed with C++ exception; see logcat");
    } catch (...) { return env->NewStringUTF("Unknown native probe exception"); }
}
