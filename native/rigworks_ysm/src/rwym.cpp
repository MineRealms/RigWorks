/* RigWorks-owned, platform-independent RWYM v1 container. */
#include "rwym.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>

namespace rigworks::ysm {
namespace {

constexpr std::uint16_t kSchemaVersion = 1;
constexpr std::uint16_t kHeaderBytes = 24;
constexpr std::size_t kDirectoryEntryBytes = 32;
constexpr std::size_t kMaxContainerBytes = 1024U * 1024U * 1024U;
constexpr std::uint32_t kMaxItems = 1'000'000;
constexpr std::uint32_t kMaxStringBytes = 16U * 1024U * 1024U;
constexpr std::uint32_t kMaxSectionCount = 4096;

enum class SectionType : std::uint32_t {
    metadata = 1, models = 2, animations = 3, controllers = 4,
    resources = 5, references = 6, opaque = 7,
};

class Writer {
public:
    void u8(std::uint8_t value) { data_.push_back(value); }
    void u32(std::uint32_t value) {
        for (unsigned i = 0; i < 4; ++i) data_.push_back(static_cast<std::uint8_t>(value >> (i * 8U)));
    }
    void u64(std::uint64_t value) {
        for (unsigned i = 0; i < 8; ++i) data_.push_back(static_cast<std::uint8_t>(value >> (i * 8U)));
    }
    void f32(float value) { u32(std::bit_cast<std::uint32_t>(value)); }
    void raw(std::span<const std::uint8_t> bytes) {
        if (bytes.size() > kMaxContainerBytes - data_.size()) throw std::length_error("RWYM section exceeds 1 GiB");
        data_.insert(data_.end(), bytes.begin(), bytes.end());
    }
    void bytes(std::span<const std::uint8_t> bytes) {
        if (bytes.size() > kMaxContainerBytes ||
            bytes.size() > std::numeric_limits<std::uint32_t>::max()) {
            throw std::length_error("RWYM byte array exceeds limit");
        }
        u32(static_cast<std::uint32_t>(bytes.size()));
        raw(bytes);
    }
    void string(const std::string& value) {
        if (value.size() > kMaxStringBytes) throw std::length_error("RWYM string exceeds 16 MiB");
        count(value.size());
        raw(std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>(value.data()), value.size()));
    }
    void count(std::size_t value) {
        if (value > kMaxItems || value > std::numeric_limits<std::uint32_t>::max())
            throw std::length_error("RWYM collection count " + std::to_string(value) + " exceeds limit");
        u32(static_cast<std::uint32_t>(value));
    }
    [[nodiscard]] const std::vector<std::uint8_t>& data() const noexcept { return data_; }
    [[nodiscard]] std::vector<std::uint8_t> take() && { return std::move(data_); }
private:
    std::vector<std::uint8_t> data_;
};

class Reader {
public:
    explicit Reader(std::span<const std::uint8_t> data) : data_(data) {}
    [[nodiscard]] std::size_t remaining() const noexcept { return data_.size() - pos_; }
    [[nodiscard]] std::span<const std::uint8_t> raw(std::size_t size) {
        require(size); const auto result = data_.subspan(pos_, size); pos_ += size; return result;
    }
    [[nodiscard]] std::uint8_t u8() { return raw(1)[0]; }
    [[nodiscard]] std::uint32_t u32() {
        const auto b = raw(4);
        return static_cast<std::uint32_t>(b[0]) | (static_cast<std::uint32_t>(b[1]) << 8U) |
               (static_cast<std::uint32_t>(b[2]) << 16U) | (static_cast<std::uint32_t>(b[3]) << 24U);
    }
    [[nodiscard]] std::uint64_t u64() {
        const auto b = raw(8); std::uint64_t value = 0;
        for (unsigned i = 0; i < 8; ++i) value |= static_cast<std::uint64_t>(b[i]) << (i * 8U);
        return value;
    }
    [[nodiscard]] float f32() { return std::bit_cast<float>(u32()); }
    [[nodiscard]] std::uint32_t count() {
        const auto value = u32(); if (value > kMaxItems) throw std::length_error("RWYM count exceeds limit"); return value;
    }
    [[nodiscard]] std::vector<std::uint8_t> bytes() {
        const auto size = u32();
        if (size > kMaxContainerBytes || size > remaining()) throw std::invalid_argument("RWYM byte array exceeds section bounds");
        const auto view = raw(size); return {view.begin(), view.end()};
    }
    [[nodiscard]] std::string string() {
        const auto size = u32();
        if (size > kMaxStringBytes || size > remaining()) throw std::invalid_argument("RWYM string exceeds section bounds");
        const auto view = raw(size);
        if (view.empty()) return {};
        return {reinterpret_cast<const char*>(view.data()), view.size()};
    }
    void require_end() const { if (remaining() != 0) throw std::invalid_argument("trailing bytes in RWYM section"); }
private:
    void require(std::size_t size) const { if (size > remaining()) throw std::invalid_argument("truncated RWYM section"); }
    std::span<const std::uint8_t> data_;
    std::size_t pos_ = 0;
};

struct DirectoryEntry { std::uint32_t type, flags; std::uint64_t offset, length; std::uint32_t checksum; };
struct Section { std::uint32_t type, flags; std::vector<std::uint8_t> bytes; };

[[nodiscard]] std::uint32_t crc32(std::span<const std::uint8_t> bytes) noexcept {
    std::uint32_t crc = 0xFFFFFFFFU;
    for (std::uint8_t byte : bytes) {
        crc ^= byte;
        for (unsigned bit = 0; bit < 8; ++bit) crc = (crc >> 1U) ^ (0xEDB88320U & (0U - (crc & 1U)));
    }
    return ~crc;
}

void write_vec3(Writer& w, const Vec3& v) { w.f32(v.x); w.f32(v.y); w.f32(v.z); }
Vec3 read_vec3(Reader& r) { return {r.f32(), r.f32(), r.f32()}; }
void write_pair(Writer& w, const std::pair<std::string, std::string>& p) { w.string(p.first); w.string(p.second); }
std::pair<std::string, std::string> read_pair(Reader& r) { return {r.string(), r.string()}; }

void write_component(Writer& w, const ComponentValue& value) {
    if (value.tag != 1 && value.tag != 2) throw std::invalid_argument("unknown RWYM animation value tag");
    w.u8(value.tag); if (value.tag == 1) w.f32(value.number); else w.string(value.expression);
}
ComponentValue read_component(Reader& r) {
    ComponentValue value; value.tag = r.u8();
    if (value.tag == 1) value.number = r.f32();
    else if (value.tag == 2) value.expression = r.string();
    else throw std::invalid_argument("unknown RWYM animation value tag");
    return value;
}
void write_value3(Writer& w, const Value3& value) { for (const auto& c : value.components) write_component(w, c); }
Value3 read_value3(Reader& r) { Value3 v; for (auto& c : v.components) c = read_component(r); return v; }

void write_image(Writer& w, const Image& image) {
    w.u32(image.kind); w.string(image.name); w.string(image.hash); w.u32(image.width); w.u32(image.height);
    w.u32(image.format); w.u32(image.flags); w.bytes(image.rgba);
}
Image read_image(Reader& r) {
    Image image; image.kind = r.u32(); image.name = r.string(); image.hash = r.string();
    image.width = r.u32(); image.height = r.u32(); image.format = r.u32(); image.flags = r.u32(); image.rgba = r.bytes();
    if ((image.width == 0) != (image.height == 0) || (image.width != 0 && static_cast<std::uint64_t>(image.width) * image.height * 4U != image.rgba.size()))
        throw std::invalid_argument("invalid RGBA dimensions in RWYM");
    return image;
}

template <typename T, typename F>
void write_vector(Writer& w, const std::vector<T>& values, F&& fn) { w.count(values.size()); for (const auto& v : values) fn(v); }
template <typename T, typename F>
std::vector<T> read_vector(Reader& r, F&& fn) {
    const auto count = r.count(); std::vector<T> values; values.reserve(count);
    for (std::uint32_t i = 0; i < count; ++i) values.push_back(fn()); return values;
}

void write_metadata_section(Writer& w, const Asset& asset) {
    w.bytes(asset.skipped_prefix);
    const Metadata& m = asset.metadata;
    w.string(m.payload_sha256); w.u32(m.source_layout_flag); w.u32(m.source_metadata_revision);
    w.string(m.name); w.string(m.tips); w.string(m.license_type); w.string(m.license_description);
    write_vector(w, m.authors, [&](const MetadataAuthor& a) {
        w.string(a.name); w.string(a.role); write_vector(w, a.contacts, [&](const auto& p) { write_pair(w, p); }); w.string(a.comment);
    });
    write_vector(w, m.links, [&](const auto& p) { write_pair(w, p); });
    const Properties& p = m.properties; w.f32(p.width_scale); w.f32(p.height_scale);
    write_vector(w, p.extra_animations, [&](const auto& pair) { write_pair(w, pair); });
    write_vector(w, p.extra_animation_buttons, [&](const ExtraAnimationButton& b) {
        w.string(b.id); w.string(b.name); w.u32(b.unknown_value);
        write_vector(w, b.forms, [&](const ExtraAnimationButton::Form& f) {
            w.string(f.type); w.string(f.title); w.string(f.description); w.string(f.value);
            w.f32(f.step); w.f32(f.minimum); w.f32(f.maximum);
            write_vector(w, f.labels, [&](const auto& pair) { write_pair(w, pair); });
        });
    });
    write_vector(w, p.animation_classifications, [&](const AnimationClassification& item) {
        w.string(item.id); write_vector(w, item.extra_animations, [&](const auto& pair) { write_pair(w, pair); });
    });
    w.string(p.default_texture); w.string(p.preview_animation); w.u32(p.free_flag); w.u32(p.render_layers_first);
    w.u32(p.all_cutout); w.u32(p.disable_preview_rotation);
}

void read_metadata_section(Reader& r, Asset& asset) {
    asset.skipped_prefix = r.bytes(); Metadata& m = asset.metadata;
    m.payload_sha256 = r.string(); m.source_layout_flag = r.u32(); m.is_new_layout = m.source_layout_flag != 0;
    m.source_metadata_revision = r.u32(); m.name = r.string(); m.tips = r.string();
    m.license_type = r.string(); m.license_description = r.string();
    m.authors = read_vector<MetadataAuthor>(r, [&] {
        MetadataAuthor a; a.name = r.string(); a.role = r.string();
        a.contacts = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); });
        a.comment = r.string(); return a;
    });
    m.links = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); });
    Properties& p = m.properties; p.width_scale = r.f32(); p.height_scale = r.f32();
    p.extra_animations = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); });
    p.extra_animation_buttons = read_vector<ExtraAnimationButton>(r, [&] {
        ExtraAnimationButton b; b.id = r.string(); b.name = r.string(); b.unknown_value = r.u32();
        b.forms = read_vector<ExtraAnimationButton::Form>(r, [&] {
            ExtraAnimationButton::Form f; f.type = r.string(); f.title = r.string(); f.description = r.string(); f.value = r.string();
            f.step = r.f32(); f.minimum = r.f32(); f.maximum = r.f32();
            f.labels = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); }); return f;
        }); return b;
    });
    p.animation_classifications = read_vector<AnimationClassification>(r, [&] {
        AnimationClassification i; i.id = r.string();
        i.extra_animations = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); }); return i;
    });
    p.default_texture = r.string(); p.preview_animation = r.string(); p.free_flag = r.u32();
    p.render_layers_first = r.u32(); p.all_cutout = r.u32(); p.disable_preview_rotation = r.u32();
}

