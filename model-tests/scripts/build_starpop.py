"""Build the original Starpop avatar from the BlockPlayer Blender rig.

blender --background --factory-startup --python-exit-code 1 --python model-tests/scripts/build_starpop.py
"""

from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import zlib

import bpy
from mathutils import Quaternion, Vector


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "build/starpop"
PIXEL = .01
PALETTE = dict(
    cream="fff0da", cream_light="fff8eb", cream_shadow="e9c5c8", lace="ffdce1",
    skin="fac3a8", skin_light="ffd2b8", skin_shadow="dfa191", blush="f79ca1",
    rose="e35f86", rose_light="fa8fa1", rose_dark="a73b63", strawberry="bc436c",
    petal="ed809e", petal_light="ffb3bd", lining="a75382", lavender="8a578d",
    plum="542742", plum_light="773a5b", mulberry="3b263a",
    hair="e68b99", hair_light="ffd0b6", hair_glint="f8a9a3", hair_shadow="c26685",
    hair_deep="974b72", mint="81dac4", mint_light="bcf1d3", mint_shadow="449985",
    gold="efb56b", gold_light="fff0a9", iris_dark="64253f", iris="ad4562",
    iris_light="e76c81", eye_light="fff8ea", eye_shadow="e6a0a7",
)
TILE = 12
ATLAS_WIDTH = 96
ATLAS_HEIGHT = math.ceil(len(PALETTE) / (ATLAS_WIDTH // TILE)) * TILE


def png(path, rows):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", ATLAS_WIDTH, ATLAS_HEIGHT,
                     8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"".join(b"\0" + r for r in rows)))
                     + chunk(b"IEND", b""))


