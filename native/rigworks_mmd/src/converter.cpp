#include "rigworks_mmd.h"
#include "glb.hpp"
#include "preflight.hpp"

#include <Saba/Model/MMD/PMXFile.h>
#include <Saba/Model/MMD/PMXModel.h>
#include <Saba/Model/MMD/PMDFile.h>
#include <Saba/Model/MMD/PMDModel.h>
#include <Saba/Model/MMD/VMDFile.h>
#include <Saba/Model/MMD/VMDAnimation.h>

#define GLM_ENABLE_EXPERIMENTAL
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>
#include <glm/gtx/matrix_decompose.hpp>
#include <glm/gtx/quaternion.hpp>

#include <array>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <unordered_map>
#include <utility>

namespace rwmd {
namespace fs=std::filesystem;

Json vec(glm::vec3 v){return Json::array({v.x,v.y,v.z});}
Json quat(glm::quat q){return Json::array({q.x,q.y,q.z,q.w});}
glm::vec3 reflect(glm::vec3 v){return v*glm::vec3(1,1,-1);}
glm::quat reflect(glm::quat q){return glm::normalize(glm::quat(q.w,-q.x,-q.y,q.z));}
bool finite(glm::vec3 v){return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z);}
bool finite(glm::quat q){return std::isfinite(q.x)&&std::isfinite(q.y)&&std::isfinite(q.z)&&std::isfinite(q.w);}
void require(bool valid,const std::string& error){if(!valid)throw std::runtime_error(error);}
void warn(Json& metadata,const std::string& message){metadata["warnings"].push_back(message);}

std::vector<uint8_t> read_file(const fs::path& path,size_t max) {
    auto length=fs::file_size(path);require(length>0&&length<=max,"Input file exceeds size budget: "+path.u8string());
    std::vector<uint8_t> bytes(static_cast<size_t>(length));std::ifstream in(path,std::ios::binary);
    require(bool(in),"Cannot open input: "+path.u8string());in.read(reinterpret_cast<char*>(bytes.data()),std::streamsize(length));
    require(bool(in),"Cannot read input: "+path.u8string());return bytes;
}

struct Vertex {
    glm::vec3 position,normal;
    glm::vec2 uv;
    std::array<uint16_t,4> bones{};
    std::array<float,4> weights{};
};
struct Bone { std::string name,source;int parent;glm::vec3 position; };
struct Material { std::string name,texture,sphere,toon;glm::vec4 color;size_t count;bool doubleSided; };
struct Morph { std::string name;size_t source;std::map<uint32_t,glm::vec3> offsets; };
struct Body {
    std::string name;int bone,mode,shape,group,mask;
    glm::vec3 size,position,rotation;
    float mass,linearDamping,angularDamping,friction,restitution;
};
struct Joint {
    std::string name;int a,b,type;
    glm::vec3 position,rotation,linearLower,linearUpper,angularLower,angularUpper,linearSpring,angularSpring;
};
struct Data {
    std::vector<Vertex> vertices;
    std::vector<uint32_t> indices;
    std::vector<Bone> bones;
    std::vector<Material> materials;
    std::vector<Morph> morphs;
    std::vector<Body> bodies;
    std::vector<Joint> joints;
    std::vector<std::vector<std::pair<size_t,float>>> groups;
    std::vector<std::string> sourceMorphNames;
    Json rig={{"version",1},{"defaults",Json::object()},{"boneMorphs",Json::object()},{"groups",Json::object()}};
    glm::vec3 min,max,offset;
    float scale=1;
};

std::string unique_name(std::string name,const std::string& prefix,size_t index,std::set<std::string>& used,Json& meta) {
    if(name.empty()||name.size()>256||name.find('\0')!=std::string::npos)name=prefix+std::to_string(index);
    if(used.insert(name).second)return name;
    std::string replacement=prefix+std::to_string(index);
    while(!used.insert(replacement).second)replacement.push_back('_');
    warn(meta,"Duplicate "+prefix+" name renamed: "+name+" -> "+replacement);return replacement;
}

void influences(Vertex& out,const int32_t* bones,const float* weights,size_t count,size_t numBones) {
    float sum=0;
    for(size_t i=0;i<count;i++) {
        require(std::isfinite(weights[i])&&weights[i]>=0,"Invalid vertex weight");
        if(weights[i]>0) {
            require(bones[i]>=0&&size_t(bones[i])<numBones,"Weighted vertex references unknown bone");
            out.bones[i]=static_cast<uint16_t>(bones[i]);out.weights[i]=weights[i];sum+=weights[i];
        }
    }
    require(std::isfinite(sum)&&sum>1e-8f,"Vertex has no valid skin weights");
    for(auto& w:out.weights)w/=sum;
}

void validate_hierarchy(const Data& d) {
    require(!d.bones.empty()&&d.bones.size()<=4096,"Model needs between 1 and 4096 bones");
    std::vector<uint8_t> state(d.bones.size());
    std::function<void(size_t)> visit=[&](size_t i) {
        if(state[i]==2)return;require(state[i]!=1,"Cyclic bone parent hierarchy at "+d.bones[i].source);state[i]=1;
        auto p=d.bones[i].parent;
        require(p>=-1&&(p<0||size_t(p)<d.bones.size()),"Invalid bone parent index");
        if(p>=0)visit(size_t(p));state[i]=2;
    };
    for(size_t i=0;i<state.size();i++)visit(i);
}

