# YSM Full Support — shared contract across 3 worktrees

Branches:
- `RigWorks-ysm-native` / `feature/ysm-native` — native ysmprocessor + JNA + pure-Java decrypt fallback
- `RigWorks-ysm-decode` / `feature/ysm-decode` — decoded binary -> in-memory YSM model -> GLB
- `RigWorks-ysm-runtime` / `feature/ysm-runtime` — apply as player avatar + blink + item-switch + controllers

Original `H:\MinecraftMods\RigWorks` is OFF LIMITS. Only edit your own worktree.

## Shared Java API (owned by native worktree, consumed by decode worktree)

Package `cn.minerealms.rigworks.ysm`:

```java
public final class YsmDecoder {
    /** Checks BOM+YSGP magic (legacy) and BOM+YSGP2 (modern). */
    public static boolean isYsm(byte[] fileBytes);
    /** Returns format version from header metadata (<format> N), or -1. */
    public static int detectFormat(byte[] fileBytes);
    /**
     * Full decode: legacy V3 decrypt (XChaCha20+MT19937+zstd-wash+zstd) or
     * modern container decompress. Returns raw decompressed payload bytes.
     * Uses native backend if available, otherwise pure Java.
     */
    public static byte[] decode(byte[] fileBytes) throws IOException;
    /** true when native backend loaded. */
    public static boolean nativeAvailable();
    /** "native" | "java" */
    public static String backend();
}
```

Native C ABI in `native/ysmprocessor/` must export (JNA-callable):
```c
int32_t ysp_init(void);
void ysp_shutdown(void);
const char* ysp_version(void);
int32_t ysp_legacy_v3_decode(const uint8_t* file, int32_t len, uint8_t** out, int32_t* outLen);
int32_t ysp_zstd_decompress(const uint8_t* src, int32_t len, uint8_t** out, int32_t* outLen);
void ysp_free(void* ptr);
const char* ysp_last_error(void);
```
Build with Zig from `H:\MinecraftMods\FabriKNative\.tools\zig-windows-x86_64-0.13.0\zig.exe`, produce `dist/ysmprocessor-<plat>-<arch>.<ext>` and copy into `src/main/resources/native/ysmprocessor/<arch>/ysmprocessor.<ext>`. Prebuilt binaries committed; `-PbuildNative` triggers rebuild via script.

Pure-Java fallback: cleanroom reimplementation (~80% similarity ok) of the algorithm from OpenYSM references: XChaCha20 + CityHash64 + MT19937 XOR + YsmZstd wash + zstd decompress. Reference files:
- `H:\MinecraftMods\OpenYSM\common\src\main\java\rip\ysm\security\YsmCrypt.java` (decryptYsmFile)
- `H:\MinecraftMods\OpenYSM\common\src\main\java\rip\ysm\algorithms\{XChaCha20,ChaCha20Base,CityHash,MT19937,YsmZstd}.java`
- `H:\MinecraftMods\OpenYSM\common\src\main\java\rip\ysm\zstd\` (37 java files — pure Java zstd)
Do NOT copy verbatim; rename packages/classes and refactor.

## Shared model API (owned by decode worktree, consumed by runtime worktree)

Package `cn.minerealms.rigworks.ysm.model`:
```java
public record YsmBone(String name, String parent, Vec3 pivot, Vec3 rotation, List<YsmCube> cubes) {}
public record YsmCube(Vec3 origin, Vec3 size, Vec3 pivot, Vec3 rotation, Map<String,YsmFace> faces, float inflate, boolean mirror) {}
public record YsmFace(int[] uv, String texture) {}
public record YsmTexture(String name, int width, int height, byte[] pngBytes) {}
public record YsmKeyframe(float time, float[] value, String interpolation) {}
public record YsmChannel(String bone, String path, List<YsmKeyframe> frames) {}
public record YsmAnimation(String name, float duration, boolean loop, List<YsmChannel> channels) {}
public record YsmController(String name, String initialState, Map<String,YsmState> states) {}
public record YsmState(Map<String,String> animations, List<YsmTransition> transitions) {}
public record YsmTransition(String to, String condition) {}
public record YsmModel(int format, String modelId, List<YsmBone> bones, Map<String,YsmTexture> textures,
                       Map<String,YsmAnimation> animations, List<YsmController> controllers) {}
public final class YsmBinaryDecoder {
    public static YsmModel decode(byte[] payload) throws IOException;
}
public final class YsmGlbWriter {
    public static byte[] toGlb(YsmModel model) throws IOException;
}
```

## Runtime API (owned by runtime worktree)

Package `cn.minerealms.rigworks.ysm.runtime`:
- `YsmAvatarInstaller.install(YsmModel model, AssimpScene scene, String name)` — applies as local player avatar.
- `YsmExpressionRuntime` — evaluates Molang subset for controllers + blink + item state.
- `YsmBlinkLayer` — installs `blink` layer with eyelid mask.
- `YsmItemStateBridge` — maps held item (`ctrl.hold(':bow')`, `ctrl.use(...)`) to controller states.

## Merge order
1. native -> 2. decode -> 3. runtime.
