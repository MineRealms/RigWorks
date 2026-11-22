"""Append existing avatar scenes without changing the source .blend files."""

import json
from pathlib import Path

import bpy


REPO = Path(__file__).resolve().parents[2]
WORK = REPO / "build/vmd-motion-preview"
WORK.mkdir(parents=True, exist_ok=True)


def append_scene(path, name):
    existing = next((scene for scene in bpy.data.scenes
                     if scene.get("rw_vmd_source") == str(path) and scene.get("rw_vmd_scene") == name), None)
    if existing:
        return existing
    with bpy.data.libraries.load(str(path), link=False) as (source, target):
        if name not in source.scenes:
            raise RuntimeError(f"Missing scene {name} in {path}: {source.scenes}")
        target.scenes = [name]
    scene = target.scenes[0]
    scene["rw_vmd_source"] = str(path)
    scene["rw_vmd_scene"] = name
    return scene


block_scene = append_scene(REPO / "model-tests/block_player/block_player.blend", "BlockPlayer_CharacterEngine")
madoka_file = Path("G:/DOWNLOAD-EDGE/madoka-exedra-100107-work/madoka_exedra_minecraft_1_85.blend")
madoka_scene = append_scene(madoka_file, "Madoka_Minecraft_Actions") if madoka_file.exists() else None

report = []
for scene in [block_scene, madoka_scene]:
    if scene is None:
        continue
    rigs = [obj for obj in scene.objects if obj.type == "ARMATURE"]
    report.append({"scene": scene.name, "rigs": [
        {"name": rig.name, "bones": len(rig.data.bones), "matrix": [list(row) for row in rig.matrix_world],
         "action": rig.animation_data.action.name if rig.animation_data and rig.animation_data.action else None,
         "tracks": [track.name for track in rig.animation_data.nla_tracks] if rig.animation_data else []}
        for rig in rigs], "meshes": [obj.name for obj in scene.objects if obj.type == "MESH"]})

bpy.context.window.scene = block_scene
(WORK / "target_inventory.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=True, indent=2))
