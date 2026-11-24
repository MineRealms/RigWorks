# YSM / RigWorks 优先级 TODO

更新日期：2026-11-25

这份清单按玩家实际体验和后续功能依赖排序。`RWYM` 是一次转换后的缓存资产；
以下任务针对导入后的 RigWorks 运行时，不要求每次启动重新解密 YSM。

## 当前实现状态

本地和远程玩家已使用独立的 `YsmRuntimeInstance`，共享只读资源和编译定义。
下面未勾选项保留完整功能和客户端验收要求，不能直接解释为相关代码完全未实现。

2026-11-25 本轮补齐：

- [x] 持物条件支持完整物品 ID、tag、类型、使用动画和空手，逻辑主副手跟随惯用手。
- [x] 区分 YSM `ctrl.use` 的全局使用条件和实际使用手查询，避免双手姿态条件丢失。
- [x] 将真实船和可骑乘实体输入接到 `boat` / `ride` 动作，保留模组 provider 的输入。
- [x] controller 自己的 `all_animations_finished` / `any_animation_finished` 驱动结束过渡；循环、保持末帧和静态姿势分别处理。
- [x] 重复导入相同资产保留角色配置，render-cache hash/revision 改变时更换内存 scene。
- [x] 删除副本释放无引用的导入映射，待活跃角色/工作台释放引用后清理内存 scene，保护其他副本与磁盘缓存。
- [x] 每角色只缓存最新持物条件快照，表达式求值复用不可变 token 与函数索引。

本轮采用两套目标的 Java/真实引擎离线回归及完整构建验证；新增行为仍需客户端复测，
不将离线测试记为画面验收。近期更影响体验的未完成项是模型自定义函数、Workbench 内
完整 controller 预览，以及动画/状态音效播放；其余细分范围见下文。

## P0：先修复玩家马上能看到的问题

### P0-1 导入可靠性和错误诊断

影响：模型打不开时，后续所有功能都无法验证。目前 Legacy V3 payload 1 和 9 已有真实样本，
但 V1/V2、Modern MXC、尚未有真实样本的 V3 payload 和模型自定义 Molang 仍可能失败或降级。

当前已有真实外部样本：`_Rumia.ysm` 为 payload 9（3 models、13,440 faces），
`168.ysm` 为 payload 1（3 models、11,407 faces），`2_0lingsha(1).ysm` 为 payload 1
（2 models、6,780 faces）。兼容测试读取到 225/130/97 clips 和 456/371/296 expressions，
坏表达式确定性 fallback 为 1/1/0；日志包含 source clip、bone、axis 和 keyframe time。

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

影响：YSM bridge 已支持本地和远程角色实例；Workbench 预览仍未接入完整的 YSM runtime，
因此不能把资源注册误认为控制器预览已经完成。

- [x] 抽出 `YsmRuntimeInstance`，持有自己的输入快照、变量、控制器状态、动画时间和 blink 状态。
- [x] 本地玩家和远程玩家分别创建实例，不共享静态 pose/controller 状态。
- [ ] Workbench 预览创建独立实例并驱动 YSM controller/clip。
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
- [x] 通过 controller-local 完成查询实现 play-once 结束后的自动过渡。
- [ ] 完整的动作中断与恢复策略，并验证所有历史动作类别。

### P1-3 物品替换和变体

- [x] 持物/使用条件的完整 ID、tag、类型与主副手选择；模组弓弩盾等复用统一类型识别。
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

- [x] 重复导入保留同资产自定义配置；删除最后一个副本释放导入名额和模型内存，保留磁盘缓存。
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
