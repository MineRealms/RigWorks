/*
 * RigWorks-owned legacy YSM payload model.
 * YSM format observations are based on YesSteveModel and YSMParser source,
 * which are licensed under Apache-2.0. This cleanroom parser does not depend
 * on their binaries or libraries.
 */
#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace rigworks::ysm {

struct OpaqueBytes {
    std::string label;
    std::vector<std::uint8_t> bytes;
};

struct OpaqueValue {
    std::string label;
    std::uint32_t value = 0;
};

struct Vec3 {
    float x = 0;
    float y = 0;
    float z = 0;
};

struct FaceVertex {
    Vec3 position;
    float u = 0;
    float v = 0;
};

struct Face {
    Vec3 normal;
    std::array<FaceVertex, 4> vertices;
};

struct Cube {
    std::vector<Face> faces;
    std::array<std::uint32_t, 3> unknown_values{};
};

struct ModelInfo {
    std::string name;
    std::string tips;
    std::vector<std::string> extra_animation_names;
    std::vector<std::string> authors;
    std::string license;
    std::uint32_t free_flag = 0;
};

struct Bone {
    std::string parent_name;
    std::string name;
    std::vector<Cube> cubes;
    std::array<std::uint32_t, 5> unknown_values{};
    Vec3 pivot;
    Vec3 rotation_radians;
};

struct Model {
    std::uint32_t source_id = 0;
    std::string category;
    std::uint32_t entry_unknown = 0;
    std::string identifier;
    float texture_height = 0;
    float texture_width = 0;
    float visible_bounds_height = 0;
    float visible_bounds_width = 0;
    std::vector<float> visible_bounds_offset;
    float scale_x = 0;
    float scale_y = 0;
    std::vector<Bone> bones;
    std::optional<ModelInfo> info;
    std::uint32_t info_flag = 0;
    std::array<std::uint32_t, 3> trailing_unknown_values{};
};

struct ComponentValue {
    // YSM tag 1 is a float; tag 2 is a source expression string.
    std::uint8_t tag = 1;
    float number = 0;
    std::string expression;
};

struct Value3 {
    std::array<ComponentValue, 3> components;
};

struct Keyframe {
    float time_ticks = 0;
    std::uint32_t interpolation = 0;
    Value3 post;
    std::optional<Value3> pre;
};

struct BoneAnimation {
    std::string bone_name;
    std::array<std::vector<Keyframe>, 3> channels;  // rotation, position, scale
};

struct TimelineEventGroup {
    float time_ticks = 0;
    std::vector<std::string> instructions;
};

struct SoundEvent {
    float time_ticks = 0;
    std::string sound;
};

struct AnimationClip {
    std::string name;
    float duration_ticks = 0;
    std::uint32_t loop_mode = 0;
    std::vector<std::uint32_t> format_flags;
    std::vector<ComponentValue> blend_weight;
    std::vector<BoneAnimation> bones;
    std::vector<TimelineEventGroup> timeline;
    std::vector<SoundEvent> sound_events;
};

struct AnimationCategory {
    std::uint32_t source_id = 0;
    std::string name;
    std::uint32_t entry_unknown = 0;
    std::vector<AnimationClip> clips;
};

struct ControllerAnimation {
    std::string animation;
    std::string condition;
};

struct ControllerTransition {
    std::string condition;
    std::string next_state;
};

struct BlendPoint {
    float key = 0;
    float value = 0;
};

struct ControllerState {
    std::string name;
    std::vector<ControllerAnimation> animations;
    std::vector<ControllerTransition> transitions;
    std::vector<std::string> on_entry;
    std::vector<std::string> on_exit;
    bool has_fixed_blend_transition = false;
    float fixed_blend_transition = 0;
    std::vector<BlendPoint> blend_transitions;
    bool blend_via_shortest_path = false;
    std::vector<std::string> sound_effects;
};

struct Controller {
    std::string name;
    std::string source_group;
    std::string hash;
    std::uint32_t entry_unknown = 0;
    std::string initial_state;
    std::vector<ControllerState> states;
};

