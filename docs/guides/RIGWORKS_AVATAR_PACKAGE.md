# RigWorks 角色副本与 ZIP

RigWorks 导入角色后，会在 AssimpLoader 的同一工作台中编辑模型覆盖配置。FBX、网格和贴图仍是源资产；变换、节点、材质、挂点、RigWorks 角色参数和动作脚本作为可独立导入的覆盖数据保存。导出不会改写 FBX。

## 副本身份

每个 RigWorks 角色副本都有稳定的 UUID。profile 与脚本作者目录按这个 UUID 隔离：

```text
profile: rigworks:avatars/<uuid>/profile.json
scripts: kubejs/rigworks/avatars/<uuid>/
```

副本 UUID 不随脚本内容变化。脚本包的 manifest `version` 是文件格式版本；内容 SHA-256 则随脚本或副本身份变化。两个副本可以引用同一 FBX，但它们各自保存 profile、变换覆盖和脚本，不会因为其中一个副本 Apply 而改动另一个副本。

RigWorks 脚本放在 `kubejs/rigworks/avatars/`，不进入 KubeJS 全局 `client_scripts`。每个包使用独立的有界脚本运行环境；相同 controller ID 也不会共享 JavaScript 状态。

## 导出选项

模型文件和 PBR 贴图是 ZIP 的必需内容。其他数据在导出对话框中单独勾选：

| 选项 | 内容 |
| --- | --- |
| Rig and animation setup | Humanoid 骨骼映射、动作到 FBX clip 的映射、Rig solver/IK/morph 等角色设置 |
| Equipment and hand transforms | 左右手挂点、握持规则及装备 IK |
| Character root transform | 角色根节点的位置、旋转和缩放覆盖 |
| Physics and runtime options | 角色物理刚体、关节和运行开关 |
| KubeJS avatar scripts | 该副本的 manifest、映射和所有 JSON/JS 脚本 |

未勾选的可选数据不会进入导出 ZIP。取消 KubeJS 后会同时从项目元数据和归档目录中移除脚本快照，避免旧脚本在重新导入时残留。AssimpLoader 的普通节点、材质、socket 和动画预览数据也可在同一对话框中选择。

`.instance.json` 是 AssimpLoader 随模型资源发现的通用实例 sidecar，不是 RigWorks 的 `equipment.json`。如果 sidecar 已位于工作台项目的模型资产目录中，它随必需模型文件一并导出；它定义的状态、通用挂点、脚底 IK、弹簧和插值不会被 RigWorks 手持物挂点替代。

## 同一副本再次导入

导入带 RigWorks 标记的角色 ZIP 时，普通 Import、Open、拖入和 RigWorks 入口统一先选择 **Create an independent copy** 或 **Update the archive's existing copy**。新副本总是分配新的 UUID、profile 和脚本目录，即使包内容完全相同也不会共享可编辑配置；直接打开已经保存的本地项目则保留其身份。

选择更新原副本后，若脚本内容与本地一致则复用该副本；内容不同会继续询问：

- **Replace this copy's scripts**：只覆盖 UUID、profile 和脚本 ID 完全对应的本地副本，使用暂存和原子目录替换；失败时保留原脚本。
- **Import as a new copy**：分配新的 UUID、profile 和脚本目录。新副本之后可以独立修改与分发。
- **Keep the existing scripts**：采用本地脚本，保留 ZIP 中角色的其他可编辑配置。

取消对话框会恢复上一个编辑会话，并删除本次新解压的项目目录。普通裸模型通过 AssimpLoader 导入时仍是普通模型；RigWorks 的 Import avatar 才会为裸 FBX 创建角色 profile 和 `[RigWorks]` 编辑能力。已有 RigWorks 标记的 ZIP 则在全部入口恢复角色导入流程。

选择更新且脚本 hash 完全一致时，不会重写本地脚本文件。更新可以新增工作台项目目录，但它明确使用包内同一个角色身份；需要彼此独立的配置时选择新副本。

## 导入后的角色列表

导入角色 ZIP 或 FBX 后，在工作台中 **Save / Apply** 才会把该副本保存为可再次选择的本地角色。RigWorks 的 **Characters** 页面同时显示资源包 profile 和已保存的本地 profile。切换回默认角色后，仍可从该列表选回导入的副本；打开页面或点击 Reload 会重新读取列表。

