#include "wine_runtime.hpp"
#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>
#include <stdexcept>

namespace frostbox {
namespace fs=std::filesystem;
WineRuntimeManager::WineRuntimeManager(WineInstallation installation,fs::path profile):installation_(std::move(installation)) {
    if(!profile.is_absolute() || !installation_.wine.is_absolute() || !installation_.wineserver.is_absolute())
        throw std::invalid_argument("Absolute Wine and profile paths required");
    if(!installation_.translator.empty() && !installation_.translator.is_absolute()) throw std::invalid_argument("Absolute translator path required");
    fs::create_directories(profile);
    profile_=fs::canonical(profile);prefix_=profile_/"prefix";
    // Refuse an existing redirected prefix before creating or executing anything in it.
    if(fs::is_symlink(prefix_)) throw std::invalid_argument("Symlink Wine prefix is forbidden");
    lock_fd_=open((profile_/"runtime.lock").c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
    if(lock_fd_<0) throw std::runtime_error("Cannot open prefix lock");
    if(flock(lock_fd_,LOCK_EX|LOCK_NB)!=0) {close(lock_fd_);lock_fd_=-1;throw std::runtime_error("Prefix is in use");}
}
Command WineRuntimeManager::command(const fs::path& executable,std::vector<std::string> args) const {
    Command c;
    if(!installation_.translator.empty()) {c.executable=installation_.translator.string();c.arguments.push_back(executable.string());}
    else c.executable=executable.string();
    c.arguments.insert(c.arguments.end(),args.begin(),args.end());
    c.directory=profile_.string();c.timeout=std::chrono::seconds(90);
    c.environment={{"HOME",profile_.string()},{"WINEPREFIX",prefix_.string()},
        {"WINEDEBUG","-all"},{"WINEDLLOVERRIDES","mscoree,mshtml="},
        {"PATH",installation_.wine.parent_path().string()+":/usr/bin:/bin"},
        {"BOX64_DYNACACHE","0"},{"BOX64_LOG","0"}};
    return c;
}
ProcessSnapshot WineRuntimeManager::initialize() {
    server_started_=true;
    auto result=run(command(installation_.wine,{"wineboot","--init"}));
    // wineboot exit alone does not imply registry files have been flushed by wineserver.
    if(result.exit_code==0 && !result.timed_out) {
        auto wait=run(command(installation_.wineserver,{"-w"}));
        if(wait.exit_code!=0 || wait.timed_out || wait.exec_errno) {
            result.error="wineserver completion failed: "+wait.json();return result;
        }
    }
    initialized_=result.exit_code==0 && result.exec_errno==0 && !result.timed_out &&
        fs::is_regular_file(prefix_/"system.reg") && fs::is_regular_file(prefix_/"user.reg");
    if(!initialized_ && result.error.empty()) result.error="wineboot did not produce a verified prefix";
    return result;
}
ProcessSnapshot WineRuntimeManager::execute(const fs::path& exe) {
    if(!initialized_) throw std::runtime_error("Wine prefix has not been initialized and checked");
    if(!exe.is_absolute() || !fs::is_regular_file(exe)) throw std::invalid_argument("Absolute existing PE path required");
    return run(command(installation_.wine,{exe.string()}));
}
ProcessSnapshot WineRuntimeManager::shutdown() {
    if(!server_started_) return {};
    auto c=command(installation_.wineserver,{"-k"});c.timeout=std::chrono::seconds(5);
    auto result=run(c);
    // Prefix-scoped wineserver only. Never killall, and never a user's default Wine prefix.
    if(result.exit_code==0) server_started_=false;
    initialized_=false;return result;
}
WineRuntimeManager::~WineRuntimeManager() {
    if(server_started_) {try {(void)shutdown();}catch(...) {}}
    if(lock_fd_>=0) close(lock_fd_);
}
}
