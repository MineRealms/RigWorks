#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rwmd {
void preflight_model(const void* data, size_t size, bool pmx);
void preflight_vmd(const std::vector<uint8_t>& data);
}
