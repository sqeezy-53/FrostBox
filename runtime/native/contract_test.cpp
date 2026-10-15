#include "contract.hpp"
int main() {
    static_assert(frostbox::api_version == 2);
    static_assert(!frostbox::can_launch);
    return frostbox::unavailable.empty() ? 1 : 0;
}
