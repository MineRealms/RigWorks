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
package cn.minerealms.rigworks.ysm.java;

import java.io.IOException;

/** Pure-Java entry point used when the native ysmprocessor backend cannot load. */
public final class YsmJavaDecoder {
    private YsmJavaDecoder() {
    }

    public static byte[] decode(byte[] fileBytes) throws IOException {
        return LegacyV3Decrypt.decrypt(fileBytes);
    }
}
