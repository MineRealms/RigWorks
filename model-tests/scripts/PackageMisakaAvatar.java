package cn.minerealms.rigworks.character.client;

import java.nio.file.Path;

/** Official Workbench archive with calibrated avatar data and no automatic script takeover. */
public final class PackageMisakaAvatar {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PackageMisakaAvatar <AssetDir> <Output.rig.zip>");
        PackageStarpopAvatar.packageAvatar(Path.of(args[0]), Path.of(args[1]), "misaka", null);
    }
}
