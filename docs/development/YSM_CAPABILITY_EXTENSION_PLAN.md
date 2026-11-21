# YSM 能力评估与 ysmprocessor 扩展执行文档

> 历史调研和原型记录。当前实现状态、RWYM 缓存和已验证范围以
> [YSM_RIGWORKS_PIPELINE.md](YSM_RIGWORKS_PIPELINE.md) 为准；后续优先级见
> [YSM_PRIORITY_TODO.md](YSM_PRIORITY_TODO.md)。本页保留两条分支的研究证据，不作为当前支持清单。

> 目标项目：`H:\MinecraftMods\RigWorks` + 子模块 `AssimpLoader`
> 参考实现：`YesSteveModel`（Java 运行时）、`YesSteveModel-Native`（C++）、`blockbench`（FBX/GLTF 导出参考）
> 日期：2026-11-19

---

## 第一部分：YSM 模型到底能做什么

### 1. 眨眼 / 闭一只眼的真相（先回答你的具体问题）

**没有"眨眼功能开关"，也没有专门的服务端预设**。它是三样东西拼出来的：

| 组成 | 位置 | 说明 |
|---|---|---|
| 内置布尔查询 `ysm.is_close_eyes` | `YesSteveModel/src/main/java/com/elfmcys/ysm/expression/host/builtin/EntityBuiltins.java:36-47` | 睡眠时恒 true；否则 `(renderTicks + \|uuid.leastSignificantBits\| % 10) % 90 ∈ (85,90)` → **每 90 tick（4.5s）眨一次、持续约 5 tick（0.25s）**，按 UUID 相位错开 |
| 骨骼通道用 Molang 表达式驱动 | `assets/ysm/builtin/wine_fox/06_hanfu/animations/main.animation.json:12252-12267` | `LeftEyelid/RightEyelid.scale = "ysm.is_close_eyes ? 0 : 1"`、眉毛 position 同理 |
| 闭一只眼（wink） | `.../18_wedding/animations/main.animation.json:13591-13600` + `functions/更随机的眨眼@player_ctrl_pre_parallel_3.molang:9-23` | 一根 `wink` 骨骼关键帧 + coded controller 用户函数：`math.random_integer(1600,2000)`、`ctrl.set_animation('BlinkEye'/'BlinkTwo')` |

而**持弓是另一套独立状态机**：`assets/ysm/builtin/misc/4_default_controllers/controller/main_controllers.json:18-150`，`ctrl.hold('offhand',':bow')` → `bow` 状态，`ctrl.use(...)` → `bow_use`，含 `bow_idle`/`bow_shoot`；物品分类 `:bow` 来自 `InnerClassify.java:48`（BowItem / ModItemTags.BOWS）。

**结论**：所谓"持弓时闭一只眼"= 作者把 `ctrl.hold(':bow')` 状态条件和 `ysm.is_close_eyes`/wink 动画组合起来。所以我们要移植的不是"眨眼功能"，而是 **(a) 内置查询变量、(b) 骨骼通道上的表达式求值、(c) coded controller 用户函数分发** 这三块通用能力。

### 2. YSM 作者可表达的完整能力集

