# Avatar validation tool

`InspectAvatar.java` is a read-only offline inspection helper. It loads one FBX/GLB
with the current AssimpLoader classes and writes a JSON report. It does not launch
Minecraft, run KubeJS, or decide whether a player action is visually correct.
An optional `-Profile <profile.json>` adds a pure-Java DEFAULT policy check on
12 movement and item actions. It records finite transforms, IK residuals and
final skinned bounds. It does not install native pose overlays, rig/morph defaults,
equipment or physics; these still need the runtime validation workflow.

The PowerShell launcher compiles against the selected RigWorks and AssimpLoader
node classes, filters native jars to the current Windows architecture, and adds
the NeoForge universal/merged jars only for the NeoForge node. This avoids mixing
the two Minecraft classpaths.

```powershell
.\Invoke-AvatarValidation.ps1 `
  -Model C:\work\avatar.fbx `
  -GameNode 1.20.1-forge `
  -Joint Head `
  -Output C:\work\report.json `
  -JavaHome $env:JAVA17_HOME
```

`Joint` is a node name used for sampled world position and rotation. An unknown
name is reported as `sample_node_known=false`; it is not silently treated as a
valid humanoid role. `result=inspected` means the file was inspected. The
`semantic_status` field deliberately remains unknown until a profile, runtime
policy and live client acceptance are checked.

Bounds include both skinned geometry and unskinned Morph targets. The report's
raw default weights and zero-weight probe are separate from runtime rig defaults;
nonzero FBX defaults require an explicit sidecar policy review.
