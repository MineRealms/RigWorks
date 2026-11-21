/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: C++20 implementation with strict envelope validation and bounded
 *          zstd output.
 */
#include "ysm_v3.hpp"

#include <zstd.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <span>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace rigworks::ysm {
namespace {

constexpr std::uint64_t kSeedKeyDerivation = 0xD017CBBA7B5D3581ULL;
constexpr std::uint64_t kSeedFileVerification = 0x9E5599DB80C67C29ULL;
constexpr std::uint64_t kSeedResourceVerification = 0xA62B1A2C43842BC3ULL;
constexpr std::uint64_t kCityK0 = 0xE4986A230E5AAA17ULL;
constexpr std::uint64_t kCityK1 = 0x91AF10802CAB25A5ULL;
constexpr std::uint64_t kCityK2 = 0xAF29CE778879D9C7ULL;
constexpr std::uint64_t kCityMul = 0xDE0F6EE09BDBAB91ULL;
constexpr std::size_t kFooterBytes = 64;
constexpr std::size_t kKeyBytes = 32;
constexpr std::size_t kIvBytes = 24;
constexpr std::size_t kMaxInputBytes = 256U * 1024U * 1024U;
constexpr std::size_t kMaxOutputBytes = 512U * 1024U * 1024U;
constexpr int kMaxZstdWindowLog = 29;

std::uint32_t read_le32(std::span<const std::uint8_t> bytes,
                        std::size_t offset) noexcept {
    return static_cast<std::uint32_t>(bytes[offset]) |
           (static_cast<std::uint32_t>(bytes[offset + 1]) << 8U) |
           (static_cast<std::uint32_t>(bytes[offset + 2]) << 16U) |
           (static_cast<std::uint32_t>(bytes[offset + 3]) << 24U);
}

std::uint64_t read_le64(std::span<const std::uint8_t> bytes,
                        std::size_t offset) noexcept {
    std::uint64_t result = 0;
    for (std::size_t index = 0; index < 8; ++index) {
        result |= static_cast<std::uint64_t>(bytes[offset + index])
                  << (index * 8U);
    }
    return result;
}

std::uint32_t load_le32(const std::uint8_t* bytes) noexcept {
    return static_cast<std::uint32_t>(bytes[0]) |
           (static_cast<std::uint32_t>(bytes[1]) << 8U) |
           (static_cast<std::uint32_t>(bytes[2]) << 16U) |
           (static_cast<std::uint32_t>(bytes[3]) << 24U);
}

std::uint64_t fetch64(std::span<const std::uint8_t> bytes,
                      std::size_t offset) noexcept {
    return read_le64(bytes, offset);
}

std::uint32_t fetch32(std::span<const std::uint8_t> bytes,
                      std::size_t offset) noexcept {
    return read_le32(bytes, offset);
}

std::uint64_t shift_mix(std::uint64_t value) noexcept {
    return value ^ (value >> 47U);
}

std::uint64_t byte_swap64(std::uint64_t value) noexcept {
    value = ((value & 0x00FF00FF00FF00FFULL) << 8U) |
            ((value >> 8U) & 0x00FF00FF00FF00FFULL);
    value = ((value & 0x0000FFFF0000FFFFULL) << 16U) |
            ((value >> 16U) & 0x0000FFFF0000FFFFULL);
    return (value << 32U) | (value >> 32U);
}

std::uint64_t hash_len16_mul(std::uint64_t u, std::uint64_t v,
                             std::uint64_t mul) noexcept {
    std::uint64_t a = (u ^ v) * mul;
    a ^= a >> 47U;
    std::uint64_t b = (v ^ a) * mul;
    b ^= b >> 47U;
    return b * mul;
}

std::uint64_t hash_len16(std::uint64_t u, std::uint64_t v) noexcept {
    const std::uint64_t term1 = (u ^ v) * kCityMul;
    const std::uint64_t term2 = (shift_mix(term1) ^ u) * kCityMul;
    return kCityMul * shift_mix(term2);
}

std::uint64_t hash_len0_to16(std::span<const std::uint8_t> bytes,
                             std::size_t offset,
                             std::size_t length) noexcept {
    if (length >= 8) {
        const std::uint64_t mul = kCityK2 + 2U * length;
        const std::uint64_t a = fetch64(bytes, offset) + kCityK2;
        const std::uint64_t b = fetch64(bytes, offset + length - 8);
        const std::uint64_t c = std::rotr(b, 37) * mul + a;
        const std::uint64_t d = (std::rotr(a, 25) + b) * mul;
        return hash_len16_mul(c, d, mul);
    }
    if (length >= 4) {
        const std::uint64_t mul = kCityK2 + 2U * length;
        const std::uint64_t a = fetch32(bytes, offset);
        const std::uint64_t b = fetch32(bytes, offset + length - 4);
        return hash_len16_mul(length + (a << 3U), b, mul);
    }
    if (length != 0) {
        const std::uint64_t a = bytes[offset];
        const std::uint64_t b = bytes[offset + (length >> 1U)];
        const std::uint64_t c = bytes[offset + length - 1];
        const std::uint64_t y = a + (b << 8U);
        const std::uint64_t z = length + (c << 2U);
        return shift_mix((y * kCityK2) ^ (z * kCityK0)) * kCityK2;
    }
    return kCityK2;
}

std::uint64_t hash_len17_to32(std::span<const std::uint8_t> bytes,
                              std::size_t offset,
                              std::size_t length) noexcept {
    const std::uint64_t mul = kCityK2 + 2U * length;
    const std::uint64_t a = fetch64(bytes, offset) * kCityK1;
    const std::uint64_t b = fetch64(bytes, offset + 8);
    const std::uint64_t c = fetch64(bytes, offset + length - 8) * mul;
    const std::uint64_t d = fetch64(bytes, offset + length - 16) * kCityK2;
    return hash_len16_mul(std::rotr(a + b, 43) + std::rotr(c, 30) + d,
                          a + std::rotr(b + kCityK2, 18) + c, mul);
}

std::uint64_t hash_len33_to64(std::span<const std::uint8_t> bytes,
                              std::size_t offset,
                              std::size_t length) noexcept {
    const std::uint64_t mul = kCityK2 + 2U * length;
    std::uint64_t a = fetch64(bytes, offset) * kCityK2;
    const std::uint64_t b0 = fetch64(bytes, offset + 8);
    const std::uint64_t c = fetch64(bytes, offset + length - 24);
    const std::uint64_t d = fetch64(bytes, offset + length - 32);
    const std::uint64_t e = fetch64(bytes, offset + 16) * kCityK2;
    const std::uint64_t f = fetch64(bytes, offset + 24) * 9U;
    const std::uint64_t g = fetch64(bytes, offset + length - 8);
    const std::uint64_t h = fetch64(bytes, offset + length - 16) * mul;
    const std::uint64_t u = std::rotr(a + g, 43) +
                            (std::rotr(b0, 30) + c) * 9U;
    const std::uint64_t v = ((a + g) ^ d) + f + 1U;
    const std::uint64_t w = byte_swap64((u + v) * mul) + h;
    const std::uint64_t x = std::rotr(e + f, 42) + c;
    const std::uint64_t y = (byte_swap64((v + w) * mul) + g) * mul;
    const std::uint64_t z = e + f + c;
    a = byte_swap64((x + z) * mul + y) + b0;
    const std::uint64_t b = shift_mix((z + a) * mul + d + h) * mul;
    return b + x;
}

std::pair<std::uint64_t, std::uint64_t> weak_hash_32(
    std::span<const std::uint8_t> bytes, std::size_t offset,
    std::uint64_t a, std::uint64_t b) noexcept {
    const std::uint64_t w = fetch64(bytes, offset);
    const std::uint64_t x = fetch64(bytes, offset + 8);
    const std::uint64_t y = fetch64(bytes, offset + 16);
    const std::uint64_t z = fetch64(bytes, offset + 24);
    a += w;
    b = std::rotr(b + a + z, 21);
    const std::uint64_t c = a;
    a += x + y;
    b += std::rotr(a, 44);
    return {a + z, b + c};
}

std::uint64_t city_hash64(std::span<const std::uint8_t> bytes) noexcept {
    const std::size_t length = bytes.size();
    if (length <= 16) {
        return hash_len0_to16(bytes, 0, length);
    }
    if (length <= 32) {
        return hash_len17_to32(bytes, 0, length);
    }
    if (length <= 64) {
        return hash_len33_to64(bytes, 0, length);
    }

    std::uint64_t x = fetch64(bytes, length - 40);
    std::uint64_t y = fetch64(bytes, length - 16) + fetch64(bytes, length - 56);
    std::uint64_t z = hash_len16(fetch64(bytes, length - 48) + length,
                                 fetch64(bytes, length - 24));
    auto v = weak_hash_32(bytes, length - 64, length, z);
    auto w = weak_hash_32(bytes, length - 32, y + kCityK1, x);
    x = x * kCityK1 + fetch64(bytes, 0);

    std::size_t remaining = (length - 1) & ~std::size_t{63};
    std::size_t offset = 0;
    while (remaining != 0) {
        x = std::rotr(x + y + v.first + fetch64(bytes, offset + 8), 37) * kCityK1;
        y = std::rotr(y + v.second + fetch64(bytes, offset + 48), 42) * kCityK1;
        x ^= w.second;
        y += v.first + fetch64(bytes, offset + 40);
        z = std::rotr(z + w.first, 33) * kCityK1;
        v = weak_hash_32(bytes, offset, v.second * kCityK1, x + w.first);
        w = weak_hash_32(bytes, offset + 32, z + w.second,
                         y + fetch64(bytes, offset + 16));
        std::swap(x, z);
        offset += 64;
        remaining -= 64;
    }
    return hash_len16(hash_len16(v.first, w.first) + shift_mix(y) * kCityK1 + z,
                      hash_len16(v.second, w.second) + x);
}

std::uint64_t city_hash64_with_seed(std::span<const std::uint8_t> bytes,
                                    std::uint64_t seed) noexcept {
    return hash_len16(city_hash64(bytes) - kCityK2, seed);
}

class Mt19937_64 {
public:
    explicit Mt19937_64(std::uint64_t seed) {
        state_[0] = seed;
        for (std::size_t index = 1; index < state_.size(); ++index) {
            const std::uint64_t previous = state_[index - 1];
            state_[index] = 6364136223846793005ULL *
                                (previous ^ (previous >> 62U)) +
                            index;
        }
    }

