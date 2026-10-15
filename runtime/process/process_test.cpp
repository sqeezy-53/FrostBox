#include "process.hpp"
#include <cstdlib>
#include <fcntl.h>
#include <poll.h>
#include <iostream>
#include <signal.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <unistd.h>
using namespace frostbox;
void check(bool value,const char* message) {if(!value) throw std::runtime_error(message);}
int main(int argc,char** argv) {
    try {
        check(argc==2,"fixture path required");
        check(prctl(PR_SET_CHILD_SUBREAPER,1)==0,"test-only subreaper setup");
        Command c; c.executable=argv[1];c.directory="/tmp";
        auto s=run(c);
        check(s.exit_code==42 && s.out=="FROSTBOX_NATIVE_RESULT=5050\n" && s.err=="FROSTBOX_NATIVE_STDERR=ok\n","computation/output");
        int status;check(waitpid(s.pid,&status,WNOHANG)==-1 && errno==ECHILD,"child reaped");
        c.arguments={"args","spaces ; $(echo bad)","'quote'"};c.environment={{"FROSTBOX_TOKEN","literal=$VALUE"}};
        s=run(c);check(s.exit_code==0 && s.out.find("arg=spaces ; $(echo bad)")!=std::string::npos && s.out.find("token=literal=$VALUE")!=std::string::npos,"literal argv/environment");
        c.arguments={"descendant"};s=run(c);
        auto descendant=static_cast<pid_t>(std::stoi(s.out));
        check(waitpid(descendant,&status,0)==descendant && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL,"process-group descendant cleanup");
        sigset_t blocked,previous;sigemptyset(&blocked);sigaddset(&blocked,SIGTERM);
        sigprocmask(SIG_BLOCK,&blocked,&previous);auto previous_handler=signal(SIGTERM,SIG_IGN);
        c.arguments={"wait-term"};auto masked=Process::spawn(c);
        sigprocmask(SIG_SETMASK,&previous,nullptr);signal(SIGTERM,previous_handler);
        do {s=masked->poll();(void)::poll(nullptr,0,10);}while(s.running && s.err.find("READY")==std::string::npos);
        masked->cancel();
        do {s=masked->poll();(void)::poll(nullptr,0,10);}while(s.running);
        check(s.signal==SIGTERM,"child signal mask/disposition reset");
        c.arguments={"flood"};c.output_limit=1024;s=run(c);
        check(s.exit_code==7 && s.out.size()==1024 && s.err.size()==1024 && s.dropped_out>0 && s.dropped_err>0,"bounded simultaneous stdout/stderr");
        c.arguments={"sleep"};c.timeout=std::chrono::milliseconds(100);s=run(c);
        check(s.timed_out && s.signal==SIGKILL && s.elapsed_ms<2000,"timeout/escalation");
        c.timeout=std::chrono::milliseconds(0);
        auto p=Process::spawn(c);(void)::poll(nullptr,0,50);p->cancel();
        do {s=p->poll();(void)::poll(nullptr,0,10);}while(s.running);
        check(s.cancelled && !s.timed_out && s.signal!=0,"unbounded user session remains cancellable");
        c.timeout=std::chrono::seconds(5);
        int fd=open("/dev/null",O_RDONLY);check(fd>=0,"open inherited fd");
        c.arguments={"fd",std::to_string(fd)};s=run(c);close(fd);check(s.exit_code==0,"descriptor isolation");
        int saved_stdin=fcntl(STDIN_FILENO,F_DUPFD_CLOEXEC,3);check(saved_stdin>=3,"save stdin");
        close(STDIN_FILENO);c.arguments={"stdin"};s=run(c);
        check(dup2(saved_stdin,STDIN_FILENO)==STDIN_FILENO,"restore stdin");close(saved_stdin);
        check(s.exit_code==0,"closed parent stdin replaced with /dev/null");
        c.arguments={"crash"};s=run(c);check(s.signal==SIGABRT,"signal report");
        c.executable="/missing-frostbox-executable";c.arguments={};s=run(c);check(s.exec_errno==ENOENT,"exec error pipe");
        c.executable=argv[1];c.directory="/missing-frostbox-directory";s=run(c);check(s.exec_errno==ENOENT,"cwd error pipe");
        c.directory="/tmp";c.arguments={std::string("bad\0arg",7)};
        bool rejected=false;try {run(c);}catch(const std::invalid_argument&) {rejected=true;}check(rejected,"embedded NUL rejected");
        c.arguments={"sleep"};pid_t owned;
        {auto cleanup=Process::spawn(c);owned=cleanup->poll().pid;}
        check(waitpid(owned,&status,WNOHANG)==-1 && errno==ECHILD,"destructor reaps");
        check(json_string(std::string("\0\xff",2))=="\"\\u0000\\u00ff\"","byte-safe JSON");
        check(json_string("Ж ёЁ № €")=="\"\\u0416 \\u0451\\u0401 \\u2116 \\u20ac\"","UTF-8 JSON characters");
        check(json_string("😀")=="\"\\ud83d\\ude00\"","supplementary UTF-8 JSON");
        check(json_string(std::string("\xe0\x80\x80",3))=="\"\\u00e0\\u0080\\u0080\"","overlong UTF-8 rejected");
        std::cout<<"Process lifecycle checks passed\n";
        return 0;
    } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
