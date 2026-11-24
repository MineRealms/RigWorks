# 从零理解 RigWorks 动画

本文面向第一次接触骨骼动画的玩家和美术人员，解释当前项目如何把 Minecraft 动作、FBX 自带动画、KubeJS、IK 和视觉物理组合起来。

先记住一句话：**模型提供可变形的身体，动画提供姿态，RigWorks 根据玩家状态选择和组合姿态，AssimpLoader 负责底层动画求值与渲染。** 一个 FBX 不需要自带全部 Minecraft 动作，才能成为可活动的玩家模型。

操作 API 和完整 Demo 见 [KubeJS 动画实用指南](KUBEJS_ANIMATION_GUIDE.md)。本文解释概念和选择方法；具体配置以当前源码和目标模型为准。

## 1. 一个角色为什么能动

| 概念 | 通俗解释 | 在角色中的例子 |
| --- | --- | --- |
| 网格 Mesh | 看得见的顶点和三角形，是角色表面 | 脸、衣服、头发 |
| 骨骼 Skeleton / Rig | 带父子关系的变换节点 | 胸部带着肩膀，肩膀带着手臂 |
| 蒙皮权重 Skin Weights | 指定每个顶点跟随哪些骨骼，以及跟随多少 | 肘部顶点同时受上臂和前臂影响 |
| 绑定姿态 Bind Pose | 网格与骨架建立蒙皮关系时的参考状态 | 制作时的 A Pose 或 T Pose |
| 关键帧 Keyframe | 某个时刻的骨骼位置、旋转或缩放 | 第 0 帧抬脚，第 10 帧落脚 |
| 动画片段 Clip | 一段可单独播放的关键帧序列 | `Idle`、`WalkForward`、`Attack01` |
| 程序化动画 | 代码根据状态算出姿态 | 头跟随视线，跳起时弯膝 |
| 重定向 Retargeting | 把一种骨架的动作转换到另一种骨架 | 将原版玩家抬臂转换成自定义角色抬臂 |
| IK，逆向运动学 | 给出手或脚的目标位置，反求中间关节怎么弯 | 手抵达枪的副握把，肘部随之弯曲 |
| 烘焙 Bake | 将约束、IK 或其他计算结果记录成关键帧 | 把 Blender 控制器算出的走路记录到变形骨 |

没有蒙皮权重的衣服，不会因为骨架动了就自动变形。只有骨架而没有动画片段的角色，仍然可以由原版姿态或程序化代码驱动。

### 一帧画面大致经历什么

```text
Minecraft 的真实玩家状态
  移动速度、是否着地、游泳、挥击、使用物品、视线等
        |
        v
RigWorks 选择动作来源
  原版姿态 / FBX 片段 / 脚本控制
        |
        v
AssimpLoader 动画求值
  关键帧插值、姿态混合、骨骼层级、IK、已配置的视觉物理
        |
        v
完成的骨骼姿态与装备挂载变换
        |
        v
蒙皮与绘制
```

这是便于理解的概览。引擎会按处理器类别安排实际求值阶段，不能仅凭 JS 调用顺序推断最终覆盖关系。KubeJS 不需要逐个移动模型顶点。

## 2. 先选择动作模式

角色有四种模式，配置默认值是 `VANILLA`。这些模式是不同入口，并非始终同时运行。YSM 源角色优先使用自己的 controller。

| 模式 | 当前行为 | 你需要提供什么 |
| --- | --- | --- |
| `VANILLA` | 采样 Minecraft 原版玩家姿态，再重定向到自定义骨架 | 已蒙皮的模型、正确的人体映射和必要的轴向校准 |
| `CLIPS` | 根据真实玩家状态选择导入片段，组合上半身动作，缺少特殊片段时使用原版姿态 | 模型中的真实动画片段及动作映射；人体映射仍用于回退和持物 |
| `SCRIPTED` | 执行选中的本地 KubeJS 控制器或角色专用动作包，由它决定动画组合 | 控制器，以及它使用的片段、映射和 solver 数据 |
| `DEFAULT` | 内置 25 段 VMD 来源求解数据，纯 Java 按人体 role 重定向 | 正确的人体映射与轴向标定，目标模型不需要同名动画片段；见 [DEFAULT](DEFAULT_MOTION_PRESET.md) |

