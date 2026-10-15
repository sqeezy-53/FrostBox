// SPDX-License-Identifier: MIT
// ANativeWindow registry is local to :runtime. Never exports addresses over Binder.
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>
#include "../../graphics/wsi/image_bridge.hpp"
#include <memory>

namespace {
std::mutex lock;
std::map<jlong, ANativeWindow*> windows;
jlong next_id = 1;
std::map<jlong, std::unique_ptr<frostbox::wsi::ImageBridge>> bridges;
void fail(JNIEnv* env, const char* message) {
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
std::string quote(const std::string& value) {
    std::ostringstream out; out << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 32 || c >= 127) { const char* hex = "0123456789abcdef"; out << "\\u00" << hex[c >> 4] << hex[c & 15]; }
        else out << c;
    }
    out << '"'; return out.str();
}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_frostbox_app_display_RuntimeSurfaceBridge_acquire(JNIEnv* env, jobject, jobject surface) {
    auto* window = ANativeWindow_fromSurface(env, surface);
    if (!window) { if (!env->ExceptionCheck()) fail(env, "ANativeWindow acquisition failed"); return 0; }
    std::lock_guard guard(lock);
    const auto id = next_id++;
    try { windows.emplace(id, window); }
    catch (...) { ANativeWindow_release(window); fail(env, "Surface registry allocation failed"); return 0; }
    return id;
}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_display_RuntimeSurfaceBridge_describe(JNIEnv* env, jobject, jlong id) {
    std::lock_guard guard(lock);
    const auto it = windows.find(id);
    if (it == windows.end()) { fail(env, "Unknown window ID"); return nullptr; }
    std::ostringstream out;
    out << "{\"width\":" << ANativeWindow_getWidth(it->second) << ",\"height\":" << ANativeWindow_getHeight(it->second)
        << ",\"format\":" << ANativeWindow_getFormat(it->second) << ",\"ownerPid\":" << getpid()
        << ",\"liveReferences\":" << windows.size() << '}';
    return env->NewStringUTF(out.str().c_str());
}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_display_RuntimeSurfaceBridge_release(JNIEnv*, jobject, jlong id) {
    std::lock_guard guard(lock);
    const auto it = windows.find(id);
    if (it != windows.end()) { bridges.erase(id); ANativeWindow_release(it->second); windows.erase(it); }
}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_display_RuntimeSurfaceBridge_loaderDiagnostic(JNIEnv* env, jobject, jstring directory) {
    const char* chars = env->GetStringUTFChars(directory, nullptr);
    if (!chars) return nullptr;
    const std::string path = std::string(chars) + "/libbox64_vulkan.so";
    env->ReleaseStringUTFChars(directory, chars);
    dlerror(); void* library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    const char* error = dlerror(); std::string message = error ? error : "";
    void* entry = nullptr;
    if (library) { dlerror(); entry = dlsym(library, "main"); error = dlerror(); if (error) message = error; }
    if (library) dlclose(library);
    const std::string json = "{\"pid\":" + std::to_string(getpid()) + ",\"uid\":" + std::to_string(getuid())
        + ",\"libraryLoadable\":" + (library ? "true" : "false") + ",\"mainExported\":" + (entry ? "true" : "false")
        + ",\"entryInvoked\":false,\"error\":" + quote(message) + "}";
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_display_RuntimeSurfaceBridge_imageFrame(JNIEnv* env, jobject, jlong id, jstring native_dir, jstring work_dir, jint color, jint total) {
    try {
        std::lock_guard guard(lock);
        auto it=windows.find(id);if(it==windows.end())throw std::runtime_error("Unknown window ID");
        auto& bridge=bridges[id];if(!bridge)bridge=std::make_unique<frostbox::wsi::ImageBridge>(it->second);
        const char* n=env->GetStringUTFChars(native_dir,nullptr);if(!n)return nullptr;
        std::string native(n);env->ReleaseStringUTFChars(native_dir,n);
        const char* w=env->GetStringUTFChars(work_dir,nullptr);if(!w)return nullptr;
        std::string work(w);env->ReleaseStringUTFChars(work_dir,w);
        auto result=total>1?bridge->persistentFrame(native,work,static_cast<unsigned>(total)):bridge->frame(native,work,color);return env->NewStringUTF(result.c_str());
    } catch(const std::exception& e){fail(env,e.what());return nullptr;}
}

extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_display_RuntimeSurfaceBridge_guestWsi(JNIEnv* env,jobject,jlong id,jstring endpoint) {
    try {std::lock_guard guard(lock);auto it=windows.find(id);if(it==windows.end())throw std::runtime_error("Unknown window");auto& bridge=bridges[id];if(!bridge)bridge=std::make_unique<frostbox::wsi::ImageBridge>(it->second);
        if(endpoint){const char* value=env->GetStringUTFChars(endpoint,nullptr);if(!value)return nullptr;std::string name(value);env->ReleaseStringUTFChars(endpoint,value);auto result=bridge->startGuest(name);return env->NewStringUTF(result.c_str());}
        auto result=bridge->guestStatus();return env->NewStringUTF(result.c_str());
    } catch(const std::exception& e){fail(env,e.what());return nullptr;}
}
