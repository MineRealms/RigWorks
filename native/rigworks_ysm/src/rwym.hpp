/* RigWorks-owned RWYM persistent asset container, schema version 1. */
#pragma once

#include "ysm_payload.hpp"

namespace rigworks::ysm {

// Serializes structured sections with per-section CRC32 and a bounded directory.
[[nodiscard]] std::vector<std::uint8_t> write_rwym_v1(const Asset& asset);
[[nodiscard]] Asset read_rwym_v1(std::span<const std::uint8_t> bytes);

}  // namespace rigworks::ysm
