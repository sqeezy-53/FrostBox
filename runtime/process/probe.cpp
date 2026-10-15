// Original FrostBox deterministic process fixture; also packaged as an Android PIE executable.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
int main(int argc,char** argv) {
    if(argc>1 && std::strcmp(argv[1],"wait-term")==0) {std::fputs("READY\n",stderr);std::fflush(stderr);for(;;) pause();}
    if(argc>1 && std::strcmp(argv[1],"descendant")==0) {
        pid_t child=fork();if(child<0)return 1;
        if(child==0) {signal(SIGTERM,SIG_IGN);for(;;) pause();}
        std::printf("%d\n",child);return 0;
    }
    if(argc>1 && std::strcmp(argv[1],"sleep")==0) {signal(SIGTERM,SIG_IGN);for(;;) pause();}
    if(argc>1 && std::strcmp(argv[1],"flood")==0) {
        for(int i=0;i<32768;++i) {std::fputs("0123456789abcdef\n",stdout);std::fputs("error-stream\n",stderr);}return 7;
    }
    if(argc>1 && std::strcmp(argv[1],"args")==0) {
        for(int i=2;i<argc;++i) std::printf("arg=%s\n",argv[i]);
        std::printf("token=%s\n",std::getenv("FROSTBOX_TOKEN")?std::getenv("FROSTBOX_TOKEN"):"<absent>");return 0;
    }
    if(argc>2 && std::strcmp(argv[1],"fd")==0) return fcntl(std::atoi(argv[2]),F_GETFD)==-1?0:1;
    if(argc>1 && std::strcmp(argv[1],"stdin")==0) {char byte;return read(STDIN_FILENO,&byte,1)==0?0:1;}
    if(argc>1 && std::strcmp(argv[1],"crash")==0) {raise(SIGABRT);return 1;}
    volatile unsigned total=0;
    for(unsigned i=1;i<=100;++i) total=total+i;
    std::printf("FROSTBOX_NATIVE_RESULT=%u\n",total);
    std::fputs("FROSTBOX_NATIVE_STDERR=ok\n",stderr);
    return total==5050?42:1;
}
