#include "process.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <climits>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sstream>
#include <stdexcept>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

namespace frostbox {
namespace {
struct Fd {
    int fd=-1;
    ~Fd() { if(fd>=0) close(fd); }
    int release() { int n=fd;fd=-1;return n; }
};
void pipe_cloexec(Fd& a,Fd& b) {
    int p[2]; if(pipe2(p,O_CLOEXEC)!=0) throw std::runtime_error(std::strerror(errno));
    a.fd=p[0];b.fd=p[1];
    for(auto* f:{&a,&b}) if(f->fd<3) {
        int n=fcntl(f->fd,F_DUPFD_CLOEXEC,3);
        if(n<0) throw std::runtime_error(std::strerror(errno));
        close(f->fd);f->fd=n;
    }
}
void nonblocking(int fd) {
    if(fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK)<0) throw std::runtime_error(std::strerror(errno));
}
void check_string(const std::string& s) {
    if(s.find('\0')!=std::string::npos || s.size()>65536) throw std::invalid_argument("Invalid command string");
}
void validate(const Command& c) {
    check_string(c.executable);check_string(c.directory);
    if(c.executable.empty() || c.executable.front()!='/' || c.directory.empty() || c.directory.front()!='/')
        throw std::invalid_argument("Executable and working directory must be absolute");
    if(c.timeout.count()<0 || c.timeout>std::chrono::minutes(10) || c.output_limit>4*1024*1024)
        throw std::invalid_argument("Invalid timeout/output bound");
    if(c.arguments.size()>256 || c.environment.size()>256) throw std::invalid_argument("Command too large");
    for(const auto& a:c.arguments) check_string(a);
    for(const auto& [k,v]:c.environment) {
        check_string(k);check_string(v);
        if(k.empty() || k.find('=')!=std::string::npos) throw std::invalid_argument("Invalid environment key");
    }
}
// Child path: only async-signal-safe calls/raw Linux syscalls between fork and exec.
[[noreturn]] void child_fail(int fd,int e) {
    const char* p=reinterpret_cast<const char*>(&e);size_t left=sizeof(e);
    while(left) { auto n=write(fd,p,left);if(n<0 && errno==EINTR) continue;if(n<=0) break;p+=n;left-=static_cast<size_t>(n); }
    _exit(127);
}
void close_child_fds(int keep,int max_fd) {
#if defined(__NR_close_range) && !defined(__ANDROID__)
    bool first=keep==3 || syscall(__NR_close_range,3u,static_cast<unsigned>(keep-1),0)==0;
    bool second=syscall(__NR_close_range,static_cast<unsigned>(keep+1),~0u,0)==0;
    if(first && second) return;
#endif
    for(int fd=3;fd<max_fd;++fd) if(fd!=keep) close(fd);
}
void drain(int& fd,std::string& target,uint64_t& dropped,size_t limit) {
    if(fd<0) return;
    char buffer[4096]; size_t budget=65536;
    while(budget) {
        auto n=read(fd,buffer,std::min(sizeof(buffer),budget));
        if(n>0) {
            auto count=static_cast<size_t>(n),keep=std::min(count,limit-target.size());
            target.append(buffer,keep);dropped+=count-keep;budget-=count;
        } else if(n==0) { close(fd);fd=-1;break; }
        else if(errno==EINTR) continue;
        else if(errno==EAGAIN || errno==EWOULDBLOCK) break;
        else { close(fd);fd=-1;break; }
    }
}
}
std::string json_string(const std::string& s) {
    // Decode valid UTF-8 before escaping: escaping each byte independently corrupts
    // Unicode in JSONObject. Keep invalid/truncated bytes representable as before.
    std::string out="\"";const char* hex="0123456789abcdef";
    auto unit=[&](unsigned c) {
        out+="\\u";for(int shift=12;shift>=0;shift-=4)out+=hex[(c>>shift)&15];
    };
    for(size_t i=0;i<s.size();++i) {
        unsigned c=static_cast<unsigned char>(s[i]);
        if(c=='"' || c=='\\') {out+='\\';out+=static_cast<char>(c);}
        else if(c<32 || c==127) unit(c);
        else if(c<128) out+=static_cast<char>(c);
        else {
            unsigned length=c>=0xc2 && c<=0xdf?2:c>=0xe0 && c<=0xef?3:c>=0xf0 && c<=0xf4?4:0;
            unsigned cp=length?c & ((1u<<(7-length))-1):c;
            bool valid=length && i+length<=s.size();
            for(unsigned j=1;valid && j<length;++j) {
                unsigned next=static_cast<unsigned char>(s[i+j]);
                if((next&0xc0)!=0x80)valid=false;else cp=(cp<<6)|(next&63);
            }
            valid=valid && cp>=(length==2?0x80u:length==3?0x800u:0x10000u) &&
                cp<=0x10ffff && !(cp>=0xd800 && cp<=0xdfff);
            if(!valid) unit(c);
            else {
                if(cp<=0xffff)unit(cp);
                else {cp-=0x10000;unit(0xd800+(cp>>10));unit(0xdc00+(cp&1023));}
                i+=length-1;
            }
        }
    }
    return out+'"';
}
std::string ProcessSnapshot::json() const {
    std::ostringstream s;
    s<<"{\"pid\":"<<pid<<",\"running\":"<<(running?"true":"false")<<",\"exitCode\":"<<exit_code
     <<",\"signal\":"<<signal<<",\"execErrno\":"<<exec_errno<<",\"timedOut\":"<<(timed_out?"true":"false")
     <<",\"cancelled\":"<<(cancelled?"true":"false")<<",\"stdout\":"<<json_string(out)<<",\"stderr\":"<<json_string(err)
     <<",\"error\":"<<json_string(error)<<",\"droppedOut\":"<<dropped_out<<",\"droppedErr\":"<<dropped_err
     <<",\"elapsedMs\":"<<elapsed_ms<<",\"peakRssKiB\":"<<peak_rss_kib<<",\"userMs\":"<<user_ms<<",\"systemMs\":"<<system_ms<<'}';
    return s.str();
}
Process::Process(Command c):command_(std::move(c)) { validate(command_); }
std::shared_ptr<Process> Process::spawn(Command c) {
    auto process=std::shared_ptr<Process>(new Process(std::move(c)));
    process->launch();return process;
}
void Process::launch() {
    Fd out_read,out_write,err_read,err_write,exec_read,exec_write,null_in;
    pipe_cloexec(out_read,out_write);pipe_cloexec(err_read,err_write);pipe_cloexec(exec_read,exec_write);
    nonblocking(out_read.fd);nonblocking(err_read.fd);nonblocking(exec_read.fd);
    null_in.fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
    if(null_in.fd<0) throw std::runtime_error("Cannot open /dev/null");
    std::vector<std::string> args{command_.executable},env;
    args.insert(args.end(),command_.arguments.begin(),command_.arguments.end());
    for(const auto& [k,v]:command_.environment) env.push_back(k+'='+v);
    std::vector<char*> argv,envp;
    for(auto& a:args) argv.push_back(a.data());
    argv.push_back(nullptr);
    for(auto& e:env) envp.push_back(e.data());
    envp.push_back(nullptr);
    struct rlimit limit{};
    if(getrlimit(RLIMIT_NOFILE,&limit)!=0 || limit.rlim_cur>INT_MAX) throw std::runtime_error("Invalid descriptor limit");
    int max_fd=static_cast<int>(limit.rlim_cur);
    struct rlimit no_core{0,0};
    pid_t parent=getpid();
    sigset_t empty_mask;sigemptyset(&empty_mask);
    struct sigaction default_action{};default_action.sa_handler=SIG_DFL;sigemptyset(&default_action.sa_mask);
    // Resolve all C++ object accesses before fork. No allocation, locks, JNI, logs or exceptions in child.
    const char* executable=command_.executable.c_str();const char* cwd=command_.directory.c_str();
    auto ap=argv.data();auto ep=envp.data();
    started_=std::chrono::steady_clock::now();
    pid_t pid=fork();
    if(pid<0) throw std::runtime_error(std::strerror(errno));
    if(pid==0) {
        if(setpgid(0,0)!=0) child_fail(exec_write.fd,errno);
        (void)setrlimit(RLIMIT_CORE,&no_core);
        if(sigprocmask(SIG_SETMASK,&empty_mask,nullptr)!=0) child_fail(exec_write.fd,errno);
        for(int sig=1;sig<NSIG;++sig) if(sig!=SIGKILL && sig!=SIGSTOP) (void)sigaction(sig,&default_action,nullptr);
        if(prctl(PR_SET_PDEATHSIG,SIGKILL)!=0 || getppid()!=parent) child_fail(exec_write.fd,ECANCELED);
        if(dup2(null_in.fd,STDIN_FILENO)<0 || dup2(out_write.fd,STDOUT_FILENO)<0 || dup2(err_write.fd,STDERR_FILENO)<0)
            child_fail(exec_write.fd,errno);
        // dup2(fd, fd) preserves CLOEXEC if the parent originally had stdin closed.
        if(fcntl(STDIN_FILENO,F_SETFD,0)<0) child_fail(exec_write.fd,errno);
        if(chdir(cwd)!=0) child_fail(exec_write.fd,errno);
        close_child_fds(exec_write.fd,max_fd);
        execve(executable,ap,ep);child_fail(exec_write.fd,errno);
    }
    // The child sets its group too; parent closes the cancellation race before exec.
    (void)setpgid(pid,pid);
    state_.pid=pid;state_.running=true;
    out_fd_=out_read.release();err_fd_=err_read.release();exec_fd_=exec_read.release();
}
void Process::stop_locked(bool timeout) {
    if(!state_.running || stopping_requested_) return;
    stopping_requested_=true;stopping_=std::chrono::steady_clock::now();
    state_.timed_out=timeout;state_.cancelled=!timeout;
    (void)kill(-state_.pid,SIGTERM);
}
void Process::cancel() {std::lock_guard lock(mutex_);stop_locked(false);}
void Process::drain_locked() {
    drain(out_fd_,state_.out,state_.dropped_out,command_.output_limit);
    drain(err_fd_,state_.err,state_.dropped_err,command_.output_limit);
    if(exec_fd_>=0) {
        int code=0;auto n=read(exec_fd_,&code,sizeof(code));
        if(n==sizeof(code)) {state_.exec_errno=code;state_.error=std::strerror(code);}
        if(n==0 || n==sizeof(code)) {close(exec_fd_);exec_fd_=-1;}
    }
}
ProcessSnapshot Process::poll() {
    std::lock_guard lock(mutex_);
    if(!state_.running) return state_;
    auto now=std::chrono::steady_clock::now();
    state_.elapsed_ms=std::chrono::duration_cast<std::chrono::milliseconds>(now-started_).count();
    if(command_.timeout.count()>0 && now-started_>=command_.timeout) stop_locked(true);
    if(stopping_requested_ && now-stopping_>=std::chrono::milliseconds(250)) (void)kill(-state_.pid,SIGKILL);
    drain_locked();
    siginfo_t info{};
    int waited=waitid(P_PID,static_cast<id_t>(state_.pid),&info,WEXITED|WNOHANG|WNOWAIT);
    if(waited==0 && info.si_pid==state_.pid) {
        // Leader remains a zombie, reserving PID/PGID until cleanup signals are sent (no PID reuse race).
        (void)kill(-state_.pid,SIGKILL);
        int status=0;struct rusage usage{};
        pid_t reaped;
        do {reaped=wait4(state_.pid,&status,0,&usage);} while(reaped<0 && errno==EINTR);
        if(reaped==state_.pid) {
            if(WIFEXITED(status)) state_.exit_code=WEXITSTATUS(status);
            if(WIFSIGNALED(status)) state_.signal=WTERMSIG(status);
            state_.peak_rss_kib=usage.ru_maxrss;
            state_.user_ms=usage.ru_utime.tv_sec*1000.0+usage.ru_utime.tv_usec/1000.0;
            state_.system_ms=usage.ru_stime.tv_sec*1000.0+usage.ru_stime.tv_usec/1000.0;
        } else state_.error="wait4 failed";
        state_.running=false;
        // Final finite drain. Descendants outside this process group are not a supported sandbox.
        for(int i=0;i<16 && (out_fd_>=0 || err_fd_>=0);++i) drain_locked();
        close_fds();
    } else if(waited<0 && errno!=EINTR) {
        state_.error="waitid failed: "+std::string(std::strerror(errno));
        // An external reaper violates exclusive ownership; never signal a potentially reused PID.
        state_.running=false;close_fds();
    }
    return state_;
}
void Process::close_fds() {
    for(int* p:{&out_fd_,&err_fd_,&exec_fd_}) {if(*p>=0) close(*p);*p=-1;}
}
Process::~Process() {
    std::lock_guard lock(mutex_);
    if(state_.running) {
        (void)kill(-state_.pid,SIGKILL);
        int status;while(waitpid(state_.pid,&status,0)<0 && errno==EINTR) {}
    }
    close_fds();
}
ProcessSnapshot run(Command command) {
    auto p=Process::spawn(std::move(command));
    while(true) {auto s=p->poll();if(!s.running)return s;(void)::poll(nullptr,0,10);}
}
}
