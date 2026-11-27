# Default Model Pack

A collection of characters for RigWorks. FBX characters are ready-to-import
`.rig.zip` Workbench packages. The public collection includes Marisa as an
original `.mmd.zip` source package; the player's client converts it during
**Import MMD**. The supplied Misaka PMD is kept separately as local-only.
The optional collection ZIP must be extracted before selecting a character ZIP.

## Characters

| Package | Contents | Motion |
| --- | --- | --- |
| `madoka-exedra-hd.rig.zip` | Madoka Exedra, PBR and adapted VMD clips | **CLIPS** |
| `madoka-vita-01.rig.zip` | Vita Madoka, first costume, original clips; albedo only | **CLIPS** |
| `madoka-vita-03.rig.zip` | Vita Madoka, alternate costume, original clips; albedo only | **CLIPS** |
| `lusia-hd-pbr.rig.zip` | Lusia, PBR and locomotion clips | **CLIPS** |
| `madoka-pixelized.rig.zip` | Starpop pink-haired block character, clips, expressions and springs | **CLIPS** |
| `misaka-pixelized.rig.zip` | Pixel Misaka, pixel PBR and springs | **DEFAULT** |
| `marisa.mmd.zip` | Original Marisa R PMX and textures; converted on import | **DEFAULT** |

The supplied Misaka Mikoto Ver1.0 PMD prohibits redistribution in its original
`readme.txt`. Its `misaka-mikoto.mmd.zip` is a **local-only personal import
package**, excluded from public release assets and the downloadable collection.
It can be distributed only with the original author's permission.

The Vita models have color/albedo textures. They do not include a complete PBR
map set. PBR on the other models uses AssimpLoader's material pipeline; the final
lighting also depends on the shaders and resource packs enabled in Minecraft.

## Import FBX Characters

1. Install compatible RigWorks and AssimpLoader versions.
2. Open RigWorks in the in-game workbench and choose **Import avatar**.
3. Select one character's `.rig.zip` and create an independent copy.
4. Wait for preparation to finish, then apply the imported profile.
5. Select the **Motion** mode listed above. Importing a package does not change
   this client preference automatically.

**CLIPS** plays the motion clips included in that model. **DEFAULT** uses
RigWorks' built-in Java motion preset through the model's calibrated humanoid
mapping. Pixelized Misaka deliberately has no baked clips or behavior scripts.

## Import MMD Sources

Use a RigWorks version with **MMD source ZIP** support. Select **Import MMD**,
choose one `.mmd.zip`, wait for conversion, then select **DEFAULT** motion.
The ZIP contains the original PMX/PMD and its textures, with a checked
`rigworks.mmd-source` manifest. No preconverted GLB or baked replacement model
is distributed in the source ZIP. Conversion happens on the player's client,
is cached on disk, and is not repeated during playback. CustomSteve is not
required. Older RigWorks versions can extract the ZIP and select the model
inside `assets/` with **Import MMD**.

MMD appearance is subject to the importer's supported features. For example,
SDEF/QDEF use linear skinning approximations, dedicated MMD material effects
are not reproduced, and generic physics is limited to 128 bodies. See the
[MMD guide](../guides/MMD_IMPORT.md) for the full scope.

## Physics And Customization

Starpop and Pixelized Misaka include cosmetic spring motion for hair and skirt
panels. Enable **Rig physics** in the character settings to use it. Physics
configuration varies by model; it is not cloth self-collision and does not
change the server's player collision box.

Use the workbench to adjust the character's scale, orientation, humanoid
mapping, equipment sockets and physics settings. Where a package includes
avatar scripts, their JSON and JavaScript sources belong to that imported
copy and can be edited independently. The included clip-based and DEFAULT
models do not require global KubeJS client scripts to play their basic motion.

## Credits

The HD models were collected as free online resources. Model, texture and
character rights remain with their original authors and rights holders.
This pack does not claim ownership of those assets or replace their original
terms of use.

- Madoka Exedra comes from the Magi3Dviewer `chara_100107_battle_unit` asset.
- The Vita Madoka variants originate from the *Puella Magi Madoka Magica:
  The Battle Pentagram* model collection.
- Lusia retains the rights of its original model and texture owners.
- The Marisa and Misaka Mikoto source packages retain the rights of their
  original MMD model and character owners.

The Pixelized Starpop / Madoka and Pixelized Misaka models were authored and
exported in Blender by **CharlesKo**. Copyright (c) **CharlesKo**.

RigWorks configuration and format conversion do not transfer ownership of the
underlying character or source assets.

HD 资源为网络搜集的免费资源，原模型、贴图和角色版权仍归原权利人。
Pixelized 模型由 CharlesKo 在 Blender 制作并导出，版权归 CharlesKo。
