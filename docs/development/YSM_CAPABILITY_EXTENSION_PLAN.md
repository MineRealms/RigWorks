# YSM Capability Extension — Validation

The capability SPI and its wire-up are described in [YSM_INTERFACE_CONTRACT.md](YSM_INTERFACE_CONTRACT.md).
This page records the headless probes used to validate them without driving the GUI.

## Pure-Java signature probe (no Minecraft runtime)

`YsmFormatProbe` (test source set) reads a `.ysm` file and asks `YsmApi.bridge()` what it detects.
It reports `bridgeAvailable`, `isYsm`, the leading bytes, and either a successful `toGlb` or the
`UnsupportedOperationException` raised while the AssimpLoader YSM API is still unmerged.

```
javac -d build/ysm-probe -cp "H:\MinecraftMods\AssimpLoader-ysm-import\versions\1.21.1-neoforge\build\classes\java\main" src/main/java/cn/minerealms/rigworks/character/importing/YsmApi.java src/main/java/cn/minerealms/rigworks/character/importing/ReflectiveBridge.java src/test/java/cn/minerealms/rigworks/character/importing/YsmFormatProbe.java
java -cp "build/ysm-probe;H:\MinecraftMods\AssimpLoader-ysm-import\versions\1.21.1-neoforge\build\classes\java\main" cn.minerealms.rigworks.character.importing.YsmFormatProbe "H:\MinecraftMods\OpenYSM\forge\run\config\yes_steve_model\custom\Flandre_Scarlet.ysm"
```

The same logic runs as a JUnit probe:

```
.\gradlew.bat :1.21.1-neoforge:test --tests cn.minerealms.rigworks.character.importing.YsmFormatProbeTest -PclientYsmAsset="H:\MinecraftMods\OpenYSM\forge\run\config\yes_steve_model\custom\Flandre_Scarlet.ysm" -PassimpLoaderDir=../AssimpLoader-ysm-import --console=plain
```

## Headless import smoke in a real 1.21.1 client

`YsmImportSmokeTest` is opt-in and client-only. It runs on the client tick thread, reads the file at
`rigworks.ysm.asset`, and calls `AvatarImportRegistry.get().load(...)` followed by `.apply(...)`
through the YSM handler — no file picker, no server state. It writes
`rigworks-ysm-smoke-result.json` into the run directory with `success`, `error`, `bridgeAvailable`,
`detectedAsYsm`, `meshCount`, `animationCount`, `boneCount` and `applied`.

From the repository root:

```
.\gradlew.bat :1.21.1-neoforge:runClient -PclientYsmSmoke -PclientYsmAsset="H:\MinecraftMods\OpenYSM\forge\run\config\yes_steve_model\custom\Flandre_Scarlet.ysm" -PassimpLoaderDir=../AssimpLoader-ysm-import --console=plain
```

`-PclientYsmSmoke` is the boolean enable switch. The probe fires on the client tick once a playable
level is present, so either enter a world after launch or pass a world name as the property value
(for example `-PclientYsmSmoke=Ysm_Import_Smoke`) to open it automatically. The `runClient` task
checks the result file when it ends; the same check is available on demand as
`:1.21.1-neoforge:checkYsmSmokeResult`. Leave the client open until the result file appears.
