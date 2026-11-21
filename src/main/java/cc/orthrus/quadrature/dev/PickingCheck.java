package cc.orthrus.quadrature.dev;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.depth.DepthCalls;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.vecmath.Vector3f;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Whether a mod that reads depth back to pick a block still picks the right one: behind
 * {@code -Dquadrature.check.picking=true}, once the player has been in a world for ten seconds, a screen of its
 * own is open for one frame, in which GregTech's world scene renderer (GTCEu: Reloaded's
 * {@code ImmediateWorldSceneRenderer}, reached by reflection) draws a small scene of stone at 25 pointer
 * positions. That renderer reads the depth under the pointer with {@code glReadPixels}, unprojects it through the
 * matrices it reads back, and traces a ray to name the hovered block, all in its own classes, which the depth
 * call rewriter translates. For each pointer the check logs the block it named and the point it unprojected,
 * against the block and the surface point found on the CPU along the same ray from the same camera.
 *
 * <p>Run once with reversed depth and once with {@code -Dquadrature.reversedDepth=false}: the two logs must
 * agree, and the points lie on the surfaces. Without GTCEu: Reloaded installed it says so and does nothing.
 */
public final class PickingCheck {

    public static final boolean ON = Boolean.getBoolean("quadrature.check.picking");

    private static final String RENDERER = "gregtech.client.renderer.scene.ImmediateWorldSceneRenderer";
    private static final String SCENE_RENDERER = "gregtech.client.renderer.scene.WorldSceneRenderer";
    private static final String WORLD = "gregtech.client.utils.TrackedDummyWorld";
    private static final String BLOCK_INFO = "gregtech.api.util.BlockInfo";
    private static final int GRID = 5;
    private static final float FOV = 60f;
    private static final float NEAR = 0.1f;
    private static final float FAR = 10000f;

