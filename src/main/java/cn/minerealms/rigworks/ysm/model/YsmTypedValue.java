/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: preserve the encoded YSM value kind and expression source.
 */
package cn.minerealms.rigworks.ysm.model;

/** A YSM scalar without coercing Molang strings into numbers. */
public record YsmTypedValue(int typeTag, int floatBits, String rawString) {
    public static final int FLOAT_TAG = 1;
    public static final int STRING_TAG = 2;

    public YsmTypedValue {
        if (typeTag == FLOAT_TAG && rawString != null)
            throw new IllegalArgumentException("Float YSM values cannot carry a string");
        if (typeTag == STRING_TAG && rawString == null)
            throw new IllegalArgumentException("String YSM values require their source text");
        if (typeTag != FLOAT_TAG && typeTag != STRING_TAG)
            throw new IllegalArgumentException("Unsupported YSM value tag: " + typeTag);
    }

    public static YsmTypedValue ofFloat(float value) {
        return floatBits(Float.floatToRawIntBits(value));
    }

    public static YsmTypedValue floatBits(int bits) {
        return new YsmTypedValue(FLOAT_TAG, bits, null);
    }

    public static YsmTypedValue expression(String source) {
        return new YsmTypedValue(STRING_TAG, 0, source);
    }

    public boolean isFloat() {
        return typeTag == FLOAT_TAG;
    }

    public float floatValue() {
        if (!isFloat()) throw new IllegalStateException("YSM value is an expression: " + rawString);
        return Float.intBitsToFloat(floatBits);
    }
}
