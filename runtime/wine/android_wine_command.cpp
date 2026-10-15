#include "android_wine_command.hpp"
#include "box64_backend.hpp"
#include <stdexcept>
namespace frostbox {
Command android_wine_command(const std::filesystem::path& native_dir,
    const std::filesystem::path& root,const std::filesystem::path& prefix,
    const std::filesystem::path& work,const std::string& op,
    const std::filesystem::path& exe,const std::vector<std::string>& args) {
    namespace fs=std::filesystem;
    if(!root.is_absolute() || !prefix.is_absolute() || !work.is_absolute()) throw std::invalid_argument("Absolute Wine paths required");
    auto wine=root/"x86_64/bin/wine";
    auto server=root/"x86_64/bin/wineserver";
    bool control=op=="kill" || op=="wait";
    auto c=Box64Backend(native_dir/"libbox64.so").diagnostic(control?server:wine,work,true);
    if(control) c.arguments.push_back(op=="kill"?"-k":"-w");
    else if(op=="initialize") {
        c.arguments.push_back((prefix/"drive_c/windows/system32/wineboot.exe").string());
        c.arguments.push_back("--init");
    } else if(op=="launch") {
        if(!exe.is_absolute() || !fs::is_regular_file(exe)) throw std::invalid_argument("Existing absolute EXE required");
        c.arguments.push_back(exe.string());c.arguments.insert(c.arguments.end(),args.begin(),args.end());
    } else throw std::invalid_argument("Unknown Wine operation");
    c.timeout=std::chrono::seconds(control?15:(op=="launch"?0:90));c.output_limit=32768;
    c.environment["BOX64_DYNAREC_DUMP"]="0";
    c.environment["BOX64_LOG"]="0";
    c.environment["BOX64_PATH"]=(root/"x86_64/bin").string();
    c.environment["BOX64_LD_LIBRARY_PATH"]=(root/"x86_64/lib/wine/x86_64-unix").string();
    c.environment["PATH"]="/system/bin";
    c.environment["TMPDIR"]=work.string();
    c.environment["WINEPREFIX"]=prefix.string();
    c.environment["WINELOADER"]=wine.string();
    c.environment["WINESERVER"]=server.string();
    c.environment["WINEDLLPATH"]=(root/"x86_64/lib/wine").string();
    c.environment["WINEDLLOVERRIDES"]="mscoree,mshtml=";
    c.environment["WINEDEBUG"]=op=="launch"?"err+all,warn+module":"err+all,warn+module,+loaddll";
    c.environment["WINEARCH"]="win64";
    // Intentionally no WINEBOOTSTRAPMODE or forced kernel32/ntdll overrides.
    return c;
}
}