| 类别 | 能力 | 关键位置 |
|---|---|---|
| 骨骼动画 | rotation/position/scale 关键帧；线性/catmullrom；关键帧值可为 Molang 字符串；动画级 `loop/anim_time_update/blend_weight/override_previous_animation` | `pojo/animation/Animation.java:25-47`、`BoneKeyFrameList.java:53-87`、`EasingType.java:8-26` |
| 表达式 | C 风格表达式、三元、`??`、`loop/for_each`、用户函数 `fn.*`、事件钩子（init/update/sync/defer/coded-controller）、AOT 编译执行分离 | `MolangSourceParser.java:465-467,296-309`、`expression/aot/`、`ProgramRole.java:20-66` |
| 内置查询 | 60+ 个 `q.*`/`ysm.*`/`ctrl.*`；实体/世界/输入/物品/装甲/天气/其他 mod 门控；完整符号表 | `builtin-capability-oracle.json:2-993`、`EntityBuiltins.java:21`、`ControlBuiltins.java:22-160`、`ModelBuiltins.java:17-173` |
| 变量 | `v.*` 实例持久、`t.*` 瞬时、`v.roaming.*` 网络同步（≤16 floats，FULL/DELTA） | `state-inputs-and-sync.md:38-60`、`MolangEventCatalog.java:26-38` |
| 状态控制器 | Bedrock state machine（条件转换、多动画混合、`blend_via_shortest_path`、`on_entry/on_exit`、状态音效）、coded controller（用户函数返回 CONTINUE/STOP/PAUSE/BYPASS）、Hybrid、最深 5 层子控制器 | `BedrockAnimationController.java:231-341,448-698`、`CodedAnimationController.java:66-176`、`State.java:9-25`、`BlendTransition.java:15-39` |
| Locator | 13 个玩家 locators（Head/LeftRightHand/Backpack/Elytra/Pistol/Rifle/Waist/Shoulder/Blade/Sheath）+ 第一人称/载具/投射物；注册事件可扩展 | `PlayerLocator.java:13-25`、`GeoLocator.java:6-23`、`RegisterModelLocatorEvent.java:10-23` |
| 时间轴效果 | 关键帧 Molang 指令、音效（`ysm.play_sound/stop_sound/stop_all_sounds`）、粒子（`ysm.particle/abs_particle`）、骨骼 glow/transparency/color、一阶/二阶物理 | `Animation.java:119-181`、`ModelBuiltins.java:80-130,205-240`、`InstructionKeyFrameExecutor` |
| 渲染目标 | manifest 按实体 match 绑定 player / fp_arm / 投射物 / 载具，按 mod capability 门控（TACZ、Curios、ParCool 等） | `builtin/default/ysm.json:66-170`、`OptionalCapability.java:6-12` |
| 配置表单 | 作者定义 GUI 控件绑定 `v.roaming.*` | `ConfigForms.java:5-13`、`ExtraAnimationButton.java` |

### 3. 对照：AssimpLoader / RigWorks 已有什么、缺什么

| 能力 | 现状 | 缺口 |
|---|---|---|
| 层覆盖（眨眼可直接用） | **已有** `AnimationState.setLayer(id, clip, weight, additive, mask)`（`:327`）、`AnimationPoseLayer`（`:33`）、生产用法 `PlayerAnimationController.java:133` | 无 |
| 状态机 | `AnimationStateMachine.java:18-60`，但**只有名字+时长，无条件、无变量** | 条件表达式、混合过渡 |
| 表达式/变量 | **完全没有** Molang/表达式求值；`PlayerAnimationController.java:263` 是硬编码 Java 优先级级联 | 整套表达式运行时 + 变量存储 |
| Locator | 有等价物：`ModelInstance.Attachment`（`:15`）、`WorkbenchDocument.Socket`（`:96`）、`CharacterEquipmentDefinition.Mount`（`:21`） | 命名语义、动态 pose 查询已够用 |
| 粒子 | 有 `AttachmentParticleEmitter.Sink`（`:7`）+ `MinecraftAttachmentParticles.java:16` | 无 |
| 音效 | **完全没有**（两个 src/main 树里无 sound hook） | 关键帧音效回调 |
| 变量存储 | `CharacterScriptRuntime.Session.state` `Map<String,Object>`（`:108`）、`CharacterActionFrame.state()`（`:44`） | 可作为 `v.*` 的落点，但缺表达式绑定 |
| 脚本扩展 | KubeJS `CharacterScriptApi.register`（`:17`）、Rhino avatar session（`:167`） | 可映射 coded controller 函数 |

---

## 第二部分：ysmprocessor.dll 设计

### 4. 设计原则

