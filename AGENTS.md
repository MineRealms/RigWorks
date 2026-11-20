# RigWorks Agent Entry

When authoring or importing a RigWorks avatar, read
[`docs/guides/AVATAR_VALIDATION_WORKFLOW.md`](docs/guides/AVATAR_VALIDATION_WORKFLOW.md)
before changing model, profile, rig, or KubeJS files.

The workflow separates three kinds of evidence:

1. Blender/DCC authoring evidence: rig axes, IK target error, baked clips,
   neutral bounds, and visual inspection.
2. Offline engine evidence: real AssimpLoader and RigWorks classes loading the
   exported FBX, morph defaults, clip selection, native compatibility, hashes,
   and package round-trip integrity.
3. Client evidence: a fresh client, the current standalone AssimpLoader build,
   the imported profile, real input, texture settling, equipment, F5/head
   pitch, terrain placement, and shader/render output.

Do not call an avatar gameplay-ready from a compile, a ZIP export, or an
offline probe alone. Mark every report item as tested, unverified, or failed.
For model-specific work, use the standalone AssimpLoader path explicitly when
it is newer than the pinned submodule:

```text
-PassimpLoaderDir=H:/MinecraftMods/AssimpLoader
```

Keep user changes in the worktree. Commit code and documentation separately
from large generated FBX/ZIP artifacts unless the repository explicitly tracks
those binaries.
