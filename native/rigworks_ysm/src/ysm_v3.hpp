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
 * Changes: C++20 API and bounded legacy V3 decode.
 */
#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace rigworks::ysm {

// Verifies and decodes a legacy YSGP V3 file to its decompressed payload.
// Throws std::invalid_argument for malformed or unsupported input,
// std::length_error when a configured size limit is exceeded, and
// std::runtime_error when zstd rejects the washed frame.
[[nodiscard]] std::vector<std::uint8_t> decode_legacy_v3(
    std::span<const std::uint8_t> file);

}  // namespace rigworks::ysm