void write_models_section(Writer& w, const Asset& asset) {
    write_vector(w, asset.models, [&](const Model& m) {
        w.u32(m.source_id); w.string(m.category); w.u32(m.entry_unknown); w.string(m.identifier);
        w.f32(m.texture_height); w.f32(m.texture_width); w.f32(m.visible_bounds_height); w.f32(m.visible_bounds_width);
        write_vector(w, m.visible_bounds_offset, [&](float f) { w.f32(f); }); w.f32(m.scale_x); w.f32(m.scale_y);
        write_vector(w, m.bones, [&](const Bone& b) {
            w.string(b.parent_name); w.string(b.name);
            write_vector(w, b.cubes, [&](const Cube& c) {
                write_vector(w, c.faces, [&](const Face& f) {
                    write_vec3(w, f.normal); for (const auto& v : f.vertices) { write_vec3(w, v.position); w.f32(v.u); w.f32(v.v); }
                }); for (auto u : c.unknown_values) w.u32(u);
            }); for (auto u : b.unknown_values) w.u32(u); write_vec3(w, b.pivot); write_vec3(w, b.rotation_radians);
        });
        w.u32(m.info_flag); w.u8(m.info.has_value());
        if (m.info) {
            const auto& i = *m.info; w.string(i.name); w.string(i.tips);
            write_vector(w, i.extra_animation_names, [&](const std::string& s) { w.string(s); });
            write_vector(w, i.authors, [&](const std::string& s) { w.string(s); }); w.string(i.license); w.u32(i.free_flag);
        }
        for (auto u : m.trailing_unknown_values) w.u32(u);
    });
}