    std::uint64_t next() noexcept {
        if (index_ >= state_.size()) {
            twist();
        }
        std::uint64_t value = state_[index_++];
        value ^= (value >> 29U) & 0x5555555555555555ULL;
        value ^= (value << 17U) & 0x71D67FFFEDA60000ULL;
        value ^= (value << 37U) & 0xFFF7EEE000000000ULL;
        value ^= value >> 43U;
        return value;
    }

private:
    void twist() noexcept {
        for (std::size_t index = 0; index < state_.size(); ++index) {
            const std::uint64_t value =
                (state_[index] & 0xFFFFFFFF80000000ULL) |
                (state_[(index + 1) % state_.size()] & 0x7FFFFFFFULL);
            std::uint64_t mixed = value >> 1U;
            if ((value & 1U) != 0) {
                mixed ^= 0xB5026F5AA96619E9ULL;
            }
            state_[index] = state_[(index + 156) % state_.size()] ^ mixed;
        }
        index_ = 0;
    }

    std::array<std::uint64_t, 312> state_{};
    std::size_t index_ = 312;
};

std::uint32_t rotate_left32(std::uint32_t value, unsigned amount) noexcept {
    return (value << amount) | (value >> (32U - amount));
}

void quarter_round(std::array<std::uint32_t, 16>& state, std::size_t a,
                   std::size_t b, std::size_t c, std::size_t d) noexcept {
    state[a] += state[b];
    state[d] = rotate_left32(state[d] ^ state[a], 16);
    state[c] += state[d];
    state[b] = rotate_left32(state[b] ^ state[c], 12);
    state[a] += state[b];
    state[d] = rotate_left32(state[d] ^ state[a], 8);
    state[c] += state[d];
    state[b] = rotate_left32(state[b] ^ state[c], 7);
}

void shuffle(std::array<std::uint32_t, 16>& state, unsigned rounds) noexcept {
    for (unsigned round = 0; round < rounds / 2; ++round) {
        quarter_round(state, 0, 4, 8, 12);
        quarter_round(state, 1, 5, 9, 13);
        quarter_round(state, 2, 6, 10, 14);
        quarter_round(state, 3, 7, 11, 15);
        quarter_round(state, 0, 5, 10, 15);
        quarter_round(state, 1, 6, 11, 12);
        quarter_round(state, 2, 7, 8, 13);
        quarter_round(state, 3, 4, 9, 14);
    }
}

class ModifiedXChaCha20 {
public:
    ModifiedXChaCha20(std::span<const std::uint8_t> key,
                      std::span<const std::uint8_t> nonce, unsigned rounds) {
        std::array<std::uint32_t, 16> hstate{
            0x61707865U, 0x3320646EU, 0x79622D32U, 0x6B206574U};
        for (std::size_t index = 0; index < 8; ++index) {
            hstate[index + 4] = load_le32(key.data() + index * 4);
        }
        for (std::size_t index = 0; index < 4; ++index) {
            hstate[index + 12] = load_le32(nonce.data() + index * 4);
        }
        shuffle(hstate, rounds);

        const std::array<std::uint32_t, 8> subkey{
            hstate[0], hstate[1], hstate[2], hstate[3],
            hstate[12], hstate[13], hstate[14], hstate[15]};
        state_[0] = 0x61707865U;
        state_[1] = 0x3320646EU;
        state_[2] = 0x79622D32U;
        state_[3] = 0x6B206574U;
        std::copy(subkey.begin(), subkey.end(), state_.begin() + 4);
        state_[12] = 0;
        state_[13] = 0;
        state_[14] = load_le32(nonce.data() + 16);
        state_[15] = load_le32(nonce.data() + 20);
        rounds_ = rounds;
    }

