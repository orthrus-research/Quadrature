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

        /** One line for the timing log. */
        String summary();
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
    }

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
        return drawer == null ? null : drawer.summary();
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
