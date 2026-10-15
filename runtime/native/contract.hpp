#pragma once
#include <string_view>
namespace frostbox {
inline constexpr unsigned api_version = 2;
inline constexpr bool can_launch = false;
inline constexpr std::string_view unavailable = "Windows runtime unavailable: Android Wine/userspace and display bridge are not integrated. Box64 built-in ELF diagnostics are separate from Windows execution.";
}
