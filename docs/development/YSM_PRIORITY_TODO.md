# YSM / RigWorks 优先级 TODO

更新日期：2026-11-25。源码基准：`16a971e`。

本清单区分已有实现、功能缺口和待验收内容。剩余工作归为 **8 类功能扩展 + 2 类验收**，
不是十个尚未实现的子系统。导入只转换一次，后续运行读取 RigWorks 的 `.rwym` 资产；
YSM 解码、转换和来源语义都由 RigWorks 负责。

## 当前支持与边界

| 范围 | 当前实现 | 尚缺或待验证 |
| --- | --- | --- |
| 导入 | C++ Legacy V3 解码、原子缓存与回滚、来源哈希、完整异常日志；明确拒绝 V1/V2 和 MXC | inner payload 仅 1/9 有真实样本覆盖；未覆盖的布局不能视为完整兼容 |
| 生命周期 | 同资产重导入保留配置；删除最后引用后延迟释放内存 scene，保留磁盘资产 | 完整编辑、导出、重开和缓存维护的客户端验收 |
| 角色实例 | 本地和远端独立 controller、变量、时间轴、持物输入与 blink；共享只读定义 | 多客户端、切世界和频繁换模的组合验收 |
| 动作 | 主分类、`arm`、`tac`，状态转换、并行层、过渡混合、loop/hold/once、instruction、controller-local 完成查询 | 其他历史分类、完整控制器语义和高级表达式 |
| 第一人称 | 独立手臂 geometry、`fp_arm` controller；没有专用模型时可按前臂过滤 | 各模型、左右手、FOV、TaCZ gunpack 的显示与遮挡矩阵 |
| 持物 | locator、主副手、完整 ID/tag/类型/use 条件、locator 零缩放隐藏、普通物品与 TaCZ 渲染接入 | 通用变体选择、替换优先级和缺失资源 fallback |
| 可选模组 | Java 输入 provider SPI、内置 TaCZ 适配及缺失依赖降级 | 其他模组需要具体适配器；SPI 存在不代表已兼容 |
| 工作台 | RWYM 随项目携带、来源恢复、动作和 controller 元数据检查 | 完整 YSM runtime 预览尚未接入 |
| 脚本 | 普通角色有 JSON/KubeJS 动作包；YSM 有独立 runtime | YSM 分支尚未调用普通角色的 per-profile script hooks |
| 声音、函数与特效 | 原生资产保存相关源数据；通用引擎有材质和物理能力 | 保存数据不等于执行；YSM 自定义函数、声效及来源专用物理未完整消费 |
| 平台 | 六平台原生构建、CTest、架构/打包校验及两版本构建已通过 CI | 各平台 Minecraft 画面与玩法不能由 native CTest 代替 |

### 已完成的近期修复

- [x] 持物条件支持 `$namespace:item`、`#namespace:tag`、类型、使用动画和空手；逻辑主副手跟随惯用手。
- [x] `ctrl.use` 遵循 YSM 的全局使用条件，`q.use_*` 保留实际使用手语义。
- [x] 原版船和可骑乘实体输入接到 `boat` / `ride`，保留 provider 输入。
- [x] controller-local `all_animations_finished` / `any_animation_finished` 支持自动结束转换。
- [x] 重复导入保留同资产配置；render-cache hash/revision 改变时更新 scene。
- [x] 删除副本清理无引用导入映射；活跃本地、远端和工作台释放后才回收 scene。
- [x] 每 owner 缓存最新持物快照，求值复用不可变 token 与函数索引。

## P0：优先影响现有模型体验

### P0-1 模型函数、表达式与控制器语义

现有 Molang 是子集。C++ 已保存 functions，但 Java 资产视图尚未把它们接到求值器；
`t.*` 赋值当前未保留，未知函数也可能得到零值。这些语义缺口会让模型加载成功但动作不对。

- [ ] 从 RWYM 暴露模型函数，按角色实例绑定参数与变量，补充 coded/hybrid/subcontroller 的所需语义。
- [ ] 实现临时变量作用域，补齐真实样本需要的 builtin、query 和表达式行为。
- [ ] 未支持函数/符号给出模型、controller、clip 和位置上下文，限频记录 fallback。
- [ ] 限制调用深度、求值步数与输入长度；编译定义共享，变量与执行状态按实例隔离。

