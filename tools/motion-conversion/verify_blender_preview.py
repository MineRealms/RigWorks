"""Check saved generated Actions, mesh bounds and playback without the game."""

import json
import math
from pathlib import Path

import bpy
import numpy as np
from mathutils import Quaternion, Vector


REPO = Path(__file__).resolve().parents[2]
WORK = REPO / "build/vmd-motion-preview"
config_path = Path(globals().get("RW_VMD_CONFIG", Path(__file__).with_name("retarget_preview.json")))
config = json.loads(config_path.read_text(encoding="utf-8"))
report = json.loads((WORK / "retarget_report.json").read_text(encoding="utf-8"))
scene = bpy.data.scenes[config["scene"]]
bpy.context.window.scene = scene
if bpy.context.screen.is_animation_playing:
    bpy.ops.screen.animation_cancel(restore_frame=False)
saved_frame = scene.frame_current
results = []


def quaternion_continuity(action):
    groups = {}
    if hasattr(action, "fcurves"):
        curves = action.fcurves
    else:
        curves = [curve for layer in action.layers for strip in layer.strips
                  for bag in strip.channelbags for curve in bag.fcurves]
    for curve in curves:
        if curve.data_path.endswith(".rotation_quaternion"):
            groups.setdefault(curve.data_path, {})[curve.array_index] = curve
    count, minimum_midpoint_magnitude = 0, math.inf
    for path, channels in groups.items():
        if set(channels) != {0, 1, 2, 3}:
            raise RuntimeError("Incomplete quaternion channels: " + action.name + " " + path)
        frames = sorted({float(key.co.x) for curve in channels.values() for key in curve.keyframe_points})
        for left, right in zip(frames, frames[1:]):
            rotations = [Quaternion(tuple(channels[index].evaluate(frame) for index in range(4)))
                         for frame in (left, right, (left + right) * .5)]
            if any(not all(math.isfinite(value) for value in rotation) or rotation.magnitude < .5
                   for rotation in rotations):
                raise RuntimeError("Degenerate quaternion interval: " + action.name + " " + path
                                   + " frames=" + str((left, right)))
            a, b, midpoint = [rotation.normalized() for rotation in rotations]
            if a.dot(b) < -1e-5:
                raise RuntimeError("Antipodal quaternion keys: " + action.name + " " + path
                                   + " frames=" + str((left, right)))
            minimum_midpoint_magnitude = min(minimum_midpoint_magnitude, rotations[2].magnitude)
            count += 1
    return {"intervals": count, "minimumMidpointMagnitude": minimum_midpoint_magnitude}


def mesh_bounds(rig, skirt_indices, capsules):
    minimum = np.full(3, math.inf)
    maximum = np.full(3, -math.inf)
    count = 0
    worst = None
    for obj in scene.objects:
        if obj.type != "MESH" or not any(m.type == "ARMATURE" and m.object == rig for m in obj.modifiers):
            continue
        evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
        mesh = evaluated.to_mesh()
        try:
            coordinates = np.empty(len(mesh.vertices) * 3, dtype=np.float64)
            mesh.vertices.foreach_get("co", coordinates)
            matrix = np.array(evaluated.matrix_world)
            points = coordinates.reshape(-1, 3) @ matrix[:3, :3].T + matrix[:3, 3]
            if not np.isfinite(points).all():
                raise RuntimeError("Non-finite skinned vertex for " + rig.name)
            minimum = np.minimum(minimum, points.min(axis=0))
            maximum = np.maximum(maximum, points.max(axis=0))
            count += len(points)
            skirt_info = skirt_indices.get(obj.name)
            if skirt_info is not None:
                indices = skirt_info["vertices"]
                skirt = points[indices]
                for label, a, b, radius in capsules:
                    segment = b - a
                    along = np.clip((skirt - a) @ segment / max(segment @ segment, 1e-15), 0, 1)
                    distances = np.linalg.norm(skirt - (a + along[:, None] * segment), axis=1) - radius
                    index = int(np.argmin(distances))
                    if worst is None or distances[index] < worst["signedDistance"]:
                        worst = {"mesh": obj.name, "vertex": int(indices[index]), "capsule": label,
                                 "signedDistance": float(distances[index]), "frame": scene.frame_current}
                triangle_indices = skirt_info["triangles"]
                if len(triangle_indices):
                    triangles = points[triangle_indices]
                    samples = np.concatenate((triangles,
                        (triangles[:, [0]] + triangles[:, [1]]) * .5,
                        (triangles[:, [1]] + triangles[:, [2]]) * .5,
                        (triangles[:, [2]] + triangles[:, [0]]) * .5,
                        triangles.mean(axis=1, keepdims=True)), axis=1).reshape(-1, 3)
                    faces = np.repeat(skirt_info["faces"], 7)
                    for label, a, b, radius in capsules:
                        segment = b - a
                        along = np.clip((samples - a) @ segment / max(segment @ segment, 1e-15), 0, 1)
                        distances = np.linalg.norm(samples - (a + along[:, None] * segment), axis=1) - radius
                        sample_index = int(np.argmin(distances))
                        if worst is None or distances[sample_index] < worst["signedDistance"]:
                            worst = {"mesh": obj.name, "vertex": -1, "face": int(faces[sample_index]),
                                     "sample": samples[sample_index].tolist(), "capsule": label,
                                     "signedDistance": float(distances[sample_index]),
                                     "frame": scene.frame_current}
        finally:
            evaluated.to_mesh_clear()
    if not count:
        raise RuntimeError("Missing skinned vertices for " + rig.name)
    return [minimum.tolist(), maximum.tolist()], worst


