package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.material.MaterialTable;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.terrain.CoverageMap;
import cc.orthrus.quadrature.core.terrain.SearchEdge;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.view.ShadingTargets;
import cc.orthrus.quadrature.core.view.ShadowMaps;
import cc.orthrus.quadrature.core.view.View;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

/**
 * The one place the rest of the mod reaches own terrain through. It names nothing of Cubature's, so
 * it loads where Cubature is absent; the terrain classes themselves load only once a cube source
 * exists.
 */
public final class TerrainHook {

    /** What own terrain offers the frame. */
    public interface Drawer {
        /** Whether the opaque layers of the current world are drawn from own meshes. */
        boolean active();

        /** Fills and returns the player's view for the frame about to be drawn. */
        View mainView(double partialTicks, Entity viewer);

        /** Draws one opaque layer for a view. */
        void draw(BlockRenderLayer layer, View view);

        /** What is published of the depth drawn along the light, or null when it is not drawn. */
        ShadowMaps shadowMaps();

        /** The world as lighting needs it, or null when it is not kept. */
        LightScene lightScene();

        /** The light scene as a shader reads it, or null when it is not copied to the GPU. */
        PublishedScene publishedScene();

        /** For each chunk column of the box, whether its surface is loaded and meshed; null when not worked out. */
        CoverageMap coverage();

        /** Whether the player's view's search came to the edge of the box. */
        SearchEdge searchEdge();

        /** The targets the opaque terrain pass writes for shading, or null when they are not drawn. */
        ShadingTargets shadingTargets();

        /** What every block state's surface is like, or null when no table is kept. */
        MaterialTable materials();

        /** The number of a state's row of the table, the row filled if the state was not met before. */
        int material(IBlockState state);

        /** The frame's index: how many world passes own terrain has drawn. */
        long frame();

        /** The buffer the material table lies in, brought up to the table's version; 0 where there is none. */
        int materialsBuffer();

        /** One line for the timing log. */
        String summary();

        /** Writes the images that waited for the shading stage to run. */
        default void writeImagesDue() {
        }

        /** Whether images wait to be written once the shading stage has run in this frame. */
        default boolean imagesDue() {
            return false;
        }

        /** How many cubes the light's view drew when it was last drawn, or -1 where that is not known. */
        default int lightViewCubes() {
            return -1;
        }

        /** A diagnostic: what near terrain knows of the cube holding a world place, as one line's part. */
        default String cubeReport(double x, double y, double z) {
            return "no near terrain";
        }

        /** What the tier benchmark draws again, or null where near terrain offers nothing to it. */
        default Probes probes() {
            return null;
        }
    }

    /**
     * What the tier benchmark asks of near terrain (D22): whether it has caught up, and the frame's own
     * draws issued again. Called on the client thread, only from inside {@link #afterOpaque}'s callback.
     */
    public interface Probes {
        /** How many cubes wait to be meshed or sorted; 0 once near terrain has caught up. */
        int waiting();

        /**
         * How many meshes near terrain holds. Nothing waits in a place whose cubes have not arrived yet, so
         * caught up means this has stopped changing as well.
         */
        int held();

        /**
         * Draws one opaque layer of the frame's view again, with the commands and records already handed to
         * the driver this frame, into whatever framebuffer is bound, under the state that is set. Nothing is
         * counted, searched or written for shading.
         *
         * @return false where the frame's draws cannot be issued again: drawn by a call a cube
         */
        boolean opaqueAgain(BlockRenderLayer layer);

        /**
         * Draws the light view's wide box whole into its scratch layer, the way the light's depth check does,
         * where no box is being drawn into the scratch over frames. {@code before} runs after the cubes are
         * chosen and the commands handed to the driver, just before the first draw, and {@code after} just
         * after the last, so that only the drawing lies between them.
         *
         * @return false where the light's depth is off, or its scratch is in use
         */
        boolean lightAgain(Runnable before, Runnable after);
    }

    private static Runnable afterOpaque;

    /** The tier benchmark's probes, or null where own terrain is off or offers none. */
    public static Probes probes() {
        return drawer == null ? null : drawer.probes();
    }

    /**
     * Sets what runs once a frame after near terrain's last opaque layer is drawn, under the state the game
     * set for that layer; null for nothing. For the tier benchmark: it puts back what it changes.
     */
    public static void afterOpaque(Runnable callback) {
        afterOpaque = callback;
    }