可以在角色设置中选择，或者使用：

```text
/assimploader character mode vanilla
/assimploader character mode clips
/assimploader character mode scripted
```

选择模式不会替你制作动画。`CLIPS` 不会执行全局 KubeJS 玩家控制器；有效的 `SCRIPTED` 控制器也需要自己请求原版基线或明确提供替代动作。脚本缺失或执行失败时，集成路径会尝试回退原版姿态。

当前入口见 [LocalPlayerCharacter](../../src/main/java/cn/minerealms/rigworks/character/client/LocalPlayerCharacter.java)，默认配置见 [RigWorksConfig](../../src/main/java/cn/minerealms/rigworks/RigWorksConfig.java)。

## 3. 原版动作怎样驱动另一套骨架

### 3.1 采样实际姿态和状态

系统会调用 Minecraft 原版玩家模型的准备、姿态计算和实体旋转逻辑，因此可以沿用原版对持物、使用物品、游泳、鞘翅等状态的处理。

动作状态还会读取真实位移、垂直速度、是否着地和已经发生的挥击。跳跃观测区分着地、起跳、上升、顶点、下落和落地，连续移动跳跃不会仅凭“空格一直按着”来决定腿部姿态。远程玩家也可以根据观测到的状态求值。

目前动作路径已接入下列状态；实际观感仍取决于骨架和配置：

| 类型 | 状态 |
| --- | --- |
| 地面移动 | 待机、行走、冲刺、侧移、倒退、蹲伏、蹲行 |
| 腾空与特殊移动 | 跳跃、下落、创造飞行、鞘翅、爬梯、激流 |
| 水与低矮姿态 | 游泳、水中停留、陆地爬行、熔岩状态 |
| 实体状态 | 骑乘、睡觉、受伤、冰冻、死亡 |
| 手部动作 | 物理左/右手持物、挥击、吃喝及原版物品使用姿态 |

对于多关节骨架，驱动器还增加了有限的肘膝动作，例如腾空弯膝、爬梯和进食。它们是视觉动画近似，不会改变玩家重力、骨骼长度或服务器碰撞箱。

源码入口：[MinecraftPlayerInputSampler](../../src/main/java/cn/minerealms/rigworks/character/client/MinecraftPlayerInputSampler.java)、[VanillaPlayerPoseDriver](../../src/main/java/cn/minerealms/rigworks/character/client/VanillaPlayerPoseDriver.java)。

### 3.2 告诉系统“哪根是手，哪根是腿”

FBX 中可能叫 `Hand.R`、`mixamorig:RightHand` 或其他名字。RigWorks 使用 `profile.json` 的 `humanoid` 表，把语义角色对应到实际节点。

下面是映射片段，需要放入完整 profile 的 `humanoid` 对象内，并换成模型真实名称：

```json
{
  "right_upper_arm": "UpperArm.R",
  "right_forearm": "Forearm.R",
  "right_hand": "Hand.R",
  "right_thigh": "Thigh.R",
  "right_shin": "Shin.R",
  "right_foot": "Foot.R"
}
```

系统还需要知道原始骨轴、模型前方、绑定姿态与原版中立姿态的差别。`referenceRotation` 和 `nativeRotation` 用来校准这些关系；它们使用 XYZW 四元数，不是 XYZ 角度。完整说明见 [自定义骨架校准](CUSTOM_RIG_NATIVE_CALIBRATION.md)。

**骨骼名称正确不等于已经校准。** A Pose 的角色可能需要先把手臂中立方向校准为自然下垂；高跟鞋的脚轴也不能直接套用平底鞋参数。重定向会考虑绑定姿态和参考坐标，不保证任意非人形或任意复杂骨架直接导入就能自然活动。

相关实现：[VanillaPoseRetargeter](../../src/main/java/cn/minerealms/rigworks/character/client/VanillaPoseRetargeter.java)。

## 4. FBX 自带动画怎样接到 Minecraft 动作上

### 4.1 文件里有片段，不代表系统知道片段的用途

