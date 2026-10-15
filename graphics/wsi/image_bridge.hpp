// SPDX-License-Identifier: MIT
#pragma once
#include <android/native_window.h>
#include <memory>
#include <string>
namespace frostbox::wsi {
// Fixed diagnostic only: one imported image/frame, real host Android swapchain.
// This is not a VkSwapchainKHR exposed to Wine and not a Vulkan command RPC.
class ImageBridge {
public:
    explicit ImageBridge(ANativeWindow* borrowed);
    ~ImageBridge();
    std::string frame(const std::string& native_dir,const std::string& work,unsigned color);
    std::string persistentFrame(const std::string& native_dir,const std::string& work,unsigned total);
    std::string startGuest(const std::string& endpoint);
    std::string guestStatus();
private:
    struct State;
    std::unique_ptr<State> state;
};
}
