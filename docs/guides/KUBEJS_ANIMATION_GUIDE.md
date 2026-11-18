# RigWorks KubeJS 动画实用报告与 Demo

通用 API 的播放、骨骼姿态、混合、IK、烘焙约定见 [AssimpLoader 详细教程](https://github.com/MineRealms/AssimpLoader/blob/feat/oculus-injector/docs/guides/KUBEJS_ANIMATION_GUIDE.md)。本篇说明玩家输入如何驱动它们。

## 1. 两种脚本入口

| 入口 | API / 运行方式 | 是否自动分享 |
| --- | --- | --- |
| `kubejs/client_scripts/*.js` | KubeJS 全局 `RigWorks.register(id, callback)`，本地角色每 tick 回调 | 不上传整个全局脚本目录 |
| `kubejs/rigworks/<avatar>/` | `manifest.json` + `mappings.json` + `controller.js`，受限独立 Rhino 环境，按玩家隔离 | 按分享开关和服务器协议发布 |

两种环境 API 不相同。下面 `frame.model()` 等示例用于 **KubeJS 全局客户端控制器**，不能原样复制进专用动作包。可分享包使用经过验证的 `api.command(...)` 命令形式，见[专用动作包](AVATAR_BEHAVIOR_RUNTIME.md)和[完整包示例](../../examples/rigworks/avatar_behavior/controller.js)。

## 2. 最小原版动作基线

```javascript
RigWorks.register('pack:player', function (frame) {
  frame.defaultAnimation()
})
```

放进 `kubejs/client_scripts/`，执行 `/kubejs reload client_scripts`。在角色设置或 `/assimploader character mode scripted` 选择 Scripted，并启用已校准的角色。注册成功会选择该控制器，但不会悄悄启用玩家换模。

`defaultAnimation()` 保留完整原版姿态，包括原版物品动作；`defaultMotion()` 保留移动和头部基线，让脚本接管上半身物品动作。后者不是同义词。`CLIPS` 模式不执行这个 KubeJS 控制器。

## 3. 可直接安装的混合、骨骼、IK Demo

将 [rigworks_hybrid_tutorial.js](../../examples/kubejs/client_scripts/rigworks_hybrid_tutorial.js) 单独安装到 `kubejs/client_scripts/`。它会：

1. 每 tick 采样完整原版动作。
2. 在当前 profile 有映射的 `walk` 片段时，仅给已映射腿骨加入烘焙行走层。
3. 站立持剑时给已映射右前臂加入轻微模型轴增量姿态。
4. 原地蹲伏时，给已映射左臂三骨链启用低权重 CCD IK，并让目标小幅移动。
5. 使用物品、攻击、游泳、飞行、骑乘、睡眠或死亡时关闭额外 processor，保留原版特殊动作。

处理器按角色绑定初始化一次，tick 中仅更新权重、开关和目标。`frame.state()` 在模型/世界/控制器版本变化时清理，因此不会把旧骨架的初始化状态带到下一代实例。

Demo 依赖真实的人体映射；缺少某项映射会跳过对应控制。映射了错误层级的左臂会被 IK 校验拒绝。目标偏移 `0.08` 是模型单位，适用于米制测试件；厘米制或特殊大小资产需要调整。样例是操作演示，不是武器握持或地面接触的最终配置。

仓库内建的 `rigworks:models/character/block_player/profile.json` 可作为确定骨架和片段的首个测试对象。已有 [完整玩家动作 Demo](../../examples/kubejs/client_scripts/rigworks_player_actions.js) 提供连击、物品分类、原版阶段同步和动作回退。一次只安装一个主控制器，避免“最后注册者被选中”覆盖预期。

## 4. Frame 和状态

```javascript
RigWorks.register('pack:inspect', function (frame) {
  frame.defaultAnimation()
  var input = frame.input()
  var memory = frame.state()
  var model = frame.model()
  var hand = frame.bone('right_hand')
  var walk = frame.clip('walk')
  // input/memory 是 Java Map，使用 get/put。
  if (memory.get('printed') !== true) {
    console.info('右手=' + hand + ', walk=' + walk + ', speed=' + input.get('speed'))
    memory.put('printed', true)
  }
})
```

Frame 只在本次回调有效，不要存到全局后延迟使用。`state()` 属于当前角色/模型会话，用来记初始化、动作 ID、上次输入和阶段，不需要自己每帧创建新状态机。

常用输入包括 `onGround`、`speed`、`sprinting`、`crouching`、`swimming`、`elytra`、`usingItem`、`swingActive`、`swingGeneration`、`swingProgress`、`mainItem`、`offItem`。物品又是 Map，如 `input.get('mainItem').get('attackType')`。具体输入全集按 [CharacterActionFrame](../../src/main/java/cn/minerealms/rigworks/compat/kubejs/CharacterActionFrame.java) 的快照实现读取。

## 5. 原版输入驱动烘焙片段

```javascript
var model = frame.model()
var input = frame.input()
var memory = frame.state()
var intent = Number(input.get('speed')) > 0.04 ? 'walk' : 'idle'
if (memory.get('intent') !== intent) {
  var clip = frame.clip(intent)
  if (clip) model.play(String(clip), 0.15)
  memory.put('intent', intent)
}
```

这是**自行替换全身基线**的片段切换示意，不能直接代替完整原版动作兼容。实际角色还要处理蹲伏、游泳、滑翔、物品使用和死亡等优先级。推荐从 `defaultAnimation()` + 局部 processor/遮罩开始，或使用已覆盖这些状态的主动作 Demo。

烘焙片段要先在 Blender 中制作并导出，`frame.clip('walk')` 读取 profile 的“动作意图 → 实际片段名”映射。不能凭空调用一个 FBX 中不存在的 `Walk`。

## 6. IK、接触和物理控制

| 控制 | 前提和边界 |
| --- | --- |
| `frame.ikChains()`、`ik(name, enabled)`、`ikTarget(name,x,y,z)`、`ikStatus(name)` | 角色 solver JSON 先定义命名链；目标为模型空间 |
| `model.processorDefinition(...)` + `processorTarget(...)` | 脚本自有 CCD 链；参考 Demo，按真实骨架创建一次 |
| `frame.contacts()`、`contact(id, mainHand)`、`contactWeight(w)`、`noContact()` | 装备定义有 grips/contact 规则，且启用接触策略 |
| `frame.morphs()`、`morph(name,weight)` | solver 定义中已存在命名 Morph；不同于通用 meshIndex 数组接口 |
| `frame.motions()`、`motion(name,loop)`、`seekMotion(seconds)`、`pauseMotion(bool)` | 已安装 solver motion 定义；它不是 FBX clip 的另一个名字 |
| `frame.physics()`、`physicsBodies()`、`body(id)`、`impulse(...)`、`torqueImpulse(...)` | 已有 physics 定义和运行中的视觉物理服务 |
| `frame.ragdoll(bool)`、`automaticRagdoll()` | 临时视觉布娃娃覆盖和恢复自动策略 |

`ikTarget` 和 `processorTarget` 不接收屏幕坐标。挂点、角色根变换、导入单位和模型朝向会影响世界 → 模型坐标转换。握武器应优先校准装备 socket/grip，再用接触 IK 修正副手，不要让两个系统持续争抢同一条手臂。

## 7. 攻击与时间同步

攻击使用 Minecraft 实际接受的 `swingGeneration` 和归一化 `swingProgress`。新攻击发生时切换动作，阶段由 `model.phase(...)` 或层/processor 的 phase API 对齐；结束后解除阶段锁。不能把任意鼠标点击都当作已经造成伤害的攻击。

脚本姿态只改变视觉，不修改服务端伤害、攻击距离或碰撞箱。全局 `ClientEvents.tick` 和 RigWorks 回调也不应同时推进同一角色时钟。

## 8. 重载、应用与多人

- 修改全局 JS：`/kubejs reload client_scripts`。固定开发版本的失败重载保留 RigWorks 最近有效控制器，详见[重载机制](KUBEJS_RELOAD_LIFECYCLE.md)。
- 修改 FBX/材质：`F3+T` 后等待重新导入；旧 handle 不再可用。
- 修改 processor JSON：资源重载后显式 `reloadProcessor`，或重建角色绑定；相同资源 ID 的 `processor(...)` 会命中缓存。
- 工作台 `Apply` 更新当前配置，`Save` 持久化并可再次打开。人物、装备、solver、物理配置由完整角色快照共享。
- 自动上传不包含任意 `client_scripts`。需要他人执行的个性动作应放进专用 `kubejs/rigworks/<avatar>/` 包，并使用包内受限 API。
- 接收端须拥有匹配 FBX/贴图等资产；只收到 JSON/JS 不会得到模型文件。服务端不运行客户端骨骼求解，也不逐帧分发全部骨骼矩阵。

## 9. 常见排查

| 现象 | 检查 |
| --- | --- |
| 只有待机或像死模型 | 是否误选 Clips；动作映射是否仅 idle；是否忘了默认动作基线；保存是否继承了原角色脚本 |
| JS 注册了却没动作 | 角色是否启用、模式是否 Scripted、active 控制器是否被后续文件覆盖；查看 `RigWorks.status()` / `failure()` |
| 动画一直从开头抖动 | 是否每 tick 重新 `play()`、重新创建层或编译定义 |
| 手臂翻转/IK 拉飞 | 绑定轴、骨骼 roll、人体映射、目标空间/单位及祖先层级 |
| 他人看不到自定义动作 | 是否用了只存在本机的全局脚本；分享开关、服务端 RigWorks 支持、接收资源哈希 |
| 材质/运动拖影 | 检查当前 Oculus 路径和光影；不要先假定是骨骼或 Z-fighting |

源码入口：[CharacterScriptApi](../../src/main/java/cn/minerealms/rigworks/compat/kubejs/CharacterScriptApi.java)、[CharacterActionFrame](../../src/main/java/cn/minerealms/rigworks/compat/kubejs/CharacterActionFrame.java)、[CharacterScriptRuntime](../../src/main/java/cn/minerealms/rigworks/character/client/CharacterScriptRuntime.java)。Demo 已做语法和源码契约检查，具体美术效果仍需按目标骨架在客户端预览。
