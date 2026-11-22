"""Bake offline MMD solver samples onto existing calibrated Blender avatars.

Run through blender_bridge.py after prepare_blender_preview.py. The original
avatar scenes and files are preserved. This is an authoring preview, not a
runtime PMX/VMD importer or a gameplay validation.
"""

import hashlib
import importlib.util
import json
import math
from pathlib import Path

import bpy
import numpy as np
from mathutils import Euler, Matrix, Quaternion, Vector


REPO = Path(__file__).resolve().parents[2]
CONFIG_PATH = Path(globals().get("RW_VMD_CONFIG", Path(__file__).with_name("retarget_preview.json")))
CONFIG = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
SAMPLES_PATH = REPO / CONFIG["samples"]
SOURCE = json.loads(SAMPLES_PATH.read_text(encoding="utf-8"))
OUT = REPO / CONFIG["output"]
FPS = SOURCE["fps"]
skirt_spec = importlib.util.spec_from_file_location("rw_vmd_skirt", Path(__file__).with_name("skirt_dynamics.py"))
skirt_module = importlib.util.module_from_spec(skirt_spec)
skirt_spec.loader.exec_module(skirt_module)
SkirtDynamics = skirt_module.SkirtDynamics
SOURCE_INDEX = {bone["name"]: index for index, bone in enumerate(SOURCE["bones"])}
SOURCE_ROLES = {role: SOURCE_INDEX[node] for role, node in CONFIG["sourceRoles"].items()}
ROLE_CHILD = {"spine": "chest", "chest": "neck", "neck": "head"}
for side in ("left", "right"):
    ROLE_CHILD.update({side + "_shoulder": side + "_upper_arm", side + "_upper_arm": side + "_forearm",
                       side + "_forearm": side + "_hand", side + "_thigh": side + "_shin",
                       side + "_shin": side + "_foot", side + "_foot": side + "_toe"})


def source_position(sample, role):
    index = SOURCE_ROLES[role] * 3
    return Vector(sample["worldPositions"][index:index + 3])


def source_rotation(sample, role, basis):
    index = SOURCE_ROLES[role] * 4
    x, y, z, w = sample["worldRotations"][index:index + 4]
    return (basis @ Quaternion((w, x, y, z)).to_matrix() @ basis.inverted()).to_quaternion().normalized()


def orthogonal_frame(forward, across):
    along = forward.normalized()
    side = (across - along * across.dot(along)).normalized()
    normal = side.cross(along).normalized()
    return Matrix((side, along, normal)).transposed()


def curves(action):
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    return [curve for layer in action.layers for strip in layer.strips
            for bag in strip.channelbags for curve in bag.fcurves]


def align_quaternion_keys(action):
    channels = {}
    for curve in curves(action):
        if curve.data_path.endswith(".rotation_quaternion"):
            channels.setdefault(curve.data_path, {})[curve.array_index] = curve
    flips = 0
    for path, group in channels.items():
        if set(group) != {0, 1, 2, 3}:
            raise RuntimeError("Incomplete quaternion channels: " + action.name + " " + path)
        keys = [group[index].keyframe_points for index in range(4)]
        frames = [[float(key.co.x) for key in points] for points in keys]
        if any(values != frames[0] for values in frames[1:]):
            raise RuntimeError("Unsynchronized quaternion keys: " + action.name + " " + path)
        previous = None
        for index in range(len(keys[0])):
            rotation = Quaternion(tuple(points[index].co.y for points in keys))
            if rotation.magnitude < 1e-8:
                raise RuntimeError("Zero quaternion key: " + action.name + " " + path)
            if previous is not None and rotation.dot(previous) < 0:
                rotation.negate()
                for component, points in enumerate(keys):
                    points[index].co.y = rotation[component]
                flips += 1
            previous = rotation
        for curve in group.values():
            curve.update()
    return {"channels": len(channels), "flippedKeys": flips}


def world(rig, name):
    return rig.matrix_world @ rig.pose.bones[name].matrix


def set_world(rig, name, matrix):
    rig.pose.bones[name].matrix = rig.matrix_world.inverted() @ matrix
    bpy.context.view_layer.update()


def clear_owned_preview():
    previous = bpy.data.scenes.get(CONFIG["scene"])
    if previous and previous.get("rw_vmd_generated"):
        if bpy.context.window.scene == previous:
            bpy.context.window.scene = next(scene for scene in bpy.data.scenes if scene != previous)
        for obj in list(previous.objects):
            if obj.get("rw_vmd_generated") and len(obj.users_scene) == 1:
                bpy.data.objects.remove(obj, do_unlink=True)
        bpy.data.scenes.remove(previous)
    for action in list(bpy.data.actions):
        if action.get("rw_vmd_generated"):
            action.use_fake_user = False
            if not action.users:
                bpy.data.actions.remove(action)


