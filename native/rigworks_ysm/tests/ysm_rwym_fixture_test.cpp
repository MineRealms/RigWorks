/* Fixture-level conversion and RWYM integrity test for RigWorks YSM import. */
#include "../src/rwym.hpp"
#include "../src/ysm_v3.hpp"

#include <algorithm>
#include <array>
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

std::size_t count_faces(const rigworks::ysm::Asset& asset) {
    std::size_t count = 0;
    for (const auto& model : asset.models)
        for (const auto& bone : model.bones)
            for (const auto& cube : bone.cubes)
                count += cube.faces.size();
    return count;
}

std::size_t count_keyframes(const rigworks::ysm::Asset& asset) {
    std::size_t count = 0;
    for (const auto& category : asset.animation_categories)
        for (const auto& clip : category.clips)
            for (const auto& bone : clip.bones)
                for (const auto& channel : bone.channels)
                    count += channel.size();
    return count;
}

std::size_t count_pre_values(const rigworks::ysm::Asset& asset) {
    std::size_t count = 0;
    for (const auto& category : asset.animation_categories)
        for (const auto& clip : category.clips)
            for (const auto& bone : clip.bones)
                for (const auto& channel : bone.channels)
                    for (const auto& key : channel)
                        count += key.pre.has_value() ? 1U : 0U;
    return count;
}

std::size_t count_expressions(const rigworks::ysm::Asset& asset) {
    std::size_t count = 0;
    const auto count_value = [&](const rigworks::ysm::Value3& value) {
        for (const auto& component : value.components)
            count += component.tag == 2 ? 1U : 0U;
    };
    for (const auto& category : asset.animation_categories)
        for (const auto& clip : category.clips) {
            for (const auto& weight : clip.blend_weight) count += weight.tag == 2 ? 1U : 0U;
            for (const auto& bone : clip.bones)
                for (const auto& channel : bone.channels)
                    for (const auto& key : channel) {
                        if (key.pre) count_value(*key.pre);
                        count_value(key.post);
                    }
        }
    return count;
}

std::size_t count_timeline_events(const rigworks::ysm::Asset& asset) {
    std::size_t count = 0;
    for (const auto& category : asset.animation_categories)
        for (const auto& clip : category.clips)
            for (const auto& group : clip.timeline) count += group.instructions.size();
    return count;
}

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::invalid_argument("usage: ysm_rwym_fixture_test <fixture.ysm>");
        const auto file = read_file(argv[1]);
        const auto payload = rigworks::ysm::decode_legacy_v3(file);
        const auto source = rigworks::ysm::parse_legacy_payload(payload);
        require(source.source_format == 9, "fixture payload format changed");
        require(!source.models.empty(), "fixture has no models");
        require(!source.animation_categories.empty(), "fixture has no animation categories");
        require(!source.textures.empty(), "fixture has no textures");
        require(count_faces(source) > 0, "fixture has no direct faces");
        require(count_keyframes(source) > 0, "fixture has no animation keys");
        require(count_pre_values(source) > 0, "fixture does not exercise pre/post keyframes");
        require(count_expressions(source) > 0, "fixture does not exercise expression values");
        require(count_timeline_events(source) > 0, "fixture does not exercise timeline events");

        const auto encoded = rigworks::ysm::write_rwym_v1(source);
        const auto decoded = rigworks::ysm::read_rwym_v1(encoded);
        require(decoded.source_format == source.source_format, "source version was not preserved");
        require(decoded.models.size() == source.models.size(), "model count changed in RWYM");
        require(decoded.animation_categories.size() == source.animation_categories.size(), "animation category count changed");
        require(decoded.textures.size() == source.textures.size(), "texture count changed in RWYM");
        require(count_faces(decoded) == count_faces(source), "face geometry count changed in RWYM");
        require(count_keyframes(decoded) == count_keyframes(source), "keyframe count changed in RWYM");
        require(count_pre_values(decoded) == count_pre_values(source), "pre/post values changed in RWYM");
        require(count_expressions(decoded) == count_expressions(source), "expression values changed in RWYM");
        require(count_timeline_events(decoded) == count_timeline_events(source), "timeline events changed in RWYM");
        require(decoded.models.front().bones.front().name == source.models.front().bones.front().name,
                "bone identity changed in RWYM");
        require(decoded.animation_categories.front().clips.front().name == source.animation_categories.front().clips.front().name,
                "clip identity changed in RWYM");
        require(decoded.textures.front().color.rgba == source.textures.front().color.rgba,
                "texture RGBA changed in RWYM");
        require(rigworks::ysm::write_rwym_v1(decoded) == encoded,
                "RWYM serialization is not stable after read/write");

        auto large_texture = source;
        large_texture.textures.front().color.rgba.assign(4'194'304, 0x42);
        large_texture.textures.front().color.width = 1024;
        large_texture.textures.front().color.height = 1024;
        const auto large_texture_bytes = rigworks::ysm::write_rwym_v1(large_texture);
        const auto large_texture_decoded = rigworks::ysm::read_rwym_v1(large_texture_bytes);
        require(large_texture_decoded.textures.front().color.rgba.size() == 4'194'304,
                "RWYM large texture blob was truncated or rejected");

        auto extensible = source;
        extensible.opaque_sections.push_back({0x80000001U, 7, {0x13, 0x37, 0x42}});
        const auto extensible_bytes = rigworks::ysm::write_rwym_v1(extensible);
        const auto extensible_decoded = rigworks::ysm::read_rwym_v1(extensible_bytes);
        require(extensible_decoded.opaque_sections.size() == 1 &&
                    extensible_decoded.opaque_sections.front().type == 0x80000001U &&
                    extensible_decoded.opaque_sections.front().flags == 7 &&
                    extensible_decoded.opaque_sections.front().bytes ==
                        std::vector<std::uint8_t>{0x13, 0x37, 0x42},
                "unknown RWYM section was not retained");

        bool newer_format_rejected = false;
        const std::array<std::uint8_t, 4> unsupported_payload{16, 0, 0, 0};
        try { (void)rigworks::ysm::parse_legacy_payload(unsupported_payload); }
        catch (const std::invalid_argument&) { newer_format_rejected = true; }
        require(newer_format_rejected, "unsupported modern source version was accepted");

        auto corrupt = encoded;
        corrupt.back() ^= 0x80U;
        bool checksum_rejected = false;
        try { (void)rigworks::ysm::read_rwym_v1(corrupt); }
        catch (const std::exception&) { checksum_rejected = true; }
        require(checksum_rejected, "RWYM corruption was not rejected");

        std::cout << "format=" << source.source_format
                  << " models=" << source.models.size()
                  << " bones=" << source.models.front().bones.size()
                  << " faces=" << count_faces(source)
                  << " animation_categories=" << source.animation_categories.size()
                  << " clips=" << source.animation_categories.front().clips.size()
                  << " keyframes=" << count_keyframes(source)
                  << " pre_values=" << count_pre_values(source)
                  << " expressions=" << count_expressions(source)
                  << " timeline_events=" << count_timeline_events(source)
                  << " textures=" << source.textures.size()
                  << " unknown_section=roundtripped"
                  << " rwym_stable=exact"
                  << " rwym_bytes=" << encoded.size()
                  << " checksum_corruption=rejected\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
