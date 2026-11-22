"""Check saved generated Actions, mesh bounds and playback without the game."""

import json
import math
from pathlib import Path

import bpy
from mathutils import Vector


REPO = Path(__file__).resolve().parents[2]
WORK = REPO / "build/vmd-motion-preview"
config = json.loads(Path(__file__).with_name("retarget_preview.json").read_text(encoding="utf-8"))
report = json.loads((WORK / "retarget_report.json").read_text(encoding="utf-8"))
scene = bpy.data.scenes[config["scene"]]
bpy.context.window.scene = scene
if bpy.context.screen.is_animation_playing:
    bpy.ops.screen.animation_cancel(restore_frame=False)
saved_frame = scene.frame_current
results = []


def mesh_bounds(rig):
    points = []
    for obj in scene.objects:
        if obj.type != "MESH" or not any(m.type == "ARMATURE" and m.object == rig for m in obj.modifiers):
            continue
        evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
        mesh = evaluated.to_mesh()
        try:
            points.extend(evaluated.matrix_world @ vertex.co for vertex in mesh.vertices)
        finally:
            evaluated.to_mesh_clear()
    if not points or any(not math.isfinite(value) for point in points for value in point):
        raise RuntimeError("Missing/non-finite skinned vertices for " + rig.name)
    return [[min(point[i] for point in points) for i in range(3)],
            [max(point[i] for point in points) for i in range(3)]]


for target in report["targets"]:
    rig = scene.objects[target["rig"]]
    data = rig.animation_data
    old_action, old_slot = data.action, data.action_slot
    tracks = list(data.nla_tracks)
    old_mutes = [track.mute for track in tracks]
    try:
        for track in tracks:
            track.mute = True
        for name, clip in target["clips"].items():
            action = bpy.data.actions[clip["action"]]
            data.action = action
            if len(action.slots):
                data.action_slot = action.slots[0]
            lowest, highest = math.inf, -math.inf
            start, end = None, None
            for frame in range(1, max(2, clip["samples"]) + 1):
                scene.frame_set(frame)
                bpy.context.view_layer.update()
                if any(not math.isfinite(value) for bone in rig.pose.bones for row in bone.matrix for value in row):
                    raise RuntimeError("Non-finite pose in " + action.name)
                bounds = mesh_bounds(rig)
                lowest = min(lowest, bounds[0][2])
                highest = max(highest, bounds[1][2])
                positions = {role: list((rig.matrix_world @ rig.pose.bones[node].matrix).translation)
                             for role, node in target["mappedRoles"].items()}
                if start is None:
                    start = positions
                end = positions
            if lowest < -.001 or abs(lowest) > .01:
                raise RuntimeError("Ground calibration failed: " + action.name + " floor=" + str(lowest))
            if highest < 1.4 or highest > 2.2:
                raise RuntimeError("Unexpected posed height: " + action.name + " top=" + str(highest))
            seam = max((Vector(start[role]) - Vector(end[role])).length for role in start)
            if name in config.get("loopClips", []) and seam > 1e-4:
                raise RuntimeError("Loop endpoint mismatch: " + action.name + " error=" + str(seam))
            results.append({"action": action.name, "floorMinimum": lowest, "maximumHeight": highest,
                            "endpointJointDifference": seam, "finite": True,
                            "fcurves": sum(len(bag.fcurves) for layer in action.layers for strip in layer.strips for bag in strip.channelbags)})
    finally:
        data.action = old_action
        if old_action and old_slot:
            data.action_slot = old_slot
        for track, mute in zip(tracks, old_mutes):
            track.mute = mute

scene.frame_set(saved_frame)
bpy.context.view_layer.update()
output = {"status": "TESTED_BLENDER_AUTHORING_ONLY", "actions": results,
          "originalBlockActions": len(bpy.data.scenes["BlockPlayer_CharacterEngine"].objects["BlockPlayerRig"].animation_data.nla_tracks),
          "gameplay": "UNVERIFIED_LIVE"}
(WORK / "blender_verification.json").write_text(json.dumps(output, indent=2), encoding="utf-8")
print(json.dumps(output, indent=2))
