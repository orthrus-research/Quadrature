package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.Travel;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Frames of distant terrain while it is still arriving, for a tour: off unless
 * {@code -Dquadrature.distant.travelCapture} lists delays in milliseconds
 * ({@code 500,2000,5000,12000}). Each time the viewer jumps more than 8 blocks, as a teleport
 * does, a frame is saved at every delay after the jump, at the end of that frame's world pass,
 * into the game's screenshots as {@code quadrature-travel-<jump>-<delay>ms.png}. The first frame
 * of a world counts as jump 0. A jump before the last delay drops the delays not yet reached.
 *
 * <p>A still, settled capture never shows tiles arriving, a tile fading in, or the coverage map
 * handing columns over; these frames are for that and nothing else. Nothing is drawn differently.
 */
final class TravelCapture {

    static final long[] DELAYS = Travel.delays(System.getProperty("quadrature.distant.travelCapture"));

    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    private long jumpedAt;
    private int jump = -1;
    private int next;
    private String due;
    private boolean registered;

    /**
     * Once a world pass, on the client thread.
     *
     * @return whether a frame is to be saved at the end of this pass, so the caller can log beside it
     */
    boolean frame(Entity viewer) {
        if (DELAYS.length == 0) {
            return false;
        }
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(this);
        }
        long now = System.nanoTime();
        if (Double.isNaN(lastX) || Travel.jumped(lastX, lastY, lastZ, viewer.posX, viewer.posY, viewer.posZ)) {
            jump++;
            jumpedAt = now;
            next = 0;
            Quadrature.LOGGER.info(String.format("Travel capture: jump %d to %.1f %.1f %.1f", jump, viewer.posX,
                viewer.posY, viewer.posZ));
        }
        lastX = viewer.posX;
        lastY = viewer.posY;
        lastZ = viewer.posZ;
        if (next < DELAYS.length && (now - jumpedAt) / 1_000_000L >= DELAYS[next]) {
            due = String.format("quadrature-travel-%02d-%05dms.png", jump, DELAYS[next]);
            Quadrature.LOGGER.info("Travel capture: {} {} ms after the jump", due, (now - jumpedAt) / 1_000_000L);
            next++;
            return true;
        }
        return false;
    }

    /** A world left: the next frame is a first frame again. */
    void forget() {
        lastX = Double.NaN;
    }

    @SubscribeEvent
    public void worldDrawn(RenderWorldLastEvent event) {
        if (due == null) {
            return;
        }
        String name = due;
        due = null;
        Minecraft minecraft = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(minecraft.gameDir, name, minecraft.displayWidth, minecraft.displayHeight,
            minecraft.getFramebuffer());
    }
}
