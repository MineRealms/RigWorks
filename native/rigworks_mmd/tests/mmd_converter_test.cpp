#include "rigworks_mmd.h"

#include <nlohmann/json.hpp>
#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/quaternion.hpp>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>

namespace fs=std::filesystem;
using Json=nlohmann::json;

struct Bytes {
    std::vector<uint8_t> data;
    void u8(uint8_t v){data.push_back(v);}
    void u16(uint16_t v){for(int i=0;i<2;i++)u8(uint8_t(v>>(8*i)));}
    void u32(uint32_t v){for(int i=0;i<4;i++)u8(uint8_t(v>>(8*i)));}
    void f(float v){uint32_t n;std::memcpy(&n,&v,4);u32(n);}
    void zeros(size_t n){data.resize(data.size()+n);}
    void fixed(const std::string& s,size_t n){for(size_t i=0;i<n;i++)u8(i<s.size()?uint8_t(s[i]):0);}
    void text(const std::string& s){u32(uint32_t(s.size()));for(auto c:s)u8(uint8_t(c));}
    void v(float x,float y,float z){f(x);f(y);f(z);}
    void q(float x=0,float y=0,float z=0,float w=1){v(x,y,z);f(w);}
};

void check(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
void save(const fs::path& p,const std::vector<uint8_t>& b){std::ofstream o(p,std::ios::binary);o.write(reinterpret_cast<const char*>(b.data()),std::streamsize(b.size()));check(bool(o),"fixture write failed");}
Json json_file(const fs::path& p){std::ifstream in(p);Json j;in>>j;return j;}

Bytes pmx(bool rigTest=false) {
    Bytes b;b.fixed("PMX ",4);b.f(2);b.u8(8);b.u8(1);b.u8(0);for(int i=0;i<6;i++)b.u8(1);
    b.text("RigWorks authored fixture");b.text("");b.text("");b.text("");
    b.u32(3);
    for(int i=0;i<3;i++) {
        b.v(i==1?1.f:0,i==2?2.f:0,0);b.v(0,0,-1);b.f(i==1?1.f:0);b.f(i==2?0.f:1.f);
        b.u8(1);b.u8(0);b.u8(1);b.f(.25f);b.f(1);
    }
    b.u32(3);b.u8(0);b.u8(1);b.u8(2);b.u32(0);b.u32(1);
    b.text("cloth");b.text("");b.v(1,1,1);b.f(1);b.v(0,0,0);b.f(0);b.v(0,0,0);b.u8(1);b.q(0,0,0,1);b.f(0);
    b.u8(255);b.u8(255);b.u8(0);b.u8(1);b.u8(0);b.text("");b.u32(3);
    b.u32(rigTest?5:2);
    for(int i=0;i<2;i++) {
        b.text(i?"tip":"root");b.text("");b.v(0,i?1.f:0,0);b.u8(i?0:255);b.u32(0);b.u16(0x1e);b.v(0,1,0);
    }
    if(rigTest) {
        b.text("ik");b.text("");b.v(0,1,0);b.u8(255);b.u32(0);b.u16(0x3e);b.v(0,1,0);
        b.u8(1);b.u32(32);b.f(.5f);b.u32(1);b.u8(0);b.u8(0);
        b.text("grant");b.text("");b.v(3,0,0);b.u8(255);b.u32(1);b.u16(0x31e);b.v(0,1,0);b.u8(4);b.f(.5f);
        b.text("driver");b.text("");b.v(2,0,0);b.u8(255);b.u32(0);b.u16(0x1e);b.v(0,1,0);
    }
    b.u32(1);b.text("blink");b.text("");b.u8(2);b.u8(1);b.u32(1);b.u8(2);b.v(0,.2f,0);
    b.u32(0);b.u32(rigTest?2:0);
    if(rigTest)for(int i=0;i<2;i++) {
        b.text(i?"tip_body":"root_body");b.text("");b.u8(uint8_t(i));b.u8(uint8_t(i));b.u16(65535);b.u8(0);b.v(.1f,0,0);
        b.v(i?.1f:0,i?1.f:0,i?.1f:0);b.v(i?.1f:0,i?.2f:0,i?.3f:0);
        b.f(i?1.f:0);b.f(.05f);b.f(.1f);b.f(0);b.f(.5f);b.u8(uint8_t(i));
    }
    b.u32(rigTest?1:0);
    if(rigTest) {
        b.text("joint");b.text("");b.u8(0);b.u8(0);b.u8(1);b.v(.2f,.5f,.3f);b.v(.1f,.2f,.3f);
        b.v(0,0,0);b.v(0,0,0);b.v(0,0,0);b.v(0,0,0);b.v(1,2,3);b.v(1,2,3);
    }
    return b;
}

Bytes pmd() {
    Bytes b;b.fixed("Pmd",3);b.f(1);b.fixed("RigWorks fixture",20);b.zeros(256);b.u32(3);
    for(int i=0;i<3;i++) {
        b.v(i==1?1.f:0,i==2?2.f:0,0);b.v(0,0,-1);b.f(i==1?1.f:0);b.f(i==2?0.f:1.f);b.u16(0);b.u16(1);b.u8(25);b.u8(0);
    }
    b.u32(3);b.u16(0);b.u16(1);b.u16(2);b.u32(1);
    b.v(1,1,1);b.f(1);b.f(0);b.v(0,0,0);b.v(0,0,0);b.u8(255);b.u8(0);b.u32(3);b.zeros(20);
    b.u16(2);
    for(int i=0;i<2;i++){b.fixed(i?"tip":"root",20);b.u16(i?0:65535);b.u16(65535);b.u8(0);b.u16(0);b.v(0,i?1.f:0,0);}
    b.u16(0);b.u16(2);
    b.fixed("base",20);b.u32(1);b.u8(0);b.u32(2);b.v(0,2,0);
    b.fixed("blink",20);b.u32(1);b.u8(2);b.u32(0);b.v(0,.2f,0);
    b.u8(0);b.u8(0);b.u32(0);return b;
}

Bytes vmd() {
    Bytes b;b.fixed("Vocaloid Motion Data 0002",30);b.fixed("RigWorks fixture",20);b.u32(2);
    for(int key=0;key<2;key++) {
        b.fixed("tip",15);b.u32(key?30:0);b.v(key?1.f:0,0,0);b.q();
        uint8_t controls[64]{};
        for(int axis=0;axis<4;axis++){controls[axis]=127;controls[4+axis]=0;controls[8+axis]=127;controls[12+axis]=0;}
        if(key==0)for(int axis=0;axis<4;axis++){controls[axis]=20;controls[4+axis]=20;controls[8+axis]=107;controls[12+axis]=107;}
        for(auto c:controls)b.u8(c);
    }
    b.u32(2);
    for(int key=0;key<2;key++){b.fixed("blink",15);b.u32(key?30:0);b.f(float(key));}
    b.u32(0);b.u32(0);b.u32(0);b.u32(0);return b;
}

Bytes rig_motion(bool switches=false) {
    Bytes b;b.fixed("Vocaloid Motion Data 0002",30);b.fixed("RigWorks fixture",20);b.u32(4);
    for(auto name:{"ik","driver"})for(int key=0;key<2;key++) {
        b.fixed(name,15);b.u32(key?30:0);
        if(std::strcmp(name,"ik")==0)b.v(key?.5f:0,key?-.1339746f:0,0);
        else b.v(key?1.f:0,0,0);
        if(key&&std::strcmp(name,"driver")==0)b.q(0,0,.70710678f,.70710678f);else b.q();
        uint8_t controls[64]{};for(int axis=0;axis<4;axis++){controls[axis]=20;controls[4+axis]=20;controls[8+axis]=107;controls[12+axis]=107;}
        for(auto c:controls)b.u8(c);
    }
    b.u32(0);b.u32(0);b.u32(0);b.u32(0);b.u32(switches?3:0);
    if(switches)for(int frame:{0,15,30}){b.u32(uint32_t(frame));b.u8(1);b.u32(1);b.fixed("ik",20);b.u8(frame==15?0:1);}
    return b;
}

struct Glb {
    Json doc;std::vector<uint8_t> bytes;size_t binary=0;
    explicit Glb(const fs::path& p) {
        std::ifstream in(p,std::ios::binary);bytes.assign(std::istreambuf_iterator<char>(in),{});
        check(bytes.size()>28,"missing GLB");check(u32(0)==0x46546c67&&u32(4)==2&&u32(8)==bytes.size(),"invalid GLB header");
        auto length=u32(12);doc=Json::parse(bytes.begin()+20,bytes.begin()+20+length);binary=28+length;
    }
    uint32_t u32(size_t p)const {check(p+4<=bytes.size(),"GLB read out of bounds");uint32_t n=0;for(int i=0;i<4;i++)n|=uint32_t(bytes[p+i])<<(8*i);return n;}
    float number(size_t accessor,size_t item)const {
        auto a=doc["accessors"][accessor];auto v=doc["bufferViews"][a["bufferView"].get<size_t>()];
        size_t offset=binary+v.value("byteOffset",size_t(0))+a.value("byteOffset",size_t(0))+item*4;
        uint32_t n=u32(offset);float f;std::memcpy(&f,&n,4);return f;
    }
};

void verify_rig(const fs::path& output) {
    Glb glb(output/"model.glb");auto clip=glb.doc["animations"][1];
    auto value=[&](int node,const char* path,int c) {
        for(auto channel:clip["channels"])if(channel["target"]["node"]==node&&channel["target"]["path"]==path) {
            auto a=clip["samplers"][channel["sampler"].get<size_t>()]["output"].get<size_t>();
            size_t count=glb.doc["accessors"][a]["count"].get<size_t>();int width=std::strcmp(path,"rotation")==0?4:3;
            return glb.number(a,(count-1)*width+c);
        }
        throw std::runtime_error("solved rig channel missing");
    };
    float z=value(1,"rotation",2),w=value(1,"rotation",3);
    check(std::abs(-2*z*w-.5f)<.005f&&std::abs(1-2*z*z-.8660254f)<.005f,"IK endpoint was not solved and baked");
    check(std::abs(value(4,"translation",0)-3.5f*.925f)<.001f,"append translation lost VMD source offset");
    check(std::abs(value(4,"rotation",2)-.3826834f)<.001f,"append rotation was not baked");
    check(glb.doc["animations"][1]==glb.doc["animations"][2] || glb.doc["animations"][1]["channels"].size()==glb.doc["animations"][2]["channels"].size(),"second clip changed solver layout");
    // Identical clips must have identical sampled values even after the first evaluator ran.
    auto second=glb.doc["animations"][2];
    for(size_t c=0;c<clip["channels"].size();c++) {
        auto sa=clip["samplers"][clip["channels"][c]["sampler"].get<size_t>()];
        auto sb=second["samplers"][second["channels"][c]["sampler"].get<size_t>()];
        auto a=sa["output"].get<size_t>(),b=sb["output"].get<size_t>();
        auto aa=glb.doc["accessors"][a],bb=glb.doc["accessors"][b];check(aa["count"]==bb["count"],"clip reset changed key count");
        int width=aa["type"]=="VEC4"?4:aa["type"]=="VEC3"?3:1;
        for(size_t i=0;i<aa["count"].get<size_t>()*width;i++)check(std::abs(glb.number(a,i)-glb.number(b,i))<1e-6f,"clip reset is not deterministic");
    }
    auto meta=json_file(output/"metadata.json"),physics=meta["physics"];
    check(physics["bodies"].size()==2&&physics["joints"].size()==1,"source rigid body/joint lost");
    auto vector=[](const Json& j){return glm::vec3(j[0].get<float>(),j[1].get<float>(),j[2].get<float>());};
    auto matrix=[&](const Json& j,const char* p,const char* r){auto q=j[r];return glm::translate(glm::mat4(1),vector(j[p]))*
        glm::mat4_cast(glm::quat(q[3].get<float>(),q[0].get<float>(),q[1].get<float>(),q[2].get<float>()));};
    auto joint=physics["joints"][0];
    auto worldA=matrix(physics["bodies"][0],"position","rotation")*matrix(joint,"positionA","rotationA");
    auto worldB=glm::translate(glm::mat4(1),vector(meta["bones"][1]["position"]))*matrix(physics["bodies"][1],"position","rotation")*matrix(joint,"positionB","rotationB");
    for(int column=0;column<4;column++)for(int row=0;row<4;row++)check(std::abs(worldA[column][row]-worldB[column][row])<1e-5f,"joint local frames do not meet in world space");
    check(std::abs(physics["bodies"][0]["mass"].get<float>()-.001f)<1e-8f&&physics["bodies"][0]["mode"]=="FOLLOW","static MMD body mode/mass adaptation failed");
}

void verify_ik_switch(const fs::path& output) {
    Glb glb(output/"model.glb");auto clip=glb.doc["animations"][1];bool found=false;
    for(auto channel:clip["channels"])if(channel["target"]["node"]==1&&channel["target"]["path"]=="rotation") {
        auto a=clip["samplers"][channel["sampler"].get<size_t>()]["output"].get<size_t>();
        check(std::abs(glb.number(a,30*4+2))<1e-6f,"IK did not disable at exact VMD timestamp");
        check(std::abs(glb.number(a,29*4+2))>.02f,"IK disabled before its VMD timestamp");
        check(std::abs(glb.number(a,60*4+2))>.1f,"IK did not restore at exact VMD timestamp");found=true;
    }
    check(found,"IK switch fixture produced no skeletal rotation");
}

void verify_model(const fs::path& output,bool animated) {
    auto meta=json_file(output/"metadata.json");check(meta["bones"].size()==2,"lost skeleton");
    check(std::abs(meta["bounds"]["max"][1].get<float>()-1.85f)<1e-5f,"height calibration failed");
    check(std::abs(meta["bounds"]["min"][1].get<float>())<1e-5f,"feet offset failed");
    check(meta["morphNames"].size()==1&&meta["morphNames"][0]=="blink","lost vertex morph");
    check(!fs::exists(output/".source.pmx")&&!fs::exists(output/".source.pmd"),"source staging leaked");
    Glb glb(output/"model.glb");auto primitive=glb.doc["meshes"][0]["primitives"][0];auto attrs=primitive["attributes"];
    auto weight=attrs["WEIGHTS_0"].get<size_t>();check(std::abs(glb.number(weight,0)-.25f)<1e-6f&&std::abs(glb.number(weight,1)-.75f)<1e-6f,"two weights were flattened");
    auto bind=glb.doc["skins"][0]["inverseBindMatrices"].get<size_t>();
    check(std::abs(glb.number(bind,16+13)+.925f)<1e-5f,"inverse bind lost child translation");
    auto target=primitive["targets"][0]["POSITION"].get<size_t>();check(glb.doc["accessors"][target]["sparse"]["count"]==1,"morph sparse delta lost");
    check(glb.doc["animations"].size()==(animated?2:1),"clip count wrong");
    if(animated) {
        auto clip=glb.doc["animations"][1];bool found=false;
        for(auto channel:clip["channels"])if(channel["target"]["node"]==2&&channel["target"]["path"]=="translation") {
            auto sampler=clip["samplers"][channel["sampler"].get<size_t>()];
            auto outputAccessor=sampler["output"].get<size_t>();float middle=glb.number(outputAccessor,30*3);
            check(middle>0&&middle<.15f,"destination-key Bezier ownership not preserved");found=true;
        }
        check(found,"authored VMD channel missing");
    }
}

int main(int argc,char** argv) {
    try {
        if(argc>=4) {
            fs::path modelPath=fs::u8path(argv[1]),output=fs::u8path(argv[2]),motionPath=fs::u8path(argv[3]);
            std::ifstream in(modelPath,std::ios::binary);std::vector<uint8_t> bytes(std::istreambuf_iterator<char>(in),{});
            Json options={{"height",1.85},{"sampleRate",60},{"motions",Json::array({{{"path",motionPath.u8string()},{"name",motionPath.stem().u8string()}}})}};
            int status=rwmd_convert(bytes.data(),bytes.size(),modelPath.u8string().c_str(),output.u8string().c_str(),options.dump().c_str());
            if(status)throw std::runtime_error(rwmd_last_error());
            auto meta=json_file(output/"metadata.json");std::cout<<meta.dump(2)<<'\n';return 0;
        }
        fs::path root=argc>1?fs::u8path(argv[1]):fs::temp_directory_path()/"rigworks-mmd-native-fixture";
        fs::create_directories(root);
        auto motion=vmd();save(root/"wave.vmd",motion.data);
        Json options={{"height",1.85},{"sampleRate",60},{"motions",Json::array({{{"path",(root/"wave.vmd").u8string()},{"name","walk"}}})}};
        for(bool isPmx:{true,false}) {
            auto model=isPmx?pmx():pmd();auto name=isPmx?"fixture.pmx":"fixture.pmd";auto output=root/(isPmx?"pmx":"pmd");
            int status=rwmd_convert(model.data.data(),model.data.size(),name,output.u8string().c_str(),options.dump().c_str());
            if(status)throw std::runtime_error(rwmd_last_error());
            verify_model(output,true);
            auto empty=root/(isPmx?"pmx-neutral":"pmd-neutral");
            status=rwmd_convert(model.data.data(),model.data.size(),name,empty.u8string().c_str(),"{}");
            if(status)throw std::runtime_error(rwmd_last_error());verify_model(empty,false);
            auto damaged=model.data;damaged.resize(damaged.size()-1);
            check(rwmd_convert(damaged.data(),damaged.size(),name,(root/"broken").u8string().c_str(),"{}")!=0,"truncated model accepted");
        }
        auto rig=pmx(true),rigVmd=rig_motion();save(root/"rig.vmd",rigVmd.data);
        Json rigOptions={{"motions",Json::array({{{"path",(root/"rig.vmd").u8string()},{"name","rig_a"}},{{"path",(root/"rig.vmd").u8string()},{"name","rig_b"}}})}};
        auto rigOut=root/"rig";int status=rwmd_convert(rig.data.data(),rig.data.size(),"rig.pmx",rigOut.u8string().c_str(),rigOptions.dump().c_str());
        if(status)throw std::runtime_error(rwmd_last_error());verify_rig(rigOut);
        auto switched=rig_motion(true);save(root/"switch.vmd",switched.data);
        Json switchOptions={{"motions",Json::array({{{"path",(root/"switch.vmd").u8string()},{"name","switch"}}})}};
        auto switchedOut=root/"switch";status=rwmd_convert(rig.data.data(),rig.data.size(),"rig.pmx",switchedOut.u8string().c_str(),switchOptions.dump().c_str());
        if(status)throw std::runtime_error(rwmd_last_error());verify_ik_switch(switchedOut);
        std::cout<<"PMD/PMX weighted skin, inverse bind, Morph, Bezier clips, neutral and truncation checks passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