if bpy.context.screen.is_animation_playing:
    bpy.ops.screen.animation_cancel(restore_frame=False)
clear_owned_preview()
scene = bpy.data.scenes.new(CONFIG["scene"])
scene["rw_vmd_generated"] = True
scene.render.fps = FPS
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 1
bpy.context.window.scene = scene


def reference_basis(spec, source):
    if spec["neutral"] == "bind":
        return {bone.name: Matrix.Identity(4) for bone in source.pose.bones}
    if spec["neutral"] == "action":
        old_scene = bpy.context.window.scene
        source_scene = bpy.data.scenes[spec["scene"]]
        old_frame = source_scene.frame_current
        data = source.animation_data
        old_action, old_slot = data.action, data.action_slot
        try:
            bpy.context.window.scene = source_scene
            data.action = bpy.data.actions[spec["neutralAction"]]
            source_scene.frame_set(1)
            bpy.context.view_layer.update()
            return {bone.name: bone.matrix_basis.copy() for bone in source.pose.bones}
        finally:
            data.action = old_action
            if old_slot:
                data.action_slot = old_slot
            source_scene.frame_set(old_frame)
            bpy.context.window.scene = old_scene
    with bpy.data.libraries.load(spec["neutralFile"], link=False) as (available, selected):
        if spec["neutralObject"] not in available.objects:
            raise RuntimeError("Missing calibrated neutral rig: " + spec["neutralObject"])
        selected.objects = [spec["neutralObject"]]
    reference = selected.objects[0]
    result = {bone.name: bone.matrix_basis.copy() for bone in reference.pose.bones}
    if set(result) != set(source.pose.bones.keys()):
        raise RuntimeError("Neutral and animated rigs have different bones")
    return result


