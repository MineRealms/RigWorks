# YSM / RigWorks 优先级 TODO

更新日期：2026-11-21

这份清单按玩家实际体验和后续功能依赖排序。`RWYM` 是一次转换后的缓存资产；
以下任务针对导入后的 RigWorks 运行时，不要求每次启动重新解密 YSM。

## Current implementation status (2026-11-21)

P0-4 is implemented and covered by focused runtime tests: immutable model/controller
definitions are shared, while pose, variables, clocks, blink state and item/input snapshots
belong to each local or remote owner. P0 grounding/cache work is also in the current branch.
The remaining P0 checkboxes below are validation or format-coverage work, not permission to
silently fall back to a half-written profile. Native converter tests were run with the Windows
x64 library from the native-import worktree; a clean checkout needs the CMake/native artifact
before those tests can execute.

## P0：先修复玩家马上能看到的问题

### P0-1 导入可靠性和错误诊断

影响：模型打不开时，后续所有功能都无法验证。目前 Legacy V3 已能导入真实格式 9，
但 V1/V2、Modern MXC、部分 V3 payload 和模型自定义 Molang 仍可能失败或降级。

- [ ] 对 V3 各 payload layout 做真实样本回归，而不是只依赖格式 9。
- [ ] 对 V1/V2 和 Modern MXC 给出明确的 unsupported 结果，禁止生成半成品 profile。
- [ ] 将失败定位到文件、版本、payload、动画名、控制器、表达式位置和 fallback。
- [ ] Import 失败时不留下不可 Apply 的脏 profile、临时模型或孤立缓存。
- [ ] 保留 UI 简短错误，同时把完整异常写入 `latest.log`。

验收：`_Rumia.ysm`、`Flandre_Scarlet.ysm`、`168.ysm`、`2_0lingsha(1).ysm` 等样本都能得到
明确的成功或可操作失败原因；重复导入不产生坏 profile。

### P0-2 原版移动、姿态和地面接触

影响：这是最直接的“模型行为不对”，包括蹲下陷地、游泳交叉、鞘翅俯冲水平、
F5 俯仰翻转和疯狂点击时动作跟不上。

- [ ] 将 vanilla 状态采样、YSM controller、RigWorks scripted 动作统一到同一个 tick/partialTick 时间轴。
- [ ] 修正 crouch/slide/landing 的视觉地面校正，确保脚底不穿入方块且不改变服务端碰撞。
- [ ] 修正 swim 的双臂分离、手臂划水方向和腿部摆动。
- [ ] 修正 elytra 上升、滑翔、俯冲三个姿态的俯仰轴和速度响应。
- [ ] 修正第三人称头部俯仰和模型 forward 轴，覆盖 front/back 两种视角。
- [ ] 动作触发必须使用真实攻击/use tick，连续点击时进入下一段连击，不能依赖 W+Space 等偶然组合。

验收：站立、走路、疾跑、蹲下、跳跃、游泳、鞘翅、骑乘、睡觉和攻击逐项截图/数值检查；
动作速度与 vanilla action tick 一致，连续攻击不会落后或卡在上一段。

### P0-3 手持物品、双手动作和第一人称

影响：弓、十字弩、鱼竿、盾牌和普通剑是最常用的视觉反馈；错误会让模型看起来像“动作丢失”。

- [ ] 统一主手/副手映射，确保鱼竿线、剑、弓、十字弩和盾牌使用正确的 HumanoidArm。
- [ ] 实现 bow/crossbow 的双手接触和瞄准姿态，避免两臂交叉或弯曲方向反转。
- [ ] 保留 vanilla 使用、拉弓、装填、挥手时间轴，再叠加 YSM/Mo' Bends 视觉动作。
- [ ] 第一人称只绘制配置的前臂/手部骨骼，检查屏幕边缘偏移、缩放、遮挡和装备变换。
- [ ] 鞘翅、披风、盔甲、头戴物跟随同一套渲染姿态。
- [ ] 将模型自带武器显示、原版物品隐藏和普通物品 fallback 做成互斥规则。

验收：主手和副手交换后仍正确；弓、十字弩、鱼竿、盾牌、地图、望远镜和鞘翅各有一条回归场景。

### P0-4 将 YSM Runtime 改为每角色实例

影响：当前 YSM bridge 主要绑定本地玩家单例，阻碍远程角色、Workbench 预览和多角色同时运行。

- [x] 抽出 `YsmRuntimeInstance`，持有自己的输入快照、变量、控制器状态、动画时间和 blink 状态。
- [x] 本地玩家、远程玩家、Workbench 预览分别创建实例，不共享静态 pose/controller 状态。
- [x] 资源和控制器定义可以共享只读缓存，实例只保存小型运行时状态。
- [x] 增加生命周期、世界切换和模型替换时的释放，避免旧 profile 继续驱动新模型。

