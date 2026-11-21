/*
 * RigWorks-owned cleanroom parser for legacy YSM V1.5 payloads.
 * Format observations are cross-checked against the Apache-2.0 YesSteveModel
 * and YSMParser projects; this implementation links neither project.
 */
#include "ysm_payload.hpp"

#include <bit>
#include <cmath>
#include <iterator>
#include <limits>
#include <stdexcept>
#include <string_view>

namespace rigworks::ysm {
namespace {

constexpr std::uint32_t kMinLegacyFormat = 1;
constexpr std::uint32_t kMaxLegacyFormat = 15;
constexpr std::uint32_t kMaxCollectionItems = 1'000'000;
constexpr std::size_t kMaxStringBytes = 16U * 1024U * 1024U;
constexpr std::size_t kMaxAssetBytes = 512U * 1024U * 1024U;

class Reader {
public:
    explicit Reader(std::span<const std::uint8_t> bytes) : bytes_(bytes) {
        if (bytes.size() > kMaxAssetBytes) {
            throw std::length_error("YSM payload exceeds 512 MiB");
        }
    }

    [[nodiscard]] std::size_t position() const noexcept { return position_; }
    [[nodiscard]] std::size_t remaining() const noexcept {
        return bytes_.size() - position_;
    }
    [[nodiscard]] std::span<const std::uint8_t> read_span(std::size_t count) {
        require(count);
        const auto result = bytes_.subspan(position_, count);
        position_ += count;
        return result;
    }
    [[nodiscard]] std::vector<std::uint8_t> read_bytes(std::size_t count) {
        const auto view = read_span(count);
        return {view.begin(), view.end()};
    }
    [[nodiscard]] std::uint8_t read_u8() {
        require(1);
        return bytes_[position_++];
    }
    [[nodiscard]] std::uint32_t read_u32() {
        const auto bytes = read_span(4);
        return static_cast<std::uint32_t>(bytes[0]) |
               (static_cast<std::uint32_t>(bytes[1]) << 8U) |
               (static_cast<std::uint32_t>(bytes[2]) << 16U) |
               (static_cast<std::uint32_t>(bytes[3]) << 24U);
    }
    [[nodiscard]] float read_float() {
        return std::bit_cast<float>(read_u32());
    }
    [[nodiscard]] std::uint32_t read_varint() {
        std::uint32_t value = 0;
        for (unsigned index = 0; index < 5; ++index) {
            const std::uint8_t byte = read_u8();
            if (index == 4 && (byte & 0xF0U) != 0) {
                throw std::invalid_argument("YSM varint exceeds uint32");
            }
            value |= static_cast<std::uint32_t>(byte & 0x7FU) << (7U * index);
            if ((byte & 0x80U) == 0) {
                return value;
            }
        }
        throw std::invalid_argument("unterminated YSM varint");
    }
    [[nodiscard]] std::size_t read_count(std::string_view label) {
        const std::size_t offset = position_;
        const std::uint32_t value = read_varint();
        if (value > kMaxCollectionItems) {
            throw std::length_error(std::string("YSM ") + std::string(label) +
                                    " count " + std::to_string(value) +
                                    " exceeds limit at offset " +
                                    std::to_string(offset));
        }
        return value;
    }
    [[nodiscard]] std::vector<std::uint8_t> read_byte_array() {
        const std::uint32_t size = read_varint();
        if (size > kMaxAssetBytes || size > remaining()) {
            throw std::length_error("YSM byte array exceeds payload bounds");
        }
        return read_bytes(size);
    }
    [[nodiscard]] std::string read_string() {
        const auto data = read_byte_array();
        if (data.size() > kMaxStringBytes) {
            throw std::length_error("YSM string exceeds 16 MiB");
        }
        if (data.empty()) {
            return {};
        }
        return {reinterpret_cast<const char*>(data.data()), data.size()};
    }
    [[nodiscard]] Vec3 read_vec3() {
        return {read_float(), read_float(), read_float()};
    }
    [[nodiscard]] std::vector<std::uint8_t> read_remaining() {
        return read_bytes(remaining());
    }

private:
    void require(std::size_t count) const {
        if (count > remaining()) {
            throw std::invalid_argument("truncated YSM payload at offset " +
                                        std::to_string(position_));
        }
    }