void read_models_section(Reader& r, Asset& asset) {
    asset.models = read_vector<Model>(r, [&] {
        Model m; m.source_id = r.u32(); m.category = r.string(); m.entry_unknown = r.u32(); m.identifier = r.string();
        m.texture_height = r.f32(); m.texture_width = r.f32(); m.visible_bounds_height = r.f32(); m.visible_bounds_width = r.f32();
        m.visible_bounds_offset = read_vector<float>(r, [&] { return r.f32(); }); m.scale_x = r.f32(); m.scale_y = r.f32();
        m.bones = read_vector<Bone>(r, [&] {
            Bone b; b.parent_name = r.string(); b.name = r.string();
            b.cubes = read_vector<Cube>(r, [&] {
                Cube c; c.faces = read_vector<Face>(r, [&] {
                    Face f; f.normal = read_vec3(r);
                    for (auto& v : f.vertices) { v.position = read_vec3(r); v.u = r.f32(); v.v = r.f32(); } return f;
                }); for (auto& u : c.unknown_values) u = r.u32(); return c;
            }); for (auto& u : b.unknown_values) u = r.u32(); b.pivot = read_vec3(r); b.rotation_radians = read_vec3(r); return b;
        });
        m.info_flag = r.u32(); const auto marker = r.u8(); if (marker > 1) throw std::invalid_argument("invalid RWYM model info marker");
        if (marker) {
            ModelInfo i; i.name = r.string(); i.tips = r.string();
            i.extra_animation_names = read_vector<std::string>(r, [&] { return r.string(); });
            i.authors = read_vector<std::string>(r, [&] { return r.string(); }); i.license = r.string(); i.free_flag = r.u32(); m.info = std::move(i);
        }
        for (auto& u : m.trailing_unknown_values) u = r.u32(); return m;
    });
}

