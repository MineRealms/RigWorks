package cn.minerealms.rigworks.mmd;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Import-only C ABI. Ordinary converted GLB/profile loading does not initialize this class. */
public final class MmdNative {
    public static final int ABI_VERSION = 0x00010000;
    public static final int MAX_SOURCE_BYTES = 256 * 1024 * 1024;

    private interface Api extends Library {
        int rwmd_abi_version();
        int rwmd_convert(Pointer source, long length, String sourcePath, String outputDirectory, String options);
        String rwmd_last_error();
    }

    private static final class Holder {
        static final Api API;
        static final String FAILURE;
        static {
            Api api = null;
            String failure = "";
            try {
                if (Native.SIZE_T_SIZE != Long.BYTES) throw new IOException("MMD converter requires a 64-bit runtime");
                String path = System.getProperty("rigworks.mmd.nativeLibrary", "");
                if (path.isBlank()) {
                    String filename = System.mapLibraryName("rigworks_mmd");
                    String resource = "/natives/rigworks_mmd/" + Platform.RESOURCE_PREFIX + "/" + filename;
                    try (var input = MmdNative.class.getResourceAsStream(resource)) {
                        if (input == null) throw new IOException("Missing MMD converter for " + Platform.RESOURCE_PREFIX);
                        Path directory = Files.createTempDirectory("rigworks-mmd-native-");
                        Path library = directory.resolve(filename);
                        Files.copy(input, library);
                        directory.toFile().deleteOnExit();
                        library.toFile().deleteOnExit();
                        path = library.toString();
                    }
                }
                api = Native.load(path, Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                if (api.rwmd_abi_version() != ABI_VERSION) throw new IOException("Incompatible MMD converter ABI");
            } catch (IOException | RuntimeException | LinkageError error) {
                api = null;
                failure = error.getMessage() == null ? error.toString() : error.getMessage();
                org.apache.logging.log4j.LogManager.getLogger("rigworks.mmd").warn(
                        "Cannot load MMD converter for {}", Platform.RESOURCE_PREFIX, error);
            }
            API = api;
            FAILURE = failure;
        }
    }

    private MmdNative() {}
    public static boolean available() { return Holder.API != null; }
    public static String failure() { return Holder.FAILURE; }

    public static void convert(byte[] source, Path sourcePath, Path output, String options) throws IOException {
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES)
            throw new IOException("MMD source must be between 1 byte and 256 MiB");
        if (Holder.API == null) throw new IOException("MMD conversion unavailable: " + Holder.FAILURE);
        try (Memory input = new Memory(source.length)) {
            input.write(0, source, 0, source.length);
            int status = Holder.API.rwmd_convert(input, source.length, sourcePath.toString(), output.toString(), options);
            if (status != 0) throw new IOException("MMD conversion failed (" + status + "): " + Holder.API.rwmd_last_error());
        }
    }
}