def clone_avatar(spec):
    original_scene = bpy.data.scenes[spec["scene"]]
    original = original_scene.objects[spec["rig"]]
    rig = original.copy()
    rig.data = original.data.copy()
    rig.name = "CSM_" + spec["id"] + "_Rig"
    rig["rw_vmd_generated"] = True
    rig.parent = None
    rig.animation_data_clear()
    rig.show_in_front = False
    rig.data.display_type = "STICK"
    scene.collection.objects.link(rig)
    rig.matrix_world = original.matrix_world.copy()
    rig.matrix_world.translation += Vector(spec["offset"])
    neutral = reference_basis(spec, original)
    for bone in rig.pose.bones:
        bone.rotation_mode = "QUATERNION"
        for constraint in bone.constraints:
            constraint.mute = True
        bone.matrix_basis = neutral[bone.name]
    meshes = []
    for obj in original_scene.objects:
        if obj.type != "MESH" or not any(mod.type == "ARMATURE" and mod.object == original for mod in obj.modifiers):
            continue
        clone = obj.copy()
        clone.data = obj.data.copy()
        clone.name = "CSM_" + spec["id"] + "_" + obj.name
        clone["rw_vmd_generated"] = True
        clone.parent = rig
        clone.animation_data_clear()
        if clone.data.shape_keys:
            clone.data.shape_keys.animation_data_clear()
            for key in clone.data.shape_keys.key_blocks:
                key.value = 0
        for modifier in clone.modifiers:
            if modifier.type == "ARMATURE":
                modifier.object = rig
        scene.collection.objects.link(clone)
        clone.matrix_world = obj.matrix_world.copy()
        clone.matrix_world.translation += Vector(spec["offset"])
        if spec["id"] == "BlockPlayer":
            for slot in clone.material_slots:
                if slot.material is None or not slot.material.use_nodes:
                    continue
                slot.material = slot.material.copy()
                for node in slot.material.node_tree.nodes:
                    if node.type != "TEX_IMAGE" or node.image is None:
                        continue
                    texture = REPO / "src/main/resources/assets/rigworks/models/character/block_player" / (node.image.name.split(".")[0] + ".png")
                    if texture.is_file():
                        node.image = bpy.data.images.load(str(texture), check_existing=False)
                        if "albedo" not in texture.stem:
                            node.image.colorspace_settings.name = "Non-Color"
                        node.image.pack()
        meshes.append(clone)
    if not meshes:
        raise RuntimeError("No skinned meshes for " + spec["id"])
    bpy.context.view_layer.update()
    reference = {bone.name: world(rig, bone.name).copy() for bone in rig.pose.bones}
    roles = spec["roles"]
    for name in roles.values():
        if name not in rig.pose.bones:
            raise RuntimeError("Unknown target bone: " + name)
    # Native samples are already right handed: (x,y,-PMX.z). Blender is Z-up.
    basis = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))
    source_left = basis @ (source_position(SOURCE["bind"], "left_upper_arm") - source_position(SOURCE["bind"], "right_upper_arm"))
    target_left = reference[roles["left_upper_arm"]].translation - reference[roles["right_upper_arm"]].translation
    if source_left.dot(target_left) < 0:
        # Some existing avatars label physical sides opposite the source rig.
        basis = Matrix(((-1, 0, 0), (0, 1, 0), (0, 0, 1))) @ basis
    correction = {}
    for role, node in roles.items():
        calibrated = reference[node].to_3x3().copy()
        child = ROLE_CHILD.get(role)
        toe = spec.get("footReferences", {}).get(role)
        if toe:
            src = basis @ (source_position(SOURCE["bind"], role.replace("_foot", "_toe")) - source_position(SOURCE["bind"], role))
            dst = reference[toe].translation - reference[node].translation
            src.z = dst.z = 0
            if min(src.length, dst.length) < 1e-6:
                raise RuntimeError("Missing horizontal sole direction for " + node)
            # Match sole heading, retaining the calibrated heel/toe pitch and imported roll.
            calibrated = dst.rotation_difference(src).to_matrix() @ calibrated
        elif child:
            src = basis @ (source_position(SOURCE["bind"], child) - source_position(SOURCE["bind"], role))
            if child in roles:
                target = reference[roles[child]].translation - reference[node].translation
            else:
                target = calibrated @ Vector((0, 1, 0))
            if src.length > 1e-6 and target.length > 1e-6:
                calibrated = target.rotation_difference(src).to_matrix() @ calibrated
        elif role.endswith("_hand"):
            hand_refs = spec.get("handReferences", {}).get(role)
            if hand_refs:
                side = role.split("_", 1)[0]
                src_forward = basis @ (source_position(SOURCE["bind"], side + "_middle_base") - source_position(SOURCE["bind"], role))
                src_across = basis @ (source_position(SOURCE["bind"], side + "_index_base") - source_position(SOURCE["bind"], side + "_middle_base"))
                dst_forward = reference[hand_refs["middle"]].translation - reference[node].translation
                dst_across = reference[hand_refs["index"]].translation - reference[hand_refs["middle"]].translation
                calibrated = orthogonal_frame(src_forward, src_across) @ orthogonal_frame(dst_forward, dst_across).inverted() @ calibrated
            else:
                forearm = role.replace("_hand", "_forearm")
                src = basis @ (source_position(SOURCE["bind"], role) - source_position(SOURCE["bind"], forearm))
                target = calibrated @ Vector((0, 1, 0))
                calibrated = target.rotation_difference(src).to_matrix() @ calibrated
        # Keep signed scale and handedness from the imported bind matrices.
        correction[role] = source_rotation(SOURCE["bind"], role, basis).to_matrix().inverted() @ calibrated
    target_leg = sum((reference[roles[a]].translation - reference[roles[b]].translation).length
                     for a, b in [("left_thigh", "left_shin"), ("left_shin", "left_foot")])
    source_leg = sum((source_position(SOURCE["bind"], a) - source_position(SOURCE["bind"], b)).length
                     for a, b in [("left_thigh", "left_shin"), ("left_shin", "left_foot")])
    scale = target_leg / source_leg
    ordered = sorted(roles, key=lambda role: len(rig.data.bones[roles[role]].parent_recursive))
    return {"spec": spec, "rig": rig, "meshes": meshes, "neutral": neutral, "reference": reference,
            "basis": basis, "correction": correction, "scale": scale, "ordered": ordered}


def reset(target):
    for bone in target["rig"].pose.bones:
        bone.matrix_basis = target["neutral"][bone.name]
    bpy.context.view_layer.update()


def in_mask(role, mask):
    if mask == "full_body":
        return True
    if mask == "upper_body":
        return role != "hips" and not any(role.endswith(suffix) for suffix in ("_thigh", "_shin", "_foot"))
    if mask in ("left_arm", "right_arm"):
        return role.startswith(mask.split("_", 1)[0] + "_") and any(
            role.endswith(suffix) for suffix in ("_shoulder", "_upper_arm", "_forearm", "_hand"))
    raise ValueError("Unknown source role mask: " + mask)