void write_animations_section(Writer& w, const Asset& asset) {
    write_vector(w, asset.animation_categories, [&](const AnimationCategory& c) {
        w.u32(c.source_id); w.string(c.name); w.u32(c.entry_unknown);
        write_vector(w, c.clips, [&](const AnimationClip& a) {
            w.string(a.name); w.f32(a.duration_ticks); w.u32(a.loop_mode);
            write_vector(w, a.format_flags, [&](std::uint32_t v) { w.u32(v); });
            write_vector(w, a.blend_weight, [&](const ComponentValue& v) { write_component(w, v); });
            write_vector(w, a.bones, [&](const BoneAnimation& b) {
                w.string(b.bone_name);
                for (const auto& channel : b.channels) write_vector(w, channel, [&](const Keyframe& k) {
                    w.f32(k.time_ticks); w.u32(k.interpolation); w.u8(k.pre.has_value());
                    if (k.pre) write_value3(w, *k.pre); write_value3(w, k.post);
                });
            });
            write_vector(w, a.timeline, [&](const TimelineEventGroup& e) {
                w.f32(e.time_ticks); write_vector(w, e.instructions, [&](const std::string& s) { w.string(s); });
            });
            write_vector(w, a.sound_events, [&](const SoundEvent& e) { w.f32(e.time_ticks); w.string(e.sound); });
        });
    });
}

