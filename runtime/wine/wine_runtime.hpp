#pragma once
#include "process.hpp"
#include <filesystem>
namespace frostbox {
struct WineInstallation {
    std::filesystem::path wine;
    std::filesystem::path wineserver;
    std::filesystem::path translator; // Empty only for native host Wine tests.
};
/** Private-prefix manager. Not exposed as an Android launch capability until a Wine package is qualified. */
class WineRuntimeManager {
public:
    WineRuntimeManager(WineInstallation installation,std::filesystem::path profile);
    ~WineRuntimeManager();
    WineRuntimeManager(const WineRuntimeManager&)=delete;
    WineRuntimeManager& operator=(const WineRuntimeManager&)=delete;
    ProcessSnapshot initialize();
    ProcessSnapshot execute(const std::filesystem::path& exe);
    ProcessSnapshot shutdown();
private:
    Command command(const std::filesystem::path& executable,std::vector<std::string> args) const;
    WineInstallation installation_;
    std::filesystem::path profile_,prefix_;
    int lock_fd_=-1;
    bool initialized_=false, server_started_=false;
};
}