def solve_two_bone(target, chain, goal, pole):
    rig = target["rig"]
    first, second, end = chain
    a, b, c = [world(rig, name).translation for name in chain]
    length_a, length_b = (b - a).length, (c - b).length
    direction = goal - a
    if min(direction.length, length_a, length_b) < 1e-7:
        raise RuntimeError("Degenerate limb: " + str(chain))
    direction.normalize()
    distance = min(max((goal - a).length, abs(length_a - length_b) + 1e-6), length_a + length_b - 1e-6)
    along = (length_a * length_a - length_b * length_b + distance * distance) / (2 * distance)
    across = pole - a - direction * (pole - a).dot(direction)
    if across.length < 1e-6:
        across = b - a - direction * (b - a).dot(direction)
    if across.length < 1e-6:
        across = direction.cross(Vector((0, 0, 1)))
    if across.length < 1e-6:
        across = direction.cross(Vector((1, 0, 0)))
    joint = a + direction * along + across.normalized() * math.sqrt(max(0, length_a * length_a - along * along))
    effector = a + direction * distance
    end_rotation = world(rig, end).to_3x3().copy()
    first_matrix = world(rig, first)
    first_matrix = (b - a).rotation_difference(joint - a).to_matrix().to_4x4() @ first_matrix
    first_matrix.translation = a
    set_world(rig, first, first_matrix)
    second_matrix = world(rig, second)
    current = world(rig, end).translation - second_matrix.translation
    second_matrix = current.rotation_difference(effector - second_matrix.translation).to_matrix().to_4x4() @ second_matrix
    second_matrix.translation = world(rig, second).translation
    set_world(rig, second, second_matrix)
    end_matrix = end_rotation.to_4x4()
    end_matrix.translation = world(rig, end).translation
    set_world(rig, end, end_matrix)
    return (world(rig, end).translation - goal).length


def fit_leg_targets(target, sample, policy):
    roles, rig = target["spec"]["roles"], target["rig"]
    hip = world(rig, roles["hips"]).translation
    goals, bends, roots, reaches = {}, {}, {}, {}
    for side in ("left", "right"):
        role = side + "_foot"
        leg_sample = sample if in_mask(role, policy.get("sourceMask", "full_body")) else SOURCE["clips"]["stand"]["frames"][0]
        source_hip = source_position(leg_sample, "hips")
        neutral_offset = target["reference"][roles[role]].translation - target["reference"][roles["hips"]].translation
        bind_offset = target["basis"] @ (source_position(SOURCE["bind"], role) - source_position(SOURCE["bind"], "hips")) * target["scale"]
        goals[side] = hip + target["basis"] @ (source_position(leg_sample, role) - source_hip) * target["scale"] + neutral_offset - bind_offset
        source_a, source_b, source_c = [source_position(leg_sample, side + suffix)
                                       for suffix in ("_thigh", "_shin", "_foot")]
        line = (source_c - source_a).normalized()
        bend = (source_b - source_a) - line * (source_b - source_a).dot(line)
        if bend.length < 1e-5:
            bend = Vector((0, 0, 1))
        bends[side] = target["basis"] @ bend.normalized()
        points = [world(rig, roles[side + suffix]).translation for suffix in ("_thigh", "_shin", "_foot")]
        roots[side] = points[0]
        lengths = [(points[i + 1] - points[i]).length for i in (0, 1)]
        reaches[side] = (abs(lengths[0] - lengths[1]) + 1e-6, sum(lengths) - 1e-6)
    adjustment = Vector((0, 0, 0))
    for _ in range(16):
        for side in ("left", "right"):
            delta = goals[side] - roots[side] - adjustment
            lower, upper = reaches[side]
            if delta.length > upper:
                adjustment += delta * ((delta.length - upper) / delta.length)
            elif 1e-7 < delta.length < lower:
                adjustment -= delta * ((lower - delta.length) / delta.length)
    hip_matrix = world(rig, roles["hips"])
    hip_matrix.translation += adjustment
    set_world(rig, roles["hips"], hip_matrix)
    errors, foot_orientation_errors = [], []
    for side in ("left", "right"):
        pole = world(rig, roles[side + "_thigh"]).translation + bends[side]
        chain = [roles[side + suffix] for suffix in ("_thigh", "_shin", "_foot")]
        desired_foot = world(rig, chain[-1]).to_3x3().copy()
        errors.append(solve_two_bone(target, chain, goals[side], pole))
        actual_foot = world(rig, chain[-1]).to_3x3()
        foot_orientation_errors.append(max(abs(actual_foot[i][j] - desired_foot[i][j]) for i in range(3) for j in range(3)))
    return {"maxAnkleTargetError": max(errors), "maxFootMatrixError": max(foot_orientation_errors),
            "pelvisAdjustment": adjustment.length}