Data load_pmx(const fs::path& path,Json& meta) {
    saba::PMXFile p{};require(saba::ReadPMXFile(&p,path.u8string().c_str()),"Saba could not parse PMX");
    Data d;std::set<std::string> names;
    for(size_t i=0;i<p.m_bones.size();i++) {
        const auto& b=p.m_bones[i];require(finite(b.m_position),"Non-finite PMX bone position");
        d.bones.push_back({unique_name(b.m_name,"bone_",i,names,meta),b.m_name,b.m_parentBoneIndex,reflect(b.m_position)});
        auto bone_ok=[&](int n){return n>=0&&size_t(n)<p.m_bones.size();};
        auto flags=uint16_t(b.m_boneFlag);
        if(flags&0x300)require(b.m_appendBoneIndex==-1||bone_ok(b.m_appendBoneIndex),"Invalid append source bone");
        if(flags&0x2400)warn(meta,"Source fixed-axis/external-parent constraints are not preserved as interactive RigWorks constraints: "+b.m_name);
        if(flags&0x20) {
            require(bone_ok(b.m_ikTargetBoneIndex),"Invalid IK target bone");
            require(b.m_ikIterationCount>=0&&b.m_ikIterationCount<=1024,"IK iteration count exceeds budget");
            require(std::isfinite(b.m_ikLimit)&&b.m_ikLimit>=0,"Invalid IK angle limit");
            for(const auto& l:b.m_ikLinks) {
                require(bone_ok(l.m_ikBoneIndex),"Invalid IK link bone");
                if(l.m_enableLimit)require(finite(l.m_limitMin)&&finite(l.m_limitMax),"Invalid IK link limits");
            }
        }
    }
    validate_hierarchy(d);
    bool sdef=false,qdef=false;
    for(const auto& v:p.m_vertices) {
        require(finite(v.m_position)&&finite(v.m_normal)&&std::isfinite(v.m_uv.x)&&std::isfinite(v.m_uv.y),"Non-finite PMX vertex");
        Vertex out{reflect(v.m_position),reflect(v.m_normal),v.m_uv};
        int32_t bones[4]={0,0,0,0};
        float weights[4]={0,0,0,0};size_t count=4;
        switch(v.m_weightType) {
        case saba::PMXVertexWeight::BDEF1:count=1;weights[0]=1;break;
        case saba::PMXVertexWeight::BDEF2:count=2;weights[0]=v.m_boneWeights[0];weights[1]=1-weights[0];break;
        case saba::PMXVertexWeight::SDEF:sdef=true;count=2;weights[0]=v.m_boneWeights[0];weights[1]=1-weights[0];break;
        case saba::PMXVertexWeight::QDEF:qdef=true;[[fallthrough]];
        case saba::PMXVertexWeight::BDEF4:for(int i=0;i<4;i++)weights[i]=v.m_boneWeights[i];break;
        default:throw std::runtime_error("Unsupported PMX skinning type");
        }
        for(size_t i=0;i<count;i++)bones[i]=v.m_boneIndices[i];
        influences(out,bones,weights,count,d.bones.size());d.vertices.push_back(out);
    }
    if(sdef)warn(meta,"SDEF skinning is approximated by normalized two-weight LBS; baked FK does not preserve SDEF deformation exactly.");
    if(qdef)warn(meta,"QDEF skinning is approximated by normalized four-weight LBS.");
    for(const auto& f:p.m_faces)for(int i=2;i>=0;i--) {
        require(f.m_vertices[i]<d.vertices.size(),"PMX face vertex index is out of range");d.indices.push_back(f.m_vertices[i]);
    }
    auto texture=[&](int index) {
        if(index==-1)return std::string();
        require(index>=0&&size_t(index)<p.m_textures.size(),"Invalid PMX texture index");return p.m_textures[index].m_textureName;
    };
    size_t total=0;
    for(size_t i=0;i<p.m_materials.size();i++) {
        const auto& m=p.m_materials[i];require(m.m_numFaceVertices>=0&&m.m_numFaceVertices%3==0,"Invalid PMX material index count");
        require(std::isfinite(m.m_diffuse.r)&&std::isfinite(m.m_diffuse.g)&&std::isfinite(m.m_diffuse.b)&&std::isfinite(m.m_diffuse.a),"Invalid PMX diffuse color");
        total+=size_t(m.m_numFaceVertices);
        std::string toon=m.m_toonMode==saba::PMXToonMode::Separate?texture(m.m_toonTextureIndex):std::string();
        d.materials.push_back({m.m_name.empty()?"material_"+std::to_string(i):m.m_name,texture(m.m_textureIndex),texture(m.m_sphereTextureIndex),toon,m.m_diffuse,size_t(m.m_numFaceVertices),(uint8_t(m.m_drawMode)&1)!=0});
        if(m.m_sphereMode!=saba::PMXSphereMode::None)warn(meta,"Sphere texture is preserved as metadata, not rendered by the generic PBR material: "+m.m_name);
    }
    require(total==d.indices.size(),"PMX material index ranges do not cover the mesh");
    names.clear();d.groups.resize(p.m_morphs.size());
    for(size_t i=0;i<p.m_morphs.size();i++)d.sourceMorphNames.push_back(unique_name(p.m_morphs[i].m_name,"morph_",i,names,meta));
    for(size_t i=0;i<p.m_morphs.size();i++) {
        const auto& m=p.m_morphs[i];const auto& name=d.sourceMorphNames[i];
        if(m.m_morphType==saba::PMXMorphType::Position) {
            Morph out{name,i,{}};
            for(const auto& v:m.m_positionMorph) {
                require(v.m_vertexIndex>=0&&size_t(v.m_vertexIndex)<d.vertices.size()&&finite(v.m_position),"Invalid PMX vertex morph offset");
                auto added=out.offsets.emplace(uint32_t(v.m_vertexIndex),glm::vec3(0));added.first->second+=reflect(v.m_position);
            }
            d.morphs.push_back(std::move(out));d.rig["defaults"][name]=0;
        } else if(m.m_morphType==saba::PMXMorphType::Bone) {
            Json out=Json::array();
            for(const auto& b:m.m_boneMorph) {
                require(b.m_boneIndex>=0&&size_t(b.m_boneIndex)<d.bones.size()&&finite(b.m_position)&&finite(b.m_quaternion)&&glm::length(b.m_quaternion)>1e-8f,"Invalid PMX bone morph");
                out.push_back({{"node",d.bones[b.m_boneIndex].name},{"translation",vec(reflect(b.m_position))},{"rotation",quat(reflect(b.m_quaternion))}});
            }
            if(out.size()<=256){d.rig["boneMorphs"][name]=out;d.rig["defaults"][name]=0;}
            else warn(meta,"Manual bone morph exceeds generic 256-element capacity: "+name);
        } else if(m.m_morphType==saba::PMXMorphType::Group) {
            for(const auto& child:m.m_groupMorph) {
                require(child.m_morphIndex>=0&&size_t(child.m_morphIndex)<p.m_morphs.size()&&std::isfinite(child.m_weight),"Invalid PMX group morph reference");
                d.groups[i].push_back({size_t(child.m_morphIndex),child.m_weight});
            }
        } else warn(meta,"Unsupported interactive morph retained only in source diagnostics: "+name+" (type "+std::to_string(int(m.m_morphType))+")");
    }
    std::vector<uint8_t> state(d.groups.size());
    std::function<void(size_t)> visit=[&](size_t i) {
        if(state[i]==2)return;require(state[i]!=1,"Cyclic PMX group morph");state[i]=1;
        for(auto child:d.groups[i])visit(child.first);state[i]=2;
    };
    for(size_t i=0;i<state.size();i++)visit(i);
    for(size_t i=0;i<d.groups.size();i++)if(!d.groups[i].empty()) {
        Json group=Json::object();
        for(auto child:d.groups[i]) {
            auto kind=p.m_morphs[child.first].m_morphType;
            if(kind==saba::PMXMorphType::Position || kind==saba::PMXMorphType::Bone || kind==saba::PMXMorphType::Group)
                group[d.sourceMorphNames[child.first]]=child.second;
        }
        d.rig["groups"][d.sourceMorphNames[i]]=group;d.rig["defaults"][d.sourceMorphNames[i]]=0;
    }
    if(d.rig["defaults"].size()>256 || d.rig["boneMorphs"].size()>256 || d.rig["groups"].size()>256) {
        warn(meta,"Interactive morph sidecar exceeds generic 256-entry capacity; vertex clips remain available, manual groups/bone morphs omitted.");
        d.rig={{"version",1},{"defaults",Json::object()}};
        for(const auto& m:d.morphs)if(d.rig["defaults"].size()<256)d.rig["defaults"][m.name]=0;
    }
    for(const auto& b:p.m_rigidbodies) {
        require(b.m_boneIndex>=-1&&(b.m_boneIndex<0||size_t(b.m_boneIndex)<d.bones.size()),"Invalid rigid-body bone index");
        d.bodies.push_back({b.m_name,b.m_boneIndex,int(b.m_op),int(b.m_shape),b.m_group,b.m_collisionGroup,b.m_shapeSize,b.m_translate,b.m_rotate,b.m_mass,b.m_translateDimmer,b.m_rotateDimmer,b.m_friction,b.m_repulsion});
    }
    for(const auto& j:p.m_joints)d.joints.push_back({j.m_name,j.m_rigidbodyAIndex,j.m_rigidbodyBIndex,int(j.m_type),j.m_translate,j.m_rotate,j.m_translateLowerLimit,j.m_translateUpperLimit,j.m_rotateLowerLimit,j.m_rotateUpperLimit,j.m_springTranslateFactor,j.m_springRotateFactor});
    return d;
}

