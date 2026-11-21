package cn.minerealms.rigworks.character.importing;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the pure-Java YSM probe when rigworks.ysm.asset is supplied (Gradle: -PclientYsmAsset=<path>). */
class YsmFormatProbeTest {
    @Test void reportsYsmSignatureWithoutMinecraft()throws Exception {
        String raw=System.getProperty("rigworks.ysm.asset");
        Assumptions.assumeTrue(raw!=null&&!raw.isBlank(),"set -Drigworks.ysm.asset=<path> to run the probe");
        Path file=Path.of(raw);
        Assumptions.assumeTrue(Files.isRegularFile(file),"YSM asset is missing: "+file);
        String report=YsmFormatProbe.report(file);
        System.out.println(report);
        assertTrue(report.contains("isYsm="),"probe did not report a bridge verdict: "+report);
        assertTrue(report.contains("ysgpPrefix="),"probe did not report the byte-level signature: "+report);
    }
}
