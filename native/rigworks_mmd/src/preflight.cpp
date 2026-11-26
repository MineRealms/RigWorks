#include "preflight.hpp"

#include <cstring>
#include <stdexcept>

namespace rwmd {
namespace {
class Cursor {
public:
    Cursor(const void* bytes, size_t length) : data(static_cast<const uint8_t*>(bytes)), size(length) {}
    void skip(size_t count) {
        if (count > size - pos) fail("truncated record");
        pos += count;
    }
    uint8_t u8() { need(1); return data[pos++]; }
    uint16_t u16() { auto a=u8(); return uint16_t(a | uint16_t(u8())<<8); }
    uint32_t u32() { uint32_t n=0; for(int i=0;i<4;i++) n|=uint32_t(u8())<<(8*i); return n; }
    float f32() { auto bits=u32(); float n; std::memcpy(&n,&bits,4); return n; }
    size_t count(uint32_t max, const char* label) { auto n=u32(); if(n>max) fail(label); return n; }
    void text(bool utf16=false) {
        size_t n=count(1024*1024,"string exceeds 1 MiB");
        if(utf16 && n%2) fail("odd UTF-16 byte count");
        skip(n);
    }
    size_t remaining() const { return size-pos; }
    [[noreturn]] void fail(const char* detail) const {
        throw std::runtime_error(std::string("MMD preflight at byte ")+std::to_string(pos)+": "+detail);
    }
private:
    void need(size_t n) { if(n>size-pos) fail("truncated value"); }
    const uint8_t* data;
    size_t size, pos=0;
};

void pmx(Cursor& c) {
    c.skip(4); float version=c.f32();
    if(version!=2.0f && version!=2.1f) c.fail("only PMX 2.0/2.1 are supported");
    if(c.u8()!=8) c.fail("PMX global header must contain eight bytes");
    auto encoding=c.u8(), uv=c.u8();
    if(encoding>1 || uv>4) c.fail("invalid PMX encoding or additional UV count");
    uint8_t widths[6];
    for(auto& w:widths) { w=c.u8(); if(w!=1&&w!=2&&w!=4)c.fail("invalid PMX index width"); }
    auto vi=widths[0], ti=widths[1], mi=widths[2], bi=widths[3], moi=widths[4], ri=widths[5];
    auto text=[&] { c.text(encoding==0); };
    for(int i=0;i<4;i++)text();
    size_t vertices=c.count(2000000,"too many vertices");
    if(!vertices)c.fail("empty mesh");
    for(size_t i=0;i<vertices;i++) {
        c.skip(32+16*size_t(uv));
        switch(c.u8()) {
        case 0:c.skip(bi);break;
        case 1:c.skip(2*bi+4);break;
        case 2:case 4:c.skip(4*bi+16);break;
        case 3:c.skip(2*bi+4+36);break;
        default:c.fail("unknown PMX weight type");
        }
        c.skip(4);
    }
    size_t indices=c.count(12000000,"too many indices");
    if(indices%3)c.fail("non-triangular face list");
    c.skip(indices*vi);
    for(size_t n=c.count(4096,"too many textures");n--;)text();
    for(size_t n=c.count(4096,"too many materials");n--;) {
        text();text();c.skip(66+2*ti);auto mode=c.u8();
        if(mode>1)c.fail("invalid shared-toon flag");
        c.skip(mode?1:ti);text();c.skip(4);
    }
    for(size_t n=c.count(4096,"too many bones");n--;) {
        text();text();c.skip(12+bi+4);auto flags=c.u16();
        c.skip(flags&1?bi:12);
        if(flags&0x300)c.skip(bi+4);
        if(flags&0x400)c.skip(12);
        if(flags&0x800)c.skip(24);
        if(flags&0x2000)c.skip(4);
        if(flags&0x20) {
            c.skip(bi+4+4);
            for(size_t links=c.count(256,"too many IK links");links--;) {
                c.skip(bi);auto limited=c.u8();
                if(limited>1)c.fail("invalid IK limit flag");
                if(limited)c.skip(24);
            }
        }
    }
    for(size_t n=c.count(1024,"too many morphs");n--;) {
        text();text();c.skip(1);auto kind=c.u8();
        size_t entries=c.count(4000000,"too many morph entries"), stride=0;
        switch(kind) {
        case 0:case 9:stride=moi+4;break;
        case 1:stride=vi+12;break;
        case 2:stride=bi+28;break;
        case 3:case 4:case 5:case 6:case 7:stride=vi+16;break;
        case 8:stride=mi+113;break;
        case 10:stride=ri+25;break;
        default:c.fail("unknown PMX morph type");
        }
        c.skip(entries*stride);
    }
    for(size_t n=c.count(4096,"too many display frames");n--;) {
        text();text();c.skip(1);
        for(size_t entries=c.count(65536,"too many display entries");entries--;) {
            auto type=c.u8();if(type>1)c.fail("invalid display entry type");c.skip(type?moi:bi);
        }
    }
    for(size_t n=c.count(2048,"too many rigid bodies");n--;) { text();text();c.skip(bi+61); }
    for(size_t n=c.count(4096,"too many joints");n--;) { text();text();c.skip(1+2*ri+96); }
    if(version==2.1f && c.remaining()) {
        if(c.count(256,"too many soft bodies"))c.fail("PMX soft bodies are not supported by this converter");
    }
    if(c.remaining())c.fail("unexpected trailing model data");
}

void pmd(Cursor& c) {
    c.skip(3);if(c.f32()!=1.0f)c.fail("only PMD 1.0 is supported");c.skip(276);
    size_t vertices=c.count(2000000,"too many vertices");
    if(!vertices)c.fail("empty mesh");c.skip(vertices*38);
    size_t indices=c.count(12000000,"too many indices");
    if(indices%3)c.fail("non-triangular face list");c.skip(indices*2);
    c.skip(c.count(4096,"too many materials")*70);
    size_t bones=c.u16();if(bones>4096)c.fail("too many bones");c.skip(bones*39);
    size_t iks=c.u16();if(iks>4096)c.fail("too many IK solvers");
    for(size_t i=0;i<iks;i++) {c.skip(4);size_t links=c.u8();c.skip(6+links*2);}
    size_t morphs=c.u16();if(morphs>1024)c.fail("too many morphs");
    for(size_t i=0;i<morphs;i++) {c.skip(20);auto count=c.count(2000000,"too many morph vertices");c.skip(1+count*16);}
    c.skip(size_t(c.u8())*2);
    size_t frames=c.u8();c.skip(frames*50);
    c.skip(c.count(65536,"too many display bones")*3);
    if(!c.remaining())return;
    auto english=c.u8();if(english>1)c.fail("invalid PMD English extension");
    if(english)c.skip(276+bones*20+(morphs?morphs-1:0)*20+frames*50);
    if(!c.remaining())return;c.skip(1000);
    if(!c.remaining())return;c.skip(c.count(2048,"too many rigid bodies")*83);
    if(!c.remaining())return;c.skip(c.count(4096,"too many joints")*124);
    if(c.remaining())c.fail("unexpected trailing model data");
}
}

void preflight_model(const void* data, size_t size, bool is_pmx) {
    if(!data || size<8 || size>256u*1024u*1024u)throw std::runtime_error("Model must be between 8 bytes and 256 MiB");
    if(std::memcmp(data,is_pmx?"PMX ":"Pmd",is_pmx?4:3))throw std::runtime_error("Model signature does not match its extension");
    Cursor cursor(data,size);if(is_pmx)pmx(cursor);else pmd(cursor);
}

void preflight_vmd(const std::vector<uint8_t>& data) {
    if(data.size()<54 || data.size()>64u*1024u*1024u || std::memcmp(data.data(),"Vocaloid Motion Data 0002",24))
        throw std::runtime_error("Motion must be a VMD 0002 file smaller than 64 MiB");
    Cursor c(data.data(),data.size());c.skip(50);
    c.skip(c.count(1000000,"too many VMD bone keys")*111);
    c.skip(c.count(1000000,"too many VMD morph keys")*23);
    if(!c.remaining())return;c.skip(c.count(1000000,"too many VMD camera keys")*61);
    if(!c.remaining())return;c.skip(c.count(1000000,"too many VMD light keys")*28);
    if(!c.remaining())return;c.skip(c.count(1000000,"too many VMD shadow keys")*9);
    if(!c.remaining())return;
    for(size_t n=c.count(1000000,"too many VMD IK keys");n--;) {
        c.skip(5);c.skip(c.count(4096,"too many VMD IK switches")*21);
    }
    if(c.remaining())c.fail("unexpected trailing motion data");
}
}