Data load_pmd(const fs::path& path,Json& meta) {
    saba::PMDFile p{};require(saba::ReadPMDFile(&p,path.u8string().c_str()),"Saba could not parse PMD");
    Data d;std::set<std::string> names;
    for(size_t i=0;i<p.m_bones.size();i++) {
        const auto& b=p.m_bones[i];auto name=b.m_boneName.ToUtf8String();require(finite(b.m_position),"Non-finite PMD bone position");
        d.bones.push_back({unique_name(name,"bone_",i,names,meta),name,b.m_parent==0xffff?-1:int(b.m_parent),reflect(b.m_position)});
    }
    validate_hierarchy(d);
    for(const auto& v:p.m_vertices) {
        require(finite(v.m_position)&&finite(v.m_normal)&&std::isfinite(v.m_uv.x)&&std::isfinite(v.m_uv.y)&&v.m_boneWeight<=100,"Invalid PMD vertex");
        Vertex out{reflect(v.m_position),reflect(v.m_normal),v.m_uv};
        int32_t bones[2]={v.m_bone[0],v.m_bone[1]};float weights[2]={v.m_boneWeight*.01f,1-v.m_boneWeight*.01f};
        influences(out,bones,weights,2,d.bones.size());d.vertices.push_back(out);
    }
    for(const auto& f:p.m_faces)for(int i=2;i>=0;i--) {require(f.m_vertices[i]<d.vertices.size(),"Invalid PMD face index");d.indices.push_back(f.m_vertices[i]);}
    size_t total=0;
    for(size_t i=0;i<p.m_materials.size();i++) {
        const auto& m=p.m_materials[i];auto file=m.m_textureName.ToUtf8String();std::string texture=file,sphere;
        auto star=file.find('*');if(star!=std::string::npos){texture=file.substr(0,star);sphere=file.substr(star+1);}
        else if(file.size()>=4&&(file.substr(file.size()-4)==".sph"||file.substr(file.size()-4)==".spa")){texture.clear();sphere=file;}
        require(m.m_faceVertexCount%3==0,"Invalid PMD material index count");total+=m.m_faceVertexCount;
        require(m.m_toonIndex==255||m.m_toonIndex<10,"Invalid PMD toon index");
        d.materials.push_back({"material_"+std::to_string(i),texture,sphere,m.m_toonIndex<10?p.m_toonTextureNames[m.m_toonIndex].ToUtf8String():"",glm::vec4(m.m_diffuse,m.m_alpha),m.m_faceVertexCount,false});
    }
    require(total==d.indices.size(),"PMD material index ranges do not cover the mesh");
    for(const auto& ik:p.m_iks) {
        require(ik.m_ikNode<d.bones.size()&&ik.m_ikTarget<d.bones.size()&&ik.m_numIteration<=1024&&std::isfinite(ik.m_rotateLimit)&&ik.m_rotateLimit>=0,"Invalid PMD IK solver");
        for(auto bone:ik.m_chanins)require(bone<d.bones.size(),"Invalid PMD IK link");
    }
    const saba::PMDMorph* base=nullptr;for(const auto& m:p.m_morphs)if(m.m_morphType==saba::PMDMorph::Base)base=&m;
    names.clear();
    for(const auto& m:p.m_morphs)if(m.m_morphType!=saba::PMDMorph::Base) {
        require(base!=nullptr,"PMD morphs need a base vertex table");
        auto name=unique_name(m.m_morphName.ToUtf8String(),"morph_",d.sourceMorphNames.size(),names,meta);
        Morph out{name,d.sourceMorphNames.size(),{}};d.sourceMorphNames.push_back(name);
        for(const auto& v:m.m_vertices) {
            require(v.m_vertexIndex<base->m_vertices.size(),"Invalid PMD base-morph index");auto index=base->m_vertices[v.m_vertexIndex].m_vertexIndex;
            require(index<d.vertices.size()&&finite(v.m_position),"Invalid PMD morph vertex");auto added=out.offsets.emplace(index,glm::vec3(0));added.first->second+=reflect(v.m_position);
        }
        d.morphs.push_back(std::move(out));d.rig["defaults"][name]=0;
    }
    d.groups.resize(d.sourceMorphNames.size());
    for(const auto& b:p.m_rigidBodies) {
        int bone=b.m_boneIndex==0xffff?-1:int(b.m_boneIndex);require(bone<0||size_t(bone)<d.bones.size(),"Invalid PMD rigid-body bone");
        glm::vec3 position=b.m_pos;if(bone>=0)position+=reflect(d.bones[bone].position);
        d.bodies.push_back({b.m_rigidBodyName.ToUtf8String(),bone,int(b.m_rigidBodyType),int(b.m_shapeType),b.m_groupIndex,b.m_groupTarget,{b.m_shapeWidth,b.m_shapeHeight,b.m_shapeDepth},position,b.m_rot,b.m_rigidBodyWeight,b.m_rigidBodyPosDimmer,b.m_rigidBodyRotDimmer,b.m_rigidBodyFriction,b.m_rigidBodyRecoil});
    }
    for(const auto& j:p.m_joints)d.joints.push_back({j.m_jointName.ToUtf8String(),int(j.m_rigidBodyA),int(j.m_rigidBodyB),0,j.m_jointPos,j.m_jointRot,j.m_constrainPos1,j.m_constrainPos2,j.m_constrainRot1,j.m_constrainRot2,j.m_springPos,j.m_springRot});
    return d;
}

