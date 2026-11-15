package cc.orthrus.quadrature.terrain;

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
     * Fills {@code view} as a view along the direction of the sun, or of the moon while the sun is
     * down, that holds what {@code main} sees.
     */
    static View alongLight(View view, View main, double partialTicks) {
        // The sun circles the north-south axis: overhead at angle 0, in the east at three quarters.
        double angle = Minecraft.getMinecraft().world.getCelestialAngle((float) partialTicks) * 2 * Math.PI;
        double towardX = -Math.sin(angle);
        double towardY = Math.cos(angle);
        if (towardY < 0) {
            towardX = -towardX;
            towardY = -towardY;
        }
        return View.alongLight(view, main, towardX, towardY, 0);
    }
}