void read_animations_section(Reader& r, Asset& asset) {
    asset.animation_categories = read_vector<AnimationCategory>(r, [&] {
        AnimationCategory c; c.source_id = r.u32(); c.name = r.string(); c.entry_unknown = r.u32();
        c.clips = read_vector<AnimationClip>(r, [&] {
            AnimationClip a; a.name = r.string(); a.duration_ticks = r.f32(); a.loop_mode = r.u32();
            a.format_flags = read_vector<std::uint32_t>(r, [&] { return r.u32(); });
            a.blend_weight = read_vector<ComponentValue>(r, [&] { return read_component(r); });
            a.bones = read_vector<BoneAnimation>(r, [&] {
                BoneAnimation b; b.bone_name = r.string();
                for (auto& channel : b.channels) channel = read_vector<Keyframe>(r, [&] {
                    Keyframe k; k.time_ticks = r.f32(); k.interpolation = r.u32(); const auto has_pre = r.u8();
                    if (has_pre > 1) throw std::invalid_argument("invalid RWYM pre marker"); if (has_pre) k.pre = read_value3(r);
                    k.post = read_value3(r); return k;
                }); return b;
            });
            a.timeline = read_vector<TimelineEventGroup>(r, [&] {
                TimelineEventGroup e; e.time_ticks = r.f32(); e.instructions = read_vector<std::string>(r, [&] { return r.string(); }); return e;
            });
            a.sound_events = read_vector<SoundEvent>(r, [&] { SoundEvent e; e.time_ticks = r.f32(); e.sound = r.string(); return e; });
            return a;
        }); return c;
    });
}

void write_controllers_section(Writer& w, const Asset& asset) {
    write_vector(w, asset.controllers, [&](const Controller& c) {
        w.string(c.name); w.string(c.source_group); w.string(c.hash); w.u32(c.entry_unknown); w.string(c.initial_state);
        write_vector(w, c.states, [&](const ControllerState& s) {
            w.string(s.name);
            write_vector(w, s.animations, [&](const ControllerAnimation& a) { w.string(a.animation); w.string(a.condition); });
            write_vector(w, s.transitions, [&](const ControllerTransition& t) { w.string(t.condition); w.string(t.next_state); });
            write_vector(w, s.on_entry, [&](const std::string& x) { w.string(x); });
            write_vector(w, s.on_exit, [&](const std::string& x) { w.string(x); });
            w.u8(s.has_fixed_blend_transition); w.f32(s.fixed_blend_transition);
            write_vector(w, s.blend_transitions, [&](const BlendPoint& b) { w.f32(b.key); w.f32(b.value); });
            w.u8(s.blend_via_shortest_path); write_vector(w, s.sound_effects, [&](const std::string& x) { w.string(x); });
        });
    });
}