def fit_hand_targets(target, sample):
    rig, roles = target["rig"], target["spec"]["roles"]
    source_chest = source_position(sample, "chest")
    target_chest = world(rig, roles["chest"]).translation
    arm_scales = []
    for side in ("left", "right"):
        chain = [side + suffix for suffix in ("_upper_arm", "_forearm", "_hand")]
        target_length = sum((target["reference"][roles[a]].translation - target["reference"][roles[b]].translation).length
                            for a, b in zip(chain, chain[1:]))
        source_length = sum((source_position(SOURCE["bind"], a) - source_position(SOURCE["bind"], b)).length
                            for a, b in zip(chain, chain[1:]))
        arm_scales.append(target_length / source_length)
    scale = sum(arm_scales) / len(arm_scales)
    goals, poles, roots, reaches = {}, {}, {}, {}
    for side in ("left", "right"):
        chain = [roles[side + suffix] for suffix in ("_upper_arm", "_forearm", "_hand")]
        points = [world(rig, node).translation for node in chain]
        roots[side] = points[0]
        lengths = [(points[i + 1] - points[i]).length for i in (0, 1)]
        reaches[side] = (abs(lengths[0] - lengths[1]) + 1e-6, sum(lengths) - 1e-6)
        goals[side] = target_chest + target["basis"] @ (source_position(sample, side + "_hand") - source_chest) * scale
        poles[side] = target_chest + target["basis"] @ (source_position(sample, side + "_forearm") - source_chest) * scale
    # Move both grips together into the target arms' workspace, retaining their separation.
    adjustment = Vector((0, 0, 0))
    for _ in range(16):
        for side in ("left", "right"):
            delta = goals[side] + adjustment - roots[side]
            distance = delta.length
            lower, upper = reaches[side]
            if distance > upper:
                adjustment -= delta * ((distance - upper) / distance)
            elif 1e-7 < distance < lower:
                adjustment += delta * ((lower - distance) / distance)
    errors, requested, actual = [], {}, {}
    for side in ("left", "right"):
        chain = [roles[side + suffix] for suffix in ("_upper_arm", "_forearm", "_hand")]
        goal = goals[side] + adjustment
        pole = poles[side] + adjustment
        errors.append(solve_two_bone(target, chain, goal, pole))
        requested[side] = goal
        actual[side] = world(rig, chain[-1]).translation
    return {"maxTargetError": max(errors), "reachAdjustment": adjustment.length,
            "requestedWristSeparation": (requested["left"] - requested["right"]).length,
            "wristSeparation": (actual["left"] - actual["right"]).length}


def retarget(target, sample, policy):
    reset(target)
    rig, roles = target["rig"], target["spec"]["roles"]
    for role in target["ordered"]:
        selected = in_mask(role, policy.get("sourceMask", "full_body"))
        role_sample = sample if selected else SOURCE["clips"]["stand"]["frames"][0]
        node = roles[role]
        current = world(rig, node)
        desired = (source_rotation(role_sample, role, target["basis"]).to_matrix() @ target["correction"][role]).to_4x4()
        if any(role.endswith(suffix) for suffix in ("_upper_arm", "_forearm", "_hand")):
            clearance = policy.get("armClearanceDegrees", target["spec"].get("armClearanceDegrees", 0))
            if not selected:
                clearance = target["spec"].get("armClearanceDegrees", 0)
            side = role.split("_", 1)[0]
            neutral_side = target["reference"][roles[side + "_upper_arm"]].translation.x - target["reference"][roles["hips"]].translation.x
            angle = math.copysign(math.radians(clearance), neutral_side)
            forward = target["basis"] @ Vector((0, 0, 1))
            desired = Quaternion(forward, angle).to_matrix().to_4x4() @ desired
        desired.translation = current.translation
        if role == "hips":
            delta = target["basis"] @ (source_position(role_sample, role) - source_position(SOURCE["bind"], role))
            # Minecraft owns horizontal travel. Only source vertical bob/jump is baked.
            desired.translation = target["reference"][node].translation + Vector((0, 0, delta.z * target["scale"]))
        set_world(rig, node, desired)
    legs = fit_leg_targets(target, sample, policy) if target["spec"].get("legTargetIK") else None
    contacts = fit_hand_targets(target, sample) if policy.get("handTargetIK") else None
    if policy.get("bodyRotationDegrees"):
        rotation = Euler(tuple(math.radians(value) for value in policy["bodyRotationDegrees"]), "XYZ").to_matrix().to_4x4()
        pivot = world(rig, roles["hips"]).translation
        transform = Matrix.Translation(pivot) @ rotation @ Matrix.Translation(-pivot)
        for bone in rig.pose.bones:
            if bone.parent is None:
                set_world(rig, bone.name, transform @ world(rig, bone.name))
    return {"hands": contacts, "legs": legs}