1. **重新建库，不复用 YSM 的 JNI 运行时**。YSM-Native 全部函数通过 `JNI_OnLoad` + `RegisterNatives` 动态注册（`modules/lib-jni/src/init.cc:11`、`modules/java/src/entry.cc:153`），导出符号仅 `JNI_OnLoad/JNI_OnUnload/YSM_*`（`version-map.txt:3`），`YSM_EXPORT` 从未用于函数。JNA 用不了，必须写新的 C ABI。
2. **C ABI 只用定长标量、不透明句柄、`const char*`**；C++ 异常 / `absl::Status` / STL 容器不得跨边界（YSM 自己的 `docs/development_guide_cn.md:42,86` 就是这个规则）。
3. **不搬 mimalloc 常驻线程和全局 `ParallelExecutor::Get()`**（`parallel_executor.cc:159-165`，明标 NOT thread-safe）；用调用方线程同步执行。
4. **只搬真正有用且轻的模块**；重编解码（AVIF/dav1d/svt-av1）先不搬。

### 5. 分层与职责

```
┌───────────────────────────────────────────────────────────┐
│ RigWorks / AssimpLoader (Java)                            │
│  YsmToGlbConverter + MolangRuntime(Java) + 扩展点适配     │
├───────────────────────────────────────────────────────────┤
│ ysmprocessor.dll (新 C ABI, JNA 调用)                     │
│  容器/压缩/哈希/legacy 导入/图像解码 (可选)               │
├───────────────────────────────────────────────────────────┤
│ 现有 LWJGL Assimp native (已在 AssimpLoader 中)           │
│  aiImportFileFromMemory → AssimpScene                     │
└───────────────────────────────────────────────────────────┘
```

### 6. C ABI 函数清单（建议）

```c
// 生命周期
int32_t ysp_init(void);                       // 0 = OK；一次性
void    ysp_shutdown(void);
const char* ysp_version(void);                // "1.0.0"

// 哈希 / 压缩（自包含，先做）
int32_t ysp_blake3(const uint8_t* data, int32_t len, uint8_t* out32);
int32_t ysp_zstd_decompress(const uint8_t* src, int32_t src_len,
                            uint8_t* dst, int32_t dst_cap, int32_t* out_len);

// 容器（.mxc）：从字节打开，逐 chunk 读取；chunk 已解密/解压
void*   ysp_container_open(const uint8_t* data, int32_t len);
int32_t ysp_container_chunk_count(void* c);
int32_t ysp_container_chunk_info(void* c, int32_t idx,
                                 char* name_out, int32_t name_cap,
                                 int32_t* payload_len);
int32_t ysp_container_read_chunk(void* c, int32_t idx,
                                 uint8_t* dst, int32_t dst_cap, int32_t* out_len);
void    ysp_container_close(void* c);

// legacy .ysm → protobuf 字节（可选，重依赖；见 §7 风险）
void*   ysp_legacy_import(const uint8_t* data, int32_t len);
int32_t ysp_legacy_payload_count(void* r);
int32_t ysp_legacy_payload(void* r, int32_t idx,
                           int32_t* kind_out, uint8_t* dst, int32_t cap, int32_t* out_len);
void    ysp_legacy_close(void* r);

// 图像解码（可选，返回 RGBA）
int32_t ysp_image_decode(const uint8_t* data, int32_t len,
                         int32_t* w, int32_t* h, uint8_t** rgba_out);
void    ysp_image_free(uint8_t* rgba);

// 错误
const char* ysp_last_error(void);
```

Java 侧用 JNA `Native.load("ysmprocessor", YsmProcessor.class)`，所有 `Pointer`/`byte[]` 参数直接映射。

### 7. 模块搬运决策