    private static final List<BlockPos> BLOCKS = List.of(
        new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0),
        new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(2, 0, 1),
        new BlockPos(0, 0, 2), new BlockPos(1, 0, 2), new BlockPos(2, 0, 2),
        new BlockPos(1, 1, 1), new BlockPos(1, 2, 1), new BlockPos(2, 1, 0));
    private static final float[] EYE = {5.5f, 4.5f, 6.0f};
    private static final float[] LOOK_AT = {1.5f, 1.0f, 1.5f};

    private int ticksInWorld;
    private boolean done;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null || minecraft.player == null || minecraft.currentScreen != null) {
            ticksInWorld = 0;
            return;
        }
        if (++ticksInWorld < 200) {
            return;
        }
        done = true;
        try {
            Class.forName(RENDERER);
        } catch (ClassNotFoundException | LinkageError absent) {
            Quadrature.LOGGER.warn("Picking check: GTCEu: Reloaded's world scene renderer is not installed; nothing is checked.");
            return;
        }
        minecraft.displayGuiScreen(new Screen());
    }

    /** The screen the scene is drawn in, for one frame. */
    private static final class Screen extends GuiScreen {
        private boolean drawn;

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            if (drawn) {
                return;
            }
            drawn = true;
            try {
                check(width, height);
            } catch (ReflectiveOperationException | RuntimeException failed) {
                Quadrature.LOGGER.error("Picking check could not run.", failed);
            }
            mc.displayGuiScreen(null);
        }
    }

    private static void check(int scaledWidth, int scaledHeight) throws ReflectiveOperationException {
        String depth = DepthCalls.active() ? "reversed depth" : "conventional depth";
        Class<?> worldClass = Class.forName(WORLD);
        Object world = worldClass.getConstructor().newInstance();
        Class<?> infoClass = Class.forName(BLOCK_INFO);
        Constructor<?> info = infoClass.getConstructor(IBlockState.class);
        Method addBlock = worldClass.getMethod("addBlock", BlockPos.class, infoClass);
        for (BlockPos pos : BLOCKS) {
            addBlock.invoke(world, pos, info.newInstance(Blocks.STONE.getDefaultState()));
        }
        Class<?> sceneClass = Class.forName(SCENE_RENDERER);
        Object renderer = Class.forName(RENDERER).getConstructor(net.minecraft.world.World.class).newInstance(world);
        sceneClass.getMethod("addRenderedBlocks", java.util.Collection.class).invoke(renderer, BLOCKS);
        sceneClass.getMethod("setCameraLookAt", Vector3f.class, Vector3f.class, Vector3f.class).invoke(renderer,
            new Vector3f(EYE), new Vector3f(LOOK_AT), new Vector3f(0, 1, 0));
        Consumer<RayTraceResult> looking = result -> { };
        sceneClass.getMethod("setOnLookingAt", Consumer.class).invoke(renderer, looking);
        Method unProject = sceneClass.getMethod("unProject", int.class, int.class);
        Method render = sceneClass.getMethod("render", float.class, float.class, float.class, float.class, int.class, int.class);
        Method lastTrace = sceneClass.getMethod("getLastTraceResult");

        Minecraft minecraft = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(minecraft);
        double sw = resolution.getScaledWidth();
        double sh = resolution.getScaledHeight();
        int dw = minecraft.displayWidth;
        int dh = minecraft.displayHeight;
        int x = scaledWidth / 2 - 100;
        int y = scaledHeight / 2 - 75;
        int w = 200;
        int h = 150;
        // The rectangle in window pixels, as the renderer works it out.
        int windowWidth = (int) (w / sw * dw);
        int windowHeight = (int) (h / sh * dh);
        int windowX = (int) (x / sw * dw);
        int windowY = dh - (int) (y / sh * dh) - windowHeight;
        float aspect = windowWidth / (windowHeight * 1.0f);

        int[] pointer = new int[2];
        Vector3f[] unprojected = new Vector3f[1];
        Consumer<Object> after = scene -> {
            try {
                unprojected[0] = (Vector3f) unProject.invoke(null, pointer[0], pointer[1]);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException(failed);
            }
        };
        sceneClass.getMethod("setAfterWorldRender", Consumer.class).invoke(renderer, after);

        int agree = 0;
        int onBlocks = 0;
        double errorSum = 0;
        double worst = 0;
        List<String> disagreements = new ArrayList<>();
        // Pointers: a grid over the rectangle, most of it background, and the centers of each block's top face and of
        // its two faces toward the camera, projected by the camera into the screen's coordinates.
        List<int[]> pointers = new ArrayList<>();
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                pointers.add(new int[] {x + (int) Math.round(w * (0.1 + 0.8 * i / (GRID - 1))),
                    y + (int) Math.round(h * (0.1 + 0.8 * j / (GRID - 1)))});
            }
        }
        for (BlockPos pos : BLOCKS) {
            double[][] faces = {{pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5},
                {pos.getX() + 1, pos.getY() + 0.5, pos.getZ() + 0.5}, {pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 1}};
            for (double[] face : faces) {
                double[] window = project(face, windowX, windowY, windowWidth, windowHeight, aspect);
                pointers.add(new int[] {(int) Math.round(window[0] / dw * sw), (int) Math.round((dh - window[1]) / dh * sh)});
            }
        }
        for (int n = 0; n < pointers.size(); n++) {
            {
                int mx = pointers.get(n)[0];
                int my = pointers.get(n)[1];
                int i = n % GRID;
                int j = n / GRID;
                pointer[0] = (int) (mx / sw * dw);
                pointer[1] = dh - (int) (my / sh * dh);
                unprojected[0] = null;
                render.invoke(renderer, (float) x, (float) y, (float) w, (float) h, mx, my);
                RayTraceResult result = (RayTraceResult) lastTrace.invoke(renderer);
                BlockPos got = result == null || result.typeOfHit != RayTraceResult.Type.BLOCK ? null : result.getBlockPos();
                float[] ray = ray(pointer[0], pointer[1], windowX, windowY, windowWidth, windowHeight, aspect);
                double t = nearestHit(ray);
                BlockPos expected = t < 0 ? null : blockAt(ray, t);
                boolean same = expected == null ? got == null : expected.equals(got);
                double error = Double.NaN;
                if (expected != null && unprojected[0] != null) {
                    double px = EYE[0] + ray[0] * t - unprojected[0].x;
                    double py = EYE[1] + ray[1] * t - unprojected[0].y;
                    double pz = EYE[2] + ray[2] * t - unprojected[0].z;
                    error = Math.sqrt(px * px + py * py + pz * pz);
                    errorSum += error;
                    worst = Math.max(worst, error);
                    onBlocks++;
                }
                if (same) {
                    agree++;
                } else {
                    disagreements.add(String.format("pointer %d,%d (window %d,%d): expected %s, named %s", i, j, pointer[0],
                        pointer[1], expected, got));
                }
                Quadrature.LOGGER.info(String.format("Picking check, %s, pointer %d,%d (window %d,%d): expected %s at %.3f, "
                        + "named %s; unprojected point %s off the surface by %.4f", depth, i, j, pointer[0], pointer[1],
                    expected, t, got, unprojected[0], error));
            }
        }
        Quadrature.LOGGER.info(String.format("Picking check, %s: %d of %d pointers named the expected block; over the %d on "
                + "blocks the unprojected point lies %.4f from the surface on average, %.4f at worst%s", depth, agree,
            pointers.size(), onBlocks, onBlocks == 0 ? 0 : errorSum / onBlocks, worst,
            disagreements.isEmpty() ? "" : "; differing: " + String.join("; ", disagreements)));
    }

    /** A point's position in the window, by the renderer's camera. */
    private static double[] project(double[] p, int vx, int vy, int vw, int vh, float aspect) {
        double tan = Math.tan(Math.toRadians(FOV / 2));
        double[] f = normalize(LOOK_AT[0] - EYE[0], LOOK_AT[1] - EYE[1], LOOK_AT[2] - EYE[2]);
        double[] s = normalize(-f[2], 0, f[0]);
        double[] u = {s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2], s[0] * f[1] - s[1] * f[0]};
        double ex = p[0] - EYE[0];
        double ey = p[1] - EYE[1];
        double ez = p[2] - EYE[2];
        double forward = ex * f[0] + ey * f[1] + ez * f[2];
        double ndcX = (ex * s[0] + ey * s[1] + ez * s[2]) / (forward * tan * aspect);
        double ndcY = (ex * u[0] + ey * u[1] + ez * u[2]) / (forward * tan);
        return new double[] {vx + (ndcX + 1) / 2 * vw, vy + (ndcY + 1) / 2 * vh};
    }

    /** The unit direction of the ray from the eye through a window position, by the renderer's camera. */
    private static float[] ray(int wx, int wy, int vx, int vy, int vw, int vh, float aspect) {
        double ndcX = 2.0 * (wx - vx) / vw - 1;
        double ndcY = 2.0 * (wy - vy) / vh - 1;
        double tan = Math.tan(Math.toRadians(FOV / 2));
        double[] f = normalize(LOOK_AT[0] - EYE[0], LOOK_AT[1] - EYE[1], LOOK_AT[2] - EYE[2]);
        double[] s = normalize(f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0);
        double[] u = {s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2], s[0] * f[1] - s[1] * f[0]};
        double ex = ndcX * tan * aspect;
        double ey = ndcY * tan;
        double[] d = normalize(s[0] * ex + u[0] * ey + f[0], s[1] * ex + u[1] * ey + f[1], s[2] * ex + u[2] * ey + f[2]);
        return new float[] {(float) d[0], (float) d[1], (float) d[2]};
    }

    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }

    /** The distance along the ray to the nearest block's box, or -1 where it meets none. */
    private static double nearestHit(float[] ray) {
        double best = -1;
        for (BlockPos pos : BLOCKS) {
            double near = Double.NEGATIVE_INFINITY;
            double far = Double.POSITIVE_INFINITY;
            int[] min = {pos.getX(), pos.getY(), pos.getZ()};
            boolean miss = false;
            for (int axis = 0; axis < 3 && !miss; axis++) {
                if (Math.abs(ray[axis]) < 1e-12) {
                    miss = EYE[axis] < min[axis] || EYE[axis] > min[axis] + 1;
                    continue;
                }
                double a = (min[axis] - EYE[axis]) / ray[axis];
                double b = (min[axis] + 1 - EYE[axis]) / ray[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
            }
            if (!miss && near <= far && far > 0 && (best < 0 || near < best)) {
                best = near;
            }
        }
        return best;
    }

    /** The block whose box the ray enters at distance t. */
    private static BlockPos blockAt(float[] ray, double t) {
        // A hair past the entry point, inside the block entered.
        double px = EYE[0] + ray[0] * (t + 1e-4);
        double py = EYE[1] + ray[1] * (t + 1e-4);
        double pz = EYE[2] + ray[2] * (t + 1e-4);
        return new BlockPos(Math.floor(px), Math.floor(py), Math.floor(pz));
    }
}
