package cc.orthrus.quadrature.orthlib;

import cc.orthrus.quadrature.Quadrature;

import dev.orthlib.api.render.Admission;
import dev.orthlib.api.render.BloomKindDefinition;
import dev.orthlib.api.render.ContributionShape;
import dev.orthlib.api.render.Primitive;
import dev.orthlib.api.render.RenderKey;
import dev.orthlib.api.render.VertexLayout;
import dev.orthlib.client.render.EmissiveSource;
import dev.orthlib.client.render.FrameView;
import dev.orthlib.client.render.RenderInterface;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.World;

/**
 * A diagnostic, off unless {@code -Dquadrature.interface.selfTest=x,y,z}: Quadrature as a consumer of the render
 * interface beside its provider, through the interface's public calls only. It defines one bloom kind with GTCEu:
 * Reloaded's emissive textures' values and contributes four squares a block across, a block apart along x from the
 * place given, facing north: untextured and opaque, untextured at half alpha, untextured and additive, and from the
 * blocks atlas (its missing-texture sprite) at half alpha. A tour that looks at them exercises every blend, the weight
 * pass, the glow and the state audit's stretch for the mods' callbacks, where no mod's own contributions are easily
 * staged.
 */
final class InterfaceSelfTest {

    private InterfaceSelfTest() {
    }

    /** Defines the kind and contributes the squares where the property is set; client thread, while mods load. */
    static void startIfAsked() {
        String place = System.getProperty("quadrature.interface.selfTest");
        if (place == null) {
            return;
        }
        String[] parts = place.split(",");
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(parts[0].trim());
            y = Double.parseDouble(parts[1].trim());
            z = Double.parseDouble(parts[2].trim());
        } catch (RuntimeException e) {
            Quadrature.LOGGER.error("quadrature.interface.selfTest={} is not x,y,z; the interface's self-test does not run.", place);
            return;
        }
        Admission<dev.orthlib.api.render.BloomKind> defined = RenderInterface.defineBloomKind(
            new BloomKindDefinition(new RenderKey("quadrature:self_test"), 1.5f, 0.1f, 0.2f, 0.5f, 1f));
        if (!(defined instanceof Admission.Admitted<dev.orthlib.api.render.BloomKind> admitted)) {
            Quadrature.LOGGER.error("The interface's self-test could not define its bloom kind: {}.", defined);
            return;
        }
        dev.orthlib.api.render.BloomKind kind = admitted.handle();
        int[][] colors = {{255, 140, 40, 255}, {60, 200, 255, 128}, {255, 60, 200, 255}, {255, 255, 255, 128}};
        dev.orthlib.api.render.Blend[] blends = {dev.orthlib.api.render.Blend.OPAQUE, dev.orthlib.api.render.Blend.ALPHA,
            dev.orthlib.api.render.Blend.ADDITIVE, dev.orthlib.api.render.Blend.ALPHA};
        for (int i = 0; i < 4; i++) {
            boolean textured = i == 3;
            ContributionShape shape = new ContributionShape(textured ? VertexLayout.POSITION_TEXTURE_COLOR
                : VertexLayout.POSITION_COLOR, Primitive.QUADS, blends[i]);
            Admission<?> contributed = RenderInterface.contribute(shape, kind,
                new Square(x + 2 * i, y, z, colors[i], textured));
            if (!(contributed instanceof Admission.Admitted<?>)) {
                Quadrature.LOGGER.error("The interface's self-test could not contribute square {}: {}.", i, contributed);
            }
        }
        Quadrature.LOGGER.info("The interface's self-test contributes four squares from {}, {}, {}.", x, y, z);
    }

    /** A square a block across at a place, facing north (toward -z), in a color, from the atlas where textured. */
    private record Square(double x, double y, double z, int[] color, boolean textured) implements EmissiveSource {

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        public @Nullable World world() {
            return null;
        }

        @Override
        public boolean shouldDraw(FrameView view) {
            return true;
        }

        @Override
        public void write(BufferBuilder buffer, FrameView view) {
            double x0 = x - view.cameraX();
            double y0 = y - view.cameraY();
            double z0 = z - view.cameraZ();
            TextureAtlasSprite sprite = textured ? Minecraft.getMinecraft().getTextureMapBlocks().getMissingSprite() : null;
            // Counter-clockwise seen from the north, so its face is the front one where faces are culled.
            double[][] corners = {{x0, y0}, {x0, y0 + 1}, {x0 + 1, y0 + 1}, {x0 + 1, y0}};
            for (double[] corner : corners) {
                buffer.pos(corner[0], corner[1], z0);
                if (sprite != null) {
                    buffer.tex(corner[0] == x0 ? sprite.getMinU() : sprite.getMaxU(),
                        corner[1] == y0 ? sprite.getMaxV() : sprite.getMinV());
                }
                buffer.color(color[0], color[1], color[2], color[3]).endVertex();
            }
        }
    }
}
