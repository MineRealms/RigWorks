# Default Model Pack Authoring

Build both repositories' `1.21.1-neoforge` classes first, then run:

```powershell
./model-tests/scripts/Invoke-DefaultModelPack.ps1
```

Pass `-AssimpLoaderDir`, `-DownloadDirectory`, `-MarisaDirectory`,
`-MisakaDirectory`, `-JavaHome` or `-OutputDirectory` to change local inputs.
The default locations identify the existing locally authored avatar sources;
they are not downloaded or committed by this tool.

`PackageReleaseAvatar.java` stages the selected FBX and authored definitions,
uses the real Workbench export/import service, checks model and asset hashes,
humanoid/clip references, texture dependencies and isolated scripts, and writes
an adjacent offline validation JSON. Existing pixel packages retain their
visual and behavior metadata. The authoring jobs are generated as UTF-8 JSON.

MMD source ZIPs contain original model/texture bytes and a versioned
`rigworks.mmd-source` manifest. The release tool calls the real source archive
reader to validate extraction and checksums, **without running conversion**.
Use `-SourcesOnly` or `-AvatarsOnly` for the corresponding stage.

`build/default-model-pack/release/` contains the public collection, individual
ZIPs, README and SHA-256 list. Validation reports and working directories are
not release payloads. The private PMD is written under `local-only/`: its
source README prohibits redistribution. Never publish that directory or use
a wildcard upload against the build directory.

The collection ZIP is a download convenience. Extract it before selecting an
individual `.rig.zip` in **Import avatar**, or `.mmd.zip` in **Import MMD**.
The release checks are offline checks and do not establish live gameplay
acceptance. The consumer-facing catalogue and credits live in
[`docs/model-packs/README.md`](../../docs/model-packs/README.md).