def skin_bounds(target):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    minimum = np.full(3, math.inf)
    maximum = np.full(3, -math.inf)
    for obj in target["meshes"]:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        try:
            coordinates = np.empty(len(mesh.vertices) * 3, dtype=np.float64)
            mesh.vertices.foreach_get("co", coordinates)
            matrix = np.array(evaluated.matrix_world)
            points = coordinates.reshape(-1, 3) @ matrix[:3, :3].T + matrix[:3, 3]
            if not np.isfinite(points).all():
                raise RuntimeError("Non-finite skin in " + obj.name)
            minimum = np.minimum(minimum, points.min(axis=0))
            maximum = np.maximum(maximum, points.max(axis=0))
        finally:
            evaluated.to_mesh_clear()
    return Vector(minimum), Vector(maximum)


def bake(target, name, clip):
    rig = target["rig"]
    policy = {"sourceMask": clip.get("sourceMask", "full_body"), **CONFIG.get("clipPolicies", {}).get(name, {})}
    action = bpy.data.actions.new("csm_" + name + "__" + target["spec"]["id"])
    action.use_fake_user = True
    action["rw_vmd_generated"] = True
    action["source_vmd"] = clip["sourceVmd"]
    action["source_sha256"] = SOURCE["sourceHashes"].get(clip["sourceVmd"], SOURCE["sourceHashes"].get(name))
    action["solver_baked"] = True
    action["root_policy"] = "in_place_with_vertical_motion"
    action["source_mask"] = policy.get("sourceMask", "full_body")
    action["placement"] = policy.get("placement", "grounded")
    rig.animation_data_create()
    rig.animation_data.action = action
    frames = clip["frames"]
    is_loop = name in CONFIG.get("loopClips", []) or clip.get("samplingMode") == "loop"
    closure = min(CONFIG.get("loopBlendFrames", 0), len(frames) - 1) if is_loop else 0
    first_pose = None
    previous_quaternions = {}
    keyed = set(target["spec"]["roles"].values())
    minimum_z = math.inf
    closure_penetration = 0.0
    knee_angles = {side: [] for side in ("left", "right")}
    contacts, legs = [], []
    skirt_state = SkirtDynamics(target, world, set_world, FPS) if target["spec"].get("skirtPhysics", {}).get("enabled") else None
    if skirt_state:
        keyed.update(skirt_state.dynamic_bones)
    for index, sample in enumerate(frames):
        frame = 1 + index
        scene.frame_set(frame)
        contact = retarget(target, sample, policy)
        if contact["hands"]:
            contacts.append(contact["hands"])
        if contact["legs"]:
            legs.append(contact["legs"])
        if skirt_state:
            skirt_state.update(1 / FPS)
        for bone in rig.pose.bones:
            if bone.name not in keyed and index not in (0, len(frames) - 1):
                continue
            quaternion = bone.rotation_quaternion
            previous = previous_quaternions.get(bone.name)
            if previous and quaternion.dot(previous) < 0:
                quaternion.negate()
            previous_quaternions[bone.name] = quaternion.copy()
            for channel in ("location", "rotation_quaternion", "scale"):
                bone.keyframe_insert(data_path=channel, frame=frame, group=bone.name)
        bpy.context.view_layer.update()
        if index < len(frames) - closure:
            minimum_z = min(minimum_z, skin_bounds(target)[0].z)
        if index == 0:
            first_pose = {bone.name: (bone.location.copy(), bone.rotation_quaternion.copy(), bone.scale.copy())
                          for bone in rig.pose.bones}
        for side in knee_angles:
            a, b, c = [world(rig, target["spec"]["roles"][side + suffix]).translation
                       for suffix in ("_thigh", "_shin", "_foot")]
            knee_angles[side].append(math.degrees((b - a).angle(c - b)))
    # Close sampled source loops explicitly; jump remains an unmodified one-shot.
    # Blender's component-wise quaternion curves need consistent signs even
    # when the closure endpoints represent the same orientation.
    closure_previous_quaternions = {}
    if closure:
        scene.frame_set(len(frames) - closure)
        bpy.context.view_layer.update()
        closure_previous_quaternions = {
            bone.name: bone.rotation_quaternion.copy() for bone in rig.pose.bones
        }
    for index in range(len(frames) - closure, len(frames)):
        frame = 1 + index
        scene.frame_set(frame)
        weight = (index - (len(frames) - closure) + 1) / closure
        weight = weight * weight * (3 - 2 * weight)
        for bone in rig.pose.bones:
            location, rotation, scale = first_pose[bone.name]
            bone.location = bone.location.lerp(location, weight)
            blended_rotation = bone.rotation_quaternion.slerp(rotation, weight)
            previous_rotation = closure_previous_quaternions.get(bone.name)
            if previous_rotation is not None and blended_rotation.dot(previous_rotation) < 0:
                blended_rotation.negate()
            bone.rotation_quaternion = blended_rotation
            closure_previous_quaternions[bone.name] = blended_rotation.copy()
            bone.scale = bone.scale.lerp(scale, weight)
        bpy.context.view_layer.update()
        if skirt_state:
            # Pose blending can put a clear panel back into a bent leg.
            # Resolve that final pose without advancing secondary motion twice.
            contact = skirt_state.resolve_contacts()
            closure_penetration = max(closure_penetration, contact["maximumVertexPenetration"])
        for bone in rig.pose.bones:
            for channel in ("location", "rotation_quaternion", "scale"):
                bone.keyframe_insert(data_path=channel, frame=frame, group=bone.name)
        bpy.context.view_layer.update()
        minimum_z = min(minimum_z, skin_bounds(target)[0].z)
    # One constant correction per clip preserves source flight and vertical bob.
    floor_correction = policy.get("floorClearance", 0) - minimum_z
    hips = target["spec"]["roles"]["hips"]
    for index in range(len(frames)):
        frame = 1 + index
        scene.frame_set(frame)
        current = world(rig, hips)
        current.translation.z += floor_correction
        set_world(rig, hips, current)
        rig.pose.bones[hips].keyframe_insert(data_path="location", frame=frame, group=hips)
    for curve in curves(action):
        for key in curve.keyframe_points:
            key.interpolation = "LINEAR"
    # Nonzero-duration actions allow static stances to be used as normal NLA strips.
    if len(frames) == 1:
        for curve in curves(action):
            curve.keyframe_points.insert(2, curve.keyframe_points[0].co.y)
    continuity = align_quaternion_keys(action)
    result = {"action": action.name, "samples": len(frames), "duration": clip["duration"],
              "floorCorrection": floor_correction, "loopClosureFrames": closure,
              "policy": policy, "loop": is_loop, "sourceVmd": clip["sourceVmd"],
              "quaternionContinuity": continuity,
              "sourceStartFrame": clip.get("sourceStartFrame", 0), "sourceEndFrame": clip.get("sourceEndFrame"),
              "kneeBendDegrees": {side: [min(values), max(values)] for side, values in knee_angles.items()}}
    if contacts:
        result["handTargets"] = {"maxTargetError": max(item["maxTargetError"] for item in contacts),
                                 "maxReachAdjustment": max(item["reachAdjustment"] for item in contacts),
                                 "minWristSeparation": min(item["wristSeparation"] for item in contacts),
                                 "minRequestedWristSeparation": min(item["requestedWristSeparation"] for item in contacts)}
    if legs:
        result["legTargets"] = {"maxAnkleTargetError": max(item["maxAnkleTargetError"] for item in legs),
                                "maxFootMatrixError": max(item["maxFootMatrixError"] for item in legs),
                                "maxPelvisAdjustment": max(item["pelvisAdjustment"] for item in legs)}
    if skirt_state:
        result["skirtPhysics"] = skirt_state.summary()
        result["skirtPhysics"]["maximumLoopClosurePenetration"] = closure_penetration
    rig.animation_data.action = None
    return action, result


