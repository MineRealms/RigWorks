# RigWorks KubeJS Demo

具体 API、动作模式、单位与重载见[详细教程](../../docs/guides/KUBEJS_ANIMATION_GUIDE.md)。

## 推荐第一份样例

1. 安装 RigWorks、AssimpLoader `-all.jar` 及 KubeJS / Rhino / Architectury。
2. 把 [rigworks_hybrid_tutorial.js](client_scripts/rigworks_hybrid_tutorial.js) 放进实例 `kubejs/client_scripts/`。
3. 执行 `/kubejs reload client_scripts`，启用已映射角色并选择 Scripted。
4. 行走时看腿部烘焙层；站立持剑看右前臂增量；原地蹲伏看左手 IK 目标小幅移动。

可以先选择内建 block-player profile。样例从当前 profile 读取语义骨骼和 `walk` 映射，没有片段或对应骨骼就跳过该部分。目标幅度是模型单位，特殊单位资产需要调小或调大。

| 脚本 | 用途 |
| --- | --- |
| [混合/IK 教程](client_scripts/rigworks_hybrid_tutorial.js) | 初始化时生成处理器，tick 调权重和目标，保留原版特殊动作 |
| [完整玩家动作](client_scripts/rigworks_player_actions.js) | 可编辑物品分类、连击、姿态、阶段同步和状态优先级 |
| [选择原生通道](client_scripts/rigworks_native_channels.js) | 通过资源定义控制原版动作通道 |
| [副手接触](client_scripts/rigworks_hand_contacts.js) | 需要装备定义和启用手部接触策略 |
| [JSON Processor](client_scripts/assimp_animation_processor.js) | 内建 block-player 专用；先启用 `examples/animation_processors/` 资源包，展示 JSON 定义的片段、pose 和 IK |

主控制器每次注册都会成为当前选择。先只安装一个示例；确需多个控制器时，在自有脚本中明确 `RigWorks.use('期望的ID')`。

上述全局脚本不会自动发送给其他玩家。需要分享的专用包示例在 [avatar_behavior](../rigworks/avatar_behavior/manifest.json)，它使用 `api.command(...)`，不能直接使用全局回调的 `frame.model()`。详见[包运行时](../../docs/guides/AVATAR_BEHAVIOR_RUNTIME.md)。
