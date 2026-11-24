# Misaka Avatar Templates

These sidecars describe the zero-clip `DEFAULT` avatar. Generate the model with
`model-tests/scripts/build_misaka.py`, then run
`model-tests/scripts/Invoke-MisakaPackage.ps1` to calibrate the imported FBX and
produce `build/misaka/misaka.rig.zip`.

The generated `rig.json` adds explicit zero weights for the imported shape-key
names. The generated instance sidecar contains spring tails and body capsules
in their actual imported bind-local coordinates. Neither file should be
replaced with uncalibrated Blender coordinates.

See [Misaka Pixel Avatar](../../docs/guides/MISAKA_PIXEL_AVATAR.md) for the
authoring contract, import steps and validation scope.
