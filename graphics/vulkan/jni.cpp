// SPDX-License-Identifier: MIT
#include "renderer.hpp"
#include <jni.h>
#include <android/native_window_jni.h>
#include <map>
namespace {
std::mutex registryMutex;
std::map<jlong,std::shared_ptr<frostbox::gpu::Renderer>> registry;
jlong next=1;
std::shared_ptr<frostbox::gpu::Renderer> get(jlong id) {std::lock_guard guard(registryMutex);auto it=registry.find(id);if(it==registry.end())throw std::runtime_error("Invalid Vulkan handle");return it->second;}
void failure(JNIEnv* env,const std::exception& e) {auto type=env->FindClass("java/lang/IllegalStateException");if(type)env->ThrowNew(type,e.what());}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_gpu_NativeVulkan_probe(JNIEnv* env,jobject) {auto s=frostbox::gpu::probe();return env->NewStringUTF(s.c_str());}
namespace frostbox::gpu {std::string map_probe();}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_gpu_NativeVulkan_mapProbe(JNIEnv* env,jobject) {auto s=frostbox::gpu::map_probe();return env->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jlong JNICALL Java_org_frostbox_app_gpu_NativeVulkan_create(JNIEnv* env,jobject) {
    try {auto r=std::make_shared<frostbox::gpu::Renderer>();std::lock_guard guard(registryMutex);auto id=next++;registry.emplace(id,r);return id;}catch(const std::exception& e){failure(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_gpu_NativeVulkan_surface(JNIEnv* env,jobject,jlong id,jobject surface,jint width,jint height) {
    try {auto renderer=get(id);auto window=surface?ANativeWindow_fromSurface(env,surface):nullptr;if(surface && !window)throw std::runtime_error("Cannot retain Android native window");renderer->window(window,width,height);}catch(const std::exception& e){failure(env,e);}
}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_gpu_NativeVulkan_start(JNIEnv* env,jobject,jlong id,jboolean value) {try {get(id)->start(value);}catch(const std::exception& e){failure(env,e);}}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_gpu_NativeVulkan_foreground(JNIEnv* env,jobject,jlong id,jboolean value) {try {get(id)->foreground(value);}catch(const std::exception& e){failure(env,e);}}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_gpu_NativeVulkan_snapshot(JNIEnv* env,jobject,jlong id) {try {auto s=get(id)->snapshot();return env->NewStringUTF(s.c_str());}catch(const std::exception& e){failure(env,e);return nullptr;}}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_gpu_NativeVulkan_release(JNIEnv*,jobject,jlong id) {
    std::shared_ptr<frostbox::gpu::Renderer> owned;{std::lock_guard guard(registryMutex);auto it=registry.find(id);if(it!=registry.end()){owned=it->second;registry.erase(it);}}
    // Called on Kotlin retirement executor. Destruction drains GPU/WSI work off the UI thread.
}
