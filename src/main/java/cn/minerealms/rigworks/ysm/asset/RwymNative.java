package cn.minerealms.rigworks.ysm.asset;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The C++ converter is loaded only for an import; reading RWYM does not load native code. */
public final class RwymNative {
    public static final int ABI_VERSION = 0x00010000;
    public static final int MAX_SOURCE_BYTES = 256 * 1024 * 1024;
    public static final int MAX_ASSET_BYTES = 512 * 1024 * 1024;

    private interface Api extends Library {
        int rwym_abi_version();
        String rwym_version();
        int rwym_convert_legacy_v3(Pointer source, long length, PointerByReference output, LongByReference outputLength);
        int rwym_convert_payload(Pointer source, long length, PointerByReference output, LongByReference outputLength);
        void rwym_free(Pointer output);
        String rwym_last_error();
    }

    private static final class Holder {
        static final Api API;
        static final String FAILURE;
        static {
            Api api = null;
            String failure = "";
            try {
                String override = System.getProperty("rigworks.ysm.nativeLibrary", "");
                String path;
                if (!override.isBlank()) path = Path.of(override).toAbsolutePath().toString();
                else {
                    String filename = System.mapLibraryName("rigworks_ysm");
                    String resource = "/natives/rigworks_ysm/" + Platform.RESOURCE_PREFIX + "/" + filename;
                    try (var stream = RwymNative.class.getResourceAsStream(resource)) {
                        if (stream == null) throw new IOException("Missing RigWorks YSM converter: " + resource);
                        Path directory = Files.createTempDirectory("rigworks-rwym-");
                        Path library = directory.resolve(filename);
                        Files.copy(stream, library, StandardCopyOption.REPLACE_EXISTING);
                        directory.toFile().deleteOnExit();
                        library.toFile().deleteOnExit();
                        path = library.toString();
                    }
                }
                api = Native.load(path, Api.class);
                if (api.rwym_abi_version() != ABI_VERSION) throw new IOException("Incompatible RigWorks YSM converter ABI");
            } catch (IOException | RuntimeException | LinkageError error) {
                api = null;
                failure = error.getMessage();
            }
            API = api;
            FAILURE = failure;
        }
    }

    private RwymNative() {}

    public static boolean available() { return Holder.API != null; }
    public static String failure() { return Holder.FAILURE; }
    public static String version() throws IOException { return api().rwym_version(); }
    public static byte[] convert(byte[] source) throws IOException { return convert(source, false); }
    public static byte[] convertPayload(byte[] payload) throws IOException { return convert(payload, true); }

    private static Api api() throws IOException {
        if (Holder.API == null) throw new IOException("RigWorks YSM conversion unavailable: " + Holder.FAILURE);
        return Holder.API;
    }

    private static byte[] convert(byte[] source, boolean payload) throws IOException {
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES)
            throw new IOException("YSM import input must be between 1 byte and 256 MiB");
        Api api = api();
        try (Memory input = new Memory(source.length)) {
            input.write(0, source, 0, source.length);
            PointerByReference output = new PointerByReference();
            LongByReference length = new LongByReference();
            int status = payload ? api.rwym_convert_payload(input, source.length, output, length)
                    : api.rwym_convert_legacy_v3(input, source.length, output, length);
            Pointer pointer = output.getValue();
            try {
                if (status != 0) throw new IOException("YSM conversion failed (" + status + "): " + api.rwym_last_error());
                long size = length.getValue();
                if (pointer == null || size < 24 || size > MAX_ASSET_BYTES)
                    throw new IOException("YSM converter returned an invalid asset length: " + size);
                return pointer.getByteArray(0, (int) size);
            } finally {
                if (pointer != null) api.rwym_free(pointer);
            }
        }
    }
}