void normalize(Data& d,float height,Json& meta) {
    require(!d.vertices.empty()&&!d.indices.empty(),"Model contains no indexed geometry");
    d.min=glm::vec3(std::numeric_limits<float>::max());d.max=-d.min;
    for(const auto& v:d.vertices){d.min=glm::min(d.min,v.position);d.max=glm::max(d.max,v.position);}
    require(d.max.y-d.min.y>1e-5f,"Neutral model has no measurable height");
    d.scale=height/(d.max.y-d.min.y);d.offset={0,-d.min.y*d.scale,0};
    for(auto& v:d.vertices) {
        v.position=v.position*d.scale+d.offset;
        float length=glm::length(v.normal);require(std::isfinite(length)&&length>1e-8f,"Vertex normal has zero length");v.normal/=length;
    }
    for(auto& b:d.bones)b.position=b.position*d.scale+d.offset;
    for(auto& m:d.morphs)for(auto& p:m.offsets)p.second*=d.scale;
    d.min=d.min*d.scale+d.offset;d.max=d.max*d.scale+d.offset;
    if(d.rig.count("boneMorphs"))for(auto it=d.rig["boneMorphs"].begin();it!=d.rig["boneMorphs"].end();++it)
        for(auto& b:it.value())for(auto& component:b["translation"])component=component.get<float>()*d.scale;
    meta["height"]=height;meta["scale"]=d.scale;meta["offset"]=vec(d.offset);
    meta["bounds"]={{"min",vec(d.min)},{"max",vec(d.max)}};
    meta["bones"]=Json::array();
    for(const auto& b:d.bones)meta["bones"].push_back({{"name",b.name},{"sourceName",b.source},{"parent",b.parent},{"position",vec(b.position)},
        {"translation",vec(b.parent>=0?b.position-d.bones[b.parent].position:b.position)}});
    meta["morphNames"]=Json::array();for(const auto& m:d.morphs)meta["morphNames"].push_back(m.name);
    meta["rig"]=d.rig;
    for(const auto& b:d.bodies) {
        require(b.mode>=0&&b.mode<=2&&b.shape>=0&&b.shape<=2&&b.group>=0&&b.group<16,"Invalid physics mode/shape/group");
        require(finite(b.size)&&finite(b.position)&&finite(b.rotation)&&std::isfinite(b.mass)&&b.mass>=0&&
            std::isfinite(b.linearDamping)&&std::isfinite(b.angularDamping)&&std::isfinite(b.friction)&&std::isfinite(b.restitution),"Invalid rigid-body numeric value");
    }
    for(const auto& j:d.joints) {
        require(j.a>=-1&&j.b>=-1&&(j.a<0||size_t(j.a)<d.bodies.size())&&(j.b<0||size_t(j.b)<d.bodies.size()),"Invalid joint rigid-body index");
        require(finite(j.position)&&finite(j.rotation)&&finite(j.linearLower)&&finite(j.linearUpper)&&finite(j.angularLower)&&finite(j.angularUpper)&&finite(j.linearSpring)&&finite(j.angularSpring),"Invalid joint numeric value");
    }
}

glm::mat4 reflected_rotation(glm::vec3 angles,bool yxz) {
    auto x=glm::rotate(glm::mat4(1),angles.x,glm::vec3(1,0,0));
    auto y=glm::rotate(glm::mat4(1),angles.y,glm::vec3(0,1,0));
    auto z=glm::rotate(glm::mat4(1),angles.z,glm::vec3(0,0,1));
    auto r=yxz?y*x*z:z*y*x;auto reflection=glm::scale(glm::mat4(1),glm::vec3(1,1,-1));
    return reflection*r*reflection;
}