本地 profile 的 ID 是 `rigworks:avatars/<uuid>/profile.json`，它是角色副本标识，不要求磁盘上真的存在相同的资源包路径。角色配置保存在 `config/rigworks/characters/<profile ID 的 SHA-256>.json`，`config/rigworks/profiles.json` 保存可枚举的 ID。模型 ID 则由 `config/rigworks/assets.json` 映射到工作台内的 FBX。再次选中角色时，读取已保存的配置与模型映射，不会重新用默认模板覆盖编辑结果。

早期版本只枚举资源管理器的 `models/**/profile.json`，可能出现“角色已替换，但列表只有 block_player”。这是列表遗漏，不代表 ZIP 无效。新版会从当前角色和本地作者脚本的 profile 身份恢复已有配置；仍未显示的旧项目可以重新打开后 Save / Apply 一次。远端玩家的下载缓存不加入本地可编辑角色列表。

## 资源包与工作台包

旧的资源包方式继续支持，角色资源仍放在 `assets/<命名空间>/models/...`，例如：

```text
pack.mcmeta
assets/example/models/character/avatar/
  profile.json
  model.fbx
  model.instance.json
  equipment.json
  rig.json
  physics.json
  textures/...
```

资源引用仍写成 `example:models/character/avatar/profile.json` 等标准 ID。角色选择页从资源管理器发现这些 profile，并与本地导入的角色合并显示；FBX 的贴图和旁边的实例 sidecar 仍能加载。

将这类模型送进工作台时，AssimpLoader 复制模型依赖并生成 `<模型名>.resources.json`，把原命名空间 ID 映射到项目内文件；RigWorks 通过扩展接口声明 profile、装备、Rig/IK 和物理依赖。RigWorks 导入优先采用这些已制作好的配置，再分配新副本的 profile/脚本身份。它不会把已校准的骨骼和动作映射重新猜一遍。

导出 `.rig.zip` / `.zip` 是 `manifest.json + project.workbench.json + assets/ + extensions/` 格式，不是资源包，下一次从工作台导入。原始 FBX、已有 `.instance.json` 及其父定义随模型资产保存；实例配置也会在本地文件加载路径实际初始化，支持附件、状态机、脚底 IK 和弹簧。原 JSON 不被改写，运行时只在内存中重绑定模型身份。

## 默认角色 JSON

`src/main/resources/assets/rigworks/models/character/block_player/` 中的默认配置都被代码使用：

| 文件 | 作用 | 运行状态 |
| --- | --- | --- |
| `profile.json` | 角色入口；模型资源、Humanoid 骨骼、动画映射，以及 Rig、物理和装备定义的资源引用 | 必需，角色选择与加载时读取 |
| `equipment.json` | 左右手挂点和弓、弩等握持/双手 IK 规则 | 活跃；共享配置可覆盖默认值 |
| `physics.json` | 身体刚体、碰撞形状、质量、关节和弹簧 | 活跃，但只有启用角色物理时创建物理修饰器 |
| `rig.json` | 骨骼追加、IK、morph、组合动作和程序化 motion | 活跃；文件中的 `left_leg_probe` 默认禁用，`solver_probe` 需脚本或调试命令显式播放 |
| `block_player.instance.json` | AssimpLoader 通用实例状态/动画映射、附件、脚底 IK、头发弹簧和渲染插值 | AssimpLoader 按模型资源自动发现；不是 RigWorks 装备定义 |

这些资源文件是 mod 自带的默认模板，不是工作台每次编辑都会直接改写的源文件。角色编辑结果存在工作台项目与 RigWorks 的 visual 覆盖数据中；导出 ZIP 才会把当前勾选的编辑数据和资产打包带走。

## 多人分发

上传与“显示他人角色”由客户端开关控制，服务器还需要安装 RigWorks。服务端按玩家 UUID 保存该玩家的配置版本并分发角色配置与脚本包；它不分发 FBX 或贴图。接收端使用按玩家分隔的校验缓存和运行环境，不把远端脚本放入本地作者目录，也不写入 `client_scripts`。玩家 A、B 即使使用完全相同的模型文件，也有独立的拥有者身份与活动配置。

详细协议见[角色配置同步](CHARACTER_CONFIGURATION_SYNC.md)与[专用动作运行时](AVATAR_BEHAVIOR_RUNTIME.md)。
