#include <algorithm>
#include "process.hpp"
#include "box64_backend.hpp"
#include "android_wine_command.hpp"
#include <jni.h>
#include <filesystem>
#include <map>
#include <mutex>
#include <stdexcept>
namespace {
std::mutex registry_mutex;
std::map<jlong,std::shared_ptr<frostbox::Process>> registry;
jlong next_id=1;
// JNI modified UTF-8 is not filesystem UTF-8. Use the Java UTF-8 codec for real Unicode paths/logs.
std::string string(JNIEnv* env,jstring value) {
    if(!value) throw std::invalid_argument("Missing JNI string");
    auto type=env->FindClass("java/lang/String");
    auto method=env->GetMethodID(type,"getBytes","(Ljava/lang/String;)[B");
    auto charset=env->NewStringUTF("UTF-8");
    auto bytes=static_cast<jbyteArray>(env->CallObjectMethod(value,method,charset));
    env->DeleteLocalRef(charset);env->DeleteLocalRef(type);
    if(!bytes || env->ExceptionCheck()) throw std::runtime_error("JNI UTF-8 encoding failed");
    std::string result(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes,0,env->GetArrayLength(bytes),reinterpret_cast<jbyte*>(result.data()));
    env->DeleteLocalRef(bytes);return result;
}
jstring utf8(JNIEnv* env,const std::string& value) {
    auto bytes=env->NewByteArray(static_cast<jsize>(value.size()));
    if(!bytes) throw std::runtime_error("JNI byte allocation failed");
    env->SetByteArrayRegion(bytes,0,static_cast<jsize>(value.size()),reinterpret_cast<const jbyte*>(value.data()));
    auto type=env->FindClass("java/lang/String");
    auto ctor=env->GetMethodID(type,"<init>","([BLjava/lang/String;)V");
    auto charset=env->NewStringUTF("UTF-8");
    auto result=static_cast<jstring>(env->NewObject(type,ctor,bytes,charset));
    env->DeleteLocalRef(bytes);env->DeleteLocalRef(type);env->DeleteLocalRef(charset);
    return result;
}
void failure(JNIEnv* env,const std::exception& e) {
    if(env->ExceptionCheck()) return;
    auto type=env->FindClass("java/lang/IllegalStateException");
    if(type) {env->ThrowNew(type,e.what());env->DeleteLocalRef(type);}
}
std::shared_ptr<frostbox::Process> find(jlong id) {
    std::lock_guard lock(registry_mutex);
    auto it=registry.find(id);if(it==registry.end()) throw std::invalid_argument("Invalid process handle");
    return it->second;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_frostbox_app_runtime_NativeBridge_startDiagnostic(JNIEnv* env,jobject,jint mode,jstring native_dir,jstring work_dir) {
    try {
        namespace fs=std::filesystem;
        auto libs=fs::canonical(string(env,native_dir));auto work=fs::canonical(string(env,work_dir));
        frostbox::Command command;
        if(mode==0 || mode==3) {
            command.executable=(libs/"libfrostbox_probe.so").string();command.directory=work.string();
            if(mode==3) {command.arguments={"sleep"};command.timeout=std::chrono::seconds(30);}
        } else if(mode==1 || mode==2 || mode==4) {
            command=frostbox::Box64Backend(libs/"libbox64.so").diagnostic(work/(mode==4?"bionic-probe.elf":"compute-x64.elf"),work,mode==2 || mode==4);
        } else if(mode>=10 && mode<=13) {
            // Experimental executable is separate from the qualified default Box64.
            command=frostbox::Box64Backend(libs/(mode==10?"libbox64.so":"libbox64_vulkan.so")).diagnostic(work/"vulkan-compute-x64.elf",work,mode!=12);
            if(mode==13)command.arguments.push_back("--skip-dispatch");
            command.timeout=std::chrono::seconds(60);
            command.environment["BOX64_LOG"]="1";
            command.environment["BOX64_DYNAREC_DUMP"]="0";
            command.environment["BOX64_NOBANNER"]="0";
        } else if(mode>=5 && mode<=9) {
            auto root=work/"wine-userspace";
            auto wine=root/"x86_64/bin/wine";
            auto server=root/"x86_64/bin/wineserver";
            command=frostbox::Box64Backend(libs/"libbox64.so").diagnostic(mode>=8?server:wine,work,true);
            command.arguments.push_back(mode==5?"--version":mode==6?(root/"x86_64/lib/wine/x86_64-windows/wineboot.exe").string():mode==8?"-w":mode==9?"-k":(root/"console-x64.exe").string());
            if(mode==6) command.arguments.push_back("--init");
            command.timeout=std::chrono::seconds(mode>=8?15:90);
            command.environment["BOX64_DYNAREC_DUMP"]="0";
            command.environment["BOX64_LOG"]="0";
            command.environment["BOX64_PATH"]= (root/"x86_64/bin").string();
            command.environment["BOX64_LD_LIBRARY_PATH"]= (root/"x86_64/lib/wine/x86_64-unix").string();
            command.environment["PATH"]="/system/bin";
            command.environment["WINEPREFIX"]=(work/"wine-prefix").string();
            command.environment["WINELOADER"]=wine.string();
            command.environment["WINESERVER"]=server.string();
            command.environment["WINEDLLPATH"]=(root/"x86_64/lib/wine").string();
            command.environment["WINEDLLOVERRIDES"]="kernel32,ntdll=b";
            command.environment["WINEDEBUG"]="err+all,warn+module,+loaddll";
            command.environment["WINEARCH"]="win64";
            if(mode==6 || mode==7) command.environment["WINEBOOTSTRAPMODE"]="1";
        } else throw std::invalid_argument("Unknown diagnostic mode");
        // Keep worst-case escaped JSON comfortably below Binder transaction limits.
        command.output_limit=32768;
        std::lock_guard lock(registry_mutex);
        if(!registry.empty()) throw std::runtime_error("A runtime diagnostic is already active");
        auto process=frostbox::Process::spawn(std::move(command));auto id=next_id++;
        registry.emplace(id,std::move(process));return id;
    }catch(const std::exception& e) {failure(env,e);return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_frostbox_app_runtime_NativeBridge_pollDiagnostic(JNIEnv* env,jobject,jlong id) {
    try {return utf8(env,find(id)->poll().json());}
    catch(const std::exception& e) {failure(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_runtime_NativeBridge_cancelDiagnostic(JNIEnv* env,jobject,jlong id) {
    try {find(id)->cancel();}catch(const std::exception& e) {failure(env,e);}
}
extern "C" JNIEXPORT void JNICALL Java_org_frostbox_app_runtime_NativeBridge_releaseDiagnostic(JNIEnv* env,jobject,jlong id) {
    try {
        std::shared_ptr<frostbox::Process> owned;
        {std::lock_guard lock(registry_mutex);auto it=registry.find(id);if(it!=registry.end()) {owned=it->second;registry.erase(it);}}
        // Destructor kills/reaps a still-running child outside registry lock.
    }catch(const std::exception& e) {failure(env,e);}
}

extern "C" JNIEXPORT jlong JNICALL Java_org_frostbox_app_runtime_NativeBridge_startWineCommand(JNIEnv* env,jobject,
    jstring libraries,jstring root,jstring prefix,jstring work,jstring operation,jstring exe,jobjectArray arguments,jobjectArray environment_keys,jobjectArray environment_values,jstring display_endpoint,jstring display_root,jstring wsi_endpoint,jstring dxvk_directory) {
    try {
        std::vector<std::string> args;
        if(arguments) for(jsize i=0;i<env->GetArrayLength(arguments);++i) {
            auto value=static_cast<jstring>(env->GetObjectArrayElement(arguments,i));
            try {args.push_back(string(env,value));env->DeleteLocalRef(value);}
            catch(...) {env->DeleteLocalRef(value);throw;}
        }
        auto op=string(env,operation);
        bool managed_wow_gpu=op=="initialize-wow64-graphics" || op=="launch-wow64-d3d9" || op=="launch-wow64-d3d11";
        bool initialize_gpu=op=="initialize-vulkan-x86-probe" || op=="initialize-wow64-graphics";
        bool gpu_probe=op=="initialize-vulkan-x86-probe" || op=="launch-vulkan-x86-probe" || op=="launch-d3d9-x86-probe";
        auto fixture=std::filesystem::path(string(env,exe)).filename();
        if(gpu_probe && fixture!="d3d9-triangle-x86.exe" && fixture!="vulkan-compute-x86.exe") throw std::invalid_argument("Built-in x86 GPU fixture required");
        auto command=frostbox::android_wine_command(string(env,libraries),string(env,root),string(env,prefix),
            string(env,work),initialize_gpu?"initialize":(gpu_probe || managed_wow_gpu)?"launch":op,string(env,exe),args);
        if(gpu_probe || managed_wow_gpu)command.executable=(std::filesystem::canonical(string(env,libraries))/"libbox64_vulkan.so").string();
        const std::vector<std::string> allowed={"BOX64_LOG","BOX64_NOBANNER","BOX64_DYNAREC","WINEDEBUG","WINEDLLOVERRIDES"};
        if(env->GetArrayLength(environment_keys)!=env->GetArrayLength(environment_values)) throw std::invalid_argument("Environment mismatch");
        for(jsize i=0;i<env->GetArrayLength(environment_keys);++i) {
            auto key_ref=static_cast<jstring>(env->GetObjectArrayElement(environment_keys,i));
            auto val_ref=static_cast<jstring>(env->GetObjectArrayElement(environment_values,i));
            auto key=string(env,key_ref);auto value=string(env,val_ref);
            env->DeleteLocalRef(key_ref);env->DeleteLocalRef(val_ref);
            if(std::find(allowed.begin(),allowed.end(),key)==allowed.end()) throw std::invalid_argument("Forbidden environment key");
            command.environment[key]=value;
        }
        if(!string(env,display_root).empty()) {
            if(std::filesystem::canonical(string(env,display_root))!=std::filesystem::canonical(string(env,root))) throw std::invalid_argument("Display runtime view mismatch");
            command.environment["BOX64_EMULATED_LIBS"]="libfreetype.so:libfreetype.so.6";
            if(gpu_probe || managed_wow_gpu)command.environment["BOX64_EMULATED_LIBS"]+=":libfrostvk.so";
        }
        auto endpoint=string(env,display_endpoint);
        if(!endpoint.empty()) {
            auto overlay=std::filesystem::canonical(string(env,display_root));
            if(overlay!=std::filesystem::canonical(string(env,root))) throw std::invalid_argument("Display runtime view mismatch");
            if(endpoint.size()>90 || endpoint.rfind("frostbox-",0)!=0) throw std::invalid_argument("Invalid display endpoint");
            command.environment["FROSTBOX_DISPLAY"]=endpoint;
            // Use our x64/Bionic FreeType, never an incidental native wrapper/library.
            command.environment["BOX64_EMULATED_LIBS"]="libfreetype.so:libfreetype.so.6";
            if(gpu_probe || managed_wow_gpu)command.environment["BOX64_EMULATED_LIBS"]+=":libfrostvk.so";
        }
        auto wsi=string(env,wsi_endpoint);
        if(!wsi.empty()) {
            if(endpoint.empty() || wsi.rfind("@frost-guest-",0)!=0 || wsi.size()>100) throw std::invalid_argument("Invalid guest WSI endpoint");
            auto dxvk=std::filesystem::canonical(string(env,dxvk_directory));
            if(!std::filesystem::is_regular_file(dxvk/"d3d11.dll") || !std::filesystem::is_regular_file(dxvk/"dxgi.dll")) throw std::invalid_argument("Missing managed DXVK DLLs");
            command.executable=(std::filesystem::canonical(string(env,libraries))/"libbox64_vulkan.so").string();
            command.environment["BOX64_EMULATED_LIBS"]="libfreetype.so:libfreetype.so.6:libfrostvk.so";
            command.environment["FROSTBOX_WSI"]=wsi;
            command.environment["WINEPATH"]="Z:"+dxvk.string();
            command.environment["WINEDLLOVERRIDES"]+=(command.environment["WINEDLLOVERRIDES"].empty()?"":";")+std::string((op=="launch-d3d9-x86-probe" || op=="launch-wow64-d3d9")?"mscoree,mshtml=;d3d9=n":"mscoree,mshtml=;d3d11,dxgi=n");
            command.environment["DXVK_LOG_LEVEL"]="info";
            command.environment["DXVK_LOG_PATH"]=string(env,work);
        }
        std::lock_guard lock(registry_mutex);
        if(!registry.empty()) throw std::runtime_error("A native process is already active");
        auto process=frostbox::Process::spawn(std::move(command));auto id=next_id++;
        registry.emplace(id,std::move(process));return id;
    }catch(const std::exception& e) {failure(env,e);return 0;}
}

// Headless qualification only. Shares Process/JNI ownership; cannot select arbitrary EXEs or backends.
extern "C" JNIEXPORT jlong JNICALL Java_org_frostbox_app_runtime_NativeBridge_startVulkanWineDiagnostic(JNIEnv* env,jobject,
    jstring libraries,jstring root,jstring prefix,jstring work,jstring operation) {
    try {
        auto libs=std::filesystem::canonical(string(env,libraries));
        auto dir=std::filesystem::canonical(string(env,work));
        auto op=string(env,operation);
        const bool wsi=op=="wsi-capability";
        auto command=frostbox::android_wine_command(libs,string(env,root),string(env,prefix),dir,
            wsi?"launch":op,dir/(wsi?"vulkan-wsi-capability-x64.exe":"vulkan-compute-x64.exe"),{});
        command.executable=(libs/"libbox64_vulkan.so").string();
        command.timeout=std::chrono::seconds(60);
        command.environment["BOX64_LOG"]="0";
        command.environment["BOX64_NOBANNER"]="1";
        command.environment["WINEDEBUG"]="-all,+vulkan";
        command.environment["BOX64_EMULATED_LIBS"]="libfreetype.so:libfreetype.so.6";
        std::lock_guard lock(registry_mutex);
        if(!registry.empty()) throw std::runtime_error("A native process is already active");
        auto process=frostbox::Process::spawn(std::move(command));auto id=next_id++;
        registry.emplace(id,std::move(process));return id;
    }catch(const std::exception& e) {failure(env,e);return 0;}
}

extern "C" JNIEXPORT jlong JNICALL Java_org_frostbox_app_runtime_NativeBridge_startWineWsiDiagnostic(JNIEnv* env,jobject,
    jstring libraries,jstring root,jstring prefix,jstring work,jstring endpoint,jstring display,jboolean d3d11) {
    try {
        auto libs=std::filesystem::canonical(string(env,libraries));auto dir=std::filesystem::canonical(string(env,work));
        auto socket=string(env,endpoint),gdi=string(env,display);if(socket.rfind("@frost-guest-",0)!=0||socket.size()>100||gdi.rfind("frostbox-",0)!=0||gdi.size()>90)throw std::runtime_error("Invalid WSI endpoint");
        auto command=frostbox::android_wine_command(libs,string(env,root),string(env,prefix),dir,"launch",dir/(d3d11?"d3d11-cube-x64.exe":"windows-vulkan-triangle-x64.exe"),{});
        command.executable=(libs/"libbox64_vulkan.so").string();command.timeout=std::chrono::seconds(90);command.output_limit=524288;
        command.environment["BOX64_LOG"]="1";command.environment["BOX64_NOBANNER"]="1";command.environment["WINEDEBUG"]="-all,+vulkan";
        command.environment["BOX64_EMULATED_LIBS"]="libfreetype.so:libfreetype.so.6:libfrostvk.so";
        if(d3d11){command.environment["WINEDLLOVERRIDES"]="d3d11,dxgi=n";command.environment["DXVK_LOG_LEVEL"]="info";command.environment["DXVK_LOG_PATH"]=dir.string();command.environment["WINEDEBUG"]="-all,+loaddll";}
        command.environment["FROSTBOX_REPAIR_DIAGNOSTIC_METRICS"]="1";
        command.environment["FROSTBOX_WSI"]=socket;command.environment["FROSTBOX_DISPLAY"]=gdi;
        std::lock_guard lock(registry_mutex);if(!registry.empty())throw std::runtime_error("A native process is already active");
        auto process=frostbox::Process::spawn(std::move(command));auto id=next_id++;registry.emplace(id,std::move(process));return id;
    }catch(const std::exception& e){failure(env,e);return 0;}
}