    private static Drawer drawer;
    private static View mainView;

    private TerrainHook() {
    }

    static void install(Drawer installed) {
        drawer = installed;
    }

    public static boolean active() {
        return drawer != null && drawer.active();
    }

    public static void draw(BlockRenderLayer layer, double partialTicks, Entity viewer) {
        // The game draws the solid layer first: that is where a frame's view is taken.
        if (layer == BlockRenderLayer.SOLID || mainView == null) {
            fadeBandInUse = null;
            fadeOrigin = null;
            mainView = drawer.mainView(partialTicks, viewer);
        }
        drawer.draw(layer, mainView);
        if (afterOpaque != null && layer == BlockRenderLayer.CUTOUT) {
            afterOpaque.run();
        }
    }

    /**
     * What is published each frame of the depth drawn along the light: the texture, its samplers, and
     * each layer's matrix. Null when own terrain is off or the light's view is not drawn.
     */
    public static ShadowMaps shadowMaps() {
        return drawer == null ? null : drawer.shadowMaps();
    }

    /**
     * The world as lighting needs it, independent of any view: the cells of every meshed cube and what
     * each block state does to light. Null when own terrain is off or the scene is not kept. Read it on
     * the client thread.
     */
    public static LightScene lightScene() {
        return drawer == null ? null : drawer.lightScene();
    }

    /**
     * The light scene as a shader reads it: the buffers, the box they cover, and what was copied this
     * frame. Null when own terrain is off or the scene is not copied to the GPU.
     */
    public static PublishedScene publishedScene() {
        return drawer == null ? null : drawer.publishedScene();
    }

    /**
     * For each chunk column of the box near terrain holds cubes in, whether its surface is loaded and
     * meshed. Null when own terrain is off or coverage is not worked out.
     */
    public static CoverageMap coverage() {
        return drawer == null ? null : drawer.coverage();
    }

    /**
     * Whether the last search for what the player's view sees came to the edge of the box, and through
     * which of its faces. Null when own terrain is off.
     */
    public static SearchEdge searchEdge() {
        return drawer == null ? null : drawer.searchEdge();
    }

    /**
     * The four targets and the depth the opaque terrain pass writes for a shading stage to read. Null
     * when own terrain is off or the targets are not drawn.
     */
    public static ShadingTargets shadingTargets() {
        return drawer == null ? null : drawer.shadingTargets();
    }

    /**
     * What every block state's surface is like where no texture says: eight bytes a state. A state's
     * row is at the number {@link #material} gives, which is the game's own number for the state, the
     * block's identity and its variant, in 16 bits; the scene for lighting knows states by the same
     * number. A row holds the defaults until its state is first met. Null when own terrain is off or
     * the targets for shading are not drawn. Read and fill it on the client thread.
     */
    public static MaterialTable materials() {
        return drawer == null ? null : drawer.materials();
    }

    /**
     * The number of a state's row of {@link #materials}, the row filled from the list of kinds or the
     * rule if this is the first time the state is met: for something that draws surfaces near terrain
     * has not met. -1 when own terrain is off.
     */
    public static int material(IBlockState state) {
        return drawer == null ? -1 : drawer.material(state);
    }

    /**
     * The player's view as the frame's terrain was drawn through it: its projection and model-view
     * matrices, read from the driver when the first opaque layer began, column-major, and the origin
     * the model-view is relative to, which is the viewer's place and not the camera's. The camera
     * stands where the model-view sends to nothing: the origin moved by the matrix's own translation
     * undone, as {@code NearFade.origin} works it out. Null before the first frame. One instance,
     * filled again each frame: read it, do not keep it.
     */
    public static View mainView() {
        return mainView;
    }

    private static final cc.orthrus.quadrature.core.frame.TargetWriters targetWriters =
        new cc.orthrus.quadrature.core.frame.TargetWriters();

    /**
     * Adds something that writes into the targets for shading besides near terrain. It is called on
     * the client thread once in every world pass in which the targets were drawn, so that
     * {@code shadingTargets().drawnFrame() == frame()} inside the call, after near terrain's cutout
     * layer has written the targets and before a shading stage reads them, whether or not a stage is
     * on; with the game's two-colored stereo picture, once for each eye. Writers are called in the
     * order they were added. One added or removed from inside a call takes effect from the next
     * pass. A writer that throws is removed and logged once.
     *
     * <p>On entry the game's framebuffer is bound and the viewport is the window's; no program, no
     * vertex array and none of shader storage bindings 0 to 3 is bound; the depth test and depth
     * writes are on, as the game holds them in front of its entities. The writer binds
     * {@code shadingTargets().framebuffer()} and whatever else it needs, and puts back everything it
     * changed. Nothing that draws after it trusts a binding it could have changed.
     */
    public static void addTargetWriter(Runnable writer) {
        targetWriters.add(writer);
    }

