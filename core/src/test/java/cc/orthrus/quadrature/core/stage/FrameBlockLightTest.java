package cc.orthrus.quadrature.core.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.view.LightBox;
import cc.orthrus.quadrature.core.view.ShadowLayers;
import cc.orthrus.quadrature.core.view.ShadowMaps;
import cc.orthrus.quadrature.core.view.View;

import org.junit.jupiter.api.Test;

import java.util.Random;

class FrameBlockLightTest {

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    private static final double[][] LIGHTS = {{0, 1, 0}, {0.9389919, 0.3117149, 0.14535503}, {-0.93903553, 0.31160682, 0.14530465}};

    @Test
    void aPlaceTakenIntoALightViewAndBackIsWhereItWas() {
        Random random = new Random(3);
        double originX = 2563.4;
        double originY = 146.0;
        double originZ = 7624.7;
        // The eye stands a little off the view's origin, as the camera does.
        double eyeX = originX + 0.3;
        double eyeY = originY + 1.62;
        double eyeZ = originZ - 0.2;
        View player = new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, originX, originY, originZ, eyeX, eyeY, eyeZ,
            identity(), identity(), 12, 12, true);
        for (double[] light : LIGHTS) {
            ShadowLayers layers = new ShadowLayers(true);
            ShadowMaps maps = new ShadowMaps(layers.count(), ShadowLayers.TEXELS, layers.bytes());
            maps.beginFrame(originX, originY, originZ, light[0], light[1], light[2]);
            LightBox near = LightBox.around(light[0], light[1], light[2], originX, originY, originZ, ShadowLayers.NEAR_SIDE,
                544, ShadowLayers.TEXELS);
            LightBox wide = LightBox.around(light[0], light[1], light[2], originX, originY, originZ, 544, 544,
                ShadowLayers.TEXELS);
            maps.layer(0, View.throughLight(new View(), player, near), near, true, true);
            maps.layer(1, View.throughLight(new View(), player, wide), wide, true, true);
            for (int layer = 0; layer < 2; layer++) {
                double[] fromEye = FrameBlock.lightViewFromEye(maps.viewProjection(layer), eyeX - originX, eyeY - originY,
                    eyeZ - originZ);
                // Within a cube whose corners stay inside the box whichever way it is turned: 96 and 272 halves.
                double reach = layer == 0 ? 50 : 150;
                for (int i = 0; i < 300; i++) {
                    double x = (random.nextDouble() * 2 - 1) * reach;
                    double y = (random.nextDouble() * 2 - 1) * reach;
                    double z = (random.nextDouble() * 2 - 1) * reach;
                    double[] lit = FrameBlock.apply(fromEye, x, y, z, 1);
                    double u = lit[0] * 0.5 + 0.5;
                    double v = lit[1] * 0.5 + 0.5;
                    assertTrue(u > 0 && u < 1 && v > 0 && v < 1 && lit[2] > 0 && lit[2] < 1, "inside layer " + layer);
                    double[] back = FrameBlock.fromLight(fromEye, u, v, lit[2]);
                    assertNotNull(back);
                    // The matrices are single precision: a thousandth of a block is far below a texel.
                    assertEquals(x, back[0], 1e-3);
                    assertEquals(y, back[1], 1e-3);
                    assertEquals(z, back[2], 1e-3);
                }
                // A place farther from the light holds a greater depth, by 1 over the box's depth a block.
                double[] a = FrameBlock.apply(fromEye, 0, 0, 0, 1);
                double[] b = FrameBlock.apply(fromEye, -light[0], -light[1], -light[2], 1);
                double length = Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
                assertEquals(length / 544, b[2] - a[2], 1e-6);
            }
        }
    }
}