Json physics(const Data& d,Json& meta) {
    Json result={{"version",1},{"gravity",Json::array({0,-9.81,0})},{"bodies",Json::array()},{"joints",Json::array()}};
    std::set<int> owners;std::map<int,size_t> remap;std::map<int,glm::mat4> worlds;
    for(size_t i=0;i<d.bodies.size();i++) {
        const auto& b=d.bodies[i];
        if(result["bodies"].size()>=128){warn(meta,"Physics body capacity reached (128); remaining source bodies and their joints are omitted.");break;}
        if(b.bone>=0&&!owners.insert(b.bone).second){warn(meta,"Multiple rigid bodies on one bone: omitted body "+b.name);continue;}
        glm::vec3 size=b.size*d.scale;
        if(size.x<.001f||size.x>32||(b.shape==1&&(size.y<.001f||size.z<.001f||size.y>32||size.z>32))||
            (b.shape==2&&(size.y<0||size.y>64))){warn(meta,"Physics shape outside generic size limits: "+b.name);continue;}
        auto position=reflect(b.position)*d.scale+d.offset;
        glm::mat4 world=glm::translate(glm::mat4(1),position)*reflected_rotation(b.rotation,true);
        auto relative=position-(b.bone>=0?d.bones[b.bone].position:glm::vec3(0));
        if(glm::any(glm::greaterThan(glm::abs(relative),glm::vec3(64)))){warn(meta,"Physics offset outside generic limits: "+b.name);continue;}
        std::string id="mmd_body_"+std::to_string(i);
        Json body={{"id",id},{"mode",b.mode==0?"FOLLOW":b.mode==1?"DYNAMIC":"HYBRID"},
            {"shape",b.shape==0?"SPHERE":b.shape==1?"BOX":"CAPSULE"},{"size",vec(size)},
            {"position",vec(relative)},{"rotation",quat(glm::quat_cast(world))},
            {"mass",std::max(.001f,std::min(10000.f,b.mass))},{"linearDamping",std::clamp(b.linearDamping,0.f,1.f)},
            {"angularDamping",std::clamp(b.angularDamping,0.f,1.f)},{"friction",std::clamp(b.friction,0.f,10.f)},
            {"restitution",std::clamp(b.restitution,0.f,1.f)},{"group",1<<b.group},{"mask",b.mask},{"ragdoll",false}};
        if(b.bone>=0)body["bone"]=d.bones[b.bone].name;
        remap[int(i)]=result["bodies"].size();worlds[int(i)]=world;result["bodies"].push_back(body);
    }
    for(size_t i=0;i<d.joints.size();i++) {
        const auto& j=d.joints[i];
        if(j.a==j.b||!remap.count(j.a)||!remap.count(j.b)){warn(meta,"Joint has no exported body pair: "+j.name);continue;}
        if(j.type>1){warn(meta,"Unsupported non-6DOF joint omitted: "+j.name);continue;}
        if(result["joints"].size()>=256){warn(meta,"Physics joint capacity reached (256); remaining source joints are omitted.");break;}
        auto world=glm::translate(glm::mat4(1),reflect(j.position)*d.scale+d.offset)*reflected_rotation(j.rotation,false);
        auto a=glm::inverse(worlds.at(j.a))*world,b=glm::inverse(worlds.at(j.b))*world;
        glm::vec3 lower=j.linearLower*d.scale,upper=j.linearUpper*d.scale;
        std::swap(lower.z,upper.z);lower.z=-lower.z;upper.z=-upper.z;
        glm::vec3 alower=j.angularLower,aupper=j.angularUpper;
        for(int axis=0;axis<2;axis++){std::swap(alower[axis],aupper[axis]);alower[axis]=-alower[axis];aupper[axis]=-aupper[axis];}
        if(glm::any(glm::greaterThan(glm::abs(glm::vec3(a[3])),glm::vec3(64)))||glm::any(glm::greaterThan(glm::abs(glm::vec3(b[3])),glm::vec3(64)))||
            glm::any(glm::greaterThan(glm::abs(lower),glm::vec3(64)))||glm::any(glm::greaterThan(glm::abs(upper),glm::vec3(64)))) {
            warn(meta,"Joint linear frame or limit outside generic range: "+j.name);continue;
        }
        if(glm::any(glm::greaterThan(glm::abs(alower),glm::vec3(3.14159265f)))||glm::any(glm::greaterThan(glm::abs(aupper),glm::vec3(3.14159265f)))) {
            warn(meta,"Joint angular limit outside generic range: "+j.name);continue;
        }
        Json joint={{"id","mmd_joint_"+std::to_string(i)},
            {"bodyA",result["bodies"][remap.at(j.a)]["id"]},{"bodyB",result["bodies"][remap.at(j.b)]["id"]},
            {"positionA",vec(glm::vec3(a[3]))},{"rotationA",quat(glm::normalize(glm::quat_cast(a)))},
            {"positionB",vec(glm::vec3(b[3]))},{"rotationB",quat(glm::normalize(glm::quat_cast(b)))},
            {"linearLower",vec(lower)},{"linearUpper",vec(upper)},{"angularLower",vec(alower)},{"angularUpper",vec(aupper)},
            {"disableLinkedCollision",true}};
        if(glm::length(j.linearSpring)>0||glm::length(j.angularSpring)>0)joint["spring"]={
            {"linearStiffness",vec(glm::clamp(j.linearSpring,glm::vec3(0),glm::vec3(10000)))},
            {"angularStiffness",vec(glm::clamp(j.angularSpring*d.scale*d.scale,glm::vec3(0),glm::vec3(10000)))},
            {"linearDamping",Json::array({.5,.5,.5})},{"angularDamping",Json::array({.5,.5,.5})},{"maxForce",1000}};
        result["joints"].push_back(joint);
    }
    if(!d.bodies.empty())warn(meta,"Physics uses RigWorks' generic solver: damping, springs, contacts and post-physics deformation may differ from MMD.");
    return result;
}

void materials(Glb& glb,const Data& d,Json& meta) {
    meta["textures"]=Json::array();meta["materials"]=Json::array();std::map<std::string,size_t> textureIds;
    auto image=[&](std::string source) {
        std::replace(source.begin(),source.end(),'\\','/');
        if(textureIds.count(source))return textureIds.at(source);
        size_t i=textureIds.size();textureIds[source]=i;
        auto extension=fs::u8path(source).extension().u8string();
        if(extension.empty()||extension.size()>10)extension=".png";
        auto asset="textures/texture_"+std::to_string(i)+extension;
        meta["textures"].push_back({{"sourcePath",source},{"assetPath",asset}});
        glb.root["images"].push_back({{"uri",asset}});glb.root["textures"].push_back({{"source",i},{"sampler",0}});return i;
    };
    glb.root["images"]=Json::array();glb.root["textures"]=Json::array();
    glb.root["samplers"]=Json::array({{{"magFilter",9729},{"minFilter",9987},{"wrapS",10497},{"wrapT",10497}}});
    for(const auto& m:d.materials) {
        auto color=glm::clamp(m.color,glm::vec4(0),glm::vec4(1));
        Json mat={{"name",m.name},{"doubleSided",m.doubleSided},
            {"pbrMetallicRoughness",{{"baseColorFactor",Json::array({color.r,color.g,color.b,color.a})},{"metallicFactor",0},{"roughnessFactor",.85}}},
            {"alphaMode",color.a<.999f?"BLEND":"MASK"},{"alphaCutoff",.5}};
        if(!m.texture.empty())mat["pbrMetallicRoughness"]["baseColorTexture"]={{"index",image(m.texture)}};
        glb.root["materials"].push_back(mat);
        meta["materials"].push_back({{"name",m.name},{"texture",m.texture},{"sphereTexture",m.sphere},{"toonTexture",m.toon}});
    }
    if(std::any_of(d.materials.begin(),d.materials.end(),[](const Material& m){return !m.toon.empty();}))
        warn(meta,"MMD toon textures remain source material metadata; the converted asset uses generic PBR shading.");
    if(textureIds.empty()){glb.root.erase("images");glb.root.erase("textures");glb.root.erase("samplers");}
}