| 模块 | 是否搬 | 理由 / 依赖 | 位置 |
|---|---|---|---|
| Blake3 | **搬** | 自包含，32 字节摘要 | `codec/include/codec/blake3.h:8-26` |
| Zstd | **搬** | 纯 buffer in/out | `docs/infrastructure/codec_cn.md:17-21` |
| Archive (zip/7z) | **搬** | `Archive<Zip>/<SevenZip>` 模板，无 JNI | `codec/include/codec/archive.h:9-85` |
| Proto schema | **搬（header-only）** | struct_pb + yalantinglibs，无生成 .cc | `proto/CMakeLists.txt:1-8`、`ModelData.proto.h:34`、`geo_model.proto.h:46`、`animation.proto.h:74`、`animation_controller.proto.h:26` |
| Image decode | **延后** | 轻量 PNG/JPEG/WebP 可搬；**AVIF 拖入 dav1d/svt-av1/yuv/svt**（`third-party/avif/conandata.yml:10-17`），是先搬的重大成本 | `codec/include/codec/image.h:6-43` |
| Audio (opus/vorbis) | **延后** | 有严格 Feed/EndInput 协议；RigWorks 无音效管线时无用 | `codec/include/codec/audio.h:18-34` |
| Legacy `.ysm` v3 | **可选/延后** | `v3::Import` 无 JNI 类型、最干净（`legacy/include/legacy/v3.h:69-70`），但 `modules/legacy/CMakeLists.txt:19-25` 拖入整个 codec（含 AVIF）+ cryptopp | 同上 |
| Bake/Extract/Render | **不搬** | 与 RigWorks 已有 GPU skinning 重叠；依赖 cglm/yalantinglibs + SIMD 强耦合 | `docs/infrastructure/gfx_cn.md:7-27` |
| JNI 注册层 | **不搬** | JVM 全局状态（`entry.cc:11`、`class.cc:8-9`），改成 C ABI | — |

### 8. 平台与构建

- 用 CMake + C++20/23，输出 `ysmprocessor.dll` / `libysmprocessor.so` / `libysmprocessor.dylib`。
- 目标：`windows-x86_64`、`linux-x86_64`、`macos-x86_64`、`macos-aarch64`（先 4 个，必要时加 win-arm64/linux-arm64）。可直接沿用你 FabriKNative 的 `build_all.bat` + Zig 交叉编译流程。
- ABI 版本用导出数据符号 `YSM_PROCESSOR_ABI_1_0`（照 `abi_marker.cc.in:2-7` 思路），Java 侧 JNA 校验后再加载。
- **不要**复制 YSM 的 "YSM Native Holder" 常驻守护线程（`NativeLibUtil.java:164-189`，因 mimalloc）；本库同步、无全局工作线程。

---

## 第三部分：YSM 能力 → RigWorks/AssimpLoader 映射与执行

### 9. 转换主链（复用已验证路径）

```
YSM .mxc / JSON
 → ysmprocessor: container_open + read_chunk (zstd+blake3)
 → Java: protobuf 解析 → Bedrock-to-GLB 转换
 → AssimpModelLoader.loadModelFromMemory("model.glb", bytes, DYNAMIC)  (:73)
 → AssimpScene
 → Workbench / CharacterProfile / 现有渲染
```

GLB 里 cube → mesh + `JOINTS_0=[boneIdx,0,0,0]`, `WEIGHTS_0=[1,0,0,0]`——照 Blockbench `gltf.js:203-204`（`buildSkinnedMeshFromGroup`）的做法。这部分之前已详述，不重复。

### 10. 能力逐项落地

#### 10.1 眨眼 / 闭眼（最快见效）
- 落点：`AnimationState.setLayer("blink", blinkClip, weight, true, eyelidMask)`（`:327`）。`AnimationPoseLayer`（`:33`）已支持独立计时 + mask。
- 用户例子里的"持弓闭眼"= RigWorks 现有 `CharacterHandContacts`/`CharacterEquipmentRule.useAnimations`（`CharacterEquipmentRule.java:21` 已含 `bow`）提供持弓状态；再叠加 blink layer。
- 新增 `BlinkQuery`：计算 `is_close_eyes`，照 `EntityBuiltins.java:36-47` 的 90 tick 周期 + UUID 相位（RigWorks 用 `player.getUUID().getLeastSignificantBits()`）。
- 眨眼 clip 来源：YSM 模型的 eyelid/wink 骨骼关键帧，转换时单独抽出成 `blink` clip 注册到 `CharacterProfile.ANIMATIONS`（`CharacterProfile.java:21` 的 30 个 ACTION 可扩或新增 slot）。