验收：带自定义函数的真实模型驱动状态转换和骨骼动作；两角色变量互不污染，坏表达式有可定位日志。

### P0-2 工作台实时预览与编辑闭环

当前 Actions / Behavior 主要检查导入数据；同名 baked clip 的通用预览不等于运行 YSM controller。
portable prepare/open/import/packageFiles 已接线，不再列为尚未接入的 API。

- [ ] 为工作台创建独立 `YsmRuntimeInstance`，不借用游戏玩家的状态。
- [ ] 支持播放、暂停、重置和输入模拟；seek 按控制器重置/重放规则实现，避免直接改时钟造成状态错误。
- [ ] 显示当前状态、层、变量和未支持能力；允许模拟持物、使用、移动及模组输入。
- [ ] 用新进程完成 Characters → Edit → Save/Export → 清理作者缓存 → Reopen 的客户端往返。

### P0-3 物品替换与变体

已支持的 locator、条件匹配、零缩放隐藏和骨骼可见性动画继续保留；剩余是完整选择策略。

- [ ] 明确原版 ItemStack、模型内置武器和独立变体的互斥、优先级与回退规则。
- [ ] 按物品 ID/tag/类型、使用状态、枪械 ID 和主副手选择变体。
- [ ] 资源或 locator 缺失时保留可用的原版物品，避免隐藏后空手。
- [ ] 覆盖 Flandre 等自带武器模型的换手、切物品、装填和换模场景。

## P1：扩展可定制行为

### P1-1 额外动作分类与模组适配

`fp_arm` 已接入；`extra`、`arrow`、`carryon`、`parcool`、`swem`、`slashblade`、`tlm`
等分类的完整触发和组合仍需实现。TaCZ 已有基础适配，不能和尚无适配器的模组混列为完全未支持。

- [ ] 定义分类的触发条件、骨骼 mask、覆盖优先级、手势入口和中断恢复策略。
- [ ] 按需实现 SlashBlade、CarryOn、ParCool、Swem、TLM、Iron's Spell Books 等 provider。
- [ ] 继续覆盖 TaCZ 枪械 ID/类型、ADS、装填、近战、开火和双视角；缺失模组时独立降级。
- [ ] 对远端玩家只使用可获得的同步状态，不把本地玩家输入复制给远端。

### P1-2 YSM 专用 JSON / KubeJS 控制

通用角色脚本 API 和 Java provider SPI 已有；YSM-owned scene 当前走独立更新分支，
尚不能用普通角色 hook 自由覆盖其行为。

- [ ] profile/JSON 选择 controller、clip、category、provider、fallback 和遮罩。
- [ ] KubeJS 提供输入扩展、求解前后 hook、动作覆盖、变体与事件接口。
- [ ] 明确导入行为、脚本、IK、物理的执行顺序与所有权，保持 Java 层通用。
- [ ] reload 校验后原子替换，失败保留有效版本；解析/编译不放在逐帧渲染路径。

### P1-3 音效、材质特效与来源物理

目前通用 Spring/Bullet 和 PBR 能力不等于已经还原 YSM 的来源语义。

- [ ] 从 RWYM 到 Java runtime 保留并执行 animation/controller 声音与事件，正确去重、取消和循环。
- [ ] 接入客户端粒子，以及来源支持的 glow/color/transparency、材质通道与贴图选择。
- [ ] 将 YSM 一阶/二阶物理及其约束转换为明确的 RigWorks 求解配置；区分 authored 与自行补充的物理。
- [ ] 状态跳转、seek、切世界和远端显示不会重复播放声音或遗留模拟状态。

### P1-4 模型配置表单与可同步变量

普通 `v.*` 每角色持久变量已支持；`v.roaming.*` 的跨客户端语义和来源配置表单尚未实现。