def make_material():
    colors = [bytes.fromhex(color) for color in PALETTE.values()]
    across = ATLAS_WIDTH // TILE
    rows = [b"".join(colors[min((y // TILE) * across + x, len(colors) - 1)] * TILE
                     for x in range(across)) for y in range(ATLAS_HEIGHT)]
    path = OUT / "starpop_albedo.png"
    png(path, rows)
    image = bpy.data.images.load(str(path))
    image.pack()
    material = bpy.data.materials.new("Starpop | pixel palette")
    material.diffuse_color = (.82, .36, .43, 1)
    material.use_nodes = True
    bsdf = material.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Roughness"].default_value = .85
    sampler = material.node_tree.nodes.new("ShaderNodeTexImage")
    sampler.name = "Starpop palette (nearest)"
    sampler.image = image
    sampler.interpolation = "Closest"
    material.node_tree.links.new(sampler.outputs["Color"], bsdf.inputs["Base Color"])
    return material


def one(bone):
    return ((bone, 1.),)


def mixed(terms):
    weights = defaultdict(float)
    for bone, weight in terms:
        if weight > 1e-5:
            weights[bone] += weight
    total = sum(weights.values())
    return tuple(sorted((bone, round(weight / total, 7)) for bone, weight in weights.items()))


def clamp(v):
    return min(1., max(0., v))


class Surface:
    def __init__(self):
        self.vertices, self.faces, self.colors, self.weights, self.morphs = [], [], [], [], []
        self.weld = {}

    def quad(self, points, color, weight, morph=None):
        face = []
        for point in points:
            coord = tuple(round(value, 6) for value in point)
            groups = weight(coord) if callable(weight) else weight
            key = (coord, groups, morph)
            if key not in self.weld:
                self.weld[key] = len(self.vertices)
                self.vertices.append(coord)
                self.weights.append(groups)
                self.morphs.append(morph)
            face.append(self.weld[key])
        if len(set(face)) != 4:
            raise ValueError("Degenerate generated quad")
        self.faces.append(face)
        self.colors.append(color)

    def front(self, x0, x1, z0, z1, y, color, weight, morph=None):
        self.quad(((x0, y, z0), (x1, y, z0), (x1, y, z1), (x0, y, z1)),
                  color, weight, morph)

    def stamp(self, rows, left, top, y, pixel, colors, bone, morph=None, flip=False):
        for j, source in enumerate(rows):
            row = source[::-1] if flip else source
            i = 0
            while i < len(row):
                end = i + 1
                while end < len(row) and row[end] == row[i]:
                    end += 1
                if row[i] != ".":
                    self.front(left + i * pixel, left + end * pixel, top - (j + 1) * pixel,
                               top - j * pixel, y, colors[row[i]], one(bone), morph)
                i = end

    def object(self, collection, rig, material):
        mesh = bpy.data.meshes.new("Starpop_Skin")
        mesh.from_pydata(self.vertices, [], self.faces)
        if mesh.validate():
            raise ValueError("Invalid generated surface")
        mesh.update()
        mesh.materials.append(material)
        obj = bpy.data.objects.new("Starpop", mesh)
        collection.objects.link(obj)
        obj.parent = rig
        skin = obj.modifiers.new("Skin", "ARMATURE")
        skin.object = rig
        groups = {name: obj.vertex_groups.new(name=name) for name in rig.data.bones.keys()}
        batches = defaultdict(list)
        for i, weights in enumerate(self.weights):
            if not weights or len(weights) > 4 or abs(sum(w for _, w in weights) - 1.) > 1e-5:
                raise ValueError("Unnormalized skin weights at vertex " + str(i))
            for bone, weight in weights:
                batches[(bone, weight)].append(i)
        for (bone, weight), indices in batches.items():
            groups[bone].add(indices, weight, "REPLACE")
        uv = mesh.uv_layers.new(name="UVMap")
        swatches = {color: i for i, color in enumerate(PALETTE)}
        for poly, color in zip(mesh.polygons, self.colors):
            i = swatches[color]
            x, y = i % (ATLAS_WIDTH // TILE) * TILE, i // (ATLAS_WIDTH // TILE) * TILE
            for idx, (u, v) in zip(poly.loop_indices, ((3, 3), (9, 3), (9, 9), (3, 9))):
                uv.data[idx].uv = ((x + u) / ATLAS_WIDTH, 1. - (y + v) / ATLAS_HEIGHT)
        obj.shape_key_add(name="Basis")
        blink = obj.shape_key_add(name="Blink")
        smile = obj.shape_key_add(name="Smile")
        for idx, morph in enumerate(self.morphs):
            if morph == "eye":
                p = blink.data[idx].co
                p.z = 1.592 + (p.z - 1.592) * .12
            elif morph == "mouth":
                smile.data[idx].co.z += 0.013 * clamp(abs(smile.data[idx].co.x) / .030)
        return obj


class Blocks:
    """Union of occupied cells. Extract only visible outer faces and merge rectangles by swatch."""

    def __init__(self):
        self.cells = {}

    def fill(self, x0, x1, y0, y1, z0, z1, color):
        for x in range(x0, x1):
            for y in range(y0, y1):
                for z in range(z0, z1):
                    self.cells[x, y, z] = color

    def mirror(self):
        self.cells = {(-x - 1, y, z): color for (x, y, z), color in self.cells.items()}
        return self

    def extract(self, surface, weights, split_z=0):
        exposed = defaultdict(dict)
        for cell, color in self.cells.items():
            for a in range(3):
                u, v = (a + 1) % 3, (a + 2) % 3
                for sign in (-1, 1):
                    neighbor = list(cell)
                    neighbor[a] += sign
                    if tuple(neighbor) not in self.cells:
                        band = cell[2] // split_z if split_z else 0
                        exposed[a, sign, cell[a] + (sign > 0)][cell[u], cell[v]] = (color, band)
        for (a, sign, slice_), cells in exposed.items():
            u, v = (a + 1) % 3, (a + 2) % 3
            for x, y in sorted(cells):
                if (x, y) not in cells:
                    continue
                value = cells[x, y]
                width = 1
                while cells.get((x + width, y)) == value:
                    width += 1
                height = 1
                while all(cells.get((x + i, y + height)) == value for i in range(width)):
                    height += 1
                for i in range(width):
                    for j in range(height):
                        del cells[x + i, y + j]
                corners = [(x, y), (x + width, y), (x + width, y + height), (x, y + height)]
                points = []
                for p, q in corners if sign > 0 else reversed(corners):
                    point = [0., 0., 0.]
                    point[a], point[u], point[v] = slice_ * PIXEL, p * PIXEL, q * PIXEL
                    points.append(point)
                surface.quad(points, value[0], weights)


def build_head(mesh):
    head = Blocks()
    head.fill(-23, 23, -20, 19, 142, 179, "skin")
    head.fill(-21, 21, -21, 13, 145, 169, "skin_light")
    head.fill(-20, 20, -19, 16, 141, 145, "skin")
    head.fill(-23, 23, -20, 19, 167, 179, "hair")
    head.fill(-25, 25, -17, 23, 170, 180, "hair")
    head.fill(-23, 23, -14, 22, 180, 183, "hair_glint")
    head.fill(-19, 19, -10, 19, 183, 185, "hair_light")
    head.fill(-21, 21, 17, 23, 149, 171, "hair_shadow")
    head.fill(-22, 22, 20, 24, 156, 176, "hair")
    for s in (-1, 1):
        a, b = sorted((s * 21, s * 25))
        head.fill(a, b, -13, 21, 148, 173, "hair_shadow")
        head.fill(a, b, -14, 7, 153, 173, "hair")
        a, b = sorted((s * 22, s * 25))
        head.fill(a, b, -17, -9, 149, 163, "hair_deep")
        a, b = sorted((s * 22, s * 26))
        head.fill(a, b, -7, 4, 151, 158, "skin")
    # Offset pixel steps make the fringe silhouette asymmetric.
    fringe = [(-23, -17, 160), (-17, -13, 163), (-13, -8, 166), (-8, -4, 168),
              (-4, 1, 165), (1, 5, 162), (5, 10, 159), (10, 14, 162),
              (14, 18, 165), (18, 23, 161)]
    for a, b, bottom in fringe:
        head.fill(a, b, -22, -16, bottom, 177, "hair_shadow")
        head.fill(a, b, -23, -18, bottom + 2, 177, "hair")
        head.fill(a, b - 1, -24, -21, max(bottom + 5, 171), 175, "hair_glint")
        if b - a >= 4:
            head.fill(a + 1, a + 3, -24, -21, 174, 177, "hair_light")
    head.extract(mesh, one("Head"))

    eye = ["..######...", ".#########.", "##wwwdww##.", "#wwwddddd#.", ".wwdiiHdww.", ".wwdiiHHww.",
           ".wwdiiiiww.", ".ssdiiiiws.", "..siijjjs..", "...ssss...."]
    colors = {"#": "mulberry", "d": "iris_dark", "i": "iris", "j": "iris_light",
              "w": "eye_light", "H": "cream_light", "s": "eye_shadow"}
    for x, flip in [(-.176, False), (.055, True)]:
        mesh.stamp(eye, x, 1.641, -.2108, .011, colors, "Head", "eye", flip)
    for x in (-.187, .113):
        mesh.stamp([".++..+.", "++++++.", ".+.+..."], x, 1.523, -.211, .0105,
                   {"+": "blush"}, "Head")
    mesh.stamp([".p...p.", "..ppp..", "...q..."], -.035, 1.492, -.2112, .01,
               {"p": "plum_light", "q": "blush"}, "Head", "mouth")
    mesh.stamp(["..g..", "..g..", "ggmgg", ".gmg.", ".g.g."],
               -.205, 1.752, -.241, .011, {"g": "gold_light", "m": "mint"}, "Head")


def secondary_chains():
    chains = {}
    for side, sign in [("L", -1), ("R", 1)]:
        chains[f"Hair.{side}"] = ("Head", [(sign * .233, .081, 1.745),
                                           (sign * .310, .126, 1.582),
                                           (sign * .332, .197, 1.417)])
        chains[f"Ribbon.{side}"] = ("Hips", [(sign * .07, .162, .968),
                                             (sign * .162, .254, .825)])
    # Lower vectors follow the open skirt, rather than hanging through the thighs.
    for i in range(8):
        a = (i + .5) * math.tau / 8
        c, s = math.cos(a), math.sin(a)
        chains[f"Skirt.{i + 1:02}"] = ("Hips", [(.220 * c, .163 * s, .966),
                                               (.301 * c, .232 * s, .772),
                                               (.401 * c, .344 * s, .548)])
    return chains


def build_hair(mesh):
    for side in ("R", "L"):
        def weights(point):
            w = clamp((1.639 - point[2]) / .112)
            return mixed(((f"Hair.{side}.01", 1 - w), (f"Hair.{side}.02", w)))
        hair = Blocks()
        hair.fill(22, 31, 3, 16, 166, 177, "hair_shadow")
        hair.fill(24, 35, 3, 18, 161, 175, "hair")
        hair.fill(27, 37, 5, 20, 156, 171, "hair_shadow")
        hair.fill(27, 35, 4, 18, 157, 168, "hair")
        hair.fill(28, 39, 9, 23, 149, 163, "hair")
        hair.fill(29, 38, 13, 24, 143, 153, "hair_shadow")
        hair.fill(29, 35, 14, 24, 141, 149, "hair_deep")
        hair.fill(34, 37, 10, 18, 141, 152, "hair")
        hair.fill(35, 37, 8, 15, 139, 146, "hair_shadow")
        hair.fill(27, 33, 3, 7, 163, 168, "hair_glint")
        hair.fill(31, 35, 4, 6, 158, 163, "hair_glint")
        hair.fill(35, 37, 8, 10, 150, 159, "hair_glint")
        if side == "L":
            hair.mirror()
        hair.extract(mesh, weights, split_z=3)

        clip = Blocks()
        clip.fill(24, 32, 0, 10, 169, 173, "mint_shadow")
        clip.fill(23, 27, -2, 8, 169, 177, "mint")
        clip.fill(29, 33, -2, 8, 169, 177, "mint")
        clip.fill(22, 26, -1, 8, 172, 178, "mint_light")
        clip.fill(30, 34, -1, 8, 172, 178, "mint_light")
        clip.fill(26, 30, -3, 7, 171, 176, "gold")
        clip.fill(27, 29, -4, 0, 172, 174, "gold_light")
        if side == "L":
            clip.mirror()
        clip.extract(mesh, one(f"Hair.{side}.01"))


def build_clothes(mesh):
    bodice = Blocks()
    bodice.fill(-21, 21, -13, 13, 96, 140, "cream")
    bodice.fill(-21, 21, -12, 14, 121, 138, "cream_light")
    bodice.fill(-19, 19, -14, 14, 99, 127, "rose")
    bodice.fill(-15, 15, -15, 12, 99, 123, "rose_light")
    bodice.fill(-12, 12, -16, -10, 101, 122, "cream_light")
    bodice.fill(-2, 2, -17, -11, 101, 123, "petal")
    for z in (104, 110, 116):
        bodice.fill(-1, 1, -18, -16, z, z + 2, "gold_light")
    for s in (-1, 1):
        a, b = sorted((s * 11, s * 13))
        bodice.fill(a, b, -17, -13, 100, 123, "lace")
        a, b = sorted((s * 14, s * 17))
        bodice.fill(a, b, -15, -12, 105, 129, "strawberry")
        a, b = sorted((s * 9, s * 19))
        bodice.fill(a, b, -15, -11, 132, 138, "lace")
    bodice.fill(-8, 8, -16, -10, 129, 134, "rose_dark")
    bodice.fill(-6, 6, -17, -13, 130, 135, "mint_shadow")
    bodice.fill(-4, 4, -18, -13, 130, 136, "mint")
    bodice.fill(-2, 2, -19, -15, 131, 135, "mint_light")
    bodice.extract(mesh, lambda p: mixed((("Spine", clamp((1.21 - p[2]) / .21)), ("Chest", clamp((p[2] - 1.) / .21)))), split_z=4)

    collar = Blocks()
    collar.fill(-9, 9, -8, 8, 138, 147, "skin")
    collar.fill(-11, 11, -9, 8, 139, 141, "cream_light")
    collar.extract(mesh, one("Neck"))

    belt = Blocks()
    belt.fill(-23, 23, -16, 16, 93, 101, "rose_dark")
    belt.fill(-22, 22, -17, 17, 95, 99, "rose")
    belt.fill(-5, 5, -18, -13, 94, 100, "gold")
    belt.fill(-3, 3, -19, -14, 95, 99, "gold_light")
    belt.fill(-1, 1, -20, -15, 96, 98, "mint")
    belt.fill(-8, 8, 15, 21, 93, 103, "rose_dark")
    for s in (-1, 1):
        a, b = sorted((s * 5, s * 17))
        belt.fill(a, b, 17, 23, 95, 103, "rose")
        a, b = sorted((s * 6, s * 15))
        belt.fill(a, b, 21, 24, 98, 102, "rose_light")
    belt.extract(mesh, one("Hips"))
    for side, s in [("R", 1), ("L", -1)]:
        ribbon = Blocks()
        ribbon.fill(6, 14, 21, 23, 90, 96, "rose_dark")
        ribbon.fill(9, 18, 22, 25, 85, 92, "rose")
        ribbon.fill(13, 21, 23, 26, 81, 87, "rose_light")
        ribbon.fill(13, 17, 24, 26, 79, 84, "rose")
        ribbon.fill(16, 21, 25, 26, 82, 84, "gold")
        if s < 0:
            ribbon.mirror()
        ribbon.extract(mesh, lambda p: mixed((("Hips", clamp((p[2] - .88) / .08)),
                      (f"Ribbon.{side}.01", clamp((.96 - p[2]) / .08)))), split_z=3)


def build_limbs(mesh):
    for side in ("R", "L"):
        def extract(part, bone, blend=None):
            if side == "L":
                part.mirror()
            part.extract(mesh, one(f"{bone}.{side}") if blend is None else blend, split_z=3 if blend else 0)

        sleeve = Blocks()
        sleeve.fill(19, 33, -9, 9, 128, 137, "cream")
        sleeve.fill(24, 45, -11, 11, 115, 136, "cream")
        sleeve.fill(23, 47, -10, 10, 119, 135, "cream_light")
        sleeve.fill(26, 46, -12, 12, 119, 132, "cream")
        sleeve.fill(25, 45, -11, 11, 115, 118, "cream_shadow")
        sleeve.fill(27, 44, -10, 10, 112, 116, "rose")
        sleeve.fill(27, 44, -11, 11, 111, 113, "lace")
        sleeve.fill(29, 43, -8, 8, 106, 113, "skin")
        extract(sleeve, "UpperArm")

        arm = Blocks()
        arm.fill(29, 43, -8, 8, 83, 108, "skin")
        arm.fill(30, 42, -9, -6, 88, 106, "skin_light")
        arm.fill(28, 44, -9, 9, 81, 88, "cream")
        arm.fill(28, 44, -10, 9, 85, 89, "mint_shadow")
        arm.fill(31, 41, -11, -8, 86, 88, "mint")
        def forearm_weights(p):
            w = clamp((1.09 - p[2]) / .048)
            return mixed(((f"UpperArm.{side}", 1 - w), (f"Forearm.{side}", w)))
        extract(arm, "Forearm", forearm_weights)

        hand = Blocks()
        hand.fill(28, 44, -9, 8, 70, 83, "cream")
        hand.fill(29, 43, -10, -6, 72, 82, "cream_light")
        hand.fill(26, 29, -9, 3, 73, 80, "cream_shadow")
        hand.fill(32, 40, -11, -8, 75, 79, "lace")
        extract(hand, "Hand")

        thigh = Blocks()
        thigh.fill(3, 21, -10, 10, 44, 86, "cream_shadow")
        thigh.fill(4, 21, -11, 8, 47, 65, "cream")
        thigh.fill(3, 21, -10, 11, 52, 56, "lace")
        thigh.fill(4, 21, -11, 10, 54, 56, "rose_light")
        extract(thigh, "Thigh")

        shin = Blocks()
        shin.fill(4, 20, -11, 9, 10, 46, "cream")
        shin.fill(5, 19, -12, -8, 13, 42, "cream_light")
        shin.fill(4, 20, -11, 10, 13, 17, "lace")
        shin.fill(4, 20, -12, 10, 10, 13, "rose_dark")
        def shin_weights(p):
            w = clamp((.47 - p[2]) / .04)
            return mixed(((f"Thigh.{side}", 1 - w), (f"Shin.{side}", w)))
        extract(shin, "Shin", shin_weights)

        shoe = Blocks()
        shoe.fill(2, 22, -19, 13, 0, 4, "mulberry")
        shoe.fill(2, 22, -18, 12, 4, 10, "plum")
        shoe.fill(3, 21, -19, -11, 7, 11, "plum_light")
        shoe.fill(4, 20, -10, 11, 9, 15, "plum")
        shoe.fill(3, 21, -8, -4, 12, 16, "rose_dark")
        shoe.fill(16, 21, -9, -4, 14, 17, "gold")
        shoe.fill(17, 20, -10, -5, 15, 17, "gold_light")
        extract(shoe, "Foot")


def build_skirt(mesh):
    # A continuous garment with a finished hem and a real inner wall, open below.
    panels, steps = 8, 6
    count = panels * steps
    rows = [( .96, .219, .160), (.900, .244, .181), (.812, .283, .218),
            (.752, .311, .253), (.683, .356, .293), (.649, .375, .317),
            (.622, .388, .330), (.585, .400, .345), (.557, .405, .352)]
    def name(i, j):
        return f"Skirt.{i % panels + 1:02}.{j:02}"
    rings = []
    influences = []
    for line, (z, rx, ry) in enumerate(rows):
        ring = []
        ring_weights = []
        for i in range(count):
            a = i * math.tau / count
            u = (i % steps) / steps
            fold = math.sin(u * math.pi) * (.006 + .015 * clamp(line / 5)) - .005
            scallop = (line >= 6) * (.010 if i % steps in (0, 1, 5) else 0)
            c, s = math.cos(a), math.sin(a)
            ring.append(((rx + fold) * c, (ry + fold) * s, z + scallop))
            right, seam = i // steps, i % steps
            if seam == 0:
                panel_owners = [(right - 1, .5), (right, .5)]
            elif seam == 1:
                panel_owners = [(right - 1, .20), (right, .80)]
            elif seam == steps - 1:
                panel_owners = [(right, .80), (right + 1, .20)]
            else:
                panel_owners = [(right, 1.)]
            hip = clamp((z - .81) / .15)
            tip = clamp((.805 - z) / .19)
            ring_weights.append(mixed([("Hips", hip)] + [
                (name(owner, segment), (1 - hip) * w * weight)
                for owner, w in panel_owners for segment, weight in [(1, 1 - tip), (2, tip)]
            ]))
        rings.append(ring)
        influences.append(ring_weights)
    inner = [[(x - .010 * math.cos(i * math.tau / count),
               y - .010 * math.sin(i * math.tau / count), z)
              for i, (x, y, z) in enumerate(ring)] for ring in rings]

    def face(container, indices, color):
        points = [container[r][i] for r, i in indices]
        posed = dict(zip(points, [influences[r][i] for r, i in indices]))
        weights = {tuple(round(v, 6) for v in point): value for point, value in posed.items()}
        mesh.quad(points, color, weights.__getitem__)

    for r in range(len(rows) - 1):
        for i in range(count):
            j = (i + 1) % count
            edge = i % steps
            if r < 4:
                outer = ("rose_dark", "rose", "petal", "petal_light", "rose_light", "strawberry")[edge]
                if r == 3 and edge in (2, 3):
                    outer = "cream_light" if edge == 2 else "gold_light"
            elif r == 4:
                outer = "cream" if edge in (0, 5) else "lace"
            elif r == 5:
                outer = "rose_dark"
            else:
                outer = ("cream_shadow", "lace", "cream", "cream_light", "cream", "cream_shadow")[edge]
            indices = [(r + 1, i), (r + 1, j), (r, j), (r, i)]
            face(rings, indices, outer)
            face(inner, indices[::-1], "lining" if r < 5 else "cream_shadow")
    for i in range(count):
        j = (i + 1) % count
        for r, reverse, color in [(0, False, "rose_dark"), (len(rows) - 1, True, "lace")]:
            points = [rings[r][i], rings[r][j], inner[r][j], inner[r][i]]
            index = {tuple(round(v, 6) for v in p): influences[r][idx]
                     for p, idx in zip(points, (i, j, j, i))}
            mesh.quad(points[::-1] if reverse else points, color, index.__getitem__)


def build_rig(collection, chains):
    source = ROOT / "model-tests/block_player/block_player.blend"
    with bpy.data.libraries.load(str(source), link=False) as (_, loaded):
        loaded.objects = ["BlockPlayerRig"]
    rig = loaded.objects[0]
    if rig is None:
        raise ValueError("BlockPlayerRig is missing from the source .blend")
    collection.objects.link(rig)
    rig.name = "StarpopRig"
    rig.data.name = "StarpopSkeleton"
    rig.show_in_front = False
    rig.data.display_type = "STICK"
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    for bone in rig.pose.bones:
        for constraint in list(bone.constraints):
            bone.constraints.remove(constraint)
    bpy.ops.object.mode_set(mode="EDIT")
    for name in ("Hair1", "Hair2", "Pendant1", "Pendant2", "ChestFollower"):
        rig.data.edit_bones.remove(rig.data.edit_bones[name])
    dynamics = rig.data.collections.new("Secondary dynamics")
    for stem, (parent, points) in chains.items():
        for i, (head, tail) in enumerate(zip(points, points[1:]), 1):
            bone = rig.data.edit_bones.new(f"{stem}.{i:02}")
            bone.head, bone.tail = head, tail
            bone.parent = rig.data.edit_bones[parent]
            dynamics.assign(bone)
            parent = bone.name
    bpy.ops.object.mode_set(mode="OBJECT")
    return rig


def bake_export(scene, rig, model):
    tracks = list(rig.animation_data.nla_tracks)
    rig.animation_data.action = None
    rig.animation_data.use_nla = True
    for track in tracks:
        track.mute = False
        for strip in track.strips:
            strip.name = track.name
            strip.extrapolation = "NOTHING"
    for pose in rig.pose.bones:
        pose.matrix_basis.identity()
    scene.frame_set(0)
    bpy.context.view_layer.update()
    for obj in bpy.context.selected_objects:
        obj.select_set(False)
    rig.select_set(True)
    model.select_set(True)
    bpy.context.view_layer.objects.active = rig
    result = bpy.ops.export_scene.fbx(
        filepath=str(OUT / "starpop.fbx"), use_selection=True, check_existing=False,
        object_types={"ARMATURE", "MESH"}, add_leaf_bones=False, bake_anim=True,
        bake_anim_use_all_actions=False, bake_anim_use_nla_strips=True,
        bake_anim_force_startend_keying=True, bake_anim_simplify_factor=0.,
        axis_forward="-Z", axis_up="Y", apply_unit_scale=True,
        use_mesh_modifiers=False, use_triangles=True, path_mode="COPY", embed_textures=True,
    )
    if "FINISHED" not in result:
        raise RuntimeError("Starpop FBX export failed")
    # The Blender FBX exporter leaves the last sampled shape weights active.
    for shape in model.data.shape_keys.key_blocks:
        shape.value = 0.
    for track in tracks:
        track.mute = True
    rig.animation_data.use_nla = False
    return tracks


def dynamics_spec(rig, chains):
    springs = {}
    for stem, (_, coords) in chains.items():
        for i, tail in enumerate(coords[1:], 1):
            node = f"{stem}.{i:02}"
            if stem.startswith("Hair"):
                stiffness, damping, gravity, radius = (34., 6.6, 1.4, .018) if i == 1 else (25., 5.7, 1.45, .018)
            elif stem.startswith("Ribbon"):
                stiffness, damping, gravity, radius = (33., 7., 1.05, .014)
            else:
                stiffness, damping, gravity, radius = (80., 10., .40, .016) if i == 1 else (65., 9., .48, .017)
            springs[node] = dict(tail=tail, stiffness=stiffness, damping=damping, gravity=gravity, radius=radius)
    colliders = {}
    for side in ("L", "R"):
        for segment, radius in [("Thigh", .104), ("Shin", .092)]:
            node = f"{segment}.{side}"
            bone = rig.data.bones[node]
            start, end = bone.head_local.lerp(bone.tail_local, .10), bone.head_local.lerp(bone.tail_local, .93)
            colliders[node] = dict(start=list(start), end=list(end), radius=radius)
    colliders["Head"] = dict(start=[0., .035, 1.58], end=[0., .035, 1.58], radius=.191)
    return springs, colliders


def closest_point(point, a, b):
    delta = b - a
    return a if delta.length_squared < 1e-9 else a + delta * clamp((point - a).dot(delta) / delta.length_squared)


def bake_secondary(scene, rig, tracks, springs, colliders):
    # A short walk and turn changes the anchors. Integrate damped springs in
    # armature space, then save a separate authoring demo without double baking the FBX.
    walk = next(track.strips[0] for track in tracks if track.name == "walk")
    action_data = rig.animation_data
    action_data.action = walk.action
    action_data.action_slot = walk.action_slot
    spring_bones = {name: rig.pose.bones[name] for name in springs}
    local_colliders = {}
    for name, collider in colliders.items():
        local_from_rest = rig.data.bones[name].matrix_local.inverted()
        local_colliders[name] = (local_from_rest @ Vector(collider["start"]),
                                 local_from_rest @ Vector(collider["end"]), collider["radius"])
    velocity = {name: Vector((0, 0, 0)) for name in springs}
    previous = {}
    frames = {}
    max_lag = defaultdict(float)
    min_clearance = defaultdict(lambda: math.inf)
    dt = 1. / 120
    for frame in range(1, 97):
        scene.frame_set(1 + (frame - 1) % 30)
        motion = math.sin((frame - 1) * math.tau / 30)
        heading = math.sin((frame - 1) * math.tau / 72)
        rig.pose.bones["Hips"].location.x = .059 * motion
        rig.pose.bones["Head"].rotation_euler.y = .32 * heading
        rig.pose.bones["Head"].rotation_euler.z = .10 * motion
        for name in ("Thigh.L", "Thigh.R", "Shin.L", "Shin.R", "UpperArm.L", "UpperArm.R"):
            rig.pose.bones[name].rotation_euler.x *= .73
        for bone in spring_bones.values():
            bone.rotation_mode = "QUATERNION"
            bone.rotation_quaternion = Quaternion()
        bpy.context.view_layer.update()
        world_colliders = [(rig.pose.bones[name].matrix @ a, rig.pose.bones[name].matrix @ b, radius)
                           for name, (a, b, radius) in local_colliders.items()]
        fixed_anchors = {bone.parent.name: bone.parent.matrix.copy() for bone in spring_bones.values()
                         if bone.parent.name not in springs}
        pose = {}
        solved_parents = fixed_anchors
        for _ in range(4):
            posed = dict(fixed_anchors)
            for name, spec in springs.items():
                bone = spring_bones[name]
                base = posed[bone.parent.name] @ bone.parent.bone.matrix_local.inverted() @ bone.bone.matrix_local
                anchor, expected = base.translation, base @ Vector((0., bone.length, 0.))
                tail = previous.get(name, expected)
                accel = (expected - tail) * spec["stiffness"] + Vector((0, 0, -spec["gravity"]))
                speed = (velocity[name] + accel * dt) * math.exp(-spec["damping"] * dt)
                candidate = tail + speed * dt
                for _ in range(3):
                    for a, b, radius in world_colliders:
                        closest = closest_point(candidate, a, b)
                        delta = candidate - closest
                        if 1e-12 < delta.length_squared < (radius + spec["radius"]) ** 2:
                            candidate = closest + delta.normalized() * (radius + spec["radius"])
                    direction = candidate - anchor
                    if direction.length_squared < 1e-12:
                        direction = expected - anchor
                    candidate = anchor + direction.normalized() * bone.length
                velocity[name] = (candidate - tail) / dt
                previous[name] = candidate
                local = base.to_quaternion().inverted() @ (candidate - anchor).normalized()
                rotation = Vector((0, 1, 0)).rotation_difference(local)
                posed[name] = base @ rotation.to_matrix().to_4x4()
                pose[name] = rotation
                max_lag[name] = max(max_lag[name], (candidate - expected).length)
                for a, b, radius in world_colliders:
                    min_clearance[name] = min(min_clearance[name],
                                     (candidate - closest_point(candidate, a, b)).length - radius - spec["radius"])
            solved_parents = posed
        if any(not math.isfinite(v) for matrix in solved_parents.values() for row in matrix for v in row):
            raise ValueError(f"Non-finite spring pose at frame {frame}")
        for name, rotation in pose.items():
            spring_bones[name].rotation_quaternion = rotation
        frames[frame] = {bone.name: bone.matrix_basis.copy() for bone in rig.pose.bones}
    preview = bpy.data.actions.new("Starpop - walk and spring motion")
    preview.use_fake_user = True
    action_data.action = preview
    for frame, pose in frames.items():
        for name, matrix in pose.items():
            bone = rig.pose.bones[name]
            bone.matrix_basis = matrix
            bone.keyframe_insert(data_path="location", frame=frame, group=name)
            rotation_path = "rotation_quaternion" if name in springs else "rotation_euler"
            bone.keyframe_insert(data_path=rotation_path, frame=frame, group=name)
    for layer in preview.layers:
        for strip in layer.strips:
            bag = strip.channelbag(action_data.action_slot)
            if bag:
                for curve in bag.fcurves:
                    for key in curve.keyframe_points:
                        key.interpolation = "LINEAR"
    scene.frame_start, scene.frame_end = 1, 96
    scene.timeline_markers.new("Secondary motion preview", frame=1)
    scene.frame_set(1)
    return {node: {"maxLagMetres": round(max_lag[node], 6),
                   "minParticleClearanceMetres": round(min_clearance[node], 6)} for node in springs}


def studio(scene):
    collection = bpy.data.collections.new("Studio (preview only)")
    scene.collection.children.link(collection)

    def placed(name, data, position, target):
        obj = bpy.data.objects.new(name, data)
        collection.objects.link(obj)
        obj.location = position
        obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()
        return obj

    backdrop = bpy.data.meshes.new("Backdrop")
    backdrop.from_pydata([(-200, -200, -.014), (200, -200, -.014),
                          (200, 200, -.014), (-200, 200, -.014)], [], [(0, 1, 2, 3)])
    floor = bpy.data.materials.new("Backdrop | lavender")
    floor.use_nodes = True
    floor.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (.158, .119, .205, 1)
    floor.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = .86
    backdrop.materials.append(floor)
    collection.objects.link(bpy.data.objects.new("Ground", backdrop))

    camera = bpy.data.cameras.new("Portrait")
    camera.type, camera.ortho_scale = "ORTHO", 2.164
    scene.camera = placed("Portrait", camera, (2.10, -7, 2.0), (0, 0, .93))
    for name, pos, energy, size, tint in (
        ("Key", (-2.8, -4., 5.), 470, 4.0, (1., .89, .84)),
        ("Fill", (3.1, -4., 2.5), 350, 4.0, (.88, .95, 1.)),
        ("Rim", (.4, 2., 4.1), 400, 3., (1., .78, .86)),
    ):
        light = bpy.data.lights.new(name, "AREA")
        light.energy, light.size, light.color = energy, size, tint
        placed(name, light, pos, (0, 0, 1.))
    if scene.world is None:
        scene.world = bpy.data.worlds.new("Starpop studio")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (.5, .39, .61, 1)
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = .45
    scene.render.engine = "BLENDER_EEVEE"
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.view_settings.exposure = -.7
    scene.render.resolution_x, scene.render.resolution_y = 780, 940
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    return collection


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf8")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    scene = bpy.data.scenes.get("Starpop | RigWorks avatar")
    if scene is None:
        scene = bpy.data.scenes.new("Starpop | RigWorks avatar")
    bpy.context.window.scene = scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.scale_length = 1
    scene.render.fps = 30
    for obj in list(scene.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    collection = bpy.data.collections.new("Starpop | avatar")
    scene.collection.children.link(collection)
    chains = secondary_chains()
    rig = build_rig(collection, chains)
    surface = Surface()
    for part in (build_head, build_hair, build_clothes, build_limbs, build_skirt):
        part(surface)
    model = surface.object(collection, rig, make_material())
    minimum = [min(v[axis] for v in surface.vertices) for axis in range(3)]
    maximum = [max(v[axis] for v in surface.vertices) for axis in range(3)]
    if minimum[2] != 0 or abs(maximum[2] - 1.85) > 1e-6:
        raise ValueError(f"Expected neutral 1.85m above floor: {minimum}, {maximum}")

    tracks = bake_export(scene, rig, model)
    springs, colliders = dynamics_spec(rig, chains)
    spring_demo = bake_secondary(scene, rig, tracks, springs, colliders)
    manifest = {
        "version": 1, "name": "Starpop", "model": "starpop.fbx",
        "sha256": hashlib.sha256((OUT / "starpop.fbx").read_bytes()).hexdigest(),
        "sourceRig": "model-tests/block_player/block_player.blend", "height": 1.85,
        "front": [0, -1, 0], "up": [0, 0, 1],
        "meshCount": 1, "materials": 1, "vertices": len(surface.vertices), "triangles": 2 * len(surface.faces),
        "maximumInfluences": max(map(len, surface.weights)), "morphs": ["Blink", "Smile"],
        "bounds": {"min": minimum, "max": maximum}, "clips": [track.name for track in tracks],
        "bones": {b.name: {"head": list(b.head_local), "tail": list(b.tail_local),
                            "parent": b.parent.name if b.parent else None}
                  for b in rig.data.bones},
        "springInputs": springs, "colliderInputs": colliders, "blenderSpringPreview": spring_demo,
    }
    save_json(OUT / "authoring.json", manifest)
    print("STARPOP", manifest["meshCount"], manifest["vertices"], manifest["triangles"], len(manifest["bones"]),
          len(tracks), len(springs), manifest["sha256"])
    stage = studio(scene)
    for obj in stage.objects:
        obj.hide_set(True)
    rig.select_set(False)
    model.select_set(True)
    bpy.context.view_layer.objects.active = model
    for screen in bpy.data.screens:
        for area in screen.areas:
            if area.type == "VIEW_3D":
                space = area.spaces.active
                space.shading.type = "MATERIAL"
                space.overlay.show_overlays = False
                space.region_3d.view_location = (0, 0, .93)
                space.region_3d.view_rotation = scene.camera.rotation_euler.to_quaternion()
                space.region_3d.view_distance = 3.5
                space.region_3d.view_perspective = "ORTHO"
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "starpop.blend"))
    if "--render" in sys.argv:
        scene.render.filepath = str(OUT / "starpop-preview.png")
        bpy.ops.render.render(write_still=True)
        scene.frame_set(22)
        scene.camera.location = (-3.8, 6.6, 2.0)
        scene.camera.rotation_euler = (Vector((0, 0, .93)) - scene.camera.location).to_track_quat("-Z", "Y").to_euler()
        scene.render.filepath = str(OUT / "starpop-back.png")
        bpy.ops.render.render(write_still=True)


if __name__ == "__main__":
    main()
