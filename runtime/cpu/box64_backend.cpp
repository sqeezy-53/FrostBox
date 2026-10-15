#include "box64_backend.hpp"
#include <array>
#include <fstream>
#include <stdexcept>
namespace frostbox {
Command Box64Backend::diagnostic(const std::filesystem::path& guest,const std::filesystem::path& directory,bool dynarec) const {
    if(!std::filesystem::is_regular_file(executable_)) throw std::runtime_error("Box64 package is not present; build with -PwithBox64=true");
    std::array<unsigned char,64> h{};std::ifstream f(guest,std::ios::binary);f.read(reinterpret_cast<char*>(h.data()),h.size());
    if(f.gcount()!=64 || h[0]!=0x7f || h[1]!='E' || h[2]!='L' || h[3]!='F' || h[4]!=2 || h[5]!=1 || h[18]!=62 || h[19]!=0)
        throw std::runtime_error("Diagnostic guest is not an ELF64 little-endian x86-64 file");
    Command c;c.executable=executable_.string();c.arguments={guest.string()};c.directory=directory.string();
    c.timeout=std::chrono::seconds(10);
    c.environment={{"HOME",directory.string()},{"TMPDIR",directory.string()},
        {"BOX64_DYNAREC",dynarec?"1":"0"},{"BOX64_DYNACACHE","0"},{"BOX64_NOBANNER","1"},{"BOX64_LOG","0"},
        {"BOX64_RCFILE","/dev/null"},{"BOX64_TRACE_FILE","stderr"},
        {"BOX64_DYNAREC_DUMP",dynarec?"1":"0"}};
    return c;
}
}