clip_names = CONFIG.get("clips", list(SOURCE["clips"]))
missing_clips = set(clip_names) - set(SOURCE["clips"])
if missing_clips:
    raise RuntimeError("Configured clips are missing from sampled data: " + ", ".join(sorted(missing_clips)))
targets = [clone_avatar(spec) for spec in CONFIG["targets"] if bpy.data.scenes.get(spec["scene"])]
report = {"status": "TESTED_BLENDER_AUTHORING_ONLY", "samplesSha256": hashlib.sha256(SAMPLES_PATH.read_bytes()).hexdigest(),
          "sourceEngine": SOURCE["engine"], "fps": FPS, "targets": []}
for target in targets:
    actions = {}
    entries = {}
    for name in clip_names:
        clip = SOURCE["clips"][name]
        actions[name], entries[name] = bake(target, name, clip)
    track = target["rig"].animation_data.nla_tracks.new()
    track.name = "CSM_Preview_Reel"
    cursor = 1
    for segment in CONFIG["reel"]:
        if segment["clip"] not in actions:
            continue
        name = segment["clip"]
        action = actions[name]
        strip = track.strips.new("csm_" + name, cursor, action)
        strip.action_frame_start = 1
        strip.action_frame_end = max(2, len(SOURCE["clips"][name]["frames"]))
        strip.blend_type = "REPLACE"
        strip.extrapolation = "NOTHING"
        strip.repeat = segment["cycles"]
        if segment.get("staticFrames"):
            strip.frame_end = cursor + segment["staticFrames"]
        cursor = int(round(strip.frame_end))
    report["targets"].append({"id": target["spec"]["id"], "rig": target["rig"].name,
                              "mappedRoles": target["spec"]["roles"], "nativeUnitsToTarget": target["scale"],
                              "axisConversion": [list(row) for row in target["basis"]], "clips": entries})
    scene.frame_end = cursor - 1


