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
package cn.minerealms.rigworks.ysm.java.zstd;

public class MalformedInputException extends RuntimeException {
    private final long offset;

    public MalformedInputException(long offset) {
        this(offset, "Malformed input");
    }

    public MalformedInputException(long offset, String reason) {
        super(reason + ": offset=" + offset);
        this.offset = offset;
    }

    public long getOffset() {
        return offset;
    }
}