#### 10.2 表达式 / 变量运行时（最大块，分阶段）
- 新包 `cn.minerealms.rigworks.expression`（或放 AssimpLoader）：移植 Molang **子集**（C 风格三元/比较/算术、`v.*`/`t.*`/`q.*`/`ysm.*`、`math.*`）；用 Java 实现 AOT 编译为 lambda（照 `expression/aot/` 的 compile/execute 分离）。
- 变量存储：复用 `CharacterScriptRuntime.Session.state`（`Map<String,Object>`，`CharacterScriptRuntime.java:108`）承载 `v.*`；`v.roaming.*` 走 `CharacterVisualConfig`/`CharacterVisualSharing` 的 schema 有界同步（128 KiB 上限，`CHARACTER_CONFIGURATION_SYNC.md:64-76`）。
- 内置查询：新增 `RigWorksBuiltinQueries`，从 `PlayerVisualInput`（`MinecraftPlayerInputSampler.java:52-61`）、`PlayerActionState`（`:7-21`）提供 `is_sneaking/is_swimming/on_ground/health/held_item` 等；只实现实际模型用到的子集。
- **YSM 全部 60+ builtin 不必一次搬**：先做 oracle 表 `builtin-capability-oracle.json` 中被内置模型实际引用的那些。

#### 10.3 动画控制器 / 条件切换
- 扩展 `AnimationStateMachine.java:18-60`：给 `StateBinding`（`:25`）和 `addTransition`（`:53`）增加可选的 `BooleanSupplier condition` + `blendSeconds`（已有）。`request()`（`:60`）在切换前求值条件。
- 混合：复用 `AnimationState.setLayer` + `setLayerWeight` 做状态间交叉淡化（比照 `BedrockAnimationController.java:448-698` 的 BlendBoneAnimationQueue）。
- coded controller：映射到 KubeJS/Rhino 回调——`CharacterScriptApi.register`（`:17`）已能注册 per-frame `Consumer<CharacterActionFrame>`；函数返回 CONTINUE/STOP/PAUSE/BYPASS（`ControlReadBuiltins.java:30-31`）映射成 `CharacterActionFrame` 上的命令。
- **不要**直接搬 YSM 的 `BedrockAnimationController`（它强绑定 GeckoLib `AnimationPlayer`/`BoneAnimationQueue`）。

#### 10.4 Locator / 挂点
- YSM `PlayerLocator`（`:13-25`）→ `CharacterEquipmentDefinition.sockets`（`Map<String,Mount>`，`:23`）+ `socket(name)`（`:34`）；动态 pose 帧用 `PoseSnapshot.getAttachmentTransform(name, localOffset)`（`:159`）或 `CharacterAttachmentFrames.frame()`（`:32`）。
- 第一人称 arm locator → `CharacterFirstPersonArms.java:44`（已有）、`ViewLocator` → head socket。
- 工作编辑器：`WorkbenchDocument.Socket`（`:96`）+ `WorkbenchRuntime`（`:82`）已支持编辑。

#### 10.5 时间轴音效 / 粒子
- 粒子：已有 `AttachmentParticleEmitter.Sink`（`:7`）+ `MinecraftAttachmentParticles`（`:16`）——把 YSM `ysm.particle` 指令转成 emitter 事件即可。
- 音效：**需要新增**。在 `AnimationState` 的 stage 求值后加一个 `AnimationEventSink`（`onSound(id,volume,pitch)`），由客户端 `Level.playSound` 实现；对应 YSM `EventKeyFrame`（`Animation.java:156-181`）和 `ysm.play_sound/stop_sound`（`ModelBuiltins.java:80-118`）。
- 骨骼 glow/transparency/color：映射到材质 uniform（AssimpLoader 已有 `ShaderScriptApi.define/uniform`，`ShaderScriptApi.java:7-9`）；这批可延后。

#### 10.6 一阶/二阶物理（YSM 的 `ysm.first_order/second_order`）
- 直接映射到 AssimpLoader 已有的 `SpringBoneModifier.java:17`（SPRING 类别、固定步长、胶囊投影）——这是现成的落点，只需把 YSM 参数转成 spring 配置。

---

## 第四部分：执行阶段与验收

### 阶段 0：基线（1-2 天）
- 准备样本：Builtin `wine_fox/06_hanfu`（眨眼）、`18_wedding`（wink + coded controller）、`misc/4_default_controllers`（持弓）。
- 建立 JFR 基线：确认当前 `LocalPlayerCharacter`/`ClientAnimationManager.tick` 的帧时与分配。

