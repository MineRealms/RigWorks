#include "rigworks_mmd.h"

#include <cstring>
#include <iostream>
#include <string>
#include <thread>

extern "C" int mmd_c_header_abi(void);

int main() {
    if (rwmd_abi_version() != 0x00010000 || mmd_c_header_abi() != 1) {
        std::cerr << "MMD converter ABI version mismatch\n";
        return 1;
    }
    if (rwmd_convert(nullptr, 0, nullptr, nullptr, nullptr) == 0 ||
        rwmd_last_error() == nullptr || std::strlen(rwmd_last_error()) == 0) {
        std::cerr << "MMD converter must return a diagnostic for invalid inputs\n";
        return 1;
    }
    const auto* original = rwmd_last_error();
    const std::string expected(original);
    bool thread_ok = false;
    std::thread worker([&] {
        thread_ok = rwmd_convert(nullptr, 0, "test.pmx", "", "{}") != 0 &&
            rwmd_last_error() != nullptr && std::strlen(rwmd_last_error()) != 0;
    });
    worker.join();
    if (!thread_ok || expected != rwmd_last_error()) {
        std::cerr << "MMD conversion diagnostics are not thread-local\n";
        return 1;
    }
    return 0;
}