验收：两个不同 YSM 角色同时播放不同控制器和眨眼状态，互不串状态；远程角色不会读取本地玩家物品。

## P1：补齐 YSM 行为语义

### P1-1 模型自定义 Molang 函数

- [ ] 从 RWYM 的 functions section 读取函数名、参数和表达式体。
- [ ] 编译到每个 `YsmRuntimeInstance` 的受限函数表。
- [ ] 设置递归深度、表达式步数、参数数量和字符串长度上限。
- [ ] 与内置函数、TaCZ token、KubeJS provider 分开命名，避免模型覆盖安全内置函数。
- [ ] 未知函数、递归和坏表达式都要记录上下文并使用确定性 fallback。

验收：函数能参与 controller 条件、clip 权重、timeline instruction；同一个函数在两个角色实例中变量互不污染。

### P1-2 完整动画类别和动作优先级

- [ ] 接通 `fp_arm`、`extra`、`arrow`、`carryon`、`parcool`、`swem`、`slashblade`、`tlm` 等分类。
- [ ] 建立 body、arm、first-person、equipment、overlay 的固定优先级和骨骼 mask。
- [ ] 用统一动作栈表达 vanilla、YSM controller、KubeJS scripted、IK 和 physics 的覆盖关系。
- [ ] 实现 play-once 完成后的自动过渡和中断恢复。

### P1-3 物品替换和变体

- [ ] 支持 YSM locator、内置武器、物品类型、tag、使用状态和枪械 ID 的选择顺序。
- [ ] 原版物品、模型内置模型和 fallback 只能选择一个可见来源。
- [ ] 缺少目标资源时保留原版物品，不隐藏后变成空手。
- [ ] 为 Flandre 等带专用武器的模型增加真实客户端回归。

### P1-4 事件、音效和特效

- [ ] 将 animation/controller sound keyframes 接入 Minecraft 客户端声音队列。
- [ ] 区分本地一次性事件、远程可见事件和只影响客户端的粒子事件。
- [ ] 事件必须随 controller transition 取消或去重，不能因重载重复播放。

### P1-5 KubeJS / JSON 扩展层

- [ ] 允许 profile 选择启用哪些 YSM controller、clip、category 和 provider。
- [ ] 允许 KubeJS 注册或覆盖输入 token、动作优先级、物品变体和事件处理器。
- [ ] 让 YSM-owned scene 也经过 per-profile script hook，而不是绕过普通 KubeJS 回调。
- [ ] reload 采用校验后原子替换，失败保留上一个有效脚本。

## P2：扩大兼容面和工具体验

### P2-1 YSM 来源和可选模组兼容

- [ ] TaCZ：真实安装/未安装两套测试，验证枪械 ID、类型、瞄准、装填、近战和开火。
- [ ] SlashBlade、CarryOn、ParCool、Swem、TLM、Iron's Spell Books 等 provider 按可选依赖隔离。
- [ ] 远程玩家执行相同的 YSM controller、blink、Molang 和 provider 快照。

### P2-2 Workbench 和资产维护

- [ ] 在 Workbench 内预览 YSM-authored controller clip，而不是只预览通用动画。
- [ ] 支持 RWYM 编辑、保存、重新打开和 provenance 保留的完整闭环。
- [ ] 清理无效 profile、孤立缓存和转换失败留下的临时文件。
- [ ] 导出 Blockbench JSON/FBX 作为编辑交换格式，但不取代 RWYM 运行时格式。

### P2-3 性能和平台

- [ ] 对多个 YSM 角色做骨骼、材质、缓存和 FPS 压测，记录经验数据和真实数据分界。
- [ ] 复用只读几何/纹理/表达式编译缓存，限制每个实例的可变内存。
- [ ] 完成 Windows x64 之外的 native 构建和 C ABI 测试。
- [ ] 只有在 CPU/RWYM 路径有数据证明瓶颈后，再考虑 GPU bone palette、MDI 或 compute skinning。

## 暂不优先

- 不先重写成 OpenYSM 的独立 GPU VBO/SSBO 渲染器。
- 不先支持所有历史 YSM 文件版本而牺牲当前 V3 的可诊断性。
- 不把服务端碰撞、位置和伤害计算交给客户端模型或 YSM 脚本。

## 推荐执行顺序

`P0-1 -> P0-2 -> P0-3 -> P0-4 -> P1-1 -> P1-2 -> P1-3 -> P1-4 -> P1-5 -> P2`

每完成一个 P0/P1 项，都要同时补充纯 Java/native 测试和一次 Rosetta 客户端检查；
模型准备完成后至少等待 10 秒再截图，截图只作为视觉验收，不能替代日志和数值结果。
