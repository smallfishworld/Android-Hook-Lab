package dev.smallfish.hooklab.demo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ImageProcessorTest {
    @Test public void clampsFloatBrightness() {
        ImageProcessor p = new ImageProcessor();
        assertEquals(0.0f, p.adjustBrightness(0.1f, -20), 0.00001f);
        assertEquals(1.0f, p.adjustBrightness(0.9f, 50), 0.00001f);
        assertEquals(0.45f, p.adjustBrightness(0.25f, 20), 0.00001f);
    }

    @Test public void clampsIntegerBrightness() {
        assertEquals(255, new ImageProcessor().adjustBrightness(250, 10));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidLevel() {
        new ImageProcessor().validateLevel(-1);
    }
}
