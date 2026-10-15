#pragma once
#include "process.hpp"
#include <filesystem>
namespace frostbox {
// Android guest command factory; lifecycle remains owned by the existing Process class.
Command android_wine_command(const std::filesystem::path& native_dir,
    const std::filesystem::path& runtime, const std::filesystem::path& prefix,
    const std::filesystem::path& work, const std::string& operation,
    const std::filesystem::path& exe, const std::vector<std::string>& args);
}
