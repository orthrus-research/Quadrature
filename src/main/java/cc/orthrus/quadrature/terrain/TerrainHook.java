package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.terrain.CoverageMap;
import cc.orthrus.quadrature.core.terrain.SearchEdge;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.view.ShadowMaps;
import cc.orthrus.quadrature.core.view.View;

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
