#pragma once
#include "process.hpp"
#include <filesystem>
namespace frostbox {
/** Concrete command adapter for the pinned APK-packaged Bionic executable. No support claim from presence alone. */
class Box64Backend {
public:
    explicit Box64Backend(std::filesystem::path executable): executable_(std::move(executable)) {}
    Command diagnostic(const std::filesystem::path& guest,const std::filesystem::path& directory,bool dynarec) const;
private:
    std::filesystem::path executable_;
};
}