    /** Removes a writer added by {@link #addTargetWriter}; from inside a call, from the next pass. */
    public static void removeTargetWriter(Runnable writer) {
        targetWriters.remove(writer);
    }

    /** For the frame's host, after the shading stage has run: writes the images that waited for it. */
    public static void writeImagesDue() {
        if (drawer != null) {
            drawer.writeImagesDue();
        }
    }

    /** Whether images wait to be written once the shading stage has run in this frame. */
    public static boolean imagesDue() {
        return drawer != null && drawer.imagesDue();
    }

    /** How many cubes the light's view drew when it was last drawn, or -1 where that is not known. */
    public static int lightViewCubes() {
        return drawer == null ? -1 : drawer.lightViewCubes();
    }

    /**
     * A diagnostic: what near terrain knows of the cube holding a world place: its key, whether it has
     * a mesh and where, the mesh's version, and whether each layer of the light's depth listed it when
     * last drawn, with the version that layer was drawn at.
     */
    public static String cubeReport(double x, double y, double z) {
        return drawer == null ? "no near terrain" : drawer.cubeReport(x, y, z);
    }

    /** Whether writers are to be called in this pass: there is one, and the targets were drawn in it. */
    public static boolean targetWritersDue() {
        if (targetWriters.size() == 0 || drawer == null) {
            return false;
        }
        ShadingTargets targets = drawer.shadingTargets();
        return targets != null && targets.texture(0) != 0 && targets.drawnFrame() == drawer.frame();
    }

    /**
     * Calls the writers. For the frame's host, at the boundary in front of the game's entities.
     *
     * @param before called before each writer with its place in the order, or null
     */
    public static void runTargetWriters(java.util.function.IntConsumer before) {
        writersFarEdge = 0;
        writersFrame = drawer == null ? -1 : drawer.frame();
        inWriters = true;
        try {
            targetWriters.run(before, (writer, thrown) -> cc.orthrus.quadrature.Quadrature.LOGGER.error(
                "A writer of the targets for shading threw and is removed: {}: {}", writer, thrown.toString()));
        } finally {
            inWriters = false;
        }
    }

    private static boolean inWriters;
    private static boolean edgeOutsideLogged;

    private static float writersFarEdge;
    private static long writersFrame = -1;

    /**
     * For a writer, from inside its call: the smallest horizontal distance in blocks from the eye,
     * along x or along z, to the far edge of what it wrote into the targets in this pass, which is
     * half the side of the square it covers around the eye. It holds for that world pass only. With
     * several writers the greatest is kept; with none, or nothing above 0 from any, near terrain's own
     * stands. A call from outside a writer's call is ignored and logged once.
     */
    public static void targetsEdge(float blocks) {
        if (!inWriters) {
            if (!edgeOutsideLogged) {
                edgeOutsideLogged = true;
                cc.orthrus.quadrature.Quadrature.LOGGER.warn("The targets' edge was given from outside a writer's call and is "
                    + "ignored; it is said once.");
            }
            return;
        }
        if (blocks > writersFarEdge) {
            writersFarEdge = blocks;
        }
    }

    /**
     * The smallest horizontal distance in blocks from the eye, along x or along z, to the far edge of
     * what was written into the targets in the current frame: near terrain's own, as {@code NearEdge}
     * measures it to the nearest side of the cubes that can be meshed, or the greatest a writer gave
     * through {@link #targetsEdge} in this frame's pass where that is farther. 0 means it is not
     * known: own terrain is off or no view has been taken.
     */
    public static float targetsFarEdge() {
        View view = mainView;
        if (drawer == null || view == null) {
            return 0;
        }
        double[] eye = cc.orthrus.quadrature.core.fade.NearFade.origin(view.originX(), view.originY(), view.originZ(),
            view.modelView());
        float near = cc.orthrus.quadrature.core.view.NearEdge.blocks(view.originCubeX(), view.originCubeZ(), view.across(),
            eye[0], eye[2]);
        return Math.max(writersFrame == drawer.frame() ? writersFarEdge : 0, near);
    }
    /**
     * The current frame's index: a count that moves by one each time own terrain begins a world pass,
     * which is once a frame but for the game's two-colored stereo picture, which draws the world twice.
     * {@code shadingTargets().drawnFrame()} is this count as it stood when the targets were last
     * drawn. -1 when own terrain is off.
     */
    public static long frame() {
        return drawer == null ? -1 : drawer.frame();
    }