def skirt_vertex_indices(rig):
    result = {}
    for obj in scene.objects:
        if obj.type != "MESH" or not any(m.type == "ARMATURE" and m.object == rig for m in obj.modifiers):
            continue
        groups = {group.index for group in obj.vertex_groups if group.name.startswith("Skirt_")}
        indices = {vertex.index for vertex in obj.data.vertices
                   if sum(binding.weight for binding in vertex.groups if binding.group in groups) > .05}
        if indices:
            triangles, faces = [], []
            for polygon in obj.data.polygons:
                if len(polygon.vertices) < 3 or not all(index in indices for index in polygon.vertices):
                    continue
                for offset in range(1, len(polygon.vertices) - 1):
                    triangles.append((polygon.vertices[0], polygon.vertices[offset], polygon.vertices[offset + 1]))
                    faces.append(polygon.index)
            result[obj.name] = {"vertices": np.array(sorted(indices), dtype=int),
                                "triangles": np.array(triangles, dtype=int).reshape(-1, 3),
                                "faces": np.array(faces, dtype=int)}
    return result


for target in report["targets"]:
    rig = scene.objects[target["rig"]]
    spec = next(item for item in config["targets"] if item["id"] == target["id"])
    physics = spec.get("skirtPhysics", {})
    skirt_indices = skirt_vertex_indices(rig) if physics.get("enabled") else {}
    data = rig.animation_data
    old_action, old_slot = data.action, data.action_slot
    tracks = list(data.nla_tracks)
    old_mutes = [track.mute for track in tracks]
    try:
        for track in tracks:
            track.mute = True
        for name, clip in target["clips"].items():
            action = bpy.data.actions[clip["action"]]
            continuity = quaternion_continuity(action)
            data.action = action
            if len(action.slots):
                data.action_slot = action.slots[0]
            lowest, highest = math.inf, -math.inf
            start, end = None, None
            worst_skirt = None
            for frame in range(1, max(2, clip["samples"]) + 1):
                scene.frame_set(frame)
                bpy.context.view_layer.update()
                if any(not math.isfinite(value) for bone in rig.pose.bones for row in bone.matrix for value in row):
                    raise RuntimeError("Non-finite pose in " + action.name)
                capsules = []
                if skirt_indices:
                    for side in ("left", "right"):
                        points = [np.array((rig.matrix_world @ rig.pose.bones[spec["roles"][side + suffix]].matrix).translation)
                                  for suffix in ("_thigh", "_shin", "_foot")]
                        capsules.extend([(side + "_thigh", points[0], points[1], physics.get("thighRadius", .095)),
                                         (side + "_shin", points[1], points[2], physics.get("shinRadius", .09))])
                bounds, collision = mesh_bounds(rig, skirt_indices, capsules)
                if collision and (worst_skirt is None or collision["signedDistance"] < worst_skirt["signedDistance"]):
                    worst_skirt = collision
                lowest = min(lowest, bounds[0][2])
                highest = max(highest, bounds[1][2])
                positions = {role: list((rig.matrix_world @ rig.pose.bones[node].matrix).translation)
                             for role, node in target["mappedRoles"].items()}
                if start is None:
                    start = positions
                end = positions
            policy = clip.get("policy", {})
            floor = policy.get("floorClearance", 0)
            if abs(lowest - floor) > .01:
                raise RuntimeError("Ground calibration failed: " + action.name + " floor=" + str(lowest))
            height_range = policy.get("heightRange", [1.4, 2.2])
            if not height_range[0] <= highest <= height_range[1]:
                raise RuntimeError("Unexpected posed height: " + action.name + " top=" + str(highest))
            seam = max((Vector(start[role]) - Vector(end[role])).length for role in start)
            if clip.get("loop", name in config.get("loopClips", [])) and seam > 1e-4:
                raise RuntimeError("Loop endpoint mismatch: " + action.name + " error=" + str(seam))
            hand_targets = clip.get("handTargets")
            if hand_targets and hand_targets["maxTargetError"] > policy.get("maxHandTargetError", .03):
                raise RuntimeError("Unreachable hand target: " + action.name + " error=" + str(hand_targets["maxTargetError"]))
            leg_targets = clip.get("legTargets")
            if leg_targets and (leg_targets["maxAnkleTargetError"] > .001 or leg_targets["maxFootMatrixError"] > .0001):
                raise RuntimeError("Leg IK failed: " + action.name + " targets=" + str(leg_targets))
            if worst_skirt and worst_skirt["signedDistance"] < -physics.get("penetrationTolerance", .002):
                raise RuntimeError("Skirt/leg collision after baking: " + action.name + " " + str(worst_skirt))
            results.append({"action": action.name, "floorMinimum": lowest, "maximumHeight": highest,
                            "endpointJointDifference": seam, "finite": True,
                            "policy": policy, "handTargets": hand_targets, "legTargets": leg_targets,
                            "quaternionContinuity": continuity,
                            "skirtCollision": worst_skirt,
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
print(json.dumps({"status": output["status"], "actions": len(results),
                  "originalBlockActions": output["originalBlockActions"], "gameplay": output["gameplay"]}, indent=2))
