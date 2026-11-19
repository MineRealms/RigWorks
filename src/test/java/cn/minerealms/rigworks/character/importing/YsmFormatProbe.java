package cn.minerealms.rigworks.character.importing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java format probe: asks the YSM bridge what it detects in a file, without starting
 * Minecraft or opening a file picker. Runnable directly with {@code java} (see {@link #main}) or
 * through the JUnit wrapper in {@code YsmFormatProbeTest}.
 */
public final class YsmFormatProbe {
    private YsmFormatProbe() {}

    public static void main(String[] args) throws Exception {
        if(args.length!=1) {
            System.err.println("usage: YsmFormatProbe <file.ysm>");
            System.exit(2);
            return;
        }
        System.out.print(report(Path.of(args[0])));
    }

    /** Human-readable probe report for one asset; shared with the JUnit wrapper. */
    public static String report(Path file)throws Exception {
        StringBuilder out=new StringBuilder();
        out.append("asset=").append(file).append('\n');
        byte[] bytes=Files.readAllBytes(file);
        out.append("assetBytes=").append(bytes.length).append('\n');
        out.append("signature=").append(prefix(bytes,8)).append('\n');
        out.append("ysgpPrefix=").append(ysgpPrefix(bytes)).append('\n');
        YsmApi.Bridge bridge=YsmApi.bridge();
        out.append("bridgeClass=").append(bridge.getClass().getName()).append('\n');
        try {
            boolean isYsm=bridge.isYsm(bytes);
            out.append("isYsm=").append(isYsm).append('\n');
        }catch(Throwable failure) {
            out.append("isYsm=ERROR ").append(failure).append('\n');
        }
        try {
            boolean available=bridge.available();
            out.append("bridgeAvailable=").append(available).append('\n');
        }catch(Throwable failure) {
            out.append("bridgeAvailable=unavailable without full classpath: ").append(failure).append('\n');
        }
        try {
            byte[] glb=bridge.toGlb(bytes);
            out.append("toGlb=ok bytes=").append(glb.length).append('\n');
        }catch(UnsupportedOperationException absent) {
            out.append("toGlb=UnsupportedOperationException: ").append(absent.getMessage()).append('\n');
            out.append("engineApiStatus=not on classpath (expected until feature/ysm-import is merged)\n");
        }catch(Throwable failure) {
            out.append("toGlb=ERROR ").append(failure).append('\n');
        }
        return out.toString();
    }

    /** Independent byte-level check: optional UTF-8 BOM followed by the YSGP marker. */
    private static boolean ysgpPrefix(byte[] bytes) {
        int start=(bytes.length>=3&&(bytes[0]&0xFF)==0xEF&&(bytes[1]&0xFF)==0xBB&&(bytes[2]&0xFF)==0xBF)?3:0;
        if(bytes.length<start+4)return false;
        return (bytes[start]&0xFF)=='Y'&&(bytes[start+1]&0xFF)=='S'
                &&(bytes[start+2]&0xFF)=='G'&&(bytes[start+3]&0xFF)=='P';
    }

    private static String prefix(byte[] bytes,int length) {
        int count=Math.min(length,bytes.length);
        StringBuilder out=new StringBuilder(count*2);
        for(int i=0;i<count;i++)out.append(String.format("%02X",bytes[i]&0xFF));
        return out.toString();
    }
}
