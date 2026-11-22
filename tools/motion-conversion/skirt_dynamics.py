"""Small authoring-time skirt cage with measured mesh/capsule diagnostics.

The six existing two-link chains drive the original skin. This is not a cloth
surface solver: residual penetration is measured and returned to the caller.
"""

import math

import bpy
import numpy as np
from mathutils import Matrix, Quaternion, Vector


def _clamp(value, lower=0.0, upper=1.0):
    return min(upper, max(lower, value))


def _closest(point, a, b):
    segment = b - a
    along = _clamp((point - a).dot(segment) / max(segment.length_squared, 1e-15))
    return a + segment * along


def _segment_pair(a, b, c, d):
    u, v, w = b - a, d - c, a - c
    aa, bb, cc, dd, ee = u.dot(u), u.dot(v), v.dot(v), u.dot(w), v.dot(w)
    denominator = aa * cc - bb * bb
    s = _clamp((bb * ee - cc * dd) / denominator) if denominator > 1e-15 else 0
    t = _clamp((bb * s + ee) / max(cc, 1e-15))
    s = _clamp((bb * t - dd) / max(aa, 1e-15))
    t = _clamp((bb * s + ee) / max(cc, 1e-15))
    return s, a + u * s, c + v * t


class SkirtDynamics:
    def __init__(self, target, world, set_world, fps):
        self.target, self.rig = target, target["rig"]
        self.world, self.set_world = world, set_world
        self.config = target["spec"].get("skirtPhysics", {})
        self.roles = target["spec"]["roles"]
        self.fps = float(fps)
        self.substeps = int(_clamp(self.config.get("substeps", 4), 1, 16))
        self.step = 1 / (self.fps * self.substeps)
        self.iterations = int(_clamp(self.config.get("iterations", 5), 1, 16))
        self.radius = float(self.config.get("particleRadius", .02))
        self.stiffness = float(self.config.get("spring", 280))
        self.damping = float(self.config.get("damping", .08))
        self.gravity = Vector((0, 0, -float(self.config.get("gravity", 9.81))))
        if min(self.radius, self.stiffness, self.damping) < 0 or self.stiffness * self.step * self.step > 1:
            raise ValueError("Invalid skirt spring parameters")
        self.chains = []
        self.hip = self.roles["hips"]
        self.hip_reference = target["reference"][self.hip]
        self.hip_inverse = self.hip_reference.inverted()
        for root in self.rig.pose.bones:
            if not root.name.startswith("Skirt_") or "_01_" not in root.name:
                continue
            children = list(root.children)
            if len(children) != 1 or len(children[0].children) != 1:
                raise ValueError("Expected a skirt 01 -> 02 -> End chain: " + root.name)
            names = [root.name, children[0].name, children[0].children[0].name]
            points = [target["reference"][name].translation.copy() for name in names]
            angle = math.atan2(points[0].y - self.hip_reference.translation.y,
                               points[0].x - self.hip_reference.translation.x)
            self.chains.append({"names": names, "reference": points, "angle": angle,
                                "lengths": [(points[1] - points[0]).length, (points[2] - points[1]).length]})
        self.chains.sort(key=lambda chain: chain["angle"])
        if len(self.chains) != 6:
            raise ValueError("Expected six skirt chains; got " + str(len(self.chains)))
        self.dynamic_bones = [name for chain in self.chains for name in chain["names"][:2]]
        self.bone_indices = {name: index for index, name in enumerate(self.dynamic_bones)}
        self.skin_repair = self._repair_skin()
        self.meshes = self._mesh_cache()
        self.reset()

    def _world(self, name):
        return self.world(self.rig, name)

    def _set_world(self, name, matrix):
        self.set_world(self.rig, name, matrix)

    def _repair_skin(self):
        report = {"enabled": bool(self.config.get("repairHipWeights", True)), "vertices": 0,
                  "maximumTransferredHipWeight": 0.0}
        if not report["enabled"]:
            return report
        # The source skin leaves up to 76% of moving front panels on Hip. A
        # fixed Hip contribution cannot be displaced by the skirt solver.
        for obj in self.target["meshes"]:
            if not obj.get("rw_vmd_generated"):
                raise ValueError("Skirt skin repair requires a generated mesh copy: " + obj.name)
            names = {group.index: group.name for group in obj.vertex_groups}
            updates = []
            for vertex in obj.data.vertices:
                bindings = [(names[group.group], group.weight) for group in vertex.groups]
                skirt = [(name, weight) for name, weight in bindings if name in self.bone_indices]
                total = sum(weight for name, weight in skirt)
                hip = sum(weight for name, weight in bindings if name == self.hip)
                if total > .05 and hip > 0:
                    updates.append((vertex.index, skirt, total, hip))
            for index, skirt, total, hip in updates:
                obj.vertex_groups[self.hip].add([index], 0, "REPLACE")
                for name, weight in skirt:
                    obj.vertex_groups[name].add([index], weight * (1 + hip / total), "REPLACE")
                report["vertices"] += 1
                report["maximumTransferredHipWeight"] = max(report["maximumTransferredHipWeight"], hip)
            obj.data.update()
        bpy.context.view_layer.update()
        return report

    def _mesh_cache(self):
        result = []
        for obj in self.target["meshes"]:
            group_names = {group.index: group.name for group in obj.vertex_groups}
            indices, weights = [], []
            for vertex in obj.data.vertices:
                row = np.zeros(len(self.dynamic_bones), dtype=np.float64)
                total = 0.0
                for binding in vertex.groups:
                    name = group_names.get(binding.group)
                    if name in self.rig.data.bones and self.rig.data.bones[name].use_deform:
                        total += binding.weight
                    index = self.bone_indices.get(name)
                    if index is not None:
                        row[index] += binding.weight
                if row.sum() > .05:
                    indices.append(vertex.index)
                    weights.append(row / max(total, 1e-9))
            if indices:
                indices, weights = np.array(indices, dtype=int), np.array(weights)
                coordinates = np.array([list(vertex.co) + [1] for vertex in obj.data.vertices])[indices]
                mesh_to_rig = self.rig.matrix_world.inverted() @ obj.matrix_world
                bound = [coordinates @ np.array(self.rig.data.bones[name].matrix_local.inverted() @ mesh_to_rig).T
                         for name in self.dynamic_bones]
                reference_points = np.zeros((len(indices), 3))
                total_weight = np.zeros(len(indices))
                for index, vertex_index in enumerate(indices):
                    vertex = obj.data.vertices[int(vertex_index)]
                    for binding in vertex.groups:
                        name = group_names.get(binding.group)
                        if name not in self.rig.data.bones or not self.rig.data.bones[name].use_deform:
                            continue
                        transform = self.target["reference"][name] @ self.rig.data.bones[name].matrix_local.inverted() @ mesh_to_rig
                        reference_points[index] += np.array(transform @ vertex.co) * binding.weight
                        total_weight[index] += binding.weight
                reference_points /= np.maximum(total_weight[:, None], 1e-9)
                normals = []
                for side in ("left", "right"):
                    for first, second in (("_thigh", "_shin"), ("_shin", "_foot")):
                        a, b = [np.array(self.target["reference"][self.roles[side + suffix]].translation)
                                for suffix in (first, second)]
                        segment = b - a
                        along = np.clip((reference_points - a) @ segment / max(segment @ segment, 1e-15), 0, 1)
                        offset = reference_points - a - along[:, None] * segment
                        normals.append(offset / np.maximum(np.linalg.norm(offset, axis=1)[:, None], 1e-9))
                result.append((obj, indices, weights, bound, np.stack(normals, axis=1)))
        if not result:
            raise ValueError("No skirt-weighted mesh vertices")
        return result

    def reset(self):
        self.positions = None
        self.velocities = None
        self.previous_roots = None
        self.accumulator = 0.0
        self.stats = {"updates": 0, "substeps": 0, "boneCorrectionPasses": 0,
                      "maximumInitialVertexPenetration": 0.0, "maximumFinalVertexPenetration": 0.0,
                      "maximumFinalChainPenetration": 0.0, "vertexSamplesOver5mm": 0,
                      "maximumBaseFollowDegrees": 0.0, "maximumBaseAnchorLift": 0.0,
                      "maximumRootCorrectionOffset": 0.0, "maximumBoneCorrectionDegrees": 0.0,
                      "contactCorrectionRejections": 0, "maximumActiveConstraints": 0,
                      "coldStartContactRetries": 0,
                      "worstVertex": None}

    def _capsules(self):
        capsules = []
        for side in ("left", "right"):
            points = [self._world(self.roles[side + suffix]).translation for suffix in ("_thigh", "_shin", "_foot")]
            capsules.extend([(side + "_thigh", points[0], points[1], float(self.config.get("thighRadius", .095))),
                             (side + "_shin", points[1], points[2], float(self.config.get("shinRadius", .09)))])
        return capsules

    def _goals(self):
        hip = self._world(self.hip)
        delta = hip @ self.hip_inverse
        thigh_deltas = {}
        for side in ("left", "right"):
            thigh, shin = self.roles[side + "_thigh"], self.roles[side + "_shin"]
            neutral = delta.to_3x3() @ (self.target["reference"][shin].translation - self.target["reference"][thigh].translation)
            actual = self._world(shin).translation - self._world(thigh).translation
            thigh_deltas[side] = neutral.rotation_difference(actual)
        goals = []
        capsules = self._capsules()
        for chain in self.chains:
            points = [self._world(name).translation.copy() for name in chain["names"]]
            root = points[0].copy()
            nearest = min(("left", "right"), key=lambda side:
                          (self.target["reference"][self.roles[side + "_thigh"]].translation - chain["reference"][0]).length_squared)
            other = "right" if nearest == "left" else "left"
            follow = thigh_deltas[nearest].slerp(thigh_deltas[other], .25)
            angle = abs(follow.angle)
            is_front, is_side = "Skirt_F_" in chain["names"][0], "Skirt_S_" in chain["names"][0]
            if is_front and angle > math.radians(30):
                up = (delta.to_3x3() @ Vector((0, 0, 1))).normalized()
                lift = 0.0
                limit = float(self.config.get("baseAnchorLiftLimit", .15))
                for step in range(21):
                    lift = limit * step / 20
                    candidate = root + up * lift
                    if all((candidate - _closest(candidate, a, b)).length >= radius + self.radius
                           for label, a, b, radius in capsules):
                        break
                lift += float(self.config.get("crouchAnchorClearance", .035)) * _clamp(
                    (angle - math.radians(30)) / math.radians(45))
                lift = min(limit, lift)
                root += up * lift
                self.stats["maximumBaseAnchorLift"] = max(self.stats["maximumBaseAnchorLift"], lift)
            amount = (.85 if is_front else .30 if is_side else .12) * _clamp((angle - math.radians(15)) / math.radians(60))
            amount *= float(self.config.get("thighFollow", 1.0))
            follow = Quaternion().slerp(follow, _clamp(amount))
            self.stats["maximumBaseFollowDegrees"] = max(self.stats["maximumBaseFollowDegrees"], math.degrees(follow.angle))
            goals.append([root, root + follow @ (points[1] - points[0]), root + follow @ (points[2] - points[0])])
        return goals

    def _project(self, capsules, goals):
        positions = self.positions
        for index, chain in enumerate(self.chains):
            points = positions[index]
            points[0] = goals[index][0].copy()
            for link in (0, 1):
                a, b = points[link], points[link + 1]
                direction = b - a
                if direction.length < 1e-8:
                    direction = goals[index][link + 1] - goals[index][link]
                error = direction * (1 - chain["lengths"][link] / max(direction.length, 1e-8))
                if link == 0:
                    points[1] -= error
                else:
                    points[1] += error * .5
                    points[2] -= error * .5
        for ring in (1, 2):
            for index in range(6):
                other = (index + 1) % 6
                direction = positions[other][ring] - positions[index][ring]
                desired = (goals[other][ring] - goals[index][ring]).length
                if direction.length > 1e-8:
                    error = direction * ((direction.length - desired) / direction.length) * .35
                    positions[index][ring] += error
                    positions[other][ring] -= error
        for index, chain in enumerate(self.chains):
            points = positions[index]
            for link in (0, 1):
                for label, a, b, radius in capsules:
                    along, point, closest = _segment_pair(points[link], points[link + 1], a, b)
                    normal = point - closest
                    required = radius + self.radius
                    if normal.length >= required:
                        continue
                    if normal.length < 1e-8:
                        normal = goals[index][link + 1] - closest
                    if normal.length < 1e-8:
                        normal = Vector((1, 0, 0))
                    correction = normal.normalized() * min(.04, required - (point - closest).length)
                    first_weight, second_weight = (0 if link == 0 else 1 - along), along
                    denominator = first_weight * first_weight + second_weight * second_weight
                    if denominator > .0025:
                        points[link] += correction * (first_weight / denominator)
                        points[link + 1] += correction * (second_weight / denominator)

    def _apply_cage(self):
        for index, chain in enumerate(self.chains):
            points = self.positions[index]
            root = self._world(chain["names"][0])
            root.translation = points[0]
            self._set_world(chain["names"][0], root)
            for link in (0, 1):
                name, child = chain["names"][link:link + 2]
                current = self._world(name)
                origin = current.translation.copy()
                before = self._world(child).translation - origin
                after = points[link + 1] - origin
                if before.length > 1e-8 and after.length > 1e-8:
                    matrix = before.rotation_difference(after).to_matrix().to_4x4() @ current
                    matrix.translation = origin
                    self._set_world(name, matrix)

    def _vertices(self, capsules):
        blocks, deepest, over5 = [], None, 0
        graph = bpy.context.evaluated_depsgraph_get()
        for obj, indices, weights, bound, reference_normals in self.meshes:
            evaluated = obj.evaluated_get(graph)
            mesh = evaluated.to_mesh()
            try:
                if len(mesh.vertices) != len(obj.data.vertices):
                    raise ValueError("Skirt diagnostics require stable mesh vertex order")
                coordinates = np.empty(len(mesh.vertices) * 3, dtype=np.float64)
                mesh.vertices.foreach_get("co", coordinates)
                coordinates = coordinates.reshape(-1, 3)[indices]
                matrix = np.array(evaluated.matrix_world)
                points = coordinates @ matrix[:3, :3].T + matrix[:3, 3]
            finally:
                evaluated.to_mesh_clear()
            if not np.isfinite(points).all():
                raise ValueError("Non-finite skirt skin")
            distances, normals = [], []
            for label, a, b, radius in capsules:
                start, end = np.array(a), np.array(b)
                segment = end - start
                along = np.clip((points - start) @ segment / max(segment @ segment, 1e-15), 0, 1)
                offset = points - (start + along[:, None] * segment)
                length = np.linalg.norm(offset, axis=1)
                distances.append(length - radius)
                normals.append(offset / np.maximum(length[:, None], 1e-9))
            distances = np.stack(distances, axis=1)
            selected = np.argmin(distances, axis=1)
            signed = distances[np.arange(len(points)), selected]
            all_normals = np.stack(normals, axis=1)
            normal = all_normals[np.arange(len(points)), selected]
            levers = np.stack([(local @ np.array(self._world(name)).T)[:, :3] * weights[:, index, None]
                               for index, (name, local) in enumerate(zip(self.dynamic_bones, bound))], axis=1)
            effective = weights.copy()
            for chain in self.chains:
                root, child = [self.bone_indices[name] for name in chain["names"][:2]]
                levers[:, root] += levers[:, child]
                effective[:, root] += effective[:, child]
            origins = np.array([self._world(name).translation for name in self.dynamic_bones])
            levers -= effective[:, :, None] * origins[None, :, :]
            sided_normals = []
            for capsule, (label, a, b, radius) in enumerate(capsules):
                name = self.roles[label]
                delta = self._world(name).to_quaternion() @ self.target["reference"][name].to_quaternion().inverted()
                expected = reference_normals[:, capsule] @ np.array(delta.to_matrix()).T
                axis = np.array((b - a).normalized())
                expected -= (expected @ axis)[:, None] * axis[None, :]
                expected /= np.maximum(np.linalg.norm(expected, axis=1)[:, None], 1e-9)
                sided_normals.append(expected)
            blocks.append((points, effective, signed, normal, levers, distances, all_normals,
                           np.stack(sided_normals, axis=1)))
            index = int(np.argmin(signed))
            over5 += int((signed < -.005).sum())
            if deepest is None or signed[index] < deepest["signedDistance"]:
                deepest = {"mesh": obj.name, "vertex": int(indices[index]), "signedDistance": float(signed[index]),
                           "capsule": capsules[int(selected[index])][0], "position": points[index].tolist(),
                           "frame": int(bpy.context.scene.frame_current)}
        return blocks, deepest, over5

    def _bone_correction(self, capsules, blocks, worst):
        margin = float(self.config.get("meshClearance", .005))
        passes = int(_clamp(self.config.get("correctionPasses", 12), 0, 16))
        roots = list(range(0, len(self.dynamic_bones), 2))
        anchors = {index: self._world(self.dynamic_bones[index]).translation.copy() for index in roots}
        root_limit = float(self.config.get("rootClearanceLimit", .065))
        translation_scale = .12
        initial_rotations = {name: self._world(name).to_quaternion() for name in self.dynamic_bones}
        # A front panel already caught below a bent thigh has a downward SDF
        # normal. Preserve its neutral capsule side while untangling it.
        sided = [(block[5] < -.01) & (np.sum(block[6] * block[7], axis=2) < .5) for block in blocks]

        def constraints(block, mask):
            signed, actual, expected = block[5:8]
            radii = np.array([capsule[3] for capsule in capsules])
            offset = actual * (signed + radii[None, :])[:, :, None]
            plane = np.sum(offset * expected, axis=2) - radii[None, :]
            return np.maximum(0, margin - np.where(mask, plane, signed)), np.where(mask[:, :, None], expected, actual)

        for _ in range(passes):
            if worst["signedDistance"] >= -float(self.config.get("penetrationTolerance", .002)):
                break
            jacobians, errors = [], []
            projections = []
            for index in roots:
                offset = self._world(self.dynamic_bones[index]).translation - anchors[index]
                projection = np.eye(3)
                if offset.length > root_limit * .95:
                    axis = np.array(offset.normalized())
                    projection -= np.outer(axis, axis)
                projections.append(projection)
            projections = np.array(projections)
            for block, mask in zip(blocks, sided):
                points, weights, _, _, levers = block[:5]
                errors_for_capsules, normals = constraints(block, mask)
                active = errors_for_capsules > 0
                depth = errors_for_capsules[active]
                scale = np.sqrt(1 + 4 * (depth / max(margin - worst["signedDistance"], 1e-9)) ** 2)
                vertex, capsule = np.nonzero(active)
                direction = normals[vertex, capsule]
                jacobian = np.cross(levers[vertex], direction[:, None, :]).reshape(-1, len(self.dynamic_bones) * 3)
                projected = np.einsum("nd,rdk->nrk", direction, projections)
                translations = weights[vertex][:, roots, None] * projected * translation_scale
                jacobian = np.concatenate((jacobian, translations.reshape(-1, len(roots) * 3)), axis=1)
                jacobians.append(jacobian * scale[:, None])
                errors.append(depth * scale)
            jacobian, error = np.concatenate(jacobians), np.concatenate(errors)
            self.stats["maximumActiveConstraints"] = max(self.stats["maximumActiveConstraints"], len(error))
            system = jacobian.T @ jacobian
            damping = max(1e-6, float(np.trace(system)) / len(system) * .003)
            system.flat[::len(system) + 1] += damping
            solution = np.linalg.solve(system, jacobian.T @ error)
            torques = solution[:len(self.dynamic_bones) * 3].reshape(-1, 3)
            translations = solution[len(self.dynamic_bones) * 3:].reshape(-1, 3) * translation_scale
            translations = np.einsum("rij,rj->ri", projections, translations)
            torques *= float(self.config.get("correctionGain", 1.0))
            length = np.linalg.norm(torques, axis=1)
            gain = min(1, math.radians(16) / max(float(length.max()), 1e-9))
            torques *= gain
            translations *= gain
            saved = {name: self._world(name).copy() for name in self.dynamic_bones}
            previous_score = sum(float(np.sum(constraints(block, mask)[0] ** 2)) for block, mask in zip(blocks, sided))
            accepted = False
            for scale in (1.0, .5, .25):
                for name, torque in zip(self.dynamic_bones, torques):
                    # Recreate the same parent-relative candidate on every line search.
                    self._set_world(name, saved[name])
                for index, translation in zip(roots, translations):
                    name = self.dynamic_bones[index]
                    matrix = self._world(name)
                    offset = matrix.translation + Vector(translation * scale) - anchors[index]
                    if offset.length > root_limit:
                        offset *= root_limit / offset.length
                    matrix.translation = anchors[index] + offset
                    self._set_world(name, matrix)
                for name, torque in zip(self.dynamic_bones, torques):
                    rotation_vector = Vector(torque * scale)
                    if rotation_vector.length < 1e-8:
                        continue
                    matrix = self._world(name)
                    origin = matrix.translation.copy()
                    rotation = Quaternion(rotation_vector.normalized(), rotation_vector.length)
                    matrix = rotation.to_matrix().to_4x4() @ matrix
                    matrix.translation = origin
                    self._set_world(name, matrix)
                candidate, next_worst, _ = self._vertices(capsules)
                next_score = sum(float(np.sum(constraints(block, mask)[0] ** 2)) for block, mask in zip(candidate, sided))
                if next_score < previous_score:
                    accepted = True
                    break
            if not accepted:
                for name in self.dynamic_bones:
                    self._set_world(name, saved[name])
                self.stats["contactCorrectionRejections"] += 1
                break
            blocks, worst = candidate, next_worst
            self.stats["boneCorrectionPasses"] += 1
        for index in roots:
            self.stats["maximumRootCorrectionOffset"] = max(self.stats["maximumRootCorrectionOffset"],
                (self._world(self.dynamic_bones[index]).translation - anchors[index]).length)
        for name in self.dynamic_bones:
            rotation = initial_rotations[name].rotation_difference(self._world(name).to_quaternion())
            angle = min(rotation.angle, 2 * math.pi - rotation.angle)
            self.stats["maximumBoneCorrectionDegrees"] = max(self.stats["maximumBoneCorrectionDegrees"],
                                                               math.degrees(angle))
        return blocks, worst

    def resolve_contacts(self):
        """Correct the current pose without integrating time, for loop closure."""
        capsules = self._capsules()
        blocks, worst, _ = self._vertices(capsules)
        before = max(0, -worst["signedDistance"])
        blocks, worst = self._bone_correction(capsules, blocks, worst)
        if self.positions is not None:
            for index, chain in enumerate(self.chains):
                self.positions[index] = [self._world(name).translation.copy() for name in chain["names"]]
        return {"maximumVertexPenetration": max(0, -worst["signedDistance"]),
                "initialVertexPenetration": before, "worst": worst}

    def update(self, dt):
        if not math.isfinite(dt) or dt < 0:
            raise ValueError("Invalid skirt delta")
        baseline = {name: self._world(name).copy() for name in self.dynamic_bones}
        goals, capsules = self._goals(), self._capsules()
        roots = [points[0] for points in goals]
        if self.positions is None or any((a - b).length > .75 for a, b in zip(roots, self.previous_roots)):
            self.positions = [[point.copy() for point in points] for points in goals]
            self.velocities = [[Vector((0, 0, 0)) for point in points] for points in goals]
            for _ in range(self.iterations * 2):
                self._project(capsules, goals)
        self.previous_roots = [point.copy() for point in roots]
        self.accumulator = min(self.accumulator + dt, self.step * self.substeps)
        steps = min(self.substeps, int((self.accumulator + 1e-10) / self.step))
        for _ in range(steps):
            previous = [[point.copy() for point in points] for points in self.positions]
            for chain in range(6):
                for ring in (1, 2):
                    acceleration = (goals[chain][ring] - self.positions[chain][ring]) * self.stiffness + self.gravity
                    self.velocities[chain][ring] = (self.velocities[chain][ring] + acceleration * self.step) * math.exp(-self.damping)
                    self.positions[chain][ring] += self.velocities[chain][ring] * self.step
            for _ in range(self.iterations):
                self._project(capsules, goals)
            for chain in range(6):
                for ring in (1, 2):
                    self.velocities[chain][ring] = (self.positions[chain][ring] - previous[chain][ring]) / self.step
            self.accumulator -= self.step
        self._apply_cage()
        blocks, worst, over5 = self._vertices(capsules)
        self.stats["maximumInitialVertexPenetration"] = max(self.stats["maximumInitialVertexPenetration"], -worst["signedDistance"])
        blocks, worst = self._bone_correction(capsules, blocks, worst)
        tolerance = float(self.config.get("penetrationTolerance", .002))
        if worst["signedDistance"] < -tolerance and self.config.get("coldStartContactRetry", True):
            saved = {name: self._world(name).copy() for name in self.dynamic_bones}
            particles = self.positions
            velocities = self.velocities
            for name in self.dynamic_bones:
                self._set_world(name, baseline[name])
            self.positions = [[point.copy() for point in points] for points in goals]
            self.velocities = [[Vector((0, 0, 0)) for point in points] for points in goals]
            for _ in range(self.iterations * 2):
                self._project(capsules, goals)
            self._apply_cage()
            candidate, next_worst, _ = self._vertices(capsules)
            candidate, next_worst = self._bone_correction(capsules, candidate, next_worst)
            self.stats["coldStartContactRetries"] += 1
            if next_worst["signedDistance"] > worst["signedDistance"]:
                blocks, worst = candidate, next_worst
            else:
                for name in self.dynamic_bones:
                    self._set_world(name, saved[name])
                self.positions, self.velocities = particles, velocities
        final_depth = max(0, -worst["signedDistance"])
        self.stats["maximumFinalVertexPenetration"] = max(self.stats["maximumFinalVertexPenetration"], final_depth)
        self.stats["vertexSamplesOver5mm"] += sum(int((block[2] < -.005).sum()) for block in blocks)
        if self.stats["worstVertex"] is None or worst["signedDistance"] < self.stats["worstVertex"]["signedDistance"]:
            self.stats["worstVertex"] = worst
        for index, chain in enumerate(self.chains):
            self.positions[index] = [self._world(name).translation.copy() for name in chain["names"]]
            for link in (0, 1):
                for label, a, b, radius in capsules:
                    along, point, closest = _segment_pair(self.positions[index][link], self.positions[index][link + 1], a, b)
                    self.stats["maximumFinalChainPenetration"] = max(self.stats["maximumFinalChainPenetration"],
                                                                   max(0, radius + self.radius - (point - closest).length))
        self.stats["updates"] += 1
        self.stats["substeps"] += steps
        return {"maximumVertexPenetration": final_depth, "worst": worst}

    def summary(self):
        return {"method": "six_chain_spring_cage_and_capsules", "scope": "authoring_bake_only",
                "dynamicBones": list(self.dynamic_bones), "skirtVertices": sum(len(item[1]) for item in self.meshes),
                "fixedStep": self.step, "gravity": list(self.gravity), "skinRepair": self.skin_repair, **self.stats}
