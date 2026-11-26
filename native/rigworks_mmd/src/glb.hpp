#pragma once

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <filesystem>
#include <stdexcept>
#include <string>
#include <vector>

namespace rwmd {
using Json=nlohmann::json;

class Glb {
public:
    Json root={
        {"asset",{{"version","2.0"},{"generator","RigWorks MMD import ABI 1"}}},
        {"scene",0}, {"scenes",Json::array()}, {"nodes",Json::array()},
        {"meshes",Json::array()}, {"materials",Json::array()},
        {"skins",Json::array()}, {"animations",Json::array()},
        {"bufferViews",Json::array()}, {"accessors",Json::array()}
    };

    template<typename T>
    size_t view(const std::vector<T>& values, int target=0) {
        static_assert(std::is_trivially_copyable<T>::value,"binary GLB input required");
        align();size_t start=bin.size(), bytes=values.size()*sizeof(T);
        if(bytes>limit-bin.size())throw std::runtime_error("Converted GLB exceeds 256 MiB budget");
        bin.resize(start+bytes);
        if(bytes)std::memcpy(bin.data()+start,values.data(),bytes);
        Json data={{"buffer",0},{"byteOffset",start},{"byteLength",bytes}};
        if(target)data["target"]=target;
        size_t index=root["bufferViews"].size();root["bufferViews"].push_back(data);return index;
    }

    template<typename T>
    size_t accessor(const std::vector<T>& values, int componentType,const char* type,size_t components,int target=0,bool bounds=false) {
        if(values.empty() || values.size()%components)throw std::runtime_error("Empty or invalid GLB accessor");
        Json data={{"bufferView",view(values,target)},{"componentType",componentType},{"count",values.size()/components},{"type",type}};
        if(bounds) {
            Json lo=Json::array(), hi=Json::array();
            for(size_t c=0;c<components;c++) {
                T min=values[c],max=values[c];
                for(size_t i=c;i<values.size();i+=components){min=std::min(min,values[i]);max=std::max(max,values[i]);}
                lo.push_back(min);hi.push_back(max);
            }
            data["min"]=lo;data["max"]=hi;
        }
        size_t index=root["accessors"].size();root["accessors"].push_back(data);return index;
    }

    size_t sparse_position(size_t vertices,const std::vector<uint32_t>& indices,const std::vector<float>& values) {
        Json a={{"componentType",5126},{"count",vertices},{"type","VEC3"}};
        if(!indices.empty()) {
            a["sparse"]={{"count",indices.size()},
                {"indices",{{"bufferView",view(indices)},{"componentType",5125}}},
                {"values",{{"bufferView",view(values)}}}};
        } else {
            // Assimp requires data even for an all-zero target on this material primitive.
            a["sparse"]={{"count",1},
                {"indices",{{"bufferView",view(std::vector<uint32_t>{0})},{"componentType",5125}}},
                {"values",{{"bufferView",view(std::vector<float>{0,0,0})}}}};
        }
        size_t index=root["accessors"].size();root["accessors"].push_back(a);return index;
    }

    void write(const std::filesystem::path& file) {
        align();root["buffers"]=Json::array({{{"byteLength",bin.size()}}});
        std::string text=root.dump();while(text.size()%4)text.push_back(' ');
        if(text.size()+bin.size()+28>limit)throw std::runtime_error("Converted GLB exceeds 256 MiB budget");
        std::ofstream out(file,std::ios::binary|std::ios::trunc);
        if(!out)throw std::runtime_error("Cannot create converted model.glb");
        auto u32=[&](uint32_t value){char b[4];for(int i=0;i<4;i++)b[i]=char(value>>(i*8));out.write(b,4);};
        u32(0x46546c67);u32(2);u32(uint32_t(28+text.size()+bin.size()));
        u32(uint32_t(text.size()));u32(0x4e4f534a);out.write(text.data(),std::streamsize(text.size()));
        u32(uint32_t(bin.size()));u32(0x004e4942);out.write(reinterpret_cast<const char*>(bin.data()),std::streamsize(bin.size()));
        out.close();if(!out)throw std::runtime_error("Writing converted model.glb failed");
    }
private:
    void align(){while(bin.size()%4)bin.push_back(0);}
    static constexpr size_t limit=256u*1024u*1024u;
    std::vector<uint8_t> bin;
};
}