    void process(std::span<const std::uint8_t> input,
                 std::span<std::uint8_t> output) noexcept {
        std::size_t offset = 0;
        while (offset < input.size()) {
            std::array<std::uint32_t, 16> working = state_;
            shuffle(working, rounds_);
            std::array<std::uint8_t, 64> stream{};
            for (std::size_t word = 0; word < working.size(); ++word) {
                const std::uint32_t value = working[word] + state_[word];
                for (std::size_t byte = 0; byte < 4; ++byte) {
                    stream[word * 4 + byte] =
                        static_cast<std::uint8_t>(value >> (byte * 8U));
                }
            }
            const std::size_t count = std::min<std::size_t>(64, input.size() - offset);
            for (std::size_t index = 0; index < count; ++index) {
                output[offset + index] = input[offset + index] ^ stream[index];
            }
            if (++state_[12] == 0) {
                ++state_[13];
            }
            offset += count;
        }
    }

    std::size_t update_state(std::uint64_t hash) noexcept {
        rounds_ = static_cast<unsigned>(10U * (hash % 3U) + 10U);
        const auto low = static_cast<std::uint32_t>(hash);
        const auto high = static_cast<std::uint32_t>(hash >> 32U);
        for (std::size_t index = 4; index < state_.size(); ++index) {
            state_[index] ^= index % 2 == 0 ? low : high;
        }
        return static_cast<std::size_t>(((hash & 0x3FU) | 0x40U) << 6U);
    }

private:
    std::array<std::uint32_t, 16> state_{};
    unsigned rounds_ = 20;
};

void decrypt_modified_chacha(std::span<const std::uint8_t> input,
                             std::span<const std::uint8_t> key,
                             std::span<const std::uint8_t> iv,
                             std::uint64_t seed,
                             std::vector<std::uint8_t>& output) {
    std::array<std::uint8_t, kKeyBytes + kIvBytes> key_iv{};
    std::copy(key.begin(), key.end(), key_iv.begin());
    std::copy(iv.begin(), iv.end(), key_iv.begin() + kKeyBytes);
    const std::uint64_t derivation = city_hash64_with_seed(key_iv, seed);
    std::size_t next_chunk =
        static_cast<std::size_t>(((derivation & 0x3FU) | 0x40U) << 6U);
    const unsigned initial_rounds =
        static_cast<unsigned>(10U * (derivation % 3U) + 10U);
    ModifiedXChaCha20 cipher(key, iv, initial_rounds);
    output.resize(input.size());

    std::size_t offset = 0;
    while (offset < input.size()) {
        next_chunk = std::min(next_chunk, input.size() - offset);
        cipher.process(input.subspan(offset, next_chunk),
                       std::span<std::uint8_t>(output).subspan(offset, next_chunk));
        offset += next_chunk;
        if (offset < input.size()) {
            const std::uint64_t chunk_hash = city_hash64_with_seed(
                std::span<const std::uint8_t>(output).subspan(offset - next_chunk,
                                                               next_chunk),
                seed);
            next_chunk = cipher.update_state(chunk_hash);
        }
    }
}

void xor_mt19937(std::span<std::uint8_t> bytes,
                 std::span<const std::uint8_t> key_iv) noexcept {
    const std::uint64_t seed = city_hash64_with_seed(key_iv, kSeedKeyDerivation);
    Mt19937_64 generator(seed);
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const std::uint64_t random = generator.next();
        for (unsigned index = 0; index < 8 && offset < bytes.size(); ++index) {
            bytes[offset++] ^= static_cast<std::uint8_t>(random >> (index * 8U));
        }
    }
}

