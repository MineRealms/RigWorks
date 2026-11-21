/* Generic end-to-end native conversion test for legacy YSM fixtures. */
#include "rigworks_ysm.h"
#include "../src/rwym.hpp"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

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

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

std::size_t face_count(const rigworks::ysm::Asset& asset) {
    std::size_t count = 0;
    for (const auto& model : asset.models)
        for (const auto& bone : model.bones)
            for (const auto& cube : bone.cubes)
                count += cube.faces.size();
    return count;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2, "usage: ysm_compat_fixture_test <fixture.ysm>");
        const auto source = read_file(argv[1]);
        Buffer converted;
        const auto status = rwym_convert_legacy_v3(
            source.data(), source.size(), &converted.data, &converted.size);
        require(status == RWYM_OK, std::string("conversion failed: ") + rwym_last_error());
        require(rwym_validate(converted.data, converted.size) == RWYM_OK,
                std::string("RWYM validation failed: ") + rwym_last_error());

        const auto asset = rigworks::ysm::read_rwym_v1(
            std::span(converted.data, static_cast<std::size_t>(converted.size)));
        require(asset.source_format >= 1 && asset.source_format <= 15,
                "converted source format is outside the supported legacy range");
        require(!asset.models.empty(), "converted asset has no models");
        require(face_count(asset) > 0, "converted asset has no visible faces");
        require(!asset.textures.empty(), "converted asset has no textures");

        std::cout << "format=" << asset.source_format
                  << " bytes=" << source.size()
                  << " rwym_bytes=" << converted.size
                  << " models=" << asset.models.size()
                  << " faces=" << face_count(asset)
                  << " categories=" << asset.animation_categories.size()
                  << " textures=" << asset.textures.size()
                  << " conversion=ok rwym_validation=ok\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