def material(name, color, roughness=0.8):
    result = bpy.data.materials.new(name)
    result.diffuse_color = (*color, 1)
    result.use_nodes = True
    shader = result.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*color, 1)
    shader.inputs["Roughness"].default_value = roughness
    return result


def own(obj):
    obj["rw_vmd_generated"] = True
    return obj


bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, -0.012))
floor = own(bpy.context.object)
floor.name = "CSM_Preview_Floor"
floor.data.materials.append(material("CSM_Floor", (.11, .13, .15)))
for target in targets:
    text = bpy.data.curves.new("CSM_Label", "FONT")
    text.body = target["spec"]["id"]
    text.align_x = "CENTER"
    text.size = .14
    label = own(bpy.data.objects.new("CSM_Label_" + text.body, text))
    scene.collection.objects.link(label)
    label.location = Vector(target["spec"]["offset"]) + Vector((0, 0, -.002))
    label.location.y = -.75
    text.materials.append(material("CSM_Label_Material", (.78, .84, .88)))

for name, position, energy, size in [("Key", (2, -4, 6), 1200, 5), ("Fill", (-4, -1, 3), 800, 4), ("Rim", (1, 3, 4), 1000, 3)]:
    data = bpy.data.lights.new("CSM_" + name, "AREA")
    data.energy, data.shape, data.size = energy, "DISK", size
    obj = own(bpy.data.objects.new("CSM_" + name, data))
    scene.collection.objects.link(obj)
    obj.location = position
    obj.rotation_euler = (Vector((0, 0, .9)) - obj.location).to_track_quat("-Z", "Y").to_euler()

data = bpy.data.cameras.new("CSM_Preview_Camera")
camera = own(bpy.data.objects.new(data.name, data))
scene.collection.objects.link(camera)
camera.location = (3.8, -7.0, 3.2)
camera.rotation_euler = (Vector((0, 0, .95)) - camera.location).to_track_quat("-Z", "Y").to_euler()
data.type, data.ortho_scale, data.lens = "ORTHO", 4.9, 50
scene.camera = camera
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x, scene.render.resolution_y, scene.render.resolution_percentage = 1440, 1000, 100
scene.world = bpy.data.worlds.new("CSM_Preview_World")
scene.world.use_nodes = True
scene.world.node_tree.nodes["Background"].inputs[0].default_value = (.16, .18, .22, 1)
scene.world.node_tree.nodes["Background"].inputs[1].default_value = .45
scene.view_settings.view_transform = "AgX"
cursor = 1
for segment in CONFIG["reel"]:
    if segment["clip"] not in clip_names:
        continue
    name = segment["clip"]
    scene.timeline_markers.new("CSM " + name.upper(), frame=cursor)
    interval = segment.get("staticFrames", max(1, len(SOURCE["clips"][name]["frames"]) - 1) * segment["cycles"])
    cursor += interval
scene.frame_set(108)
scene.render.fps = FPS
for area in bpy.context.screen.areas:
    if area.type == "VIEW_3D":
        space = area.spaces.active
        space.region_3d.view_perspective = "CAMERA"
        space.region_3d.view_camera_zoom = 25
        space.overlay.show_overlays = False
        space.shading.type = "MATERIAL"
        space.shading.use_scene_world = False
        space.shading.use_scene_lights = False
    elif area.type == "DOPESHEET_EDITOR":
        area.ui_type = "TIMELINE"
for obj in bpy.context.selected_objects:
    obj.select_set(False)
if targets:
    targets[-1]["rig"].select_set(True)
    bpy.context.view_layer.objects.active = targets[-1]["rig"]
OUT.parent.mkdir(parents=True, exist_ok=True)
(OUT.parent / "retarget_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
bpy.ops.wm.save_as_mainfile(filepath=str(OUT), compress=True)
print(json.dumps({"blend": str(OUT), "scene": scene.name, "frameEnd": scene.frame_end,
                  "targets": [{"id": row["id"], "clipCount": len(row["clips"])} for row in report["targets"]]}, indent=2))
