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
package cn.minerealms.rigworks.ysm.runtime;

import java.io.IOException;

@FunctionalInterface
public interface YsmDecodeSource {
    byte[] decode(byte[] fileBytes) throws IOException;

    static YsmDecodeSource reflective() {
        return bytes -> {
            try {
                Class<?> owner = Class.forName("cn.minerealms.rigworks.ysm.YsmDecoder",
                        false, YsmDecodeSource.class.getClassLoader());
                var method = owner.getMethod("decode", byte[].class);
                Object result = method.invoke(null, (Object) bytes);
                if (result instanceof byte[] payload) return payload;
                throw new IOException("YsmDecoder.decode produced no payload");
            } catch (ClassNotFoundException | NoSuchMethodException absent) {
                throw new IOException("YSM decoder is not on the runtime classpath", absent);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IOException("YSM decode failed", cause);
            } catch (IllegalAccessException denied) {
                throw new IOException("Cannot access YsmDecoder.decode", denied);
            }
        };
    }
}
