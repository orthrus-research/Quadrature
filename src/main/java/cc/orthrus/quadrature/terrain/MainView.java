package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.view.SunPath;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.depth.DepthCalls;

import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import org.lwjgl.opengl.GL11;

/**
 * Fills the player's view from the game's state, once a frame, at the point where the game is about
 * to draw its first terrain layer: the matrices are the ones the game has just set for the world.
 */
final class MainView {

    /**
     * {@code -Dquadrature.sun.tilt=<degrees>}, 0 unless set: how far toward the south the sun stands at
     * noon. It leans the directions published toward the sun, the moon and the light, and the box the
     * light's view is drawn in; the game's own sky is drawn as the game draws it.
     */
    static final double SUN_TILT = tilt();

    private static double tilt() {
        try {
            return Double.parseDouble(System.getProperty("quadrature.sun.tilt", "0"));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static final float[] MODEL_VIEW = new float[16];
    private static final float[] PROJECTION = new float[16];
    private static final BlockPos.MutableBlockPos EYE = new BlockPos.MutableBlockPos();

    private MainView() {
    }

    static View fill(View view, double partialTicks, Entity viewer) {
        Minecraft minecraft = Minecraft.getMinecraft();
        // The game draws the world relative to the viewer's interpolated position, not its eyes.
        double x = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partialTicks;
        double y = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partialTicks;
        double z = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partialTicks;
        double eyeY = y + viewer.getEyeHeight();
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MODEL_VIEW);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        // As in the game: terrain hides terrain unless that is switched off, or a spectator's eyes are
        // inside an opaque block, from where every way out would be closed.
        boolean occlusion = minecraft.renderChunksMany;
        if (occlusion && viewer instanceof EntityPlayer && ((EntityPlayer) viewer).isSpectator()
            && minecraft.world.getBlockState(EYE.setPos(x, eyeY, z)).isOpaqueCube()) {
            occlusion = false;
        }
        return view.set(View.Purpose.MAIN, DepthCalls.active() ? View.Depth.REVERSED : View.Depth.CONVENTIONAL, x, y,
            z, x, eyeY, z, MODEL_VIEW, PROJECTION, minecraft.gameSettings.renderDistanceChunks,
            CubicChunksConfig.verticalCubeLoadDistance, occlusion);
    }

    /**
     * The direction toward the sun, or the moon while the sun is down, this frame. The sun circles the
     * north-south axis: overhead at angle 0, in the east at three quarters; leaned toward the south
     * where a tilt is set.
     */
    static void towardLight(double partialTicks, double[] toward) {
        SunPath.towardLight(Minecraft.getMinecraft().world.getCelestialAngle((float) partialTicks), SUN_TILT, toward);
    }
}
