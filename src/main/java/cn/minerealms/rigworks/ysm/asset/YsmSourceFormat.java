package cn.minerealms.rigworks.ysm.asset;

import java.io.IOException;

/** Cheap envelope validation before loading the native converter or consulting a disk cache. */
public final class YsmSourceFormat {
    private static final int FOOTER_BYTES = 64;

    private YsmSourceFormat() {}

    public static void requireLegacyV3(byte[] source) throws IOException {
        if (source == null || source.length < 12) throw new IOException("YSM source is empty or truncated");
        int start = source.length >= 7 && (source[0] & 0xff) == 0xef
                && (source[1] & 0xff) == 0xbb && (source[2] & 0xff) == 0xbf ? 3 : 0;
        if (!magic(source, start, "YSGP")) throw new IOException("Unrecognized YSM source header");
        if (magic(source, start, "YSGP2"))
            throw new IOException("Modern YSM/MXC containers are not supported by this importer");
        if (start == 0)
            throw new IOException("Legacy YSM V1/V2 containers are not supported; this importer requires a V3 envelope");

        int footer = source.length - FOOTER_BYTES;
        int terminator = start + 4;
        while (terminator < footer && source[terminator] != 0) terminator++;
        if (terminator >= footer || terminator + 5 > footer)
            throw new IOException("Legacy YSM V3 header is truncated");
        int offset = terminator + 1;
        long version = (source[offset] & 0xffL) | ((source[offset + 1] & 0xffL) << 8)
                | ((source[offset + 2] & 0xffL) << 16) | ((source[offset + 3] & 0xffL) << 24);
        if (version != 3)
            throw new IOException("Unsupported YSM envelope crypto version " + version + "; expected Legacy V3");
    }

    private static boolean magic(byte[] source, int offset, String expected) {
        if (offset + expected.length() > source.length) return false;
        for (int i = 0; i < expected.length(); i++)
            if (source[offset + i] != expected.charAt(i)) return false;
        return true;
    }
}
