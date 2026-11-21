/* Exception-safe, allocator-owned C facade for the RigWorks YSM converter. */
#include "rigworks_ysm.h"
#include "rwym.hpp"
#include "ysm_v3.hpp"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <span>
#include <stdexcept>
#include <vector>

namespace {

constexpr std::uint64_t kMaxFileBytes = 256ULL * 1024 * 1024;
constexpr std::uint64_t kMaxPayloadBytes = 512ULL * 1024 * 1024;
constexpr std::uint64_t kMaxAssetBytes = 1024ULL * 1024 * 1024;
thread_local std::array<char, 1024> last_error{};

void set_error(const char* message) noexcept {
    std::snprintf(last_error.data(), last_error.size(), "%s", message);
}

template <class Function>
int32_t guarded(Function&& function) noexcept {
    last_error[0] = '\0';
    try {
        function();
        return RWYM_OK;
    } catch (const std::bad_alloc&) {
        set_error("native YSM conversion ran out of memory");
        return RWYM_ERR_MEMORY;
    } catch (const std::length_error& error) {
        set_error(error.what());
        return RWYM_ERR_LIMIT;
    } catch (const std::invalid_argument& error) {
        set_error(error.what());
        return RWYM_ERR_FORMAT;
    } catch (const std::exception& error) {
        set_error(error.what());
        return RWYM_ERR_DECODE;
    } catch (...) {
        set_error("unknown native YSM conversion failure");
        return RWYM_ERR_INTERNAL;
    }
}

bool valid_input(const std::uint8_t* data, std::uint64_t length,
                 std::uint64_t maximum) noexcept {
    if (data == nullptr || length == 0) {
        set_error("input buffer must be non-null and non-empty");
        return false;
    }
    if (length > maximum || length > std::numeric_limits<std::size_t>::max()) {
        set_error("input length exceeds the native conversion limit");
        return false;
    }
    return true;
}

template <class Function>
int32_t convert(const std::uint8_t* data, std::uint64_t length,
                std::uint8_t** out, std::uint64_t* out_length,
                std::uint64_t maximum, Function&& function) noexcept {
    last_error[0] = '\0';
    if (out != nullptr) *out = nullptr;
    if (out_length != nullptr) *out_length = 0;
    if (out == nullptr || out_length == nullptr) {
        set_error("output pointer and output length are required");
        return RWYM_ERR_ARGUMENT;
    }
    if (!valid_input(data, length, maximum))
        return length > maximum ? RWYM_ERR_LIMIT : RWYM_ERR_ARGUMENT;

    return guarded([&] {
        const auto bytes = function(std::span(data, static_cast<std::size_t>(length)));
        auto* buffer = static_cast<std::uint8_t*>(std::malloc(bytes.size()));
        if (buffer == nullptr) throw std::bad_alloc();
        std::memcpy(buffer, bytes.data(), bytes.size());
        *out = buffer;
        *out_length = bytes.size();
    });
}

}  // namespace

extern "C" {

uint32_t RWYM_CALL rwym_abi_version(void) { return RWYM_ABI_VERSION; }

const char* RWYM_CALL rwym_version(void) { return "rigworks-ysm/1.0.0 rwym/1"; }

int32_t RWYM_CALL rwym_convert_legacy_v3(const uint8_t* file, uint64_t length,
                                       uint8_t** out, uint64_t* out_length) {
    return convert(file, length, out, out_length, kMaxFileBytes, [](auto bytes) {
        const auto payload = rigworks::ysm::decode_legacy_v3(bytes);
        return rigworks::ysm::write_rwym_v1(rigworks::ysm::parse_legacy_payload(payload));
    });
}

int32_t RWYM_CALL rwym_convert_payload(const uint8_t* payload, uint64_t length,
                                     uint8_t** out, uint64_t* out_length) {
    return convert(payload, length, out, out_length, kMaxPayloadBytes, [](auto bytes) {
        return rigworks::ysm::write_rwym_v1(rigworks::ysm::parse_legacy_payload(bytes));
    });
}

int32_t RWYM_CALL rwym_validate(const uint8_t* asset, uint64_t length) {
    last_error[0] = '\0';
    if (!valid_input(asset, length, kMaxAssetBytes))
        return length > kMaxAssetBytes ? RWYM_ERR_LIMIT : RWYM_ERR_ARGUMENT;
    return guarded([&] {
        (void)rigworks::ysm::read_rwym_v1(
            std::span(asset, static_cast<std::size_t>(length)));
    });
}

void RWYM_CALL rwym_free(void* buffer) { std::free(buffer); }

const char* RWYM_CALL rwym_last_error(void) { return last_error.data(); }

}  // extern "C"
