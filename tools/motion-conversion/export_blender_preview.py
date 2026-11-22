"""Export generated VMD Actions without changing the interactive preview.

Run in Blender through blender_bridge.py after retarget_blender_preview.py.
Each target is copied into its own temporary scene. The preview reel is never
exported, and source Actions, material images and object transforms stay intact.
"""

import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import uuid

import bpy
from mathutils import Matrix, Vector


REPO = Path(__file__).resolve().parents[2]
CONFIG_PATH = Path(globals().get("RW_VMD_CONFIG", Path(__file__).with_name("retarget_preview.json")))
CONFIG = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
WORK = (REPO / CONFIG["output"]).parent
REPORT_PATH = WORK / "retarget_report.json"
REPORT = json.loads(REPORT_PATH.read_text(encoding="utf-8"))
EXPORTS = WORK / "exports"
FPS = float(REPORT["fps"])


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("Unsafe export target identifier: " + repr(value))
    return value


def assign_action(rig, action):
    rig.animation_data_create()
    rig.animation_data.action = action
    slots = list(getattr(action, "slots", ()))
    if len(slots) > 1:
        raise ValueError("Expected one armature Action slot: " + action.name)
    if slots:
        rig.animation_data.action_slot = slots[0]


def skin_weights(obj, rig):
    bone_names = {bone.name for bone in rig.data.bones if bone.use_deform}
    groups = {group.index: group.name for group in obj.vertex_groups}
    weighted_bones = set()
    summary = {"mesh": obj.name, "vertices": len(obj.data.vertices),
               "unweightedVertices": 0, "nonNormalizedVertices": 0,
               "blendedVertices": 0, "maximumInfluences": 0,
               "maximumWeightSumError": 0.0}
    for vertex in obj.data.vertices:
        weights = []
        for binding in vertex.groups:
            name = groups.get(binding.group)
            if name not in bone_names:
                continue
            value = float(binding.weight)
            if not math.isfinite(value) or value < 0 or value > 1.0001:
                raise ValueError("Invalid raw skin weight: " + obj.name + "/" + str(vertex.index))
            if value > 0:
                weighted_bones.add(name)
                weights.append(value)
        total = math.fsum(weights)
        error = abs(total - 1.0)
        summary["unweightedVertices"] += total <= 0
        summary["nonNormalizedVertices"] += error > 1e-4
        summary["blendedVertices"] += len(weights) > 1
        summary["maximumInfluences"] = max(summary["maximumInfluences"], len(weights))
        summary["maximumWeightSumError"] = max(summary["maximumWeightSumError"], error)
    summary["weightedBones"] = sorted(weighted_bones)
    if not obj.data.vertices or summary["unweightedVertices"] or summary["nonNormalizedVertices"]:
        raise ValueError("Raw deform weights failed: " + json.dumps(summary))
    return summary


def bounds(meshes):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    minimum = Vector((math.inf, math.inf, math.inf))
    maximum = Vector((-math.inf, -math.inf, -math.inf))
    count = 0
    for obj in meshes:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        try:
            for vertex in mesh.vertices:
                point = evaluated.matrix_world @ vertex.co
                if any(not math.isfinite(value) for value in point):
                    raise ValueError("Non-finite deformed vertex: " + obj.name)
                for axis in range(3):
                    minimum[axis] = min(minimum[axis], point[axis])
                    maximum[axis] = max(maximum[axis], point[axis])
                count += 1
        finally:
            evaluated.to_mesh_clear()
    if not count:
        raise ValueError("The export target has no deformed vertices")
    return {"minimum": list(minimum), "maximum": list(maximum),
            "dimensions": list(maximum - minimum)}