- [ ] 将模型可配置项转换成有类型、范围、默认值的 JSON schema 和 GUI。
- [ ] 明确本地变量与允许分享的变量，只同步白名单内的小型状态。
- [ ] 重连、版本不一致和模型切换有恢复策略；服务端继续权威处理位置、碰撞和伤害。
- [ ] 网络仍不传模型、贴图、声音；远端显示要求接收端已有相同资产。

## P2：扩大资产兼容范围

### P2-1 更多来源格式与资产工具

当前 Legacy V3 内层 payload 1/9 有真实样本；1..15 有解析分支，但未全部获得真实数据覆盖。
V1/V2 外层和 Modern MXC 明确不支持，不应显示为损坏的 V3 文件。

- [ ] 收集并回归其余 payload 布局；新增格式采用显式版本分派与转换器升级。
- [ ] 完善无效 profile、孤立磁盘缓存和失败临时文件的可诊断维护工具。
- [ ] 按需导出 Blockbench JSON/FBX 作为编辑交换文件，同时携带 RWYM 的行为与来源数据。
- [ ] 保持转换一次、缓存复用与版本迁移；不把解密放入逐帧或每次选角路径。

## 两类持续验收

### V1 客户端、多人和着色器回归

这些是已有功能的验证工作，不应再写成全部未实现。

- [ ] 蹲伏/落地脚底、游泳、鞘翅俯仰、F5 头部、骑乘/睡眠、连续攻击和 use 时序。
- [ ] 左右惯用手、弓/弩/鱼竿/盾/地图/望远镜、盔甲/披风/鞘翅、第一人称边缘与 FOV。
- [ ] 两个不同 YSM 玩家同时显示、独立 blink/controller/provider、删除/重导入/切世界。
- [ ] TaCZ 安装/未安装、不同 gunpack、ADS/后坐力/瞄具，以及激活的 Iris/Oculus shader pack。
- [ ] 非 Windows x64 平台的 Minecraft 客户端与导入体验；六平台 native 测试通过不代替这些验收。

### V2 代表性性能与内存测量

只读几何、纹理与编译定义共享、每 owner 小型状态、持物快照缓存和延迟释放已有实现。

- [ ] 分别测少量/大量角色的 CPU 求值、骨骼、draw、内存、分配率及帧时间分位数。
- [ ] 覆盖不同骨骼/材质数、密集表达式、远近距离、换模和反复导入删除；检查内存是否回落。
- [ ] 先由数据定位瓶颈，再决定 Native/SIMD、GPU palette、MDI 或 compute 的投入。
- [ ] 估算数据与实测数据分开记录，单张截图的 FPS 不作为压测结果。

## 已有验证证据

- 2026-11-25 本地离线回归：NeoForge 1.21.1 共 442 项，439 通过、3 跳过；
  Forge 1.20.1 共 436 项，433 通过、3 跳过。完整构建、integration 编译、模块边界与 bundled-JAR 检查通过。
- [CI run 45500656880](https://github.com/MineRealms/RigWorks/actions/runs/45500656880)
  对 `16a971e` 的六平台 native 构建、真实 fixture/C ABI CTest、架构检查、产物收集和两版本构建均成功。
  平台为 Windows/Linux/macOS 各 x64、ARM64。
- `_Rumia.ysm`、`168.ysm`、`2_0lingsha(1).ysm` 离线读取到 225/130/97 clips 和
  456/371/296 expressions；坏表达式 fallback 为 1/1/0。这证明转换与准备路径，不证明所有源行为正确。
- 近期持物条件、完成转换、重导入保留和延迟回收改动尚未新增客户端验收。

## 执行规则

优先顺序：`P0-1 → P0-2 → P0-3 → P1`；V1 随对应功能复测，V2 随高开销改动测量。
导入可靠性与明显的动作/持物回归随发现随修，不等待新特性排期。

实现时补充针对语义风险的离线测试，客户端用 Rosetta 检查日志、数值与画面；模型就绪后至少等待
10 秒再截图。客户端验收与离线测试分别记录，当前支持范围以
[转换管线](YSM_RIGWORKS_PIPELINE.md)和[根 README](../../README.md)为准。
