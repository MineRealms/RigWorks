# DEFAULT 内置动作预设

`DEFAULT` 使用已导出的 VMD 求解数据，在 Java 中采样并重定向到当前角色骨架。目标 FBX 不需要携带这批动画，也不需要改成 MMD 骨骼名。角色仍需要配置 `profile.json` 的人体映射与轴向标定。

## 启用

在 **RigWorks > Characters > Motion** 选择 **Default**，或使用客户端指令：

```text
/assimploader character mode default
```

配置项为 `[character] animationMode = "DEFAULT"`。现有配置保持原来的模式，新安装的配置默认值仍是 `VANILLA`。更换 Java 模组版本需要重启游戏。

| 模式 | 动作来源 |
| --- | --- |
| `VANILLA` | Minecraft 的玩家姿态 |
| `SCRIPTED` | KubeJS 控制器或角色专用动作包 |
| `CLIPS` | 当前模型自带的动画片段 |
| `DEFAULT` | RigWorks 共享动作库，按目标角色骨架重定向 |

YSM 源角色始终优先使用 YSM 自己的动画和 controller。即使外观配置选择了 `DEFAULT`，YSM 路径也不会加载或执行这套预设。普通 FBX 的本地和远端角色分别持有播放状态。

## 数据范围

内置的是 `tools/motion-conversion/source_clips.tsv` 已提取的全部 **25 段**。这不是原始 64 个 VMD 文件的全量转换，也不提供 PMD/PMX/VMD 文件导入。

| 类别 | 片段 |
| --- | --- |
| 地面与空中 | stand、walk、run、jump、crouch、crouch_walk |
| 水中 | swim、swimming |
| 弓 | bow_hold、bow_draw、bow_aim |
| 十字弩 | crossbow_hold、crossbow_loaded、crossbow_draw |
| 持物与盾 | 左右手 item_hold、shield_hold、shield_block |
| 攻击 | attack_sword、attack_tool_right、attack_item、attack_item_right、attack_shield_right |

`stand` 是静态站姿；`attack_sword` 来自通用棍棒挥击，不是 Mo' Bends 五段剑连击。角色表情、手指全套动作、相机和音频不在这个人体预设里。

基础移动与上半身持物分开组合。弓弩使用双臂与躯干，单臂工具和盾挥击只覆盖对应手臂；左手攻击和左手弓弩按语义镜像。左右手由玩家实际主手、使用手和挥击手决定。

攻击相位直接跟随 Minecraft 接受的挥击 generation/progress，重复攻击不会排队等待原始 VMD 时长。弓弩跟随实际使用时长；步频按真实速度和 `profile.timing` 的步幅设置计算。

睡眠、骑乘、鞘翅、创造飞行、攀爬、激流、冻结和熔岩状态使用原版姿态。进食、望远镜、三叉戟以及尚无预设的物品使用动作保留原版手臂求解。头部视线继续跟随玩家，游泳和爬行的身体朝向由原版 renderer 层处理。

## 目标骨架

角色绑定使用 **源人体 role → 目标 profile 节点**，不按 MMD 原始骨骼名查找 FBX。

- 完整人形建议映射 hips、spine、chest、neck、head，以及左右 upper_arm、forearm、hand、thigh、shin、foot；肩、脚尖与手指根参考是可选项。
- 最低标定需要 hips、head 和两侧 upper_arm。缺少关键节点或轴向退化时记录异常并回退原版，不会继续发布无效矩阵。
- 实际静止姿态来自蒙皮的 inverse bind；导出节点残留姿态不能代替它。
- `nativeRotation` 保留既有的中立姿态标定，`referenceRotation` / `headLook.forward` / `modelYawDegrees` 仍需要成套正确配置。任意骨骼名可映射，不代表任意未校准骨架会自动得到正确姿态。
- 腿部根据目标腿长做双骨 IK，使用源膝盖弯曲平面；脚掌朝向单独保留，有 toe 后代时用它标定鞋头方向。共同髋部调整让两侧脚踝目标可达。
- 弓弩双手用目标臂长做双骨 IK，将两个握点共同移入可达范围，保留目标间距。具体物品模型仍需装备挂点和接触配置。
- 水平位移归 Minecraft，预设只贡献允许的垂直摆动；空中不重复叠加源跳跃高度。

