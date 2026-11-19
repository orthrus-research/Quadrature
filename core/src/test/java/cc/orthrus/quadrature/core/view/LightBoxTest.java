package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

class LightBoxTest {

    private static final double[][] LIGHTS = {{0, 1, 0}, {0.9389919, 0.3117149, 0.14535503}, {-0.6, 0.3, 0.74},
        {0.01, 0.9999, 0.0}};

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    private static View player(double x, double y, double z) {
        return new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, x, y, z, x, y + 1.62, z, identity(), identity(),
            12, 12, true);
    }

    /** The texel a world place lands on in a map drawn through the view, x and y, unrounded. */
    private static double[] texel(View view, double x, double y, double z) {
        float[] m = view.modelView();
        float[] p = view.projection();
        double rx = x - view.originX();
        double ry = y - view.originY();
        double rz = z - view.originZ();
        double[] eye = new double[3];
        for (int row = 0; row < 3; row++) {
            eye[row] = m[row] * rx + m[4 + row] * ry + m[8 + row] * rz + m[12 + row];
        }
        double cx = p[0] * eye[0] + p[12];
        double cy = p[5] * eye[1] + p[13];
        return new double[] {(cx * 0.5 + 0.5) * 4096, (cy * 0.5 + 0.5) * 4096};
    }

    @Test
    void everyTexelACubeCanCoverIsInsideTheRangeItIsGiven() {
        Random random = new Random(5);
        int[] range = new int[4];
        for (double[] light : LIGHTS) {
            for (double side : new double[] {192, 544}) {
                LightBox box = LightBox.around(light[0], light[1], light[2], 2563.4, 146, 7624.7, side, 544, 4096);
                View view = View.throughLight(new View(), player(2563.4, 146, 7624.7), box);
                for (int i = 0; i < 300; i++) {
                    int cubeX = 160 + random.nextInt(4);
                    int cubeY = 8 + random.nextInt(3);
                    int cubeZ = 476 + random.nextInt(4);
                    if (!box.texelsOf(cubeX, cubeY, cubeZ, range)) {
                        continue;
                    }
                    // Points on and in the cube and up to the overhang past it, its widened corners among them.
                    double low = -LightBox.OVERHANG;
                    double span = 16 + 2 * LightBox.OVERHANG;
                    for (int j = 0; j < 40; j++) {
                        double x = (cubeX << 4) + low + (j < 8 ? (j & 1) * span : random.nextDouble() * span);
                        double y = (cubeY << 4) + low + (j < 8 ? (j >> 1 & 1) * span : random.nextDouble() * span);
                        double z = (cubeZ << 4) + low + (j < 8 ? (j >> 2 & 1) * span : random.nextDouble() * span);
                        double[] at = texel(view, x, y, z);
                        if (at[0] < 0 || at[1] < 0 || at[0] >= 4096 || at[1] >= 4096) {
                            continue;
                        }
                        assertTrue(Math.floor(at[0]) >= range[0] && Math.floor(at[0]) <= range[1], "across");
                        assertTrue(Math.floor(at[1]) >= range[2] && Math.floor(at[1]) <= range[3], "up");
                    }
                }
            }
        }
    }

    @Test
    void aCubeFarAcrossTheLightCoversNothingAndItsRangeIsHeldToTheMap() {
        int[] range = new int[4];
        LightBox box = LightBox.around(0, 1, 0, 0, 64, 0, 192, 544, 4096);
        // Overhead light: across is x and z. A cube 400 blocks off lies beyond a box 96 blocks either way.
        assertFalse(box.texelsOf(25, 4, 0, range));
        assertTrue(box.texelsOf(0, 4, 0, range));
        // Overhead, a cube covers 16 by 16 blocks and its models may reach a block past it: 18 blocks of
        // texels and two either side, at most.
        assertTrue(range[1] - range[0] <= (16 + 2 * LightBox.OVERHANG) / box.texel() + 4);
        assertTrue(range[1] - range[0] >= (16 + 2 * LightBox.OVERHANG) / box.texel());
        // One at the box's edge along its second axis, x here, is held to the map.
        assertTrue(box.texelsOf(5, 4, 0, range));
        assertEquals(4095, range[3]);
    }

    @Test
    void offCenterAndTheTurnAreMeasuredFromTheBoxAsMade() {
        LightBox box = LightBox.around(0.9389919, 0.3117149, 0.14535503, 2563.4, 146, 7624.7, 544, 544, 4096);
        assertTrue(box.offCenter(2563.4, 146, 7624.7) <= box.texel() / 2 + 1e-9);
        assertEquals(10, box.offCenter(2563.4 + 10 * box.toward(0), 146 + 10 * box.toward(1), 7624.7 + 10 * box.toward(2)),
            box.texel());
        assertEquals(1, box.cosine(0.9389919 * 3, 0.3117149 * 3, 0.14535503 * 3), 1e-12);
        double tenth = Math.toRadians(0.1);
        assertEquals(Math.cos(tenth), box.cosine(Math.cos(tenth) * 0.9389919 - Math.sin(tenth) * 0.3117149,
            Math.sin(tenth) * 0.9389919 + Math.cos(tenth) * 0.3117149, 0.14535503), 1e-3);
        assertTrue(box.sized(544, 544));
        assertFalse(box.sized(544, 192));
    }
}