void read_controllers_section(Reader& r, Asset& asset) {
    asset.controllers = read_vector<Controller>(r, [&] {
        Controller c; c.name = r.string(); c.source_group = r.string(); c.hash = r.string(); c.entry_unknown = r.u32(); c.initial_state = r.string();
        c.states = read_vector<ControllerState>(r, [&] {
            ControllerState s; s.name = r.string();
            s.animations = read_vector<ControllerAnimation>(r, [&] { return ControllerAnimation{r.string(), r.string()}; });
            s.transitions = read_vector<ControllerTransition>(r, [&] { return ControllerTransition{r.string(), r.string()}; });
            s.on_entry = read_vector<std::string>(r, [&] { return r.string(); }); s.on_exit = read_vector<std::string>(r, [&] { return r.string(); });
            const auto fixed = r.u8(); if (fixed > 1) throw std::invalid_argument("invalid RWYM blend marker");
            s.has_fixed_blend_transition = fixed != 0; s.fixed_blend_transition = r.f32();
            s.blend_transitions = read_vector<BlendPoint>(r, [&] { return BlendPoint{r.f32(), r.f32()}; });
            const auto shortest = r.u8(); if (shortest > 1) throw std::invalid_argument("invalid RWYM shortest marker");
            s.blend_via_shortest_path = shortest != 0; s.sound_effects = read_vector<std::string>(r, [&] { return r.string(); }); return s;
        }); return c;
    });
}

void write_resources_section(Writer& w, const Asset& asset) {
    write_vector(w, asset.textures, [&](const Texture& t) {
        w.string(t.name); w.string(t.hash); write_image(w, t.color); write_vector(w, t.variants, [&](const Image& i) { write_image(w, i); });
    });
    write_vector(w, asset.extra_textures, [&](const NamedImage& i) { w.string(i.name); write_image(w, i.image); });
    write_vector(w, asset.sounds, [&](const SoundAsset& s) { w.string(s.name); w.string(s.hash); w.bytes(s.encoded_audio); });
    write_vector(w, asset.functions, [&](const FunctionAsset& f) { w.string(f.name); w.string(f.hash); w.bytes(f.source); });
    write_vector(w, asset.languages, [&](const LanguageAsset& l) {
        w.string(l.name); w.string(l.hash); write_vector(w, l.entries, [&](const LanguageEntry& e) { w.string(e.key); w.string(e.value); });
    });
}

void read_resources_section(Reader& r, Asset& asset) {
    asset.textures = read_vector<Texture>(r, [&] {
        Texture t; t.name = r.string(); t.hash = r.string(); t.color = read_image(r); t.variants = read_vector<Image>(r, [&] { return read_image(r); }); return t;
    });
    asset.extra_textures = read_vector<NamedImage>(r, [&] { return NamedImage{r.string(), read_image(r)}; });
    asset.sounds = read_vector<SoundAsset>(r, [&] { SoundAsset s; s.name = r.string(); s.hash = r.string(); s.encoded_audio = r.bytes(); return s; });
    asset.functions = read_vector<FunctionAsset>(r, [&] { FunctionAsset f; f.name = r.string(); f.hash = r.string(); f.source = r.bytes(); return f; });
    asset.languages = read_vector<LanguageAsset>(r, [&] {
        LanguageAsset l; l.name = r.string(); l.hash = r.string(); l.entries = read_vector<LanguageEntry>(r, [&] { return LanguageEntry{r.string(), r.string()}; }); return l;
    });
}

void write_references_section(Writer& w, const Asset& asset) {
    write_vector(w, asset.model_hashes, [&](const HashReference& h) { w.u32(h.id); w.string(h.name_or_hash); });
    write_vector(w, asset.animation_hashes, [&](const HashReference& h) { w.u32(h.id); w.string(h.name_or_hash); });
    write_vector(w, asset.texture_hashes, [&](const TextureHashReference& h) {
        w.string(h.name); w.string(h.hash); write_vector(w, h.variants, [&](const auto& v) { w.u32(v.first); w.string(v.second); });
    });
    write_vector(w, asset.controller_hashes, [&](const auto& h) { write_pair(w, h); });
    write_vector(w, asset.sound_hashes, [&](const auto& h) { write_pair(w, h); });
}

