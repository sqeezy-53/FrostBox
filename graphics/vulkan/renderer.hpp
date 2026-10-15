// SPDX-License-Identifier: MIT
#pragma once
#include "gpu.hpp"
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
namespace frostbox::gpu {
class Renderer {
public:
    Renderer();
    ~Renderer();
    void window(ANativeWindow* owned,int width,int height);
    void start(bool value);
    void foreground(bool value);
    std::string snapshot();
private:
    void loop();
    std::mutex mutex;
    std::condition_variable wake;
    std::thread thread;
    std::shared_ptr<ANativeWindow> requested;
    bool running=false,visible=true,shutdown=false;
    uint64_t generation=0;
    int width=0,height=0;
    std::string report="{\"state\":\"Stopped\",\"presentedFrames\":0}";
};
}