struct Image {
    std::uint32_t kind = 0;  // 0=color, 1=normal, 2=specular, other=source kind
    std::string name;
    std::string hash;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t format = 0;
    std::uint32_t flags = 0;
    std::vector<std::uint8_t> rgba;
};

struct Texture {
    std::string name;
    std::string hash;
    Image color;
    std::vector<Image> variants;
};

struct NamedImage {
    std::string name;
    Image image;
};

struct SoundAsset {
    std::string name;
    std::string hash;
    std::vector<std::uint8_t> encoded_audio;
};

struct FunctionAsset {
    std::string name;
    std::string hash;
    std::vector<std::uint8_t> source;
};

struct LanguageEntry {
    std::string key;
    std::string value;
};

struct LanguageAsset {
    std::string name;
    std::string hash;
    std::vector<LanguageEntry> entries;
};

struct HashReference {
    std::uint32_t id = 0;
    std::string name_or_hash;
};

struct TextureHashReference {
    std::string name;
    std::string hash;
    std::vector<std::pair<std::uint32_t, std::string>> variants;
};

struct ExtraAnimationButton {
    std::string id;
    std::string name;
    std::uint32_t unknown_value = 0;
    struct Form {
        std::string type;
        std::string title;
        std::string description;
        std::string value;
        float step = 0;
        float minimum = 0;
        float maximum = 0;
        std::vector<std::pair<std::string, std::string>> labels;
    };
    std::vector<Form> forms;
};

struct AnimationClassification {
    std::string id;
    std::vector<std::pair<std::string, std::string>> extra_animations;
};

struct Properties {
    float width_scale = 1;
    float height_scale = 1;
    std::vector<std::pair<std::string, std::string>> extra_animations;
    std::vector<ExtraAnimationButton> extra_animation_buttons;
    std::vector<AnimationClassification> animation_classifications;
    std::string default_texture;
    std::string preview_animation;
    std::uint32_t free_flag = 0;
    std::uint32_t render_layers_first = 0;
    std::uint32_t all_cutout = 0;
    std::uint32_t disable_preview_rotation = 0;
};

struct MetadataAuthor {
    std::string name;
    std::string role;
    std::vector<std::pair<std::string, std::string>> contacts;
    std::string comment;
};

struct Metadata {
    std::string payload_sha256;
    bool is_new_layout = false;
    std::uint32_t source_layout_flag = 0;
    std::uint32_t source_metadata_revision = 0;
    std::string name;
    std::string tips;
    std::string license_type;
    std::string license_description;
    std::vector<MetadataAuthor> authors;
    std::vector<std::pair<std::string, std::string>> links;
    Properties properties;
};

struct OpaqueSection {
    std::uint32_t type = 0;
    std::uint32_t flags = 0;
    std::vector<std::uint8_t> bytes;
};

struct Asset {
    std::uint32_t source_format = 0;
    std::vector<std::uint8_t> skipped_prefix;
    Metadata metadata;
    std::vector<Model> models;
    std::vector<AnimationCategory> animation_categories;
    std::vector<Controller> controllers;
    std::vector<Texture> textures;
    std::vector<NamedImage> extra_textures;
    std::vector<SoundAsset> sounds;
    std::vector<FunctionAsset> functions;
    std::vector<LanguageAsset> languages;
    std::vector<HashReference> model_hashes;
    std::vector<HashReference> animation_hashes;
    std::vector<TextureHashReference> texture_hashes;
    std::vector<std::pair<std::string, std::string>> controller_hashes;
    std::vector<std::pair<std::string, std::string>> sound_hashes;
    std::vector<OpaqueValue> opaque_values;
    std::vector<OpaqueBytes> opaque_bytes;
    std::vector<OpaqueSection> opaque_sections;
};

// Supports the structurally understood legacy V1.5 payload versions 4..15.
// Modern payload versions are rejected explicitly until their field layouts
// are implemented; no sections are silently discarded.
[[nodiscard]] Asset parse_legacy_payload(std::span<const std::uint8_t> payload);

}  // namespace rigworks::ysm