void read_references_section(Reader& r, Asset& asset) {
    asset.model_hashes = read_vector<HashReference>(r, [&] { return HashReference{r.u32(), r.string()}; });
    asset.animation_hashes = read_vector<HashReference>(r, [&] { return HashReference{r.u32(), r.string()}; });
    asset.texture_hashes = read_vector<TextureHashReference>(r, [&] {
        TextureHashReference h; h.name = r.string(); h.hash = r.string();
        h.variants = read_vector<std::pair<std::uint32_t, std::string>>(r, [&] { return std::pair{r.u32(), r.string()}; }); return h;
    });
    asset.controller_hashes = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); });
    asset.sound_hashes = read_vector<std::pair<std::string, std::string>>(r, [&] { return read_pair(r); });
}

void write_opaque_section(Writer& w, const Asset& asset) {
    write_vector(w, asset.opaque_values, [&](const OpaqueValue& v) { w.string(v.label); w.u32(v.value); });
    write_vector(w, asset.opaque_bytes, [&](const OpaqueBytes& v) { w.string(v.label); w.bytes(v.bytes); });
}
void read_opaque_section(Reader& r, Asset& asset) {
    asset.opaque_values = read_vector<OpaqueValue>(r, [&] { return OpaqueValue{r.string(), r.u32()}; });
    asset.opaque_bytes = read_vector<OpaqueBytes>(r, [&] { return OpaqueBytes{r.string(), r.bytes()}; });
}

void write_directory_entry(Writer& w, const DirectoryEntry& e) {
    w.u32(e.type); w.u32(e.flags); w.u64(e.offset); w.u64(e.length); w.u32(e.checksum); w.u32(0);
}
bool known_type(std::uint32_t type) noexcept { return type >= 1 && type <= 7; }

}  // namespace

std::vector<std::uint8_t> write_rwym_v1(const Asset& asset) {
    std::vector<Section> sections;
    sections.reserve(7 + asset.opaque_sections.size());
    auto add = [&](SectionType type, auto&& write) {
        Writer section; write(section); sections.push_back({static_cast<std::uint32_t>(type), 0, std::move(section).take()});
    };
    add(SectionType::metadata, [&](Writer& w) { write_metadata_section(w, asset); });
    add(SectionType::models, [&](Writer& w) { write_models_section(w, asset); });
    add(SectionType::animations, [&](Writer& w) { write_animations_section(w, asset); });
    add(SectionType::controllers, [&](Writer& w) { write_controllers_section(w, asset); });
    add(SectionType::resources, [&](Writer& w) { write_resources_section(w, asset); });
    add(SectionType::references, [&](Writer& w) { write_references_section(w, asset); });
    add(SectionType::opaque, [&](Writer& w) { write_opaque_section(w, asset); });
    for (const auto& opaque : asset.opaque_sections) {
        if (known_type(opaque.type)) throw std::invalid_argument("opaque section uses a reserved RWYM type");
        sections.push_back({opaque.type, opaque.flags, opaque.bytes});
    }
    if (sections.size() > kMaxSectionCount) throw std::length_error("too many RWYM sections");
    const std::uint64_t directory_offset = kHeaderBytes;
    const std::uint64_t directory_bytes = sections.size() * kDirectoryEntryBytes;
    std::uint64_t next = directory_offset + directory_bytes;
    std::vector<DirectoryEntry> entries; entries.reserve(sections.size());
    for (const auto& section : sections) {
        if (next > kMaxContainerBytes - section.bytes.size()) throw std::length_error("RWYM asset exceeds 1 GiB");
        entries.push_back({section.type, section.flags, next, section.bytes.size(), crc32(section.bytes)});
        next += section.bytes.size();
    }
    Writer out;
    out.raw(std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>("RWYM"), 4));
    out.u32(static_cast<std::uint32_t>(kSchemaVersion) | (static_cast<std::uint32_t>(kHeaderBytes) << 16U));
    out.u32(asset.source_format); out.u32(static_cast<std::uint32_t>(sections.size())); out.u64(directory_offset);
    for (const auto& entry : entries) write_directory_entry(out, entry);
    for (const auto& section : sections) out.raw(section.bytes);
    return std::move(out).take();
}

