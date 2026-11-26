package cn.minerealms.rigworks.mmd;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Original three-vertex assets; no third-party character or motion is redistributed. */
public final class MmdTestSources {
    private MmdTestSources() {}

    public static Path write(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path model = Files.write(directory.resolve("model.pmd"), pmd());
        Files.write(directory.resolve("walk.vmd"), vmd());
        return model;
    }

    private static byte[] pmd() {
        var b = new Bytes(); b.fixed("Pmd", 3); b.f(1); b.fixed("RigWorks fixture",20); b.zeros(256); b.u32(3);
        for (int i=0;i<3;i++) {
            b.v(i==1?1:0, i==2?2:0, 0); b.v(0,0,-1); b.f(i==1?1:0); b.f(i==2?0:1);
            b.u16(0); b.u16(1); b.u8(25); b.u8(0);
        }
        b.u32(3); b.u16(0); b.u16(1); b.u16(2); b.u32(1);
        b.v(1,1,1); b.f(1); b.f(0); b.v(0,0,0); b.v(0,0,0); b.u8(255); b.u8(0); b.u32(3); b.zeros(20);
        b.u16(2);
        for (int i=0;i<2;i++) { b.fixed(i==0?"root":"tip",20); b.u16(i==0?65535:0); b.u16(65535); b.u8(0); b.u16(0); b.v(0,i==0?0:1,0); }
        b.u16(0); b.u16(3);
        b.fixed("base",20); b.u32(1); b.u8(0); b.u32(2); b.v(0,2,0);
        b.fixed("blink",20); b.u32(1); b.u8(2); b.u32(0); b.v(0,.2f,0);
        b.fixed("empty",20); b.u32(0); b.u8(2);
        b.u8(0); b.u8(0); b.u32(0);
        return b.bytes();
    }

    private static byte[] vmd() {
        var b = new Bytes(); b.fixed("Vocaloid Motion Data 0002",30); b.fixed("RigWorks fixture",20); b.u32(2);
        for (int frame : new int[]{0,30}) {
            b.fixed("tip",15); b.u32(frame); b.v(frame==0?0:1,0,0); b.v(0,0,0); b.f(1);
            for (int i=0;i<64;i++) b.u8(i<8?20:i<16?107:0);
        }
        b.u32(2);
        for (int frame : new int[]{0,30}) { b.fixed("blink",15); b.u32(frame); b.f(frame==0?0:1); }
        b.u32(0); b.u32(0); b.u32(0); b.u32(0);
        return b.bytes();
    }

    private static final class Bytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void u8(int value) { out.write(value); }
        void u16(int value) { for(int i=0;i<2;i++) u8(value >>> (8*i)); }
        void u32(int value) { for(int i=0;i<4;i++) u8(value >>> (8*i)); }
        void f(float value) { u32(Float.floatToRawIntBits(value)); }
        void v(float x,float y,float z) { f(x);f(y);f(z); }
        void zeros(int count) { for(int i=0;i<count;i++) u8(0); }
        void fixed(String value,int length) { byte[] bytes=value.getBytes(StandardCharsets.US_ASCII); for(int i=0;i<length;i++) u8(i<bytes.length?bytes[i]:0); }
        byte[] bytes() { return out.toByteArray(); }
    }
}
