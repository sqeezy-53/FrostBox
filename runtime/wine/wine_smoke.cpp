#include "wine_runtime.hpp"
#include <iostream>
int main(int argc,char** argv) {
    if(argc!=6) {std::cerr<<"Usage: frostbox-wine-smoke /wine /wineserver /private-profile /x64.exe /x86.exe\n";return 2;}
    try {
        frostbox::WineRuntimeManager manager({argv[1],argv[2],{}},argv[3]);
        auto init=manager.initialize();std::cout<<"{\"stage\":\"wineboot\",\"result\":"<<init.json()<<"}\n";
        if(init.exit_code!=0 || !init.error.empty()) return 3;
        bool locked=false;try {frostbox::WineRuntimeManager second({argv[1],argv[2],{}},argv[3]);}catch(const std::runtime_error&) {locked=true;}
        if(!locked) {std::cerr<<"Concurrent prefix lock failed\n";return 4;}
        bool success=true;
        for(int i=4;i<=5;++i) {
            auto s=manager.execute(argv[i]);
            std::cout<<"{\"stage\":\""<<(i==4?"pe-x64":"pe-x86")<<"\",\"result\":"<<s.json()<<"}\n";
            success=success && s.exit_code==42 && s.out=="FROSTBOX_PE_RESULT=5050\n" && s.exec_errno==0 && !s.timed_out;
        }
        auto stop=manager.shutdown();std::cout<<"{\"stage\":\"shutdown\",\"result\":"<<stop.json()<<"}\n";
        return success && stop.exit_code==0?0:5;
    }catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
