# RigWorks 角色验证流程

这是一套从 Blender 制作角色、导出 FBX、接入 RigWorks 到实机验收的通用流程。
Madoka Exedra 只是其中一个已经测量过的例子，不能把它的骨骼轴、身高或动作数值
直接套给其他模型。

## 1. 先确定资源能力

在导出前记录以下内容：网格数量、顶点/三角形数、骨骼名称和层级、每个顶点的权重
数量、Morph 名称和默认值、动画片段名称与时长、UV0/法线/切线、材质纹理路径。
当前通用路径支持 Assimp 能读到的 FBX/GLB、LBS 蒙皮、顶点 Morph、骨骼/组合
Morph、导入骨骼动画和基础 PBR 输入。PMX/VMD 专用语义、SDEF/QDEF、任意布料
节点图和未实现的 Unity 特效不能由“导出成 FBX”自动获得。

使用米制坐标，约定 `1 block = 1 unit`，脚底在 `Y=0`。按实际蒙皮后的中立姿态做
统一缩放，不要分别缩放 X/Y/Z，也不要用运行时父节点缩放掩盖骨骼长度问题。目标身高
和落地偏移写进 profile 的 root sidecar，并记录校准前后的包围盒。

## 2. Blender 骨架与绑定姿态

Blender 中的 IK、Pole、脚底接触和手部目标用于**制作和烘焙动作**。FBX 通常只保留
烘焙后的骨骼关键帧，不保留完整约束求解器。运行时 `rig.json` 的 IK 是另一套可配置
求解器，适合脚底、支撑手等实时接触；不要因为 Blender 里有 IK 约束，就宣称客户端
拥有同样的运行时 IK。

profile 的 `referenceRotation` 是 Minecraft 语义轴到模型骨骼轴的修正；`nativeRotation`
是构造原版姿态候选时追加的 bind-local 修正。二者都是归一化 XYZW 四元数。每一条腿、
手臂、前臂和手掌都要根据实际 inverse bind 数据测量，不能复制另一个模型的四元数。
Head 的 `headLook.forward` 也必须由实际 skin bind 姿态反算到模型的脸部朝向。节点树
保存的姿态和 inverse bind 姿态可能不同；控制器应以 `SkinBindPose.locals` 为参考。

## 3. Morph、曲线和中立表情

Assimp FBX 的 `AIAnimMesh.mWeight` 不一定是作者的中立权重。显式列出所有 Morph 的
默认值、别名和动画通道。RigWorks 的 `rig.json.defaults` 应覆盖每个目标；需要中立脸
时通常把全部目标设为 `0`，只在脚本或 clip 中设置眨眼等变化。名称解析同时支持导入
原名和引擎提供的无歧义别名。

导入动画中的 Morph 曲线会按 clip 采样，但 `AnimationState.stop()` 会跳过 modifier，
不能把停止状态当作“已应用 defaults”。保持有效的 idle/中立片段，或在实例上显式
清除权重。离线工具报告的是原始 FBX 默认值，不会假装替代 rig sidecar 后的结果。

## 4. 动作策略和 Minecraft 输入

专用角色包的默认脚本调用 `frame.defaultMotion()`，由兼容控制器选择 profile 的
`idle/walk/run/swim/elytra/...` 映射，并保留原版头部、物品和缺失状态回退。只有明确
想让原版完整姿态接管时才调用 `frame.defaultAnimation()`。全局 KubeJS client script
和角色包的隔离 Rhino 脚本不是同一入口；不能只把一个脚本复制过去就假定策略相同。

移动片段按实际速度和 profile 的 stride 同步播放速率。挥击使用游戏已经接受的
`swingGeneration/swingProgress`，不要在 JavaScript 中另起一个慢时钟。自定义攻击层
使用上半身 mask；原版物品动作、双手弓/弩、鱼竿和未映射的使用动作保留 native arm
fallback。游泳、鞘翅、睡眠、死亡等片段如果存在，要由片段拥有相应的整体方向；缺失
特殊片段时才进入完整原版回退。

隔离脚本使用普通 JSON/JavaScript 数据。单次回调预算为 100,000 条解释器指令、
128 条命令；不要每 tick 扫描并重发所有 Morph。中立值写入 rig 的 defaults，脚本只
更新变化的眨眼、表情、IK 权重或目标。控制器、角色和动作映射跟随副本保存，通过
**Behavior > Reload avatar sources** 重载；不要重新注册一个全局控制器覆盖它。

## 5. 离线检查与双版本验证

仓库提供只读检查器。它输出模型 SHA-256、源格式、网格/材质/骨骼/Morph/clip 数量、
原始 Morph 默认权重和别名、skin bind 包围盒，以及每个 clip 在 0%、50%、99% 的
有限性、包围盒和指定骨骼位置/旋转采样。输出中的
`semantic_status=unknown_without_profile_or_live_client` 是有意的：离线检查不能证明
Minecraft 输入、渲染器方向、装备或服务器行为。