裙子和头发继续由角色自己的 rig / physics 设置处理。这套人体动作不会自动增加裙摆碰撞体，也不保证任意服装零穿模。校准流程见 [自定义骨架](CUSTOM_RIG_NATIVE_CALIBRATION.md)。

## 运行与性能

动作资源为版本化的 `assets/rigworks/motions/default/library.rwmd.gz`。固定字节序，60 Hz 求解采样，位置线性插值、旋转最短弧球面插值；循环末尾四个采样渐变到首帧。读取时限制解压大小、数组预算、片段数量并校验有限值和单位四元数。

该版本压缩资源 **320,260 字节**，共享姿态数组 **607,600 字节**。库第一次用于普通角色的 `DEFAULT` 时加载一次；每个角色复用约 4.8 KiB 的采样数组，并单独保留骨架标定和播放器状态。这些数字不包含通用场景、PoseGraph 和蒙皮快照的内存，也不是游戏帧率测试结果。

运行时不会调用 MMD JNI、启动 Blender、读取原始 VMD 或为每个角色解压一份库。动作数据与采样逻辑不新增平台 native 依赖；FBX 加载、渲染和可选物理仍使用 AssimpLoader 的既有基础设施。

## 生成与诊断

已有离线求解结果后，重新打包只需 Python 标准库：

```powershell
python tools/motion-conversion/pack_default_motions.py
```

输入默认是忽略目录中的 `build/vmd-motion-preview/source-samples.json` 和工具目录的 `retarget_preview.json`；资源与 manifest 确定性生成。manifest 记录源动作、帧范围、SHA-256、离线求解器身份和数值抽样。重新求解原始 VMD 的可选作者工具仍需要本地 MMD 引擎，见 [离线流程](../../tools/motion-conversion/README.md)；游戏播放不需要它。

`/assimploader character` 显示当前基础片段、左右手片段、挥击相位、原版回退原因和踝部/手部 IK 残差。异常始终写日志；开启 AssimpLoader Debug 后额外输出标定和基础状态切换，不逐骨逐帧刷屏。

## 验证边界

已完成两版本离线测试：全部 25 段与源采样抽样值一致，半帧插值无非法旋转；真实方块人 FBX，以及无动画、改名骨骼、不同体型和反射根坐标的骨架通过骨长与 IK 残差回归。另测攻击立即重触发、物品回退、模式重入、配置和网络往返。

2026-11-24 的完整测试为 Forge 1.20.1 共 382 项、NeoForge 1.21.1 共 388 项，各跳过 3 项，均无失败。两节点普通版与整合版 JAR 已构建，动作库可从相同源采样确定性重建。

使用 `tools/avatar-validation/Invoke-AvatarValidation.ps1` 的可选 `-Profile` 参数，还对本地小圆和 Starpop 各检查了 12 类 DEFAULT 动作。两模型最大踝部、手部 IK 残差均低于 `0.000001` 模型单位；报告在 `build/default-motion-verification/`。这是不含原版 renderer 根朝向、rig 默认形变、装备和物理的离线检查。抽样末帧原始蒙皮最低 Y 在 Starpop 上最低约 `-0.016`、小圆上约 `-0.004`，因此这些结果不能证明鞋底在游戏里完全贴地。

新 DEFAULT 运行时尚未完成 Minecraft 实机外观验收。已有 Blender / CLIPS 的实机结果不能代替它；小圆、Starpop 的服装余量、手掌与物品贴合、地面和着色器外观仍需按 [角色验证工作流](AVATAR_VALIDATION_WORKFLOW.md) 检查。