例如 FBX 有 `Idle`、`WalkForward` 和 `Sprint`，RigWorks 需要将“玩家正在走路”的意图映射到 `WalkForward`。动画名称区分大小写。

下面是完整、可解析的 profile 示例，指向仓库内的实际测试资产，不依赖额外 solver 或 physics 文件：

```json
{
  "version": 1,
  "model": "rigworks:models/character/block_player/block_player.fbx",
  "scale": 1,
  "modelYawDegrees": 0,
  "humanoid": {
    "hips": "Hips", "spine": "Spine", "chest": "Chest", "neck": "Neck", "head": "Head",
    "left_upper_arm": "UpperArm.L", "left_forearm": "Forearm.L", "left_hand": "Hand.L",
    "right_upper_arm": "UpperArm.R", "right_forearm": "Forearm.R", "right_hand": "Hand.R",
    "left_thigh": "Thigh.L", "left_shin": "Shin.L", "left_foot": "Foot.L",
    "right_thigh": "Thigh.R", "right_shin": "Shin.R", "right_foot": "Foot.R"
  },
  "animations": {
    "idle": "idle",
    "walk": "walk",
    "run": "run",
    "crouch": "crouch",
    "crouch_walk": "crouch_walk",
    "jump": "jump",
    "fall": "fall",
    "swim": "swim",
    "elytra": "elytra",
    "swing": "swing"
  },
  "blendSeconds": 0.15,
  "timing": {
    "movementSync": true,
    "swingSync": true,
    "walkStride": 2.4,
    "runStride": 3.2,
    "crouchStride": 1.4,
    "minRate": 0.25,
    "maxRate": 12
  },
  "headLook": {"enabled": true, "forward": [0, 0, 1], "maxYaw": 75, "maxPitch": 60}
}
```

实际项目应从工作台或导入配置继续编辑，不必照抄这套测试骨架。完整内置例子见 [block_player/profile.json](../../src/main/resources/assets/rigworks/models/character/block_player/profile.json)。测试动画用于验证链路，不能代表高质量成品动作。

`animations` 左边是 RigWorks 动作意图，右边通常是实际导入片段名，也可以引用 AssimpLoader 已定义的动画状态绑定。没有显式映射时会尝试使用同名意图，例如 `walk`。在脚本里调用 `frame.clip('walk')` 只是取得这个目标名，不会创建或验证文件里不存在的动画。

### 4.2 没有某个动画会怎样

当前生产 CLIPS 路径有兼容策略：

- 缺少跑步、倒退或侧移片段时，可以用已有走路片段。
- 缺少下蹲、游泳、鞘翅、死亡等特殊姿态时，使用原版全身姿态回退，避免始终站直播放待机。
- 没有自定义物品动作片段时，保留原版手臂动作和持物姿态。
- `land` 是可选落地片段，没有它时继续普通移动选择。

因此可以先做待机和走路，逐步补充模型专用动作。原版回退仍然依赖人体映射和校准；回退只能提供基础姿态，不能替你创作某种角色的独特跑姿、手指动作或表演。

动作意图还包括 `crawl`、`crawl_idle`、`ladder_up`、`ladder_down`、`ladder_idle`、`water_idle`、`lava`、`riptide`、`frozen` 和 `hurt`。完整策略见 [原版动作兼容说明](PLAYER_VANILLA_ACTIONS.md)和 [PlayerAnimationController](../../src/main/java/cn/minerealms/rigworks/character/core/PlayerAnimationController.java)。

## 5. 混合为什么能让角色边走边攻击

### 5.1 过渡混合

从待机切到跑步，如果直接跳到跑步第一帧，身体会突然换姿态。过渡混合是在短时间内逐渐从旧姿态转到新姿态。profile 的 `blendSeconds` 控制直接片段切换的过渡时间，例子里的 `0.15` 是 0.15 秒。

### 5.2 分层和骨骼遮罩

一个角色可以让腿继续走路，让上半身做攻击。遮罩负责告诉某一层“允许影响哪些骨骼”。当前 CLIPS 集成会将使用物品、挥击和可选受伤片段放进上半身层，排除髋和腿；基础移动片段继续播放。

