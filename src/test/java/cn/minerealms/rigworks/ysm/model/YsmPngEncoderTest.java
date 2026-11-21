package cn.minerealms.rigworks.ysm.model;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

class YsmPngEncoderTest {
    @Test
    void generatedPngIsZlibWrappedAndReadable() throws Exception {
        int w = 8, h = 4;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < rgba.length; i++) rgba[i] = (byte) (i * 37);
        byte[] png = YsmPngEncoder.toPng(rgba, w, h);

        assertArrayEquals(new byte[]{(byte)0x89,'P','N','G',0x0D,0x0A,0x1A,0x0A},
            java.util.Arrays.copyOf(png, 8));

        int off = 8;
        byte[] idat = null;
        while (off + 8 <= png.length) {
            int len = ByteBuffer.wrap(png, off, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            String type = new String(png, off + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals("IDAT")) idat = java.util.Arrays.copyOfRange(png, off + 8, off + 8 + len);
            off += 12 + len;
        }
        assertNotNull(idat);
        int cmf = idat[0] & 0xFF;
        assertEquals(0x78, cmf, "IDAT must start with a zlib header (0x78), not raw deflate");

        Inflater inflater = new Inflater();
        inflater.setInput(idat);
        byte[] out = new byte[w * h * 4 + h];
        int n = inflater.inflate(out);
        inflater.end();
        assertEquals(w * h * 4 + h, n, "raw scanlines must inflate");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "PNG must be readable by a strict decoder");
        assertEquals(w, image.getWidth());
        assertEquals(h, image.getHeight());
    }
}