### 阶段 1：ysmprocessor.dll（3-5 天）
- 实现 §6 的 C ABI：init/version/blake3/zstd/container_*。
- JNA 接口 + ABI 符号校验；Windows x64 先通，其余 3 平台交叉编译。
- **验收**：读一个真实 `.mxc`，列出 chunk，Blake3 校验通过，Zstd 解压出 protobuf 字节。

### 阶段 2：GLB 转换 + 模型导入（3-5 天）
- `YsmToGlbConverter`（纯 Java）：protobuf → GLB（bone/node/cube/skin/texture/animation）。
- 接入 `ModelWorkbenchScreen.requestImport("rigworks")`（`:100,193-208`）与 `RigWorksWorkbenchExtension.initialize`（`:39-79`）。
- **验收**：在 RigWorks 工作台导入一个 wine_fox 模型，骨骼/贴图/动画正确，Save/Apply 后游戏内可见。

### 阶段 3：眨眼与层覆盖（2-3 天）
- `BlinkQuery` + blink layer + eyelid mask；持弓状态从 `CharacterEquipmentRule` 取。
- **验收**：持弓时按 90 tick 周期眨眼，wink 模型单眼闭合；不影响服务器碰撞。

### 阶段 4：表达式运行时 + 变量（5-10 天，最大块）
- Molang 子集 parser/compiler/evaluator；`v.*`/`t.*`/`q.*`；内置查询；事件钩子。
- **验收**：`wine_fox/06_hanfu` 的 `"ysm.is_close_eyes ? 0 : 1"` 通道正确求值；`18_wedding` 的 coded controller 随机眨眼跑通。

### 阶段 5：控制器 / 音效 / locator / spring（5-10 天）
- 条件状态机、混合过渡、coded controller→KubeJS；音效 sink；locator→socket；一阶/二阶→SpringBoneModifier。
- **验收**：持弓状态机（`bow_idle`/`bow_shoot`）切换正确；粒子/音效按时间轴触发。

### 阶段 6：可选扩展
- legacy `.ysm` v3 导入（需搬 codec 全链，含 AVIF 或做裁剪）；image/audio 解码；更多内置查询；`v.roaming.*` 同步。

---

## 第五部分：红线与风险

1. **客户端视觉边界**：所有物理/动画/表达式不得写回服务器 AABB/位置/伤害（`CUSTOM_PLAYER_GAP_ASSESSMENT:89`、`CLIENT_VISUAL_AUTHORITY:25-35`）。
2. **native 不读 MC 世界**：ysmprocessor 只处理字节；实体状态由 Java 计算后传入（照 `EXPANSION_ARCHITECTURE:31,93`）。
3. **网络有界**：`v.roaming.*` 必须走 schema 有界同步（≤16 floats，配置 128 KiB / 包 512 KiB）；不传 FBX/贴图/native 二进制（`CHARACTER_CONFIGURATION_SYNC:25-76`）。
4. **平台矩阵**：ysmprocessor 需按两个 mod 节点（Forge Java17 / NeoForge Java21）分别打包，各自带四/六平台 native（`build.gradle:236-244,356-365`）。
5. **ABI 不稳定**：YSM 的 `NATIVE/CONTAINER/MODEL_SCHEMA` 都是 `0.1.x-unstable`（`AbiVersion.java:4-8`）。ysmprocessor 必须自己固定版本并做符号校验；不要把 YSM 的版本当契约。
6. **Molang 全量移植成本**：60+ builtin + AOT + 事件系统是最重的一块。强烈建议按模型实际引用裁剪，不要追求 100% 兼容。
7. **AVIF 依赖地雷**：legacy 导入会把 AV1 编解码栈拖进来（`third-party/avif/conandata.yml:10-17`）；要么裁剪 legacy 路径，要么单独做轻量解码。
8. **Blockbench 导出细节对照**：FBX 的轴固定 Y-up/Z-front（`fbx.ts:163-170`）、`export_scale=(scale||16)/100`（`:54`）、UV V 翻转（`:721-734`）——转 GLB 时也要按同样规则，否则模型倒置或贴图错位。

---

## 结论