std::size_t frame_header_size(std::uint8_t descriptor) noexcept {
    const std::uint8_t dictionary_bits = descriptor & 3U;
    const std::size_t dictionary_size = dictionary_bits == 1 ? 1U
        : dictionary_bits == 2 ? 2U
        : dictionary_bits == 3 ? 4U
                               : 0U;
    const bool single_segment = (descriptor & 0x20U) != 0;
    const std::uint8_t content_size_bits = descriptor >> 6U;
    const std::size_t content_size = content_size_bits == 0 ? (single_segment ? 1U : 0U)
        : content_size_bits == 1 ? 2U
        : content_size_bits == 2 ? 4U
                                 : 8U;
    const std::size_t window_descriptor = single_segment ? 0U : 1U;
    return 1U + window_descriptor + dictionary_size + content_size;
}

void wash_zstd_frame(std::span<std::uint8_t> frame) {
    if (frame.size() < 5 || read_le32(frame, 0) != 0xFD2FB528U) {
        throw std::invalid_argument("YSM payload is not a zstd frame");
    }

    const std::uint8_t descriptor = frame[4];
    if ((descriptor & 0x08U) != 0) {
        throw std::invalid_argument("YSM zstd frame has a reserved header bit");
    }
    frame[4] = static_cast<std::uint8_t>(descriptor & 0xFBU);
    const std::size_t header_end = 4U + frame_header_size(descriptor);
    if (header_end > frame.size()) {
        throw std::invalid_argument("YSM zstd frame header is truncated");
    }

    std::size_t offset = header_end;
    bool found_last_block = false;
    while (offset <= frame.size() && frame.size() - offset >= 3) {
        const std::uint8_t b0 = frame[offset];
        const std::uint8_t b1 = frame[offset + 1];
        const std::uint8_t b2 = frame[offset + 2];
        const bool is_last = (b0 & 0x80U) != 0;
        const std::uint32_t ysm_type = (b0 >> 5U) & 3U;
        const std::uint32_t raw_size =
            (static_cast<std::uint32_t>(b0 & 0x1FU) << 16U) |
            static_cast<std::uint32_t>(b1) |
            (static_cast<std::uint32_t>(b2) << 8U);
        const std::uint32_t compressed_size = raw_size ^ 0xD4E9U;
        const std::uint32_t standard_type = ysm_type == 0 ? 2U
            : ysm_type == 1 ? 1U
            : ysm_type == 2 ? 3U
                            : 0U;
        if (compressed_size > 128U * 1024U) {
            throw std::invalid_argument("YSM zstd block exceeds its size limit");
        }
        if (standard_type == 3U) {
            throw std::invalid_argument("YSM zstd frame uses a reserved block type");
        }
        const std::uint32_t stored_size =
            standard_type == 1U ? 1U : compressed_size;
        if (stored_size > frame.size() - offset - 3U) {
            throw std::invalid_argument("YSM zstd block is truncated");
        }

        const std::uint32_t standard_header =
            static_cast<std::uint32_t>(is_last) | (standard_type << 1U) |
            (compressed_size << 3U);
        frame[offset] = static_cast<std::uint8_t>(standard_header);
        frame[offset + 1] = static_cast<std::uint8_t>(standard_header >> 8U);
        frame[offset + 2] = static_cast<std::uint8_t>(standard_header >> 16U);
        offset += 3U + stored_size;
        if (is_last) {
            found_last_block = true;
            break;
        }
    }
    if (!found_last_block) {
        throw std::invalid_argument("YSM zstd frame has no final block");
    }
}