void geometry(Glb& glb,const Data& d,Json& meta) {
    materials(glb,d,meta);
    Json primitives=Json::array();size_t first=0;
    for(size_t material=0;material<d.materials.size();material++) {
        const auto& m=d.materials[material];if(!m.count)continue;
        std::vector<int32_t> remap(d.vertices.size(),-1);std::vector<uint32_t> original,indices;
        for(size_t index=first;index<first+m.count;index++) {
            auto source=d.indices[index];
            if(remap[source]<0){remap[source]=int32_t(original.size());original.push_back(source);}
            indices.push_back(uint32_t(remap[source]));
        }
        first+=m.count;
        std::vector<float> positions,normals,uv,weights;std::vector<uint16_t> joints;
        for(auto index:original) {
            const auto& v=d.vertices[index];
            for(int a=0;a<3;a++){positions.push_back(v.position[a]);normals.push_back(v.normal[a]);}
            uv.push_back(v.uv.x);uv.push_back(v.uv.y);
            for(size_t a=0;a<4;a++){weights.push_back(v.weights[a]);joints.push_back(v.bones[a]);}
        }
        Json attrs={{"POSITION",glb.accessor(positions,5126,"VEC3",3,34962,true)},
            {"NORMAL",glb.accessor(normals,5126,"VEC3",3,34962)},
            {"TEXCOORD_0",glb.accessor(uv,5126,"VEC2",2,34962)},
            {"JOINTS_0",glb.accessor(joints,5123,"VEC4",4,34962)},
            {"WEIGHTS_0",glb.accessor(weights,5126,"VEC4",4,34962)}};
        Json primitive={{"attributes",attrs},{"indices",glb.accessor(indices,5125,"SCALAR",1,34963)},{"material",material},{"mode",4}};
        if(!d.morphs.empty()) {
            Json targets=Json::array();
            for(const auto& morph:d.morphs) {
                std::vector<uint32_t> sparse;std::vector<float> values;
                for(size_t local=0;local<original.size();local++) {
                    auto found=morph.offsets.find(original[local]);
                    if(found==morph.offsets.end()||glm::length(found->second)<1e-12f)continue;
                    sparse.push_back(uint32_t(local));for(int a=0;a<3;a++)values.push_back(found->second[a]);
                }
                targets.push_back({{"POSITION",glb.sparse_position(original.size(),sparse,values)}});
            }
            primitive["targets"]=targets;
        }
        primitives.push_back(primitive);
    }
    Json mesh={{"name","MMD character"},{"primitives",primitives}};
    if(!d.morphs.empty()) {
        mesh["weights"]=Json::array();mesh["extras"]={{"targetNames",Json::array()}};
        for(const auto& m:d.morphs){mesh["weights"].push_back(0);mesh["extras"]["targetNames"].push_back(m.name);}
    }
    glb.root["meshes"].push_back(mesh);
    Json rootChildren=Json::array();glb.root["nodes"].push_back({{"name","RigWorks_MMD"},{"children",Json::array()}});
    std::vector<float> binds;Json jointNodes=Json::array();
    for(size_t i=0;i<d.bones.size();i++) {
        const auto& b=d.bones[i];Json node={{"name",b.name},{"translation",vec(b.parent>=0?b.position-d.bones[b.parent].position:b.position)}};
        Json children=Json::array();for(size_t c=0;c<d.bones.size();c++)if(d.bones[c].parent==int(i))children.push_back(c+1);
        if(!children.empty())node["children"]=children;
        if(b.parent<0)rootChildren.push_back(i+1);
        glb.root["nodes"].push_back(node);jointNodes.push_back(i+1);
        auto inverse=glm::translate(glm::mat4(1),-b.position);auto p=glm::value_ptr(inverse);binds.insert(binds.end(),p,p+16);
    }
    size_t meshNode=glb.root["nodes"].size();glb.root["nodes"].push_back({{"name","RigWorks_MMD_mesh"},{"mesh",0},{"skin",0}});
    rootChildren.push_back(meshNode);glb.root["nodes"][0]["children"]=rootChildren;
    glb.root["skins"].push_back({{"name","MMD skeleton"},{"joints",jointNodes},{"inverseBindMatrices",glb.accessor(binds,5126,"MAT4",16)}});
    glb.root["scenes"].push_back({{"nodes",Json::array({0})}});
}

void reset_animation(saba::MMDModel& model) {
    auto nodes=model.GetNodeManager();
    for(size_t i=0;i<nodes->GetNodeCount();i++) {
        auto n=nodes->GetMMDNode(i);n->SetAnimationTranslate(glm::vec3(0));n->SetAnimationRotate(glm::quat(1,0,0,0));
    }
    auto morphs=model.GetMorphManager();for(size_t i=0;i<morphs->GetMorphCount();i++)morphs->GetMorph(i)->SetWeight(0);
    auto ik=model.GetIKManager();for(size_t i=0;i<ik->GetIKSolverCount();i++)ik->GetMMDIKSolver(i)->Enable(true);
}

void sample_pose(saba::MMDModel& model,saba::VMDAnimation* animation,float frame) {
    model.BeginAnimation();if(animation)animation->Evaluate(frame);
    model.UpdateMorphAnimation();
    // Pinned Saba's translation grants read GetTranslate rather than AnimateTranslate.
    // Fold the authored offset into the reset local value before its grant solver.
    auto nodes=model.GetNodeManager();
    for(size_t i=0;i<nodes->GetNodeCount();i++) {
        auto n=nodes->GetMMDNode(i);n->SetTranslate(n->GetTranslate()+n->GetAnimationTranslate());n->SetAnimationTranslate(glm::vec3(0));
    }
    model.UpdateNodeAnimation(false);model.UpdateNodeAnimation(true);model.EndAnimation();
}

void neutral(Glb& glb,Json& meta) {
    std::vector<float> times={0,1.f/30.f},values={0,0,0,0,0,0};
    Json clip={{"name","mmd_neutral"},{"samplers",Json::array({{{"input",glb.accessor(times,5126,"SCALAR",1,0,true)},
        {"output",glb.accessor(values,5126,"VEC3",3)},{"interpolation","LINEAR"}}})},
        {"channels",Json::array({{{"sampler",0},{"target",{{"node",0},{"path","translation"}}}}})}};
    glb.root["animations"].push_back(clip);meta["clips"].push_back({{"name","mmd_neutral"},{"duration",1.f/30.f},{"source","neutral-bind"}});
}

