/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
package cn.minerealms.rigworks.ysm;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** JNA mapping and extraction/loading of the bundled ysmprocessor native library. */
final class YsmNative {
    static final int YSP_OK = 0;
    static final int YSP_WASHED_ZSTD_FRAME = 2;

    interface Api extends Library {
        int ysp_init();

        void ysp_shutdown();

        String ysp_version();

        int ysp_legacy_v3_decode(Pointer file, int len, PointerByReference out, IntByReference outLen);

        int ysp_zstd_decompress(Pointer src, int len, PointerByReference out, IntByReference outLen);

        void ysp_free(Pointer ptr);

        String ysp_last_error();
    }

    static final Api API;
    static final boolean AVAILABLE;
    static final String VERSION;

    static {
        Api api = null;
        boolean available = false;
        String version = "unavailable";
        try {
            Path library = extractLibrary();
            api = Native.load(library.toAbsolutePath().toString(), Api.class);
            if (api.ysp_init() == YSP_OK) {
                available = true;
                version = String.valueOf(api.ysp_version());
            }
        } catch (Throwable ignored) {
            api = null;
            available = false;
        }
        API = api;
        AVAILABLE = available;
        VERSION = version;
    }

    private YsmNative() {
    }

    private static Path extractLibrary() throws IOException {
        String resourcePrefix = Platform.RESOURCE_PREFIX;
        String fileName = Platform.isWindows()
                ? "ysmprocessor.dll"
                : (Platform.isMac() ? "libysmprocessor.dylib" : "libysmprocessor.so");
        String resource = "/native/ysmprocessor/" + resourcePrefix + "/" + fileName;
        try (InputStream in = YsmNative.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing native resource: " + resource);
            }
            Path directory = Files.createTempDirectory("rigworks-ysmprocessor-");
            Path target = directory.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return target;
        }
    }

    static String lastError() {
        try {
            return API == null ? "native backend unavailable" : String.valueOf(API.ysp_last_error());
        } catch (Throwable ignored) {
            return "unknown native error";
        }
    }

    static byte[] decodeLegacyV3(byte[] file) throws IOException {
        if (API == null) {
            throw new IOException("native ysmprocessor backend is unavailable");
        }
        Memory input = new Memory(file.length);
        input.write(0, file, 0, file.length);
        PointerByReference output = new PointerByReference();
        IntByReference outputLength = new IntByReference();
        int code = API.ysp_legacy_v3_decode(input, file.length, output, outputLength);
        Pointer result = output.getValue();
        try {
            if (code == YSP_OK || code == YSP_WASHED_ZSTD_FRAME) {
                if (result == null) {
                    throw new IOException("native decoder returned a null buffer");
                }
                byte[] bytes = result.getByteArray(0, outputLength.getValue());
                if (code == YSP_WASHED_ZSTD_FRAME) {
                    return decompressZstd(bytes);
                }
                return bytes;
            }
            throw new IOException("native ysmprocessor failed with code " + code + ": " + lastError());
        } finally {
            if (result != null) {
                API.ysp_free(result);
            }
        }
    }

    private static byte[] decompressZstd(byte[] washedFrame) throws IOException {
        Memory input = new Memory(washedFrame.length);
        input.write(0, washedFrame, 0, washedFrame.length);
        PointerByReference output = new PointerByReference();
        IntByReference outputLength = new IntByReference();
        int code = API.ysp_zstd_decompress(input, washedFrame.length, output, outputLength);
        Pointer result = output.getValue();
        try {
            if (code != YSP_OK || result == null) {
                throw new IOException("native zstd decompression failed with code " + code + ": " + lastError());
            }
            return result.getByteArray(0, outputLength.getValue());
        } finally {
            if (result != null) {
                API.ysp_free(result);
            }
        }
    }
}