当自定义上半身动作接管手臂时，原版手臂姿态会相应让出控制。这样可以减少两个动作同时争抢手臂的情况。特殊全身原版回退则有自己的优先策略。

通用脚本 API 的非空骨骼遮罩只包含明确列出的节点，**不会因为填了胸部节点就自动包含所有子节点**；CLIPS 内置上半身遮罩会自行收集子树。这两者要区分，详见 [AssimpLoader 混合 API](https://github.com/MineRealms/AssimpLoader/blob/feat/oculus-injector/docs/guides/KUBEJS_ANIMATION_GUIDE.md)。

### 5.3 覆盖混合与增量混合

| 方法 | 含义 | 用途 |
| --- | --- | --- |
| 覆盖 Override | 按权重接近另一套姿态 | 用持枪姿态替换双臂姿态 |
| 增量 Additive | 在当前姿态上加入相对于参考姿态的变化 | 呼吸、轻微后坐力、上身倾斜 |

权重为 0 表示这一层没有影响，1 表示完整影响。增量需要合适的参考姿态，不能把任意完整走路片段直接当成后坐力使用。

### 5.4 动作与游戏时间怎么对齐

移动片段可以根据实际速度和配置的步幅调整播放速度。`walkStride` 等表示一整个动作循环希望覆盖的移动距离，参数不合适仍可能滑步，需要配合动画调节。

挥击会读取 Minecraft 接受的挥击代次和归一化进度，对齐攻击片段的阶段。鼠标被点击不代表游戏已经接受一次攻击，更不代表伤害发生；动画代码不会改变服务端伤害、攻击距离或冷却。

## 6. KubeJS 应该负责什么

KubeJS 适合表达“什么时候播放、影响多少、目标在哪里”，不需要用 JS 重新实现每个顶点的蒙皮。

保留完整原版动作的最小本地控制器如下，放入 `kubejs/client_scripts/`：

```javascript
RigWorks.register('example:native_player', function (frame) {
  frame.defaultAnimation()
})
```

执行 `/kubejs reload client_scripts`，启用已校准角色并选择 `SCRIPTED`。注册控制器会选中它，但不会自动打开玩家换模。一次先安装一个主控制器，避免后注册者覆盖选中项。

| 调用 | 含义 |
| --- | --- |
| `frame.defaultAnimation()` | 请求完整原版基线，包括物品动作 |
| `frame.defaultMotion()` | 请求移动和头部基线，物品动作留给脚本接管 |
| `frame.input()` | 读取本 tick 的玩家状态快照 |
| `frame.bone('right_hand')` | 从人体映射取得模型真实右手节点名 |
| `frame.clip('walk')` | 从 profile 取得走路对应的状态或片段名 |
| `frame.model()` | 取得当前动画实例的播放、混合和 processor 控制接口 |
| `frame.state()` | 保存当前角色绑定会话中的脚本状态 |

可以在原版基线上增加局部片段、增量姿态或 IK，而不是一开始就接管全身。可直接参考 [混合与 IK Demo](../../examples/kubejs/client_scripts/rigworks_hybrid_tutorial.js)；它在特殊动作时会关闭额外覆盖，避免破坏游泳、使用物品等状态。

如果要自己设计完整动作优先级、攻击连击和物品分类，参考 [玩家动作 Demo](../../examples/kubejs/client_scripts/rigworks_player_actions.js)。只有 `idle/walk` 判断的简短脚本不能覆盖所有原版动作。

`frame` 只在本次回调有效，不要存起来供以后使用。状态变化时才切换动作，processor 定义通常只初始化一次；每 tick 只修改目标、权重或阶段。不要在每次绘制、每根骨骼或每个顶点中执行 JS，也不要同时用两个时钟推进同一角色。

## 7. Blender 烘焙和游戏内 IK 怎么选

| 目标 | 合适做法 |
| --- | --- |
| 好看的走路、奔跑、攻击或舞蹈 | 在 3D 软件制作关键帧，需要时烘焙后导出片段 |
| 手始终碰到当前武器副握把 | 先校准装备，再配置运行时接触 IK |
| 脚适应当前地形 | 配置接地与 IK 目标，不只依赖平地走路片段 |
| 头或眼睛跟随目标 | 运行时朝向或相应骨骼控制 |
| 头发、衣摆、装饰摆动 | 按模型配置 Spring 或视觉刚体物理 |
| 固定演出只需要最终运动 | 烘焙结果即可，不需要游戏重建制作约束 |

制作软件中的控制器、驱动器和约束图，不会自动变成游戏里的同款求解器。烘焙会记录计算后的变形骨运动；游戏播放的是这些结果。运行时 IK 则会根据当时目标重新计算关节。

例如你在 Blender 用脚部 IK 做走路，导出前烘焙腿骨；进入游戏后可以播放这段走路，再通过接地修正适应坡面。这两种 IK 可以各自承担一部分工作。

当前存在通用 CCD 链、角色命名 IK 和装备手部接触等接口，它们都需要真实骨链及相关定义。目标通常使用模型空间和导入后的单位，不能直接把屏幕坐标或未经转换的世界坐标传进去。求解器也不能推断任意枪械握把、自动修正所有穿模，或制造模型没有的手指结构。

FBX 中的 Root Motion，即根骨位移，目前不会自动推动服务器玩家。普通移动动画通常先做成原地循环，由 Minecraft 控制实际移动；保留根位移时要检查是否造成视觉漂移。

## 8. 装备、物理与模型动画分别管什么

**装备 socket 决定物品怎么跟随骨骼。** 左右手各有独立挂点及位置、旋转、缩放。它解决物品绑在手还是小臂、剑尖朝向、武器大小等问题。调整角色整体 transform 不等于校准了手持物品。

**手部接触 IK 决定另一条手臂怎样抵达物品上的目标。** 正确流程是先把武器挂到主手并调整朝向，再设副手接触点和肘部方向。手指握持通常还需要模型专用姿态或动画，不能只靠手腕抵达目标。

**视觉物理处理摆动、约束和已配置的布娃娃反馈。** 它需要 physics 数据和相应骨骼；它不会替代角色基础动画，也不会改变服务器玩家的移动和碰撞规则。不要让 Spring、刚体反馈和脚本同时无规则地控制同一骨骼。

进一步操作见 [装备工作台](EQUIPMENT_TRANSFORM_WORKBENCH.md)、[装备定义](CHARACTER_EQUIPMENT.md)和[手部接触](RIGWORKS_HAND_CONTACTS.md)。

## 9. 保存、重载和多人会不会丢动作

工作台编辑的是模型项目和角色配置，例如整体 transform、人体映射、动作映射、装备和 solver 数据；它不会把新的动画关键帧反向写回 FBX。当前已存在角色的配置编辑会继承完整角色配置，避免只保存模型大小而丢掉原动作来源。

| 修改内容 | 使用方式 |
| --- | --- |
| 工作台预览参数 | `Apply` 应用当前配置；`Save` 持久化，随后可重新打开 |
| FBX、贴图等资源 | `F3+T` 资源重载，等待异步重新导入 |
| 全局 KubeJS 客户端脚本 | `/kubejs reload client_scripts` |
| 通用 processor JSON | 资源重载后显式 `reloadProcessor`，或重新建立模型实例 |

资源重载会替换模型实例，旧脚本 handle 不能继续使用。脚本初始化状态应跟随当前绑定生命周期，示例使用 `frame.state()` 处理这一点。

多人分享区分两件事：

- FBX、贴图和声音是资产，当前协议不上传这些大文件；接收者需要安装匹配资产。
- 角色配置和专用动作包可以经支持 RigWorks 的服务器分发。上传和显示他人模型分别受客户端开关控制。

任意 `kubejs/client_scripts` 不会自动发给其他玩家。希望其他客户端执行的个性动作应制作成 `kubejs/rigworks/<avatar>/` 专用包，它使用独立受限环境和自己的命令 API，不能原样复制全局 `frame.model()` 脚本。服务端管理配置版本和分发，客户端计算视觉姿态，不会每帧上传整套骨骼矩阵。

详情见 [配置同步](CHARACTER_CONFIGURATION_SYNC.md)、[专用动作包](AVATAR_BEHAVIOR_RUNTIME.md)和[可分发包示例](../../examples/rigworks/avatar_behavior/controller.js)。

## 10. 新模型推荐按什么顺序做

1. 检查 3D 软件中的骨架、蒙皮、绑定姿态、单位和前方；先确保模型本身能正确变形。
2. 导入工作台，确认尺寸、脚底高度、材质和模型朝向。
3. 配置人体映射和必要的轴校准，使用 `VANILLA`，先看行走、蹲伏、移动跳跃、游泳和持物。
4. 保存并重新打开，确认读到的是保存后的配置，再处理左右手装备挂点。
5. 在 3D 软件制作少量高质量动作，导出并检查实际片段名；用 `CLIPS` 或脚本混合逐项替换。
6. 需要特殊控制时才使用 `SCRIPTED`，保留完整动作基线并明确各层影响的骨骼。
7. 最后增加手部接触、接地 IK、头发或衣摆物理，并观察性能。
8. 需要多人分享时，将动作整理成专用包，检查接收端资产一致性与实际显示结果。

每一步都建立在前一步已经正确的基础上。这样遇到手臂翻转时，可以先判断是骨轴校准问题，而不是同时排查动画、脚本、IK 和物理。

## 11. 常见问题怎么判断

| 现象 | 优先检查 |
| --- | --- |
| 只有待机，对输入没有反应 | 模式是否正确；CLIPS 是否存在对应片段；脚本是否调用基线；是否正在使用预览播放器而非角色控制器 |
| 编辑后变大了，但原有动作没了 | 是否保存/应用了完整角色项目；是否切换了 profile、模式或脚本来源；重新选择原始 profile 会回到另一份配置 |
| 手臂反折，A Pose 下不来 | 人体节点、绑定姿态、`nativeRotation` / `referenceRotation` 校准；先关闭额外 IK 和物理定位来源 |
| 连续移动跳跃时腿突然绷直 | 当前控制器是否使用真实 `onGround` / `airPhase`，是否有旧脚本按按键强行覆盖腾空姿态 |
| 攻击时腿停住 | 是否把全身攻击直接当主片段；上半身遮罩是否包含了髋或腿 |
| 动画一直从头抖动 | 是否每 tick 重播片段、重新创建层或重新编译 processor |
| 脚底滑动 | 动画步幅、移动速度和 `timing` 是否匹配；是否混入了未处理的根骨位移 |
| 武器绑在小臂或朝向不对 | 装备挂点节点、左右手独立变换及物品自身 display transform |
| IK 拉飞或够不到目标 | 骨链层级、模型空间目标、单位、目标距离和弯曲限制 |
| 别人看不到动作 | 是否只装了本地全局脚本；服务器是否支持、分享是否启用、专用包是否发布、接收资产是否匹配 |
| 开光影运动拖影，关光影正常 | 查看当前 Oculus/光影渲染路径；不能仅凭画面就断定是骨骼或 Z-fighting |

基础姿态、装备和物理可分开定位；测试通过某个状态不代表所有模型、光影、第一人称和远程玩家的每种组合都已完成视觉验收。

## 12. 继续阅读

- [快速开始](QUICK_START.md)：安装、导入、应用和保存。
- [KubeJS 动画实用指南](KUBEJS_ANIMATION_GUIDE.md)：接口、示例、重载与阶段控制。
- [Demo 安装说明](../../examples/kubejs/README.md)：选择一个主控制器开始。
- [原版动作兼容说明](PLAYER_VANILLA_ACTIONS.md)：状态观测、上半身层和回退策略。
- [自定义骨架校准](CUSTOM_RIG_NATIVE_CALIBRATION.md)：原版姿态怎样适配自定义绑定。
- [Blender 工作流](CHARACTER_BLENDER_WORKFLOW.md)：片段、骨骼、侧车数据和烘焙边界。
- [AssimpLoader 通用动画 API](https://github.com/MineRealms/AssimpLoader/blob/feat/oculus-injector/docs/guides/KUBEJS_ANIMATION_GUIDE.md)：播放、混合、processor 与 CCD IK。