void prepare_motion(saba::VMDFile& vmd,const Data& d,Json& meta) {
    std::set<std::string> nodes;for(const auto& b:d.bones)nodes.insert(b.source);
    size_t unknown=0;std::map<std::string,std::map<uint32_t,saba::VMDMotion>> tracks;
    for(const auto& key:vmd.m_motions) {
        auto name=key.m_boneName.ToUtf8String();
        if(!nodes.count(name)){unknown++;continue;}
        require(finite(key.m_translate)&&finite(key.m_quaternion)&&glm::length(key.m_quaternion)>1e-8f,"Non-finite or zero VMD transform: "+name);
        auto copy=key;copy.m_quaternion=glm::normalize(copy.m_quaternion);
        for(size_t cp=0;cp<16;cp++)require(copy.m_interpolation[cp]<=127,"VMD Bezier control exceeds 127: "+name);
        tracks[name][key.m_frame]=copy;
    }
    vmd.m_motions.clear();
    for(auto& entry:tracks) {
        auto& keys=entry.second;
        for(auto it=keys.begin();it!=keys.end();++it) {
            auto key=it->second;auto next=std::next(it);
            if(next!=keys.end())key.m_interpolation=next->second.m_interpolation;
            vmd.m_motions.push_back(key);
        }
    }
    if(unknown)warn(meta,"VMD ignored "+std::to_string(unknown)+" bone keys whose names do not exist in this source model.");
    for(auto& m:vmd.m_morphs)require(std::isfinite(m.m_weight)&&std::abs(m.m_weight)<=4,"Invalid VMD morph weight");
    if(!vmd.m_cameras.empty()||!vmd.m_lights.empty()||!vmd.m_shadows.empty())warn(meta,"VMD camera, light and shadow tracks are not character asset channels.");
    for(const auto& key:vmd.m_iks)if(!key.m_show){warn(meta,"VMD model visibility switches are not baked into character geometry.");break;}
}

void animation(Glb& glb,const Data& d,std::shared_ptr<saba::MMDModel> model,const Json& source,
               int rate,size_t maxFrames,size_t& poseBudget,Json& meta,std::set<std::string>& names) {
    require(source.is_object()&&source.count("path")&&source["path"].is_string(),"Each motion needs a path");
    auto path=fs::u8path(source["path"].get<std::string>());auto bytes=read_file(path,64u*1024u*1024u);preflight_vmd(bytes);
    saba::VMDFile vmd{};require(saba::ReadVMDFile(&vmd,path.u8string().c_str()),"Saba could not parse VMD: "+path.u8string());
    prepare_motion(vmd,d,meta);
    size_t matchedMorphs=0,matchedIk=0;
    for(const auto& key:vmd.m_morphs)if(model->GetMorphManager()->FindMorphIndex(key.m_blendShapeName.ToUtf8String())!=saba::MMDMorphManager::NPos)matchedMorphs++;
    for(const auto& key:vmd.m_iks)for(const auto& sw:key.m_ikInfos)if(model->GetIKManager()->FindIKSolverIndex(sw.m_name.ToUtf8String())!=saba::MMDIKManager::NPos)matchedIk++;
    require(!vmd.m_motions.empty()||matchedMorphs||matchedIk,"VMD contains no bone, Morph or IK tracks matching this model");
    saba::VMDAnimation evaluator;require(evaluator.Create(model)&&evaluator.Add(vmd),"Cannot bind VMD animation");
    auto maxFrame=evaluator.GetMaxKeyTime();require(maxFrame>=0&&maxFrame<=86400*30,"VMD duration exceeds one day");
    float duration=std::max(1.f/30.f,float(maxFrame)/30.f);
    size_t frames=size_t(std::ceil(double(duration)*rate))+1;require(frames<=maxFrames,"VMD sample count exceeds maxFrames");
    size_t values=frames*(d.bones.size()+d.morphs.size());
    require(values<=8000000&&poseBudget<=8000000-values,"Combined motion sample budget exceeds 8 million bone/Morph samples");poseBudget+=values;
    std::string name=source.value("name",path.stem().u8string());name=unique_name(name,"motion_",glb.root["animations"].size(),names,meta);
    Json clip={{"name",name},{"samplers",Json::array()},{"channels",Json::array()}};
    std::vector<float> times;times.reserve(frames);for(size_t i=0;i<frames;i++)times.push_back(std::min(duration,float(i)/rate));
    std::vector<std::vector<float>> translations(d.bones.size()),rotations(d.bones.size());
    for(auto& t:translations)t.reserve(frames*3);for(auto& r:rotations)r.reserve(frames*4);
    std::vector<float> weights;weights.reserve(frames*d.morphs.size());reset_animation(*model);
    auto morphMan=model->GetMorphManager();std::vector<int> morphIndex(d.sourceMorphNames.size(),-1);
    for(size_t i=0;i<d.sourceMorphNames.size();i++) {
        auto index=morphMan->FindMorphIndex(d.sourceMorphNames[i]);if(index!=saba::MMDMorphManager::NPos)morphIndex[i]=int(index);
    }
    std::vector<glm::quat> previous(d.bones.size(),glm::quat(1,0,0,0));
    std::vector<size_t> morphOrder;std::vector<uint8_t> visited(d.groups.size());
    std::function<void(size_t)> order=[&](size_t i){if(visited[i])return;visited[i]=1;for(auto child:d.groups[i])order(child.first);morphOrder.push_back(i);};
    for(size_t i=0;i<d.groups.size();i++)order(i);std::reverse(morphOrder.begin(),morphOrder.end());
    for(size_t frame=0;frame<frames;frame++) {
        sample_pose(*model,&evaluator,times[frame]*30.f);
        for(size_t i=0;i<d.bones.size();i++) {
            const auto& matrix=model->GetNodeManager()->GetMMDNode(i)->GetLocalTransform();
            glm::vec3 translation(matrix[3]);translation*=d.scale;if(d.bones[i].parent<0)translation+=d.offset;
            glm::quat rotation=glm::normalize(glm::quat_cast(matrix));
            require(finite(translation)&&finite(rotation),"Non-finite solved pose in "+name+" at bone "+d.bones[i].name);
            if(glm::dot(previous[i],rotation)<0)rotation=-rotation;previous[i]=rotation;
            for(int c=0;c<3;c++)translations[i].push_back(translation[c]);
            rotations[i].insert(rotations[i].end(),{rotation.x,rotation.y,rotation.z,rotation.w});
        }
        std::vector<float> resolved(d.sourceMorphNames.size(),0);
        for(size_t i=0;i<morphIndex.size();i++)if(morphIndex[i]>=0)resolved[i]=morphMan->GetMorph(size_t(morphIndex[i]))->GetWeight();
        for(size_t i:morphOrder)for(auto child:d.groups[i])resolved[child.first]+=resolved[i]*child.second;
        for(const auto& morph:d.morphs){float w=resolved[morph.source];require(std::isfinite(w)&&std::abs(w)<=64,"Expanded VMD Morph weight exceeds budget");weights.push_back(w);}
    }
    size_t timeAccessor=glb.accessor(times,5126,"SCALAR",1,0,true);
    auto channel=[&](size_t node,const char* property,const std::vector<float>& data,size_t width,const char* type) {
        bool constant=true;for(size_t i=width;i<data.size();i++)if(std::abs(data[i]-data[i%width])>1e-6f){constant=false;break;}
        std::vector<float> output=constant?std::vector<float>(data.begin(),data.begin()+width):data;
        std::vector<float> zero={0};size_t input=constant?glb.accessor(zero,5126,"SCALAR",1,0,true):timeAccessor;
        size_t sampler=clip["samplers"].size();clip["samplers"].push_back({{"input",input},{"output",glb.accessor(output,5126,type,std::string(type)=="SCALAR"?1:width)},{"interpolation","LINEAR"}});
        clip["channels"].push_back({{"sampler",sampler},{"target",{{"node",node},{"path",property}}}});
    };
    for(size_t i=0;i<d.bones.size();i++) {
        const auto& bone=d.bones[i];glm::vec3 bind=bone.parent>=0?bone.position-d.bones[bone.parent].position:bone.position;
        bool moved=false,rotated=false;
        for(size_t f=0;f<frames;f++){for(size_t c=0;c<3;c++)if(std::abs(translations[i][f*3+c]-bind[int(c)])>1e-6f)moved=true;
            if(std::abs(rotations[i][f*4])>1e-6f||std::abs(rotations[i][f*4+1])>1e-6f||std::abs(rotations[i][f*4+2])>1e-6f||std::abs(std::abs(rotations[i][f*4+3])-1)>1e-6f)rotated=true;}
        if(moved)channel(i+1,"translation",translations[i],3,"VEC3");
        if(rotated)channel(i+1,"rotation",rotations[i],4,"VEC4");
    }
    if(!weights.empty())channel(d.bones.size()+1,"weights",weights,d.morphs.size(),"SCALAR");
    // Keep the authored duration even when every skeletal channel is static.
    std::vector<float> rootZeros(frames*3,0);size_t sampler=clip["samplers"].size();
    clip["samplers"].push_back({{"input",timeAccessor},{"output",glb.accessor(rootZeros,5126,"VEC3",3)},{"interpolation","LINEAR"}});
    clip["channels"].push_back({{"sampler",sampler},{"target",{{"node",0},{"path","translation"}}}});
    glb.root["animations"].push_back(clip);
    meta["clips"].push_back({{"name",name},{"duration",duration},{"source",path.filename().u8string()},{"samples",frames},{"sampleRate",rate},{"bakedIk",true},{"bakedAppend",true}});
}