class TemporaryTarget:
    def __init__(self, target_id):
        self.target_id = target_id
        self.scene = bpy.data.scenes.new("RW_VMD_Export_" + target_id + "_" + uuid.uuid4().hex[:8])
        self.objects = []
        self.owned = {"meshes": [], "armatures": [], "materials": [],
                      "node_groups": [], "images": [], "actions": []}
        self.materials = {}
        self.groups = {}
        self.images = {}
        self.textures = []

    def texture(self, image):
        if image in self.images:
            return self.images[image]
        if image.source not in {"FILE", "GENERATED"}:
            raise ValueError("Bake tiled/sequence/movie textures before export: " + image.name)
        clone = image.copy()
        self.owned["images"].append(clone)
        clone.use_fake_user = False
        self.images[image] = clone
        source = Path(bpy.path.abspath(image.filepath, library=image.library)) if image.filepath else None
        suffix = source.suffix.lower() if source is not None and source.suffix else ".png"
        if not re.fullmatch(r"\.[a-z0-9]+", suffix):
            raise ValueError("Invalid texture file extension: " + image.name)
        stem = re.sub(r"[^a-z0-9_-]+", "_", Path(image.name).stem.lower()).strip("_") or "image"
        destination = EXPORTS / (self.target_id.lower() + "_textures") / (str(len(self.images)) + "_" + stem + suffix)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if image.packed_file is not None:
            destination.write_bytes(bytes(image.packed_file.data))
        elif source is not None and source.is_file():
            shutil.copy2(source, destination)
        elif image.source == "GENERATED":
            destination = destination.with_suffix(".png")
            clone.filepath_raw = str(destination)
            clone.file_format = "PNG"
            clone.save()
        else:
            raise FileNotFoundError("Missing texture: " + image.name + " / " + str(source))
        clone.filepath = str(destination)
        if any(int(value) <= 0 for value in image.size):
            raise ValueError("Texture has no image dimensions: " + image.name)
        self.textures.append({"name": image.name, "path": destination.relative_to(EXPORTS).as_posix(),
                              "width": int(image.size[0]), "height": int(image.size[1]),
                              "channels": image.channels, "colorSpace": image.colorspace_settings.name,
                              "bytes": destination.stat().st_size, "sha256": digest(destination)})
        return clone

    def node_tree_images(self, tree):
        for node in tree.nodes:
            if node.type == "TEX_IMAGE" and node.image is not None:
                node.image = self.texture(node.image)
            elif node.type == "GROUP" and node.node_tree is not None:
                original = node.node_tree
                if original not in self.groups:
                    clone = original.copy()
                    self.owned["node_groups"].append(clone)
                    clone.use_fake_user = False
                    self.groups[original] = clone
                    self.node_tree_images(clone)
                node.node_tree = self.groups[original]

    def material(self, original):
        if original not in self.materials:
            clone = original.copy()
            clone.use_fake_user = False
            self.owned["materials"].append(clone)
            self.materials[original] = clone
            if clone.use_nodes:
                self.node_tree_images(clone.node_tree)
        return self.materials[original]

    def clone(self, original, rig, offset):
        obj = original.copy()
        obj.data = original.data.copy()
        self.owned["armatures" if obj.type == "ARMATURE" else "meshes"].append(obj.data)
        self.objects.append(obj)
        self.scene.collection.objects.link(obj)
        matrix = original.matrix_world.copy()
        matrix.translation -= offset
        obj.animation_data_clear()
        obj.parent = rig
        obj.parent_type = "OBJECT"
        obj.matrix_parent_inverse = Matrix.Identity(4)
        obj.matrix_world = matrix
        obj.hide_viewport = False
        obj.hide_render = False
        obj.hide_set(False)
        if obj.type == "ARMATURE":
            obj.data.pose_position = "POSE"
            for bone in obj.pose.bones:
                for constraint in bone.constraints:
                    constraint.mute = True
        else:
            for modifier in obj.modifiers:
                if modifier.type == "ARMATURE":
                    modifier.object = rig
            if obj.data.shape_keys is not None:
                if obj.data.shape_keys == original.data.shape_keys:
                    raise ValueError("Mesh copy did not isolate shape keys: " + original.name)
                obj.data.shape_keys.animation_data_clear()
            for slot in obj.material_slots:
                if slot.material is not None:
                    slot.material = self.material(slot.material)
        return obj

    def close(self):
        for obj in reversed(self.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
        for collection in ("meshes", "armatures", "materials", "node_groups", "images", "actions"):
            for item in reversed(self.owned[collection]):
                getattr(bpy.data, collection).remove(item, do_unlink=True)
        bpy.data.scenes.remove(self.scene)


def export_target(target, spec, source_scene, window):
    target_id = identifier(target["id"])
    source_rig = source_scene.objects.get(target["rig"])
    if source_rig is None or source_rig.type != "ARMATURE":
        raise ValueError("Missing generated target rig: " + target["rig"])
    source_meshes = [obj for obj in source_scene.objects if obj.type == "MESH" and
                     any(mod.type == "ARMATURE" and mod.object == source_rig for mod in obj.modifiers)]
    if not source_meshes:
        raise ValueError("Missing skinned meshes for " + target_id)
    weights = [skin_weights(obj, source_rig) for obj in source_meshes]
    offset = Vector(spec["offset"])
    if any(not math.isfinite(value) for value in offset):
        raise ValueError("Non-finite preview offset for " + target_id)
    temporary = TemporaryTarget(target_id)
    fallback_scene = window.scene
    try:
        window.scene = temporary.scene
        scene = temporary.scene
        scene.render.fps = round(FPS)
        scene.render.fps_base = round(FPS) / FPS
        scene.unit_settings.system = "METRIC"
        scene.unit_settings.scale_length = 1
        rig = temporary.clone(source_rig, None, offset)
        meshes = [temporary.clone(obj, rig, offset) for obj in source_meshes]
        rig.animation_data_create()
        rig.animation_data.use_nla = False
        clips = []
        for name, entry in target["clips"].items():
            original_action = bpy.data.actions.get(entry["action"])
            if original_action is None:
                raise ValueError("Missing baked Action: " + entry["action"])
            start, end = 1, max(2, int(entry["samples"]))
            if entry["samples"] < 1 or not math.isfinite(float(entry["duration"])):
                raise ValueError("Invalid clip timing: " + name)
            if abs(float(original_action.frame_range[0]) - start) > 1e-4 or abs(float(original_action.frame_range[1]) - end) > 1e-4:
                raise ValueError("Baked Action range differs from retarget report: " + original_action.name)
            action = original_action.copy()
            temporary.owned["actions"].append(action)
            action.use_fake_user = False
            assign_action(rig, action)
            rotation_path = ("rotation_quaternion" if rig.rotation_mode == "QUATERNION" else
                             "rotation_axis_angle" if rig.rotation_mode == "AXIS_ANGLE" else "rotation_euler")
            # FBX takes must retain the imported armature's upright frame and scale.
            for frame in (start, end):
                for channel in ("location", rotation_path, "scale"):
                    rig.keyframe_insert(data_path=channel, frame=frame, group="ExportObjectTransform")
            samples = []
            for frame in sorted({float(start), (start + end) / 2, float(end)}):
                whole = math.floor(frame)
                scene.frame_set(whole, subframe=frame - whole)
                bpy.context.view_layer.update()
                samples.append({"frame": frame, **bounds(meshes),
                                "joints": {node: list((rig.matrix_world @ rig.pose.bones[node].matrix).translation)
                                           for node in target["mappedRoles"].values()}})
            track = rig.animation_data.nla_tracks.new()
            track.name = "RWExport_" + name
            strip = track.strips.new(original_action.name, start, action)
            strip.name = original_action.name
            if getattr(action, "slots", None):
                strip.action_slot = action.slots[0]
            strip.action_frame_start = start
            strip.action_frame_end = end
            strip.frame_start = start
            strip.frame_end = end
            strip.repeat = 1
            strip.blend_type = "REPLACE"
            strip.extrapolation = "NOTHING"
            clips.append({"name": name, "action": original_action.name, "expectedTakeName": strip.name,
                          "startFrame": start, "endFrame": end, "sourceDurationSeconds": float(entry["duration"]),
                          "expectedDurationSeconds": (end - start) / FPS, "samples": entry["samples"],
                          "sourceVmd": entry.get("sourceVmd"), "sourceStartFrame": entry.get("sourceStartFrame"),
                          "sourceEndFrame": entry.get("sourceEndFrame"), "loop": entry.get("loop", False),
                          "policy": entry.get("policy", {}),
                          "boundsSamples": samples})
        if not clips:
            raise ValueError("No baked clips for " + target_id)
        rig.animation_data.action = None
        rig.animation_data.use_nla = True
        scene.frame_start = 1
        scene.frame_end = max(clip["endFrame"] for clip in clips)
        scene.frame_set(1)
        bpy.context.view_layer.update()
        for obj in bpy.context.selected_objects:
            obj.select_set(False)
        for obj in [rig, *meshes]:
            obj.select_set(True)
        bpy.context.view_layer.objects.active = rig
        # Blender uses the FBX basename for embedded .fbm paths. Minecraft requires lowercase.
        model = EXPORTS / (target_id.lower() + ".fbx")
        result = bpy.ops.export_scene.fbx(
            filepath=str(model), check_existing=False, use_selection=True,
            object_types={"ARMATURE", "MESH"}, global_scale=1,
            axis_forward="-Z", axis_up="Y", apply_unit_scale=True,
            apply_scale_options="FBX_SCALE_NONE", bake_space_transform=False,
            use_mesh_modifiers=False, mesh_smooth_type="FACE", use_triangles=True,
            add_leaf_bones=False, use_armature_deform_only=False,
            bake_anim=True, bake_anim_use_all_bones=True,
            bake_anim_use_nla_strips=True, bake_anim_use_all_actions=False,
            bake_anim_force_startend_keying=True, bake_anim_step=1,
            bake_anim_simplify_factor=0, path_mode="COPY", embed_textures=True,
        )
        if "FINISHED" not in result or not model.is_file():
            raise RuntimeError("FBX export did not finish for " + target_id)
        return {"id": target_id, "model": model.name, "bytes": model.stat().st_size,
                "sha256": digest(model), "fps": FPS,
                "removedPreviewOffset": list(offset), "armatureWorldMatrix": [list(row) for row in rig.matrix_world],
                "boneCount": len(rig.data.bones), "bones": list(rig.data.bones.keys()),
                "meshCount": len(meshes), "vertexCount": sum(len(obj.data.vertices) for obj in meshes),
                "rawSkinWeights": weights, "clipCount": len(clips), "clips": clips,
                "textures": temporary.textures}
    finally:
        window.scene = fallback_scene
        temporary.close()


def main():
    if bpy.context.mode != "OBJECT":
        raise ValueError("Finish the current Edit/Pose operation before exporting")
    if not math.isfinite(FPS) or FPS <= 0:
        raise ValueError("Invalid sample frame rate")
    window = bpy.context.window
    if window is None:
        raise RuntimeError("Run this exporter through the open Blender bridge")
    preview = bpy.data.scenes.get(CONFIG["scene"])
    if preview is None:
        raise ValueError("Bake the generated preview scene before exporting")
    specs = {spec["id"]: spec for spec in CONFIG["targets"]}
    asset_names = set()
    for target in REPORT["targets"]:
        if target["id"] not in specs:
            raise ValueError("Missing target configuration: " + target["id"])
        asset_name = identifier(target["id"]).lower()
        if asset_name in asset_names:
            raise ValueError("Duplicate export basename: " + asset_name)
        asset_names.add(asset_name)
    old_scene, old_layer = window.scene, window.view_layer
    old_frame = (old_scene.frame_current, old_scene.frame_subframe)
    old_selection = list(bpy.context.selected_objects)
    old_active = old_layer.objects.active
    was_playing = bool(bpy.context.screen and bpy.context.screen.is_animation_playing)
    if was_playing:
        bpy.ops.screen.animation_cancel(restore_frame=False)
    EXPORTS.mkdir(parents=True, exist_ok=True)
    manifest = {"version": 1, "status": "EXPORTED_UNVERIFIED_ASSIMP",
                "blenderVersion": bpy.app.version_string, "sourceBlend": bpy.data.filepath,
                "retargetReportSha256": digest(REPORT_PATH), "upAxis": "Y", "forwardAxis": "-Z",
                "gameplay": "UNVERIFIED_LIVE", "targets": []}
    try:
        for target in REPORT["targets"]:
            manifest["targets"].append(export_target(target, specs[target["id"]], preview, window))
        if not manifest["targets"]:
            raise ValueError("The retarget report contains no targets")
        output = EXPORTS / "manifest.json"
        output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"manifest": str(output), "targets": [
            {"id": target["id"], "model": target["model"], "clips": target["clipCount"],
             "sha256": target["sha256"]} for target in manifest["targets"]]}, indent=2))
    finally:
        window.scene = old_scene
        window.view_layer = old_layer
        old_scene.frame_set(old_frame[0], subframe=old_frame[1])
        for obj in old_layer.objects:
            if obj.select_get(view_layer=old_layer):
                obj.select_set(False, view_layer=old_layer)
        for obj in old_selection:
            if obj.name in old_layer.objects:
                obj.select_set(True, view_layer=old_layer)
        old_layer.objects.active = old_active
        if was_playing:
            bpy.ops.screen.animation_play()


main()
