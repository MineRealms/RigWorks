"""Build a block-styled Misaka Sister avatar on the calibrated BlockPlayer rig.

The FBX deliberately contains no actions. RigWorks DEFAULT supplies motion;
instance.json supplies per-instance secondary dynamics after packaging.
"""

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import sys

import bpy
from mathutils import Vector


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/misaka"
spec = importlib.util.spec_from_file_location("rigworks_voxel_authoring", Path(__file__).with_name("build_starpop.py"))
voxel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(voxel)
Surface, Blocks = voxel.Surface, voxel.Blocks
one, mixed, clamp = voxel.one, voxel.mixed, voxel.clamp

PALETTE = dict(
    skin="efc5ab", skin_light="f7d4bc", skin_shadow="d6a58e", cheek="dfa594",
    hair="946449", hair_light="b0805a", hair_glint="c69870", hair_shadow="784f3d", hair_dark="573d32",
    white="ecf1ef", white_light="fafcf7", white_shadow="cbd8dc", white_fold="a7b9c2",
    vest="cbb8a1", vest_light="dfcbb0", vest_shadow="ad9989", vest_edge="776c67",
    skirt="454c60", skirt_light="596379", skirt_shadow="323a4d", skirt_edge="252d3e",
    shoe="69483c", shoe_light="856052", shoe_shadow="48342e", sole="332e2e",
    frame="263a3d", frame_edge="425459", frame_light="657578", glass="5eae8d", glass_light="a0e8b0",
    glass_shadow="326c5e", iris="706454", iris_dark="393c38", eye_white="f4f3e9", eye_shadow="c9bfb1",
    mouth="b57e6c", crest="c9b279", crest_light="ede0b6", green="437764", metal="9ca9a4",
)
voxel.PALETTE = PALETTE
voxel.ATLAS_HEIGHT = math.ceil(len(PALETTE) / (voxel.ATLAS_WIDTH // voxel.TILE)) * voxel.TILE
SECTORS = ("F", "FR", "R", "BR", "B", "BL", "L", "FL")


def material():
    across = voxel.ATLAS_WIDTH // voxel.TILE
    outputs = {}
    for channel in ("albedo", "roughness", "metallic", "normal"):
        colors = []
        for name, color in PALETTE.items():
            if channel == "albedo":
                value = bytes.fromhex(color)
            elif channel == "roughness":
                value = bytes([80 if name.startswith("glass") else 140 if name.startswith("frame") else 218] * 3)
            elif channel == "metallic":
                value = bytes([160 if name in ("metal", "frame_light") else 0] * 3)
            else:
                value = bytes((128, 128, 255))
            colors.append(value)
        rows = [b"".join(colors[min((y // voxel.TILE) * across + x, len(colors) - 1)] * voxel.TILE
                          for x in range(across)) for y in range(voxel.ATLAS_HEIGHT)]
        path = OUT / f"misaka_{channel}.png"
        voxel.png(path, rows)
        image = bpy.data.images.load(str(path), check_existing=False)
        image.name = "Misaka " + channel
        image.colorspace_settings.name = "sRGB" if channel == "albedo" else "Non-Color"
        image.pack()
        outputs[channel] = image
    mat = bpy.data.materials.new("Misaka | opaque pixel PBR")
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = next(n for n in nodes if n.type == "BSDF_PRINCIPLED")
    for channel, socket in (("albedo", "Base Color"), ("roughness", "Roughness"), ("metallic", "Metallic")):
        node = nodes.new("ShaderNodeTexImage")
        node.image, node.interpolation, node.label = outputs[channel], "Closest", channel
        links.new(node.outputs["Color"], bsdf.inputs[socket])
    node = nodes.new("ShaderNodeTexImage")
    node.image, node.interpolation = outputs["normal"], "Closest"
    normal = nodes.new("ShaderNodeNormalMap")
    links.new(node.outputs["Color"], normal.inputs["Color"])
    links.new(normal.outputs["Normal"], bsdf.inputs["Normal"])
    return mat


def chains():
    result = {}
    for index, sector in enumerate(SECTORS):
        angle = index * math.tau / len(SECTORS)
        sx, sy = math.sin(angle), -math.cos(angle)
        result["Skirt." + sector] = ("Hips", [(sx * .215, sy * .145, .965),
                                               (sx * .257, sy * .196, .815),
                                               (sx * .302, sy * .239, .675)])
    for side, sign in (("L", -1), ("R", 1)):
        result["Hair.Side." + side] = ("Head", [(sign * .235, .045, 1.70),
                                                 (sign * .248, .050, 1.565),
                                                 (sign * .252, .060, 1.43)])
        result["Hair.Back." + side] = ("Head", [(sign * .122, .230, 1.70),
                                                 (sign * .135, .250, 1.565),
                                                 (sign * .148, .255, 1.44)])
        result["Hair.Fringe." + side] = ("Head", [(sign * .110, -.215, 1.79),
                                                   (sign * .115, -.237, 1.710),
                                                   (sign * .120, -.248, 1.66)])
    return result


def build_rig(collection, secondary):
    rig = voxel.build_rig(collection, secondary)
    rig.name, rig.data.name = "MisakaRig", "MisakaSkeleton"
    rig.animation_data_clear()
    for stem in secondary:
        for i in (1, 2):
            rig.data.bones[f"{stem}.{i:02}"].name = f"{stem}.{i}"
    bpy.ops.object.mode_set(mode="EDIT")
    for side, x in (("L", -.12), ("R", .12)):
        toe = rig.data.edit_bones.new("Toe." + side)
        toe.head, toe.tail = (x, -.17, .10), (x, -.22, .10)
        toe.parent = rig.data.edit_bones["Foot." + side]
    bpy.ops.object.mode_set(mode="OBJECT")
    for bone in rig.pose.bones:
        bone.rotation_mode = "QUATERNION"
        bone.matrix_basis.identity()
    rig["rigworks_motion"] = "DEFAULT; no FBX actions or KubeJS overrides"
    rig["rigworks_secondary"] = "Per-instance hair/skirt springs; see instance.json"
    return rig


def secondary_weights(stem, top, middle, bottom, parent="Head"):
    def weights(point):
        z = point[2]
        root = clamp((z - top + .025) / .025)
        tip = clamp((middle + .035 - z) / max(.06, middle - bottom + .035))
        return mixed(((parent, root), (stem + ".1", (1 - root) * (1 - tip)),
                      (stem + ".2", (1 - root) * tip)))
    return weights


def head_and_hair(mesh):
    head = Blocks()
    head.fill(-22, 22, -20, 18, 142, 179, "skin")
    head.fill(-20, 20, -21, 12, 145, 170, "skin_light")
    head.fill(-20, 20, -20, 14, 140, 145, "skin_shadow")
    head.fill(-23, 23, -17, 22, 174, 181, "hair")
    head.fill(-23, 23, -15, 22, 181, 183, "hair_light")
    head.fill(-20, 20, -12, 19, 183, 185, "hair_light")
    head.fill(-15, 12, -9, 16, 184, 185, "hair_glint")
    head.fill(-23, 23, 18, 25, 164, 178, "hair_shadow")
    head.fill(-19, 20, 23, 26, 170, 179, "hair")
    for side in (-1, 1):
        a, b = sorted((side * 20, side * 25))
        head.fill(a, b, -17, 21, 169, 178, "hair")
        a, b = sorted((side * 21, side * 25))
        head.fill(a, b, -15, 20, 166, 172, "hair_shadow")
        a, b = sorted((side * 21, side * 24))
        head.fill(a, b, -3, 4, 156, 163, "skin_shadow")
    head.extract(mesh, one("Head"))

    eye = (".#####.", "#wwwww#", "wwiHwww", "wiiHiww", "wwiiiww", ".wiiw.", "..sss..")
    colors = {"#": "iris_dark", "w": "eye_white", "i": "iris", "H": "white_light", "s": "eye_shadow"}
    for x, mirror in ((-.178, False), (.059, True)):
        mesh.stamp(eye, x, 1.663, -.2111, .017, colors, "Head", "eye", mirror)
        mesh.front(x + .017, x + .102, 1.680, 1.691, -.2113, "hair_dark", one("Head"))
    mesh.stamp((".mmm.",), -.035, 1.497, -.2114, .014, {"m": "mouth"}, "Head", "mouth")
    mesh.front(-.012, .010, 1.528, 1.544, -.212, "skin_shadow", one("Head"))

    for side, sign in (("L", -1), ("R", 1)):
        def extracted(block, stem, top, middle, bottom):
            if sign < 0:
                block.mirror()
            block.extract(mesh, secondary_weights(stem + side, top, middle, bottom), split_z=3)
        side_hair = Blocks()
        side_hair.fill(21, 26, -13, 20, 153, 170, "hair")
        side_hair.fill(22, 27, -10, 17, 149, 164, "hair_shadow")
        side_hair.fill(22, 26, -11, 3, 145, 159, "hair")
        side_hair.fill(23, 25, -9, 9, 143, 152, "hair_shadow")
        side_hair.fill(26, 27, -8, -3, 155, 168, "hair_light")
        side_hair.fill(26, 27, 5, 10, 153, 163, "hair_light")
        extracted(side_hair, "Hair.Side.", 1.70, 1.565, 1.43)
        back = Blocks()
        back.fill(0, 21, 19, 26, 151, 168, "hair")
        back.fill(1, 20, 20, 27, 148, 161, "hair_shadow")
        back.fill(2, 8, 22, 28, 145, 163, "hair")
        back.fill(11, 18, 22, 28, 146, 164, "hair")
        back.fill(4, 7, 27, 28, 154, 165, "hair_light")
        back.fill(13, 16, 27, 28, 153, 165, "hair_light")
        extracted(back, "Hair.Back.", 1.70, 1.565, 1.44)
        fringe = Blocks()
        for a, b, low in ((0, 4, 174), (4, 8, 170), (8, 12, 172), (12, 16, 169), (16, 21, 166)):
            fringe.fill(a, b, -24, -19, low, 177, "hair_shadow")
            fringe.fill(a, b - 1, -25, -22, low + 1, 175, "hair")
            if b - a > 3:
                fringe.fill(a + 1, b - 1, -26, -24, low + 5, 173, "hair_light")
        extracted(fringe, "Hair.Fringe.", 1.79, 1.710, 1.63)

    goggles = Blocks()
    goggles.fill(-25, 25, -16, 23, 176, 180, "frame")
    # Remove the interior of the strap so hair still owns the top silhouette.
    for cell in list(goggles.cells):
        if -23 <= cell[0] < 23 and -14 <= cell[1] < 21:
            del goggles.cells[cell]
    goggles.fill(-22, 22, -26, -21, 175, 183, "frame_edge")
    goggles.fill(-20, 20, -28, -23, 176, 182, "frame")
    goggles.fill(-18, 18, -29, -27, 178, 181, "glass")
    goggles.fill(-18, 18, -30, -28, 180, 181, "glass_light")
    goggles.fill(-18, 18, -30, -28, 178, 179, "glass_shadow")
    for x0, x1 in ((-23, -21), (21, 23)):
        goggles.fill(x0, x1, -28, -25, 178, 181, "frame_light")
    goggles.extract(mesh, one("Head"))


def torso_weights(point):
    z = point[2]
    if z < 1.10:
        t = clamp((z - .94) / .14)
        return mixed((("Hips", 1 - t), ("Spine", t)))
    t = clamp((z - 1.13) / .13)
    return mixed((("Spine", 1 - t), ("Chest", t)))


def clothes(mesh):
    torso = Blocks()
    torso.fill(-22, 22, -13, 13, 93, 135, "vest")
    torso.fill(-21, 21, -14, 13, 101, 134, "vest_light")
    torso.fill(-22, 22, -14, 14, 94, 100, "vest_shadow")
    torso.fill(-23, 23, -13, 13, 96, 98, "vest_edge")
    torso.fill(-21, 21, -13, 13, 134, 139, "white")
    torso.fill(-19, 19, -14, 12, 135, 138, "white_light")
    torso.fill(-9, 9, -9, 9, 138, 145, "skin")
    torso.fill(-8, 8, -10, -7, 138, 143, "skin_light")
    torso.extract(mesh, torso_weights, split_z=3)
    # Low-resolution V neck, collar and pocket badge stay legible at game distance.
    neckline = ("....ttsssssstt....", ".....twssswt.....", ".....twwwwt......", "......twwt.......",
                "......twwt.......", ".......tt........")
    mesh.stamp(neckline, -.144, 1.382, -.1455, .017,
               {"t": "vest_edge", "w": "white_light", "s": "skin"}, "Chest")
    for sign in (-1, 1):
        coords = [(sign * .025, -.150, 1.379), (sign * .130, -.150, 1.381),
                  (sign * .098, -.155, 1.311), (sign * .030, -.155, 1.346)]
        mesh.quad(coords if sign > 0 else coords[::-1], "white_light", one("Chest"))
    mesh.stamp(("..cc..", ".cggc.", ".cggc.", "..cc..", "...c.."), -.162, 1.258, -.1458, .010,
               {"c": "crest_light", "g": "green"}, "Chest")
    for z in (1.175, 1.106, 1.038):
        mesh.front(-.006, .006, z, z + .012, -.1457, "vest_shadow", torso_weights)
    mesh.front(.075, .183, 1.079, 1.093, -.1457, "vest_shadow", torso_weights)
    shorts = Blocks()
    shorts.fill(-21, 21, -12, 12, 82, 94, "skirt_shadow")
    shorts.extract(mesh, one("Hips"))


def limbs(mesh):
    for side, sign in (("L", -1), ("R", 1)):
        def extract(block, weights, bands=3):
            if sign < 0:
                block.mirror()
            block.extract(mesh, weights, split_z=bands)
        arm = Blocks()
        arm.fill(28, 44, -8, 8, 81, 133, "skin")
        arm.fill(29, 43, -9, -6, 84, 120, "skin_light")
        arm.fill(28, 29, -8, 8, 83, 119, "skin_shadow")
        def arm_weights(p):
            t = clamp((1.092 - p[2]) / .044)
            return mixed(((f"UpperArm.{side}", 1 - t), (f"Forearm.{side}", t)))
        extract(arm, arm_weights)
        sleeve = Blocks()
        sleeve.fill(25, 47, -11, 11, 121, 136, "white")
        sleeve.fill(26, 46, -12, 11, 124, 134, "white_light")
        sleeve.fill(26, 46, -11, 12, 119, 123, "white_shadow")
        sleeve.fill(28, 44, -11, 11, 118, 120, "white")
        extract(sleeve, one(f"UpperArm.{side}"))
        hand = Blocks()
        hand.fill(29, 43, -8, 8, 69, 82, "skin")
        hand.fill(30, 42, -9, -6, 71, 80, "skin_light")
        hand.fill(27, 29, -7, 4, 72, 79, "skin_shadow")
        extract(hand, one(f"Hand.{side}"), 0)
        thigh = Blocks()
        thigh.fill(3, 21, -9, 9, 44, 86, "skin")
        thigh.fill(4, 20, -10, -7, 47, 76, "skin_light")
        thigh.fill(3, 21, -10, 10, 77, 84, "skirt_shadow")
        extract(thigh, one(f"Thigh.{side}"))
        shin = Blocks()
        shin.fill(4, 20, -10, 9, 11, 46, "skin")
        shin.fill(4, 20, -11, 10, 13, 39, "white")
        shin.fill(3, 21, -11, 11, 34, 40, "white_shadow")
        shin.fill(4, 20, -12, 10, 38, 42, "white_light")
        shin.fill(3, 20, -11, 11, 28, 33, "white_light")
        shin.fill(4, 21, -12, 11, 23, 27, "white_shadow")
        shin.fill(3, 21, -11, 11, 18, 22, "white")
        shin.fill(4, 20, -12, 11, 14, 18, "white_shadow")
        shin.fill(5, 19, -13, -10, 29, 31, "white_fold")
        shin.fill(6, 17, -13, -10, 20, 22, "white_fold")
        def shin_weights(p):
            t = clamp((.468 - p[2]) / .036)
            return mixed(((f"Thigh.{side}", 1 - t), (f"Shin.{side}", t)))
        extract(shin, shin_weights)
        shoe = Blocks()
        shoe.fill(2, 22, -21, 13, 0, 3, "sole")
        shoe.fill(2, 22, -20, 12, 3, 8, "shoe_shadow")
        shoe.fill(3, 21, -20, -9, 7, 10, "shoe")
        shoe.fill(4, 20, -18, -11, 10, 12, "shoe_light")
        shoe.fill(4, 20, -9, 11, 7, 13, "shoe")
        shoe.fill(3, 21, -10, -6, 11, 14, "shoe_light")
        shoe.fill(7, 17, -11, -7, 13, 14, "shoe_shadow")
        extract(shoe, one(f"Foot.{side}"), 0)


def skirt(mesh):
    count = 64
    rows = ((.966, .221, .151), (.920, .234, .164), (.855, .255, .190),
            (.785, .278, .213), (.715, .300, .234), (.675, .311, .245))
    rings, inner, weights = [], [], []
    for row, (z, rx, ry) in enumerate(rows):
        points, inside, groups = [], [], []
        for i in range(count):
            angle = i * math.tau / count
            sx, sy = math.sin(angle), -math.cos(angle)
            fold = (0., -.008, -.012, .003)[i % 4] * clamp(row / 2)
            points.append(((rx + fold) * sx, (ry + fold) * sy, z))
            inside.append(((rx + fold - .008) * sx, (ry + fold - .008) * sy, z))
            sector = i / (count / len(SECTORS))
            first = math.floor(sector)
            next_weight = sector - first
            hip = clamp((z - .855) / .111)
            tip = clamp((.855 - z) / .180)
            group = [("Hips", hip)]
            for owner, amount in ((first, 1 - next_weight), (first + 1, next_weight)):
                for segment, blend in ((1, 1 - tip), (2, tip)):
                    group.append((f"Skirt.{SECTORS[owner % 8]}.{segment}", (1 - hip) * amount * blend))
            groups.append(mixed(group))
        rings.append(points)
        inner.append(inside)
        weights.append(groups)

    def face(container, indices, color):
        points = [container[r][i] for r, i in indices]
        lookup = {tuple(round(v, 6) for v in p): weights[r][i] for p, (r, i) in zip(points, indices)}
        mesh.quad(points, color, lookup.__getitem__)
    for row in range(len(rows) - 1):
        for i in range(count):
            j = (i + 1) % count
            outer = ("skirt", "skirt_shadow", "skirt_light", "skirt")[i % 4]
            if row == 0:
                outer = "skirt_edge"
            face(rings, ((row + 1, i), (row + 1, j), (row, j), (row, i)), outer)
            face(inner, ((row, i), (row, j), (row + 1, j), (row + 1, i)), "skirt_shadow")
    # Only the narrow fabric edge is closed; the large lower opening stays hollow.
    for row, reverse in ((0, False), (len(rows) - 1, True)):
        for i in range(count):
            j = (i + 1) % count
            points = [rings[row][i], rings[row][j], inner[row][j], inner[row][i]]
            lookup = {tuple(round(v, 6) for v in p): weights[row][idx] for p, idx in zip(points, (i, j, j, i))}
            mesh.quad(points[::-1] if reverse else points, "skirt_edge", lookup.__getitem__)


def dynamics(rig, secondary):
    springs = {}
    for stem, (_, coords) in secondary.items():
        for i, tail in enumerate(coords[1:], 1):
            hair = stem.startswith("Hair")
            springs[f"{stem}.{i}"] = dict(tail=tail, stiffness=(48. if i == 1 else 38.) if hair else (105. if i == 1 else 85.),
                                           damping=8. if hair else 12., gravity=.8 if hair else .35,
                                           radius=.010 if hair else .012)
    colliders = {}
    for side in ("L", "R"):
        for segment, radius in (("Thigh", .095), ("Shin", .091)):
            bone = rig.data.bones[f"{segment}.{side}"]
            colliders[bone.name] = dict(start=list(bone.head_local.lerp(bone.tail_local, .10)),
                                        end=list(bone.head_local.lerp(bone.tail_local, .92)), radius=radius)
    colliders["Head"] = dict(start=[0., .035, 1.59], end=[0., .035, 1.59], radius=.192)
    return springs, colliders


def studio(scene):
    stage = bpy.data.collections.new("Misaka | preview studio (not exported)")
    scene.collection.children.link(stage)
    def place(name, data, position, target):
        obj = bpy.data.objects.new(name, data)
        stage.objects.link(obj)
        obj.location = position
        obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()
        return obj
    data = bpy.data.meshes.new("Misaka preview ground")
    data.from_pydata([(-200, -200, -.015), (200, -200, -.015), (200, 200, -.015), (-200, 200, -.015)], [], [(0, 1, 2, 3)])
    floor = bpy.data.materials.new("Misaka preview | graphite")
    floor.use_nodes = True
    bsdf = next(n for n in floor.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (.065, .092, .098, 1)
    bsdf.inputs["Roughness"].default_value = .95
    data.materials.append(floor)
    stage.objects.link(bpy.data.objects.new("Misaka preview ground", data))
    camera = bpy.data.cameras.new("Misaka portrait")
    camera.type, camera.ortho_scale = "ORTHO", 2.15
    scene.camera = place("Misaka portrait", camera, (2.8, -7., 2.9), (0., 0., .94))
    for name, location, energy, size, color in (
        ("Key", (-3, -4, 5), 450, 4., (1., .94, .87)),
        ("Fill", (3, -4, 2), 300, 4., (.86, .95, 1.)),
        ("Rim", (1, 2, 4), 470, 3., (.84, 1., .93)),
    ):
        light = bpy.data.lights.new("Misaka " + name, "AREA")
        light.energy, light.size, light.color = energy, size, color
        place("Misaka " + name, light, location, (0, 0, 1))
    scene.world = bpy.data.worlds.new("Misaka preview world")
    scene.world.use_nodes = True
    background = next(n for n in scene.world.node_tree.nodes if n.type == "BACKGROUND")
    background.inputs[0].default_value = (.3, .36, .38, 1)
    background.inputs[1].default_value = .5
    scene.render.engine = "BLENDER_EEVEE"
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.view_settings.exposure = -.3
    scene.render.resolution_x, scene.render.resolution_y = 880, 1080
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    return stage


def main(output=OUT, render=False):
    global OUT
    OUT = Path(output).resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    scene_name = "Misaka | RigWorks avatar"
    previous = bpy.data.scenes.get(scene_name)
    scene = bpy.data.scenes.new(scene_name + " build")
    bpy.context.window.scene = scene
    if previous is not None:
        for obj in list(previous.objects):
            if len(obj.users_scene) == 1:
                bpy.data.objects.remove(obj, do_unlink=True)
        bpy.data.scenes.remove(previous)
    scene.name = scene_name
    scene.unit_settings.system, scene.unit_settings.scale_length = "METRIC", 1
    scene.render.fps = 60
    scene.frame_start, scene.frame_end = 1, 180
    collection = bpy.data.collections.new("Misaka | avatar (FBX selection)")
    scene.collection.children.link(collection)
    secondary = chains()
    rig = build_rig(collection, secondary)
    surface = Surface()
    for build in (head_and_hair, clothes, limbs, skirt):
        build(surface)
    model = surface.object(collection, rig, material())
    model.name, model.data.name = "Misaka", "Misaka_Skin"
    # Keep the neutral low-expression face independent of Blender/FBX morph defaults.
    blink = model.data.shape_keys.key_blocks["Blink"]
    for i, label in enumerate(surface.morphs):
        if label == "eye":
            blink.data[i].co.z = 1.598 + (model.data.vertices[i].co.z - 1.598) * .10
    minimum = [min(p[a] for p in surface.vertices) for a in range(3)]
    maximum = [max(p[a] for p in surface.vertices) for a in range(3)]
    assert abs(minimum[2]) < 1e-7 and abs(maximum[2] - 1.85) < 1e-6, (minimum, maximum)
    for obj in bpy.context.selected_objects:
        obj.select_set(False)
    model.select_set(True)
    rig.select_set(True)
    bpy.context.view_layer.objects.active = rig
    result = bpy.ops.export_scene.fbx(
        filepath=str(OUT / "misaka.fbx"), check_existing=False, use_selection=True,
        object_types={"ARMATURE", "MESH"}, use_armature_deform_only=True, add_leaf_bones=False,
        bake_anim=False, axis_forward="-Z", axis_up="Y", apply_unit_scale=True,
        use_mesh_modifiers=False, use_triangles=True, use_tspace=True,
        path_mode="COPY", embed_textures=True,
    )
    if "FINISHED" not in result:
        raise RuntimeError("FBX export did not finish")
    # Blender's FBX shape exporter leaves every key enabled, even with bake_anim=False.
    for shape in model.data.shape_keys.key_blocks:
        shape.value = 0.
    bpy.context.view_layer.update()
    springs, colliders = dynamics(rig, secondary)
    report = dict(version=1, name="Misaka Sister", model="misaka.fbx", height=1.85,
                  sha256=hashlib.sha256((OUT / "misaka.fbx").read_bytes()).hexdigest(),
                  sourceRig="model-tests/block_player/block_player.blend", front=[0, -1, 0], up=[0, 0, 1],
                  meshCount=1, materials=1, vertices=len(surface.vertices), triangles=2 * len(surface.faces),
                  maximumInfluences=max(map(len, surface.weights)), morphs=["Blink", "Smile"],
                  bounds=dict(min=minimum, max=maximum), clips=[], motionSource=dict(kind="rigworks_default_runtime"),
                  bones={b.name: dict(head=list(b.head_local), tail=list(b.tail_local),
                                      parent=b.parent.name if b.parent else None) for b in rig.data.bones},
                  springInputs=springs, colliderInputs=colliders,
                  scope=dict(liveGame="UNVERIFIED_LIVE", physics="Spring particles with capsule contacts; no cloth self collision"))
    (OUT / "authoring.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
    stage = studio(scene)
    for obj in stage.objects:
        obj.hide_set(True)
    rig.select_set(False)
    bpy.context.view_layer.objects.active = model
    for screen in bpy.data.screens:
        for area in screen.areas:
            if area.type == "VIEW_3D":
                space = area.spaces.active
                space.shading.type = "MATERIAL"
                space.overlay.show_overlays = False
                space.region_3d.view_location = (0, 0, .94)
                space.region_3d.view_rotation = scene.camera.rotation_euler.to_quaternion()
                space.region_3d.view_distance = 3.3
                space.region_3d.view_perspective = "ORTHO"
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "misaka.blend"))
    if render:
        scene.render.filepath = str(OUT / "misaka-preview.png")
        bpy.ops.render.render(write_still=True)
    print("MISAKA", json.dumps({key: report[key] for key in ("vertices", "triangles", "maximumInfluences", "bounds", "clips")}))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--render", action="store_true")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])
    main(args.output, args.render)