    std::span<const std::uint8_t> bytes_;
    std::size_t position_ = 0;
};

[[nodiscard]] ComponentValue read_component(Reader& reader) {
    ComponentValue value;
    value.tag = reader.read_u8();
    if (value.tag == 1) {
        value.number = reader.read_float();
    } else if (value.tag == 2) {
        value.expression = reader.read_string();
    } else {
        throw std::invalid_argument("unsupported YSM animation value tag " +
                                    std::to_string(value.tag));
    }
    return value;
}

[[nodiscard]] Value3 read_value3(Reader& reader) {
    Value3 result;
    for (auto& component : result.components) {
        component = read_component(reader);
    }
    return result;
}

[[nodiscard]] ModelInfo read_model_info(Reader& reader) {
    ModelInfo info;
    info.name = reader.read_string();
    info.tips = reader.read_string();
    const std::size_t extra_count = reader.read_count("model info extra animation");
    info.extra_animation_names.reserve(extra_count);
    for (std::size_t i = 0; i < extra_count; ++i) {
        info.extra_animation_names.push_back(reader.read_string());
    }
    const std::size_t author_count = reader.read_count("model info author");
    info.authors.reserve(author_count);
    for (std::size_t i = 0; i < author_count; ++i) {
        info.authors.push_back(reader.read_string());
    }
    info.license = reader.read_string();
    info.free_flag = reader.read_varint();
    return info;
}

[[nodiscard]] Model read_model(Reader& reader, std::uint32_t format,
                               std::uint32_t model_id,
                               std::uint32_t entry_unknown) {
    Model model;
    model.source_id = model_id;
    model.category = model_id == 1 ? "main" : model_id == 2 ? "arm" :
                     model_id == 3 ? "arrow" : "unk_" + std::to_string(model_id);
    model.entry_unknown = entry_unknown;

    const std::size_t bone_count = reader.read_count("bone");
    model.bones.reserve(bone_count);
    for (std::size_t bone_index = 0; bone_index < bone_count; ++bone_index) {
        Bone bone;
        bone.parent_name = reader.read_string();
        const std::size_t cube_count = reader.read_count("cube");
        bone.cubes.reserve(cube_count);
        for (std::size_t cube_index = 0; cube_index < cube_count; ++cube_index) {
            Cube cube;
            const std::size_t face_count = reader.read_count("face");
            cube.faces.reserve(face_count);
            for (std::size_t face_index = 0; face_index < face_count; ++face_index) {
                Face face;
                face.normal = reader.read_vec3();
                for (FaceVertex& vertex : face.vertices) {
                    vertex.position = reader.read_vec3();
                    vertex.u = reader.read_float();
                    vertex.v = reader.read_float();
                }
                cube.faces.push_back(std::move(face));
            }
            for (auto& unknown : cube.unknown_values) {
                unknown = reader.read_varint();
            }
            bone.cubes.push_back(std::move(cube));
        }

        bone.name = reader.read_string();
        for (auto& unknown : bone.unknown_values) {
            unknown = reader.read_varint();
        }
        bone.pivot = reader.read_vec3();
        bone.rotation_radians = reader.read_vec3();
        model.bones.push_back(std::move(bone));
    }

    model.identifier = reader.read_string();
    model.texture_height = reader.read_float();
    model.texture_width = reader.read_float();
    model.visible_bounds_height = reader.read_float();
    model.visible_bounds_width = reader.read_float();
    const std::size_t offset_count = reader.read_count("visible bounds offset");
    model.visible_bounds_offset.reserve(offset_count);
    for (std::size_t i = 0; i < offset_count; ++i) {
        model.visible_bounds_offset.push_back(reader.read_float());
    }
    model.scale_x = reader.read_float();
    model.scale_y = reader.read_float();
    model.info_flag = reader.read_varint();
    if (model.info_flag != 0) {
        model.info = read_model_info(reader);
    }
    for (auto& unknown : model.trailing_unknown_values) {
        unknown = reader.read_varint();
    }
    (void)format;
    return model;
}

[[nodiscard]] std::vector<Keyframe> read_channel(Reader& reader) {
    const std::size_t keyframe_count = reader.read_count("keyframe");
    std::vector<Keyframe> result;
    result.reserve(keyframe_count);
    for (std::size_t i = 0; i < keyframe_count; ++i) {
        Keyframe frame;
        frame.time_ticks = reader.read_float();
        frame.interpolation = reader.read_varint();
        const Value3 first = read_value3(reader);
        const std::uint32_t has_pre = reader.read_varint();
        if (has_pre > 1) {
            throw std::invalid_argument("invalid YSM keyframe pre flag");
        }
        if (has_pre != 0) {
            frame.pre = first;
            frame.post = read_value3(reader);
        } else {
            frame.post = first;
        }
        result.push_back(std::move(frame));
    }
    return result;
}

[[nodiscard]] AnimationClip read_animation(Reader& reader,
                                           std::uint32_t format) {
    AnimationClip clip;
    clip.name = reader.read_string();
    clip.duration_ticks = reader.read_float();
    clip.loop_mode = reader.read_varint();
    if (format > 9) {
        const auto flag_a = reader.read_varint();
        const auto flag_b = reader.read_varint();
        if (flag_a != 0 || flag_b != 0) {
            throw std::invalid_argument("unsupported nonzero YSM animation flags");
        }
        clip.format_flags.push_back(flag_a);
        clip.format_flags.push_back(flag_b);
        const std::size_t blend_count = reader.read_count("blend weight");
        clip.blend_weight.reserve(blend_count);
        for (std::size_t i = 0; i < blend_count; ++i) {
            clip.blend_weight.push_back(read_component(reader));
        }
        const auto final_flag = reader.read_varint();
        if (final_flag != 0) {
            throw std::invalid_argument("unsupported nonzero YSM animation tail flag");
        }
        clip.format_flags.push_back(final_flag);
    }

    const std::size_t bone_count = reader.read_count("animation bone");
    clip.bones.reserve(bone_count);
    for (std::size_t i = 0; i < bone_count; ++i) {
        BoneAnimation bone;
        bone.bone_name = reader.read_string();
        for (auto& channel : bone.channels) {
            channel = read_channel(reader);
        }
        clip.bones.push_back(std::move(bone));
    }

    const std::size_t timeline_count = reader.read_count("timeline event group");
    clip.timeline.reserve(timeline_count);
    for (std::size_t i = 0; i < timeline_count; ++i) {
        TimelineEventGroup group;
        const std::size_t instruction_count = reader.read_count("timeline instruction");
        group.instructions.reserve(instruction_count);
        for (std::size_t j = 0; j < instruction_count; ++j) {
            group.instructions.push_back(reader.read_string());
        }
        group.time_ticks = reader.read_float();
        clip.timeline.push_back(std::move(group));
    }

    if (format > 9) {
        const std::size_t sound_count = reader.read_count("animation sound event");
        clip.sound_events.reserve(sound_count);
        for (std::size_t i = 0; i < sound_count; ++i) {
            SoundEvent event;
            event.sound = reader.read_string();
            event.time_ticks = reader.read_float();
            clip.sound_events.push_back(std::move(event));
        }
    }
    return clip;
}

[[nodiscard]] std::string animation_category_name(std::uint32_t id) {
    static constexpr std::array<std::string_view, 14> names{
        "unknown", "main", "arm", "extra", "tac", "arrow", "carryon",
        "parcool", "swem", "slashblade", "tlm", "fp_arm",
        "immersive_melodies", "irons_spell_books"};
    if (id < names.size()) {
        return std::string(names[id]);
    }
    return "unk_" + std::to_string(id);
}

[[nodiscard]] std::vector<Controller> read_controllers(
    Reader& reader, std::uint32_t format, std::uint32_t entry_unknown,
    std::string source_group, std::string hash) {
    std::vector<Controller> result;
    const std::size_t animation_count = reader.read_count("controller");
    result.reserve(animation_count);
    for (std::size_t i = 0; i < animation_count; ++i) {
        Controller controller;
        controller.name = reader.read_string();
        controller.source_group = source_group;
        controller.hash = hash;
        controller.entry_unknown = entry_unknown;
        controller.initial_state = reader.read_string();
        const std::size_t state_count = reader.read_count("controller state");
            controller.states.reserve(state_count);
        for (std::size_t state_index = 0; state_index < state_count; ++state_index) {
            ControllerState state;
            state.name = reader.read_string();
            const std::size_t animations = reader.read_count("controller animation");
            state.animations.reserve(animations);
            for (std::size_t j = 0; j < animations; ++j) {
                state.animations.push_back({reader.read_string(), reader.read_string()});
            }
            const std::size_t transitions = reader.read_count("controller transition");
            state.transitions.reserve(transitions);
            for (std::size_t j = 0; j < transitions; ++j) {
                state.transitions.push_back({reader.read_string(), reader.read_string()});
            }
            const std::size_t entry_count = reader.read_count("controller on-entry");
            state.on_entry.reserve(entry_count);
            for (std::size_t j = 0; j < entry_count; ++j) {
                state.on_entry.push_back(reader.read_string());
            }
            const std::size_t exit_count = reader.read_count("controller on-exit");
            state.on_exit.reserve(exit_count);
            for (std::size_t j = 0; j < exit_count; ++j) {
                state.on_exit.push_back(reader.read_string());
            }
            state.has_fixed_blend_transition = reader.read_varint() != 0;
            if (state.has_fixed_blend_transition) {
                state.fixed_blend_transition = reader.read_float();
            } else {
                const std::size_t blend_count = reader.read_count("blend transition");
                state.blend_transitions.reserve(blend_count);
                for (std::size_t j = 0; j < blend_count; ++j) {
                    state.blend_transitions.push_back({reader.read_float(), reader.read_float()});
                }
            }
            state.blend_via_shortest_path = reader.read_varint() != 0;
            if (format > 26) {
                const std::size_t sound_count = reader.read_count("controller sound effect");
                state.sound_effects.reserve(sound_count);
                for (std::size_t j = 0; j < sound_count; ++j) {
                    state.sound_effects.push_back(reader.read_string());
                }
            }
            controller.states.push_back(std::move(state));
        }
        result.push_back(std::move(controller));
    }
    return result;
}

[[nodiscard]] Image read_image(Reader& reader, std::string name,
                               std::string hash, std::uint32_t kind,
                               bool has_metadata) {
    Image image;
    image.kind = kind;
    image.name = std::move(name);
    image.hash = std::move(hash);
    image.rgba = reader.read_byte_array();
    image.width = reader.read_varint();
    image.height = reader.read_varint();
    if (has_metadata) {
        image.format = reader.read_varint();
        image.flags = reader.read_varint();
    }
    if (image.width == 0 || image.height == 0 ||
        image.width > (kMaxAssetBytes / 4U) / image.height ||
        static_cast<std::size_t>(image.width) * image.height * 4U != image.rgba.size()) {
        throw std::invalid_argument("YSM image dimensions do not match RGBA bytes");
    }
    return image;
}

void read_metadata(Reader& reader, std::uint32_t format, Asset& asset) {
    Metadata& metadata = asset.metadata;
    metadata.payload_sha256 = reader.read_string();
    metadata.source_layout_flag = reader.read_varint();
    metadata.is_new_layout = metadata.source_layout_flag != 0;
    if (!metadata.is_new_layout) {
        return;
    }
    metadata.source_metadata_revision = reader.read_varint();
    metadata.name = reader.read_string();
    metadata.tips = reader.read_string();
    metadata.license_type = reader.read_string();
    metadata.license_description = reader.read_string();

    const std::size_t author_count = reader.read_count("metadata author");
    metadata.authors.reserve(author_count);
    for (std::size_t i = 0; i < author_count; ++i) {
        MetadataAuthor author;
        author.name = reader.read_string();
        author.role = reader.read_string();
        const std::size_t contact_count = reader.read_count("author contact");
        author.contacts.reserve(contact_count);
        for (std::size_t j = 0; j < contact_count; ++j) {
            author.contacts.emplace_back(reader.read_string(), reader.read_string());
        }
        author.comment = reader.read_string();
        metadata.authors.push_back(std::move(author));
    }
    const std::size_t link_count = reader.read_count("metadata link");
    metadata.links.reserve(link_count);
    for (std::size_t i = 0; i < link_count; ++i) {
        metadata.links.emplace_back(reader.read_string(), reader.read_string());
    }

    Properties& properties = metadata.properties;
    properties.width_scale = reader.read_float();
    properties.height_scale = reader.read_float();
    const std::size_t extra_count = reader.read_count("extra animation mapping");
    properties.extra_animations.reserve(extra_count);
    for (std::size_t i = 0; i < extra_count; ++i) {
        properties.extra_animations.emplace_back(reader.read_string(), reader.read_string());
    }
    if (format > 9) {
        const std::size_t button_count = reader.read_count("extra animation button");
        properties.extra_animation_buttons.reserve(button_count);
        for (std::size_t i = 0; i < button_count; ++i) {
            ExtraAnimationButton button;
            button.id = reader.read_string();
            button.name = reader.read_string();
            button.unknown_value = reader.read_varint();
            if (button.unknown_value != 0) {
                throw std::invalid_argument("unsupported YSM extra-animation button flag");
            }
            const std::size_t form_count = reader.read_count("animation config form");
            button.forms.reserve(form_count);
            for (std::size_t j = 0; j < form_count; ++j) {
                ExtraAnimationButton::Form form;
                form.type = reader.read_string();
                form.title = reader.read_string();
                form.description = reader.read_string();
                form.value = reader.read_string();
                form.step = reader.read_float();
                form.minimum = reader.read_float();
                form.maximum = reader.read_float();
                const std::size_t label_count = reader.read_count("config form label");
                form.labels.reserve(label_count);
                for (std::size_t k = 0; k < label_count; ++k) {
                    form.labels.emplace_back(reader.read_string(), reader.read_string());
                }
                button.forms.push_back(std::move(form));
            }
            properties.extra_animation_buttons.push_back(std::move(button));
        }
        const std::size_t class_count = reader.read_count("animation classification");
        properties.animation_classifications.reserve(class_count);
        for (std::size_t i = 0; i < class_count; ++i) {
            AnimationClassification classification;
            classification.id = reader.read_string();
            const std::size_t mapping_count = reader.read_count("classification animation");
            classification.extra_animations.reserve(mapping_count);
            for (std::size_t j = 0; j < mapping_count; ++j) {
                classification.extra_animations.emplace_back(reader.read_string(), reader.read_string());
            }
            properties.animation_classifications.push_back(std::move(classification));
        }
    }
    properties.default_texture = reader.read_string();
    properties.preview_animation = reader.read_string();
    properties.free_flag = reader.read_varint();
    if (format > 4) {
        properties.render_layers_first = reader.read_varint();
    }
    if (format >= 15) {
        properties.all_cutout = reader.read_varint();
        properties.disable_preview_rotation = reader.read_varint();
    }
}

}  // namespace

Asset parse_legacy_payload(std::span<const std::uint8_t> payload) {
    Reader reader(payload);
    Asset asset;
    asset.source_format = reader.read_u32();
    if (asset.source_format < kMinLegacyFormat ||
        asset.source_format > kMaxLegacyFormat) {
        throw std::invalid_argument(
            "unsupported YSM payload format " + std::to_string(asset.source_format) +
            "; native parser currently supports legacy formats 1..15");
    }
    asset.skipped_prefix = reader.read_byte_array();

    std::vector<std::uint32_t> model_ids;
    const std::size_t model_count = reader.read_count("model");
    model_ids.reserve(model_count);
    asset.models.reserve(model_count);
    for (std::size_t i = 0; i < model_count; ++i) {
        const std::uint32_t model_id = reader.read_varint();
        const std::uint32_t entry_unknown = reader.read_varint();
        model_ids.push_back(model_id);
        asset.models.push_back(read_model(reader, asset.source_format, model_id, entry_unknown));
    }

    std::vector<std::uint32_t> animation_ids;
    const std::size_t animation_count = reader.read_count("animation category");
    animation_ids.reserve(animation_count);
    asset.animation_categories.reserve(animation_count);
    for (std::size_t i = 0; i < animation_count; ++i) {
        AnimationCategory category;
        category.source_id = reader.read_varint();
        category.name = animation_category_name(category.source_id);
        category.entry_unknown = reader.read_varint();
        animation_ids.push_back(category.source_id);
        const std::size_t clip_count = reader.read_count("animation clip");
        category.clips.reserve(clip_count);
        for (std::size_t j = 0; j < clip_count; ++j) {
            category.clips.push_back(read_animation(reader, asset.source_format));
        }
        asset.animation_categories.push_back(std::move(category));
    }

    if (asset.source_format > 9) {
        const std::size_t controller_count = reader.read_count("controller entry");
        asset.controllers.reserve(controller_count);
        for (std::size_t i = 0; i < controller_count; ++i) {
            const std::uint32_t entry_unknown = reader.read_varint();
            auto controllers = read_controllers(
                reader, asset.source_format, entry_unknown, "controller", {});
            asset.controllers.insert(asset.controllers.end(),
                                     std::make_move_iterator(controllers.begin()),
                                     std::make_move_iterator(controllers.end()));
        }
        const std::size_t hash_count = reader.read_count("controller hash");
        asset.controller_hashes.reserve(hash_count);
        for (std::size_t i = 0; i < hash_count; ++i) {
            asset.controller_hashes.emplace_back(reader.read_string(), reader.read_string());
        }
    }

    const std::size_t texture_count = reader.read_count("texture");
    asset.textures.reserve(texture_count);
    for (std::size_t i = 0; i < texture_count; ++i) {
        Texture texture;
        texture.name = reader.read_string();
        if (asset.source_format < 4 && reader.read_varint() != 1) {
            throw std::invalid_argument("invalid legacy YSM texture marker");
        }
        const std::string color_hash = asset.source_format > 9 ? reader.read_string() : std::string{};
        texture.hash = color_hash;
        texture.color = read_image(reader, texture.name, color_hash, 0,
                                   asset.source_format > 9);
        if (asset.source_format >= 4) {
            const std::size_t variant_count = reader.read_count("texture variant");
            texture.variants.reserve(variant_count);
            for (std::size_t j = 0; j < variant_count; ++j) {
                const std::uint32_t kind = reader.read_varint();
                const std::string hash = asset.source_format > 9 ? reader.read_string() : std::string{};
                Image variant = read_image(reader, texture.name, hash, kind,
                                           asset.source_format > 9);
                texture.variants.push_back(std::move(variant));
            }
        }
        asset.textures.push_back(std::move(texture));
    }

    if (asset.source_format > 9) {
        const std::size_t sound_count = reader.read_count("sound asset");
        asset.sounds.reserve(sound_count);
        for (std::size_t i = 0; i < sound_count; ++i) {
            SoundAsset sound;
            sound.name = reader.read_string();
            sound.encoded_audio = reader.read_byte_array();
            asset.sounds.push_back(std::move(sound));
        }
        const std::size_t sound_hash_count = reader.read_count("sound hash");
        asset.sound_hashes.reserve(sound_hash_count);
        for (std::size_t i = 0; i < sound_hash_count; ++i) {
            asset.sound_hashes.emplace_back(reader.read_string(), reader.read_string());
        }
    }

    if (asset.source_format >= 4) {
        const std::size_t extra_texture_count = reader.read_count("extra texture");
        asset.extra_textures.reserve(extra_texture_count);
        for (std::size_t i = 0; i < extra_texture_count; ++i) {
            NamedImage image;
            image.name = reader.read_string();
            image.image = read_image(reader, image.name, {}, 3, false);
            asset.extra_textures.push_back(std::move(image));
        }
    }

    const std::size_t model_hash_count = reader.read_count("model hash table");
    asset.model_hashes.reserve(model_hash_count);
    for (std::size_t i = 0; i < model_hash_count; ++i) {
        asset.model_hashes.push_back({reader.read_varint(), reader.read_string()});
    }
    const std::size_t animation_hash_count = reader.read_count("animation hash table");
    asset.animation_hashes.reserve(animation_hash_count);
    for (std::size_t i = 0; i < animation_hash_count; ++i) {
        asset.animation_hashes.push_back({reader.read_varint(), reader.read_string()});
    }
    const std::size_t texture_hash_count = reader.read_count("texture hash table");
    asset.texture_hashes.reserve(texture_hash_count);
    for (std::size_t i = 0; i < texture_hash_count; ++i) {
        TextureHashReference texture_hash;
        texture_hash.name = reader.read_string();
        texture_hash.hash = reader.read_string();
        if (asset.source_format >= 4) {
            const std::size_t variant_count = reader.read_count("texture hash variant");
            texture_hash.variants.reserve(variant_count);
            for (std::size_t j = 0; j < variant_count; ++j) {
                texture_hash.variants.emplace_back(reader.read_varint(), reader.read_string());
            }
        }
        asset.texture_hashes.push_back(std::move(texture_hash));
    }
    if (asset.source_format >= 4) {
        read_metadata(reader, asset.source_format, asset);
    } else {
        asset.metadata.payload_sha256 = reader.read_string();
    }
    if (reader.remaining() != 0) {
        asset.opaque_bytes.push_back({"trailing_payload_bytes", reader.read_remaining()});
    }
    return asset;
}

}  // namespace rigworks::ysm