std::vector<std::uint8_t> decompress_zstd(std::span<const std::uint8_t> frame) {
    using Dctx = std::unique_ptr<ZSTD_DCtx, decltype(&ZSTD_freeDCtx)>;
    Dctx context(ZSTD_createDCtx(), &ZSTD_freeDCtx);
    if (!context) {
        throw std::bad_alloc();
    }
    const std::size_t parameter_result = ZSTD_DCtx_setParameter(
        context.get(), ZSTD_d_windowLogMax, kMaxZstdWindowLog);
    if (ZSTD_isError(parameter_result)) {
        throw std::runtime_error(std::string("cannot configure zstd decoder: ") +
                                 ZSTD_getErrorName(parameter_result));
    }

    std::vector<std::uint8_t> output;
    std::array<std::uint8_t, 64U * 1024U> chunk{};
    ZSTD_inBuffer input{frame.data(), frame.size(), 0};
    std::size_t remaining = 1;
    while (true) {
        const std::size_t before = input.pos;
        ZSTD_outBuffer destination{chunk.data(), chunk.size(), 0};
        remaining = ZSTD_decompressStream(context.get(), &destination, &input);
        if (ZSTD_isError(remaining)) {
            throw std::runtime_error(std::string("zstd decode failed: ") +
                                     ZSTD_getErrorName(remaining));
        }
        if (destination.pos > kMaxOutputBytes - output.size()) {
            throw std::length_error("YSM decompressed payload exceeds 512 MiB");
        }
        output.insert(output.end(), chunk.begin(),
                      chunk.begin() + static_cast<std::ptrdiff_t>(destination.pos));

        if (remaining == 0 && input.pos == input.size) {
            break;
        }
        if (input.pos == before && destination.pos == 0) {
            throw std::runtime_error("zstd decoder made no progress");
        }
        if (input.pos == input.size && destination.pos == 0 && remaining != 0) {
            throw std::runtime_error("zstd frame is truncated");
        }
    }
    return output;
}

}  // namespace

