/* Exercise the DLL/SO boundary against the same real fixture as the C++ API. */
#include "rigworks_ysm.h"
#include "rwym.hpp"
#include "ysm_v3.hpp"

#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

extern "C" int rwym_c_header_test(void);

namespace {

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

std::vector<std::uint8_t> read_file(const char* path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error(std::string("cannot open fixture: ") + path);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

struct Buffer {
    std::uint8_t* data = nullptr;
    std::uint64_t size = 0;
    ~Buffer() { rwym_free(data); }
};

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "usage: ysm_abi_test <fixture.ysm>");
        require(rwym_c_header_test() != 0, "C header/version ABI check failed");
        const auto file = read_file(argv[1]);
        const auto payload = rigworks::ysm::decode_legacy_v3(file);
        const auto expected = rigworks::ysm::write_rwym_v1(
            rigworks::ysm::parse_legacy_payload(payload));

        Buffer converted;
        const auto status = rwym_convert_legacy_v3(file.data(), file.size(), &converted.data, &converted.size);
        require(status == RWYM_OK, rwym_last_error());
        require(converted.data != nullptr && converted.size == expected.size(), "C ABI output size differs");
        require(std::memcmp(converted.data, expected.data(), expected.size()) == 0, "C ABI output differs from C++ API");
        require(rwym_validate(converted.data, converted.size) == RWYM_OK, "converted RWYM validation failed");
        require(rwym_last_error()[0] == '\0', "successful call retained stale error");

        Buffer decoded_payload;
        require(rwym_convert_payload(payload.data(), payload.size(), &decoded_payload.data, &decoded_payload.size) == RWYM_OK,
                "decoded payload C ABI conversion failed");
        require(decoded_payload.size == converted.size &&
                    std::memcmp(decoded_payload.data, converted.data, static_cast<std::size_t>(converted.size)) == 0,
                "decoded payload and file ABI conversion differ");

        std::uint8_t* out = reinterpret_cast<std::uint8_t*>(1);
        std::uint64_t size = 99;
        require(rwym_convert_legacy_v3(nullptr, 1, &out, &size) == RWYM_ERR_ARGUMENT, "null input accepted");
        require(out == nullptr && size == 0, "failed conversion did not clear outputs");
        require(rwym_convert_legacy_v3(file.data(), file.size(), nullptr, &size) == RWYM_ERR_ARGUMENT,
                "null output accepted");
        require(rwym_convert_legacy_v3(file.data(), std::numeric_limits<std::uint64_t>::max(), &out, &size) == RWYM_ERR_LIMIT,
                "oversized input accepted");
        require(out == nullptr && size == 0, "size failure did not clear outputs");

        auto damaged = file;
        damaged.back() ^= 0x80;
        require(rwym_convert_legacy_v3(damaged.data(), damaged.size(), &out, &size) == RWYM_ERR_FORMAT,
                "invalid YSM checksum accepted");
        require(out == nullptr && size == 0, "corrupt input returned an output buffer");
        const std::string main_error = rwym_last_error();
        require(!main_error.empty(), "native failure has no error text");
        bool isolated_error = false;
        std::thread worker([&] {
            const auto worker_status = rwym_validate(nullptr, 0);
            isolated_error = worker_status == RWYM_ERR_ARGUMENT &&
                             std::string(rwym_last_error()) != main_error;
        });
        worker.join();
        require(isolated_error && std::string(rwym_last_error()) == main_error, "native error text is not thread-local");

        auto corrupt_asset = expected;
        corrupt_asset.back() ^= 0x80;
        require(rwym_validate(corrupt_asset.data(), corrupt_asset.size()) == RWYM_ERR_FORMAT,
                "corrupt RWYM checksum accepted");
        require(rwym_validate(converted.data, converted.size) == RWYM_OK, "valid RWYM failed after error");
        require(rwym_last_error()[0] == '\0', "error not cleared by successful validation");
        rwym_free(nullptr);

        std::cout << "abi=" << rwym_abi_version() << " version=" << rwym_version()
                  << " rwym_bytes=" << converted.size
                  << " native_parity=exact payload_parity=exact invalid_inputs=rejected thread_local_error=verified\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