void convert(const void* bytes,size_t size,const char* sourcePath,const char* outputDirectory,const char* optionsJson) {
    require(sourcePath&&outputDirectory,"Source and output paths are required");
    Json options=Json::parse(optionsJson&&*optionsJson?optionsJson:"{}");require(options.is_object(),"MMD options must be an object");
    float height=options.value("height",1.85f);int rate=options.value("sampleRate",60);size_t maxFrames=options.value("maxFrames",size_t(18000));
    require(std::isfinite(height)&&height>=.1f&&height<=32,"Target height must be between .1 and 32 blocks");
    require(rate>=15&&rate<=120&&maxFrames>=2&&maxFrames<=108000,"Invalid MMD sampling budget");
    fs::path source=fs::u8path(sourcePath),output=fs::u8path(outputDirectory);
    auto extension=source.extension().u8string();std::transform(extension.begin(),extension.end(),extension.begin(),[](unsigned char c){return char(std::tolower(c));});
    require(extension==".pmx"||extension==".pmd","MMD source must end in .pmx or .pmd");bool pmx=extension==".pmx";
    preflight_model(bytes,size,pmx);fs::create_directories(output);
    auto staged=output/(pmx?".source.pmx":".source.pmd");
    struct Cleanup {fs::path file;~Cleanup(){std::error_code ec;fs::remove(file,ec);}} cleanup{staged};
    {std::ofstream file(staged,std::ios::binary|std::ios::trunc);require(bool(file),"Cannot stage MMD source");file.write(static_cast<const char*>(bytes),std::streamsize(size));file.close();require(bool(file),"Staging MMD source failed");}
    Json meta={{"version",1},{"sourceFormat",pmx?"PMX":"PMD"},{"converter","rigworks_mmd_1"},{"warnings",Json::array()},{"clips",Json::array()}};
    Data data=pmx?load_pmx(staged,meta):load_pmd(staged,meta);normalize(data,height,meta);meta["physics"]=physics(data,meta);
    Glb glb;geometry(glb,data,meta);neutral(glb,meta);
    if(options.count("motions")) {
        require(options["motions"].is_array()&&options["motions"].size()<=128,"MMD supports at most 128 selected motions");
        if(!options["motions"].empty()) {
            std::shared_ptr<saba::MMDModel> model;
            if(pmx){auto p=std::make_shared<saba::PMXModel>();p->SetParallelUpdateHint(1);require(p->Load(staged.u8string(),output.u8string()),"Saba could not construct PMX skeleton");model=p;}
            else {auto p=std::make_shared<saba::PMDModel>();require(p->Load(staged.u8string(),output.u8string()),"Saba could not construct PMD skeleton");model=p;}
            require(model->GetNodeManager()->GetNodeCount()==data.bones.size(),"Evaluator bone count differs from converted skin");
            std::set<std::string> names={"mmd_neutral"};size_t budget=0;
            for(const auto& motion:options["motions"])animation(glb,data,model,motion,rate,maxFrames,budget,meta,names);
        }
    }
    glb.write(output/"model.glb");
    std::ofstream file(output/"metadata.json",std::ios::binary|std::ios::trunc);require(bool(file),"Cannot create metadata.json");
    file<<meta.dump(2);file.close();require(bool(file),"Writing metadata.json failed");
}
}

namespace { thread_local std::string lastError;std::mutex converterMutex; }

extern "C" RWMD_API uint32_t rwmd_abi_version(void){return 0x00010000u;}
extern "C" RWMD_API const char* rwmd_last_error(void){return lastError.c_str();}
extern "C" RWMD_API int rwmd_convert(const void* bytes,size_t size,const char* source,const char* output,const char* options) {
    lastError.clear();
    try {std::lock_guard<std::mutex> lock(converterMutex);rwmd::convert(bytes,size,source,output,options);return 0;}
    catch(const std::exception& error){lastError=error.what();return 1;}
    catch(...){lastError="Unknown native MMD conversion failure";return 2;}
}