- **眨眼不是预设功能**，是 `ysm.is_close_eyes` 布尔查询 + 骨骼通道 Molang 表达式 + coded controller 的组合；持弓是独立状态机。要移植的是这三块通用机制。
- **ysmprocessor.dll 只搬轻且自包含的部分**（容器/zstd/blake3/archive/proto/可选 image），不搬 JNI 注册、mimalloc 常驻线程、bake/render、AVIF 重栈。
- **现有 RigWorks 已有 blink 落点**（layer API）、spring 落点、粒子 sink、socket/locator、脚本扩展；真正缺口是 **表达式/变量运行时 + 条件状态机 + 音效 sink**。
- 路线优先级：**阶段 1（native 容器）→ 阶段 2（GLB 转换）→ 阶段 3（眨眼）→ 阶段 4（表达式）**，每阶段都有明确验收，可独立回滚。
## Historical Capability Probe

> Historical probe instructions. Current conversion is RigWorks-owned C++ -> cached RWYM,
> not the former AssimpLoader YSM bridge. See [YSM_RIGWORKS_PIPELINE.md](YSM_RIGWORKS_PIPELINE.md).

The capability SPI and its wire-up are described in [YSM_INTERFACE_CONTRACT.md](YSM_INTERFACE_CONTRACT.md).
This page records the headless probes used to validate them without driving the GUI.

## Pure-Java signature probe (no Minecraft runtime)

`YsmFormatProbe` (test source set) reads a `.ysm` file and asks `YsmApi.bridge()` what it detects.
It reports `bridgeAvailable`, `isYsm`, the leading bytes, and either a successful `toGlb` or the
`UnsupportedOperationException` raised while the AssimpLoader YSM API is still unmerged.

```
javac -d build/ysm-probe -cp "H:\MinecraftMods\AssimpLoader-ysm-import\versions\1.21.1-neoforge\build\classes\java\main" src/main/java/cn/minerealms/rigworks/character/importing/YsmApi.java src/main/java/cn/minerealms/rigworks/character/importing/ReflectiveBridge.java src/test/java/cn/minerealms/rigworks/character/importing/YsmFormatProbe.java
java -cp "build/ysm-probe;H:\MinecraftMods\AssimpLoader-ysm-import\versions\1.21.1-neoforge\build\classes\java\main" cn.minerealms.rigworks.character.importing.YsmFormatProbe "H:\MinecraftMods\OpenYSM\forge\run\config\yes_steve_model\custom\Flandre_Scarlet.ysm"
```

The same logic runs as a JUnit probe:

```
.\gradlew.bat :1.21.1-neoforge:test --tests cn.minerealms.rigworks.character.importing.YsmFormatProbeTest -PclientYsmAsset="H:\MinecraftMods\OpenYSM\forge\run\config\yes_steve_model\custom\Flandre_Scarlet.ysm" -PassimpLoaderDir=../AssimpLoader-ysm-import --console=plain
```

## Headless import smoke in a real 1.21.1 client

`YsmImportSmokeTest` is opt-in and client-only. It runs on the client tick thread, reads the file at
`rigworks.ysm.asset`, and calls `AvatarImportRegistry.get().load(...)` followed by `.apply(...)`
through the YSM handler — no file picker, no server state. It writes
`rigworks-ysm-smoke-result.json` into the run directory with `success`, `error`, `bridgeAvailable`,
`detectedAsYsm`, `meshCount`, `animationCount`, `boneCount` and `applied`.

From the repository root:

```
.\gradlew.bat :1.21.1-neoforge:runClient -PclientYsmSmoke -PclientYsmAsset="H:\MinecraftMods\OpenYSM\forge\run\config\yes_steve_model\custom\Flandre_Scarlet.ysm" -PassimpLoaderDir=../AssimpLoader-ysm-import --console=plain
```

`-PclientYsmSmoke` is the boolean enable switch. The probe fires on the client tick once a playable
level is present, so either enter a world after launch or pass a world name as the property value
(for example `-PclientYsmSmoke=Ysm_Import_Smoke`) to open it automatically. The `runClient` task
checks the result file when it ends; the same check is available on demand as
`:1.21.1-neoforge:checkYsmSmokeResult`. Leave the client open until the result file appears.