    /**
     * The name of the storage buffer the material table lies in, eight bytes a state, for reading
     * only: the one the targets' stages read at shader storage binding 3. Asking brings it up to
     * {@code materials().version()}, rows filled through {@link #material} included, so ask after
     * the last row is filled and before drawing. 0 when own terrain is off or the targets are not drawn.
     * Call on the client thread with the graphics context current.
     */
    public static int materialsBuffer() {
        return drawer == null ? 0 : drawer.materialsBuffer();
    }
    private static float fadeStart = Float.NaN;
    private static float fadeEnd = Float.NaN;

    /**
     * Sets the band over which near terrain gives its pixels up to what is drawn beyond it, where
     * {@code -Dquadrature.terrain.fade=true}: at a horizontal distance from the view's position, the
     * greater of the distances along x and along z in blocks, of {@code start} near terrain shows
     * whole, and from {@code end} none of it. Between, it keeps the pixels the complement side of
     * {@code FadePattern} keeps at fade {@code (distance - start) / (end - start)}, frame 0; what
     * draws beyond is the primary side with the same fade. Not set, the band is the last 16 blocks
     * before the last chunk of the view distance.
     */
    public static void fadeBand(float start, float end) {
        fadeStart = start;
        fadeEnd = end;
    }

    private static float[] fadeBandInUse;
    private static double[] fadeOrigin;

    /** Told by the terrain stages each time they set the fade for a layer of the frame. */
    static void fadeDrawn(float[] band, double[] origin) {
        fadeBandInUse = band;
        fadeOrigin = origin;
        if (band != null && (band[0] != fadeLogged[0] || band[1] != fadeLogged[1])) {
            // Said once each time the band is another, so that a log shows the fade is on and where.
            fadeLogged[0] = band[0];
            fadeLogged[1] = band[1];
            cc.orthrus.quadrature.Quadrature.LOGGER.info("Near fade: on, near terrain whole to {} blocks and gone from {}, by the "
                + "greater of the distances along x and along z from {}.", band[0], band[1],
                origin == null ? "the eye" : String.format("%.1f, %.1f, %.1f", origin[0], origin[1], origin[2]));
        }
    }

    private static final float[] fadeLogged = {Float.NaN, Float.NaN};

    /**
     * The band the near fade was drawn with in the current frame: start and end in blocks. Null where
     * the fade is switched off, own terrain is off, or no opaque layer has been drawn yet this frame.
     * Read it after near terrain's opaque layers and before the next frame's.
     */
    public static float[] fadeBandInUse() {
        return fadeBandInUse;
    }

    /**
     * The place the near fade's distance was measured from in the current frame, x, y and z in the
     * world: where the eye of the view's model-view matrix stands, which is the camera and so, in a
     * view from behind, not the player. Null as {@link #fadeBandInUse}. The distance is the greater of
     * that along x and that along z.
     */
    public static double[] fadeOrigin() {
        return fadeOrigin;
    }

    /** The band's start as set, or not a number. */
    static float fadeStart() {
        return fadeStart;
    }

    static float fadeEnd() {
        return fadeEnd;
    }

    /** The timing log's line, or null when own terrain is off. */
    public static String summary() {
        if (drawer == null) {
            return null;
        }
        String line = drawer.summary();
        float[] band = fadeBandInUse;
        double[] origin = fadeOrigin;
        if (band != null && origin != null) {
            line += String.format("; near fade from %.0f to %.0f blocks, measured from %.1f, %.1f, %.1f", band[0], band[1], origin[0],
                origin[1], origin[2]);
        }
        return line;
    }

    /** Starts own terrain if it was asked for; call only where Cubature is installed. */
    public static void start() {
        Terrain.start();
    }

    /** Whether own terrain was asked for on the command line. */
    public static boolean requested() {
        return "own".equals(System.getProperty("quadrature.terrain"));
    }
}
