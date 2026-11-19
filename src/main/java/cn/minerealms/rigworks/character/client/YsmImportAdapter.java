package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * TRANSIENT bridge to the YSM import API owned by AssimpLoader (feature/ysm-import).
 * All calls are reflective so this worktree keeps compiling while that API is unmerged.
 * After the merge, replace {@link #load(String, byte[])} with a direct
 * {@code AssimpModelLoader.loadYsmFromMemory(name, bytes, ModelImportMode.DYNAMIC)} call,
 * drop {@link #toGlb(byte[])} / {@link #isYsm(byte[])} and delete this class.
 * Nothing here modifies AssimpLoader or its submodule.
 */
public final class YsmImportAdapter {
    public static final List<String> EXTENSIONS = List.of("json", "mxc", "ysm");
    private static final String LOADER = "cn.minerealms.assimploader.core.AssimpModelLoader";
    private static final String IMPORTER = "cn.minerealms.assimploader.format.ysm.YsmImporter";

    private YsmImportAdapter() {}

    public static boolean accepts(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static boolean available() {
        return method(LOADER, "loadYsmFromMemory", String.class, byte[].class, ModelImportMode.class) != null
                || method(IMPORTER, "toGlb", byte[].class) != null;
    }

    /** Best-effort detection; the real check lives in the merged importer. */
    public static boolean isYsm(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        Method check = method(IMPORTER, "isYsm", byte[].class);
        if (check != null) try {
            Object result = call(check, null, (Object) bytes);
            if (result instanceof Boolean value) return value;
        } catch (IOException ignored) {
            return false;
        }
        int start = (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        return prefix(bytes, start, "YSGP");
    }

    public static AssimpScene load(String name, byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("YSM input is empty: " + name);
        Method direct = method(LOADER, "loadYsmFromMemory", String.class, byte[].class, ModelImportMode.class);
        if (direct != null) {
            Object scene = call(direct, null, name, bytes, ModelImportMode.DYNAMIC);
            return scene instanceof AssimpScene value ? value : null;
        }
        return new AssimpModelLoader().loadModelFromMemory("model.glb", toGlb(bytes), ModelImportMode.DYNAMIC);
    }

    /** Converted GLB bytes, used only to persist a memory-imported YSM avatar for later reloads. */
    public static byte[] toGlb(byte[] bytes) throws IOException {
        Method convert = method(IMPORTER, "toGlb", byte[].class);
        if (convert == null) throw new UnsupportedOperationException("YSM import requires the AssimpLoader YSM API (feature/ysm-import)");
        Object result = call(convert, null, (Object) bytes);
        if (result instanceof byte[] glb) return glb;
        throw new IOException("YSM conversion produced no GLB data");
    }

    private static boolean prefix(byte[] bytes, int start, String text) {
        byte[] expected = text.getBytes(StandardCharsets.US_ASCII);
        if (start + expected.length > bytes.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[start + i] != expected[i]) return false;
        return true;
    }

    private static Method method(String className, String name, Class<?>... parameters) {
        try {
            Class<?> owner = Class.forName(className, false, YsmImportAdapter.class.getClassLoader());
            return owner.getMethod(name, parameters);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object call(Method target, Object owner, Object... arguments) throws IOException {
        try {
            return target.invoke(owner, arguments);
        } catch (IllegalAccessException | IllegalArgumentException failure) {
            throw new IOException("Cannot invoke YSM API " + target.getName(), failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("YSM API " + target.getName() + " failed", cause);
        }
    }
}