先编译对应节点，再执行：

```powershell
$engine = (Resolve-Path ..\AssimpLoader).Path
$model = (Resolve-Path .\model-tests\block_player\block_player.fbx).Path
.\gradlew.bat :1.20.1-forge:classes :1.20.1-forge:testClasses `
  "-PassimpLoaderDir=$engine" --console=plain
.\tools\avatar-validation\Invoke-AvatarValidation.ps1 `
  -Model $model -GameNode 1.20.1-forge -Joint Head `
  -Output .\build\avatar-validation\avatar-1.20.1.json `
  -JavaHome $env:JAVA17_HOME -AssimpLoaderDir $engine

.\gradlew.bat :1.21.1-neoforge:classes :1.21.1-neoforge:testClasses `
  "-PassimpLoaderDir=$engine" --console=plain
.\tools\avatar-validation\Invoke-AvatarValidation.ps1 `
  -Model $model -GameNode 1.21.1-neoforge -Joint Head `
  -Output .\build\avatar-validation\avatar-1.21.1.json `
  -JavaHome $env:JAVA21_HOME -AssimpLoaderDir $engine
```

将 `$model` 改为自己的导出文件，`Joint` 改为实际骨骼名，并让 `JAVA17_HOME` /
`JAVA21_HOME` 指向对应 JDK。也可省略 `JavaHome` 使用当前 `JAVA_HOME`。若使用仓库
内 AssimpLoader 子模块，`$engine` 应改为 `Resolve-Path .\AssimpLoader`，并记录该提交。

两个节点必须使用同一份模型文件并比较 SHA-256。检查 `sample_node_known`、默认
Morph 状态、clip 时长、绑定包围盒和关键骨骼采样；再用实际 profile/rig 做 native
校准测试。工具发现非有限矩阵或动画时序错误会失败，但不会把“骨骼存在”当成 IK、
装备或动作语义通过。

更严格的模型回归应从 Blender 导出同一 clip 在同一时间的髋、膝、脚、肩、手和头
世界位置，转换成运行时坐标后与实际 `PoseSnapshot` 比较，记录最大距离而不只检查
矩阵有限。分别检查膝盖弯曲方向、两只手最小间距、着地脚的最低顶点、循环首尾差异、
头部 yaw/pitch 和横向飞行轴；误差容限按模型单位明确写出。通用检查器只提供采样数据，
这些接触和语义约束必须按模型补充，未知项不能自动判为通过。

## 6. 官方角色包流程

在 AssimpLoader Workbench 中使用 RigWorks 的 **Import avatar**、**Save/Apply** 和
官方 Workbench export/import。角色 ZIP 是 `manifest.json + project.workbench.json +
assets/ + extensions/` 的工作台包，不是普通资源包。导入时选择“新建独立副本”或
“更新已有副本”；新副本必须拥有新的 UUID、profile 和脚本目录。模型、贴图、profile、
rig、equipment、physics 和隔离 KubeJS 脚本的 hash 要写入验证报告。不要把绝对本地路径
或二进制 FBX 复制进共享脚本目录。

## 7. 必须进行实机验收

离线测试通过后仍必须启动目标客户端。模型和贴图加载要等待至少 **10 秒**，再记录
截图或数值；不能在异步资源刚开始时下结论。验收至少包含：

1. 站立、走、跑、蹲、跳/落地、游泳、鞘翅、骑乘、睡眠、死亡和梯子。
2. 快速连续点击攻击、主副手物品、弓、弩、鱼竿、盾牌和双手物品。
3. F5 第三人称、鼠标上下俯仰、不同地形高度、脚底与方块接触、阴影和包围盒。
4. Oculus/Iris 或目标 shader、PBR 贴图、透明度、法线/切线和装备挂载。
5. 切换 vanilla / clips / scripted，资源 reload、换世界、退出世界和失败恢复。

报告使用以下标签：`TESTED_OFFLINE`（工具/Java 测试实际通过）、`TESTED_LIVE`
（指定客户端输入和视觉步骤实际通过）、`UNVERIFIED_LIVE`（尚未实机）、`BLOCKED`
（有明确错误和日志证据）。没有启动客户端的报告不能写“玩家动作闭环完成”。

## 8. Madoka 测量例子

当前 Madoka v2 FBX 测得 4 个网格、176 个加权骨骼、60 个 Morph、34 个 clip，skin
bind 高度约 1.85。原始 FBX 的 60 个 Morph 默认权重总和为 60，因此必须通过 rig
sidecar 覆盖为零；这不是所有 FBX 的普遍规律。它的 Head bind 反算脸部轴约为局部
`[-1,0,0]`，也不能作为其他模型的默认值。该例的 24 个 `mc_*` clip 是 Blender IK
烘焙结果，是否适合玩家动作仍由上述输入、装备和实机验收决定。
