package cc.orthrus.quadrature.dev;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.depth.DepthCalls;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.vecmath.Vector3f;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Whether a mod that reads depth back to pick a block still picks the right one: behind
 * {@code -Dquadrature.check.picking=true}, once the player has been in a world for ten seconds, a screen of its
 * own is open for one frame, in which each installed preview renderer that picks by depth draws the same small
 * scene of stone, reached by reflection: GregTech's world scene renderer (GTCEu: Reloaded's
 * {@code ImmediateWorldSceneRenderer}) and ModularUI's schema renderer ({@code SchemaRenderer}). Each reads the
 * depth under a pointer with {@code glReadPixels}, unprojects it through the matrices it reads back, and traces a
 * ray to name the hovered block, all in its own classes, which the depth call rewriter translates. For each
 * pointer the check logs the block it named and the point it unprojected, against the block and the surface point
 * found on the CPU along the same ray from the same camera.
 *
 * <p>Run once with reversed depth and once with {@code -Dquadrature.reversedDepth=false}: the two logs must
 * agree, and the points lie on the surfaces. A renderer whose mod is not installed is reported and skipped.
 */
public final class PickingCheck {

    public static final boolean ON = Boolean.getBoolean("quadrature.check.picking");

    private static final String RENDERER = "gregtech.client.renderer.scene.ImmediateWorldSceneRenderer";
    private static final String SCENE_RENDERER = "gregtech.client.renderer.scene.WorldSceneRenderer";
    private static final String WORLD = "gregtech.client.utils.TrackedDummyWorld";
    private static final String BLOCK_INFO = "gregtech.api.util.BlockInfo";
    private static final String SCHEMA_RENDERER = "com.cleanroommc.modularui.utils.fakeworld.SchemaRenderer";
    private static final String BASE_SCHEMA_RENDERER = "com.cleanroommc.modularui.utils.fakeworld.BaseSchemaRenderer";
    private static final String SCHEMA_WORLD = "com.cleanroommc.modularui.utils.fakeworld.SchemaWorld";
    private static final String SCHEMA = "com.cleanroommc.modularui.utils.fakeworld.ISchema";
    private static final String CAMERA = "com.cleanroommc.modularui.utils.fakeworld.Camera";
    private static final String GUI_CONTEXT = "com.cleanroommc.modularui.screen.viewport.GuiContext";
    private static final String WIDGET_THEME = "com.cleanroommc.modularui.theme.WidgetTheme";
    /** The side of the framebuffer ModularUI's schema renderer draws into by default. */
    private static final int SCHEMA_SIDE = 1080;
    private static final int GRID = 5;
    /** Both renderers' vertical field of view in degrees; both look along +y up. */
    private static final float FOV = 60f;

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
        minecraft.displayGuiScreen(new Screen());
    }

    /** The screen the scenes are drawn in, for one frame. */
    private static final class Screen extends GuiScreen {
        private boolean drawn;

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            if (drawn) {
                return;
            }
            drawn = true;
            if (present(RENDERER, "GTCEu: Reloaded's world scene renderer")) {
                try {
                    checkGregTech(width, height);
                } catch (ReflectiveOperationException | RuntimeException failed) {
                    Quadrature.LOGGER.error("Picking check could not run GregTech's world scene renderer.", failed);
                }
            }
            if (present(SCHEMA_RENDERER, "ModularUI's schema renderer")) {
                try {
                    checkModularUi(width, height);
                } catch (ReflectiveOperationException | RuntimeException failed) {
                    Quadrature.LOGGER.error("Picking check could not run ModularUI's schema renderer.", failed);
                }
            }
            mc.displayGuiScreen(null);
        }
    }

    private static boolean present(String className, String name) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            Quadrature.LOGGER.warn("Picking check: {} is not installed; it is not checked.", name);
            return false;
        }
    }

    private static String depthMode() {
        return DepthCalls.active() ? "reversed depth" : "conventional depth";
    }

    /** One renderer's results against the CPU's, pointer by pointer. */
    private static final class Tally {
        private final String renderer;
        private final String depth = depthMode();
        private final List<String> disagreements = new ArrayList<>();
        private int pointers;
        private int agree;
        private int onBlocks;
        private double errorSum;
        private double worst;

        Tally(String renderer) {
            this.renderer = renderer;
        }

        /**
         * Sets what the renderer gave for the window position {@code (wx, wy)} beside the first hit of the same ray on
         * the CPU.
         */
        void add(int wx, int wy, float[] ray, BlockPos got, double[] unprojected) {
            pointers++;
            double t = nearestHit(ray);
            BlockPos expected = t < 0 ? null : blockAt(ray, t);
            double error = Double.NaN;
            if (expected != null && unprojected != null) {
                double px = EYE[0] + ray[0] * t - unprojected[0];
                double py = EYE[1] + ray[1] * t - unprojected[1];
                double pz = EYE[2] + ray[2] * t - unprojected[2];
                error = Math.sqrt(px * px + py * py + pz * pz);
                errorSum += error;
                worst = Math.max(worst, error);
                onBlocks++;
            }
            boolean same = expected == null ? got == null : expected.equals(got);
            if (same) {
                agree++;
            } else {
                disagreements.add(String.format("window %d,%d: expected %s, named %s", wx, wy, expected, got));
            }
            String point = unprojected == null ? "none"
                : String.format("(%.5f, %.5f, %.5f)", unprojected[0], unprojected[1], unprojected[2]);
            Quadrature.LOGGER.info(String.format("Picking check, %s, %s, pointer %d (window %d,%d): expected %s at %.3f, "
                + "named %s; unprojected point %s off the surface by %.4f", renderer, depth, pointers - 1, wx, wy, expected,
                t, got, point, error));
        }

        void report() {
            Quadrature.LOGGER.info(String.format("Picking check, %s, %s: %d of %d pointers named the expected block; over "
                    + "the %d on blocks the unprojected point lies %.4f from the surface on average, %.4f at worst%s",
                renderer, depth, agree, pointers, onBlocks, onBlocks == 0 ? 0 : errorSum / onBlocks, worst,
                disagreements.isEmpty() ? "" : "; differing: " + String.join("; ", disagreements)));
        }
    }

    /**
     * Window positions to point at in a viewport: a grid over it, most of it background, and the centers of each
     * block's top face and of its two faces toward the camera.
     */
    private static List<int[]> windowPointers(int vx, int vy, int vw, int vh) {
        float aspect = vw / (vh * 1.0f);
        List<int[]> pointers = new ArrayList<>();
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                pointers.add(new int[] {vx + (int) Math.round(vw * (0.1 + 0.8 * i / (GRID - 1))),
                    vy + (int) Math.round(vh * (0.1 + 0.8 * j / (GRID - 1)))});
            }
        }
        for (BlockPos pos : BLOCKS) {
            double[][] faces = {{pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5},
                {pos.getX() + 1, pos.getY() + 0.5, pos.getZ() + 0.5}, {pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 1}};
            for (double[] face : faces) {
                double[] window = project(face, vx, vy, vw, vh, aspect);
                pointers.add(new int[] {(int) Math.round(window[0]), (int) Math.round(window[1])});
            }
        }
        return pointers;
    }

    private static void checkGregTech(int scaledWidth, int scaledHeight) throws ReflectiveOperationException {
        Class<?> worldClass = Class.forName(WORLD);
        Object world = worldClass.getConstructor().newInstance();
        Class<?> infoClass = Class.forName(BLOCK_INFO);
        Constructor<?> info = infoClass.getConstructor(IBlockState.class);
        Method addBlock = worldClass.getMethod("addBlock", BlockPos.class, infoClass);
        for (BlockPos pos : BLOCKS) {
            addBlock.invoke(world, pos, info.newInstance(Blocks.STONE.getDefaultState()));
        }
        Class<?> sceneClass = Class.forName(SCENE_RENDERER);
        Object renderer = Class.forName(RENDERER).getConstructor(World.class).newInstance(world);
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

        Tally tally = new Tally("GregTech's world scene renderer");
        for (int[] window : windowPointers(windowX, windowY, windowWidth, windowHeight)) {
            // The renderer takes the pointer in the screen's scaled coordinates and works the window position out
            // itself; the window position the check uses is the one it works out.
            int mx = (int) Math.round(window[0] / (double) dw * sw);
            int my = (int) Math.round((dh - window[1]) / (double) dh * sh);
            pointer[0] = (int) (mx / sw * dw);
            pointer[1] = dh - (int) (my / sh * dh);
            unprojected[0] = null;
            render.invoke(renderer, (float) x, (float) y, (float) w, (float) h, mx, my);
            RayTraceResult result = (RayTraceResult) lastTrace.invoke(renderer);
            Vector3f point = unprojected[0];
            tally.add(pointer[0], pointer[1], ray(pointer[0], pointer[1], windowX, windowY, windowWidth, windowHeight, aspect),
                named(result), point == null ? null : new double[] {point.x, point.y, point.z});
        }
        tally.report();
    }

    /**
     * ModularUI's schema renderer draws into a framebuffer of its own, {@value #SCHEMA_SIDE} pixels a side, through
     * a camera set by a callback; its after-render callback runs while that framebuffer and the camera's matrices
     * are still current, and there the check asks the renderer, for each pointer, for the point it unprojects
     * ({@code screenToWorldPos}) and the block its trace from that point names ({@code rayTrace}), as a pointer over
     * its widget would.
     */
    private static void checkModularUi(int scaledWidth, int scaledHeight) throws ReflectiveOperationException {
        Object schemaWorld = Class.forName(SCHEMA_WORLD).getConstructor().newInstance();
        for (BlockPos pos : BLOCKS) {
            ((World) schemaWorld).setBlockState(pos, Blocks.STONE.getDefaultState(), 3);
        }
        Class<?> rendererClass = Class.forName(SCHEMA_RENDERER);
        Class<?> baseClass = Class.forName(BASE_SCHEMA_RENDERER);
        Object renderer = rendererClass.getConstructor(Class.forName(SCHEMA)).newInstance(schemaWorld);
        // The check traces itself, from inside the callback, rather than from the context's pointer.
        rendererClass.getMethod("rayTracing", boolean.class).invoke(renderer, false);
        Method setCamera = Class.forName(CAMERA).getMethod("setPosAndLookAt", float.class, float.class, float.class,
            float.class, float.class, float.class);
        BiConsumer<Object, Object> camera = (cam, schema) -> {
            try {
                setCamera.invoke(cam, EYE[0], EYE[1], EYE[2], LOOK_AT[0], LOOK_AT[1], LOOK_AT[2]);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException(failed);
            }
        };
        rendererClass.getMethod("cameraFunc", BiConsumer.class).invoke(renderer, camera);
        Method screenToWorld = baseClass.getDeclaredMethod("screenToWorldPos", float.class, float.class);
        screenToWorld.setAccessible(true);
        Method rayTrace = baseClass.getDeclaredMethod("rayTrace", int.class, int.class, int.class, int.class);
        rayTrace.setAccessible(true);

        int side = SCHEMA_SIDE;
        List<int[]> pointers = windowPointers(0, 0, side, side);
        List<double[]> points = new ArrayList<>();
        List<RayTraceResult> results = new ArrayList<>();
        Consumer<Object> after = drawn -> {
            try {
                for (int[] window : pointers) {
                    // The renderer reads the depth at (fx * side, side - fy * side), both truncated; a pixel's center
                    // as the fraction gives exactly the window position wanted. Its trace takes the pointer as a
                    // whole number over the widget's size, here twice the side, for the same fraction.
                    int down = side - window[1];
                    Object point = screenToWorld.invoke(drawn, (window[0] + 0.5f) / side, (down + 0.5f) / side);
                    points.add(point == null ? null : joml(point));
                    results.add((RayTraceResult) rayTrace.invoke(drawn, 2 * window[0] + 1, 2 * down + 1, 2 * side, 2 * side));
                }
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException(failed);
            }
        };
        rendererClass.getMethod("afterRender", Consumer.class).invoke(renderer, after);
        Object context = Class.forName(GUI_CONTEXT).getConstructor().newInstance();
        baseClass.getMethod("draw", Class.forName(GUI_CONTEXT), int.class, int.class, int.class, int.class,
            Class.forName(WIDGET_THEME)).invoke(renderer, context, scaledWidth / 2 - 60, scaledHeight / 2 - 60, 120, 120, null);

        Tally tally = new Tally("ModularUI's schema renderer");
        if (results.size() != pointers.size()) {
            Quadrature.LOGGER.error("Picking check: ModularUI's schema renderer called back for {} of {} pointers.",
                results.size(), pointers.size());
            return;
        }
        for (int n = 0; n < pointers.size(); n++) {
            int[] window = pointers.get(n);
            tally.add(window[0], window[1], ray(window[0], window[1], 0, 0, side, side, 1f), named(results.get(n)),
                points.get(n));
        }
        tally.report();
    }

    private static BlockPos named(RayTraceResult result) {
        return result == null || result.typeOfHit != RayTraceResult.Type.BLOCK ? null : result.getBlockPos();
    }

    private static double[] joml(Object vector) throws ReflectiveOperationException {
        Class<?> type = vector.getClass();
        return new double[] {type.getField("x").getFloat(vector), type.getField("y").getFloat(vector),
            type.getField("z").getFloat(vector)};
    }

    /** A point's position in the window, by the renderers' camera. */
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

    /** The unit direction of the ray from the eye through a window position, by the renderers' camera. */
    private static float[] ray(int wx, int wy, int vx, int vy, int vw, int vh, float aspect) {
        double ndcX = 2.0 * (wx - vx) / vw - 1;
        double ndcY = 2.0 * (wy - vy) / vh - 1;
        double tan = Math.tan(Math.toRadians(FOV / 2));
        double[] f = normalize(LOOK_AT[0] - EYE[0], LOOK_AT[1] - EYE[1], LOOK_AT[2] - EYE[2]);
        double[] s = normalize(-f[2], 0, f[0]);
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
