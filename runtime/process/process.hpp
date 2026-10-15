#pragma once
#include <chrono>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <sys/types.h>

namespace frostbox {
struct Command {
    std::string executable;
    std::vector<std::string> arguments; // Does not include argv[0]; no shell interpretation.
    std::map<std::string, std::string> environment; // Explicit; parent environment is NOT inherited.
    std::string directory;
    std::chrono::milliseconds timeout{5000}; // Zero: explicit user session, no deadline; cancellation still applies.
    size_t output_limit = 65536; // Per stream; excess is drained and counted.
};
struct ProcessSnapshot {
    pid_t pid = -1;
    bool running = false;
    int exit_code = -1, signal = 0, exec_errno = 0;
    bool timed_out = false, cancelled = false;
    std::string out, err, error;
    uint64_t dropped_out = 0, dropped_err = 0;
    int64_t elapsed_ms = 0, peak_rss_kib = 0;
    double user_ms = 0, system_ms = 0;
    std::string json() const;
};
/** Owns one child and its process group. Poll/cancel are serialized; no detached background thread. */
class Process {
public:
    static std::shared_ptr<Process> spawn(Command command);
    ~Process();
    Process(const Process&) = delete;
    Process& operator=(const Process&) = delete;
    ProcessSnapshot poll();
    void cancel();
private:
    explicit Process(Command command);
    void launch();
    void stop_locked(bool timeout);
    void drain_locked();
    void close_fds();
    Command command_;
    ProcessSnapshot state_;
    std::mutex mutex_;
    int out_fd_=-1, err_fd_=-1, exec_fd_=-1;
    std::chrono::steady_clock::time_point started_, stopping_;
    bool stopping_requested_=false;
};
ProcessSnapshot run(Command command); // Host/service worker only, never UI thread.
std::string json_string(const std::string& bytes);
}