Asset read_rwym_v1(std::span<const std::uint8_t> bytes) {
    if (bytes.size() < kHeaderBytes || bytes.size() > kMaxContainerBytes) throw std::invalid_argument("RWYM size invalid");
    Reader header(bytes); const auto magic = header.raw(4);
    if (magic[0] != 'R' || magic[1] != 'W' || magic[2] != 'Y' || magic[3] != 'M') throw std::invalid_argument("RWYM magic mismatch");
    const auto version_size = header.u32();
    const auto version = static_cast<std::uint16_t>(version_size & 0xFFFFU);
    const auto header_size = static_cast<std::uint16_t>(version_size >> 16U);
    if (version != kSchemaVersion || header_size != kHeaderBytes) throw std::invalid_argument("unsupported RWYM version");
    Asset asset; asset.source_format = header.u32(); const auto count = header.u32(); const auto directory_offset = header.u64();
    if (count > kMaxSectionCount || directory_offset != kHeaderBytes) throw std::invalid_argument("invalid RWYM directory");
    const std::uint64_t directory_end = directory_offset + static_cast<std::uint64_t>(count) * kDirectoryEntryBytes;
    if (directory_end > bytes.size()) throw std::invalid_argument("truncated RWYM directory");
    std::vector<DirectoryEntry> entries; entries.reserve(count);
    std::vector<std::pair<std::uint64_t, std::uint64_t>> ranges;
    std::array<bool, 8> seen{};
    for (std::uint32_t i = 0; i < count; ++i) {
        DirectoryEntry e{header.u32(), header.u32(), header.u64(), header.u64(), header.u32()};
        const auto reserved = header.u32();
        if (reserved != 0 || e.offset < directory_end || e.offset > bytes.size() || e.length > bytes.size() - e.offset)
            throw std::invalid_argument("invalid RWYM section range");
        if (known_type(e.type)) { if (seen[e.type]) throw std::invalid_argument("duplicate RWYM section"); seen[e.type] = true; }
        entries.push_back(e); ranges.emplace_back(e.offset, e.offset + e.length);
    }
    std::sort(ranges.begin(), ranges.end());
    for (std::size_t i = 1; i < ranges.size(); ++i) if (ranges[i].first < ranges[i - 1].second) throw std::invalid_argument("overlapping RWYM sections");
    std::array<bool, 8> parsed{};
    for (const auto& e : entries) {
        const auto section = bytes.subspan(static_cast<std::size_t>(e.offset), static_cast<std::size_t>(e.length));
        if (crc32(section) != e.checksum) throw std::invalid_argument("RWYM checksum mismatch");
        if (!known_type(e.type)) { asset.opaque_sections.push_back({e.type, e.flags, {section.begin(), section.end()}}); continue; }
        Reader r(section);
        switch (static_cast<SectionType>(e.type)) {
        case SectionType::metadata: read_metadata_section(r, asset); break;
        case SectionType::models: read_models_section(r, asset); break;
        case SectionType::animations: read_animations_section(r, asset); break;
        case SectionType::controllers: read_controllers_section(r, asset); break;
        case SectionType::resources: read_resources_section(r, asset); break;
        case SectionType::references: read_references_section(r, asset); break;
        case SectionType::opaque: read_opaque_section(r, asset); break;
        }
        r.require_end(); parsed[e.type] = true;
    }
    for (std::uint32_t type = 1; type <= 7; ++type) if (!parsed[type]) throw std::invalid_argument("RWYM missing required section");
    return asset;
}

}  // namespace rigworks::ysm