std::vector<std::uint8_t> decode_legacy_v3(
    std::span<const std::uint8_t> file) {
    if (file.size() > kMaxInputBytes) {
        throw std::length_error("YSM input exceeds 256 MiB");
    }
    if (file.size() < 8U + 4U + 2U + 5U + kFooterBytes) {
        throw std::invalid_argument("YSM V3 file is too short");
    }
    if (file[0] != 0xEFU || file[1] != 0xBBU || file[2] != 0xBFU ||
        file[3] != 'Y' || file[4] != 'S' || file[5] != 'G' || file[6] != 'P') {
        throw std::invalid_argument("YSM V3 header magic is invalid");
    }

    const std::size_t footer_offset = file.size() - kFooterBytes;
    const auto header_end_it = std::find(file.begin() + 7, file.begin() + footer_offset, 0U);
    if (header_end_it == file.begin() + footer_offset) {
        throw std::invalid_argument("YSM V3 header terminator is missing");
    }
    const std::size_t header_end =
        static_cast<std::size_t>(header_end_it - file.begin());
    const std::size_t crypto_offset = header_end + 1U;
    if (crypto_offset > footer_offset || footer_offset - crypto_offset < 4U + 7U) {
        throw std::invalid_argument("YSM V3 crypto body is truncated");
    }

    const std::uint64_t expected_hash = read_le64(file, file.size() - 8U);
    if (city_hash64_with_seed(file.first(file.size() - 8U),
                              kSeedFileVerification) != expected_hash) {
        throw std::invalid_argument("YSM V3 footer hash mismatch");
    }

    if (read_le32(file, crypto_offset) != 3U) {
        throw std::invalid_argument("unsupported YSM V3 crypto version");
    }
    const std::size_t body_offset = crypto_offset + 4U;
    const auto key = file.subspan(footer_offset, kKeyBytes);
    const auto iv = file.subspan(footer_offset + kKeyBytes, kIvBytes);
    const auto encrypted = file.subspan(body_offset, footer_offset - body_offset);

    std::vector<std::uint8_t> body;
    decrypt_modified_chacha(encrypted, key, iv, kSeedResourceVerification, body);
    std::array<std::uint8_t, kKeyBytes + kIvBytes> key_iv{};
    std::copy(key.begin(), key.end(), key_iv.begin());
    std::copy(iv.begin(), iv.end(), key_iv.begin() + kKeyBytes);
    xor_mt19937(body, key_iv);

    if (body.size() < 2U) {
        throw std::invalid_argument("YSM V3 decrypted body is truncated");
    }
    const std::size_t padding =
        (static_cast<std::size_t>(body[0]) |
         (static_cast<std::size_t>(body[1]) << 8U)) & 0x3FFU;
    if (padding > body.size() - 2U || body.size() - 2U - padding < 5U) {
        throw std::invalid_argument("YSM V3 padding length is invalid");
    }
    const std::size_t frame_offset = 2U + padding;
    auto frame = std::span<std::uint8_t>(body).subspan(frame_offset);
    wash_zstd_frame(frame);
    return decompress_zstd(frame);
}

}  // namespace rigworks::ysm
