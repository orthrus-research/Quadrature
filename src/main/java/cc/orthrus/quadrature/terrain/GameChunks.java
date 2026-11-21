package cc.orthrus.quadrature.terrain;

import net.minecraft.world.World;

/**
 * The game's own chunk system in a world own terrain draws (PLAN item 12g): its terrain setup, its search over render
 * chunks, its chunk rebuilds and the render chunks themselves. In such a world Quadrature takes all of it over: the
 * game's terrain setup and chunk updates do nothing, its grid of render chunks shrinks to one, its rebuild buffers to a
 * single set, and entities and block entities are drawn for the cubes Quadrature's own view sees ({@link EntityPass}).
 * {@code -Dquadrature.terrain.gameChunks=keep} keeps the game's chunk system running beside own terrain, as it ran
 * before, for comparison and for the diagnostics that read the game's selection.
 *
 * <p>Decided per world, when the game loads its renderers for it, which is before the cube source attaches to that
 * world: a world is taken over where own terrain is asked for and the world is cubic. Names nothing of Cubature's
 * itself, so it loads where Cubature is absent; {@link CubicWorlds} is reached only where own terrain is asked for,
 * which it is not without Cubature.
 */
public final class GameChunks {

    /** Whether the game's chunk system keeps running beside own terrain ({@code quadrature.terrain.gameChunks=keep}). */
    public static final boolean KEEP = "keep".equals(System.getProperty("quadrature.terrain.gameChunks"));

    private static final boolean TAKES_OVER = !KEEP && TerrainHook.requested();

    private GameChunks() {
    }

    /** Whether Quadrature takes over the game's chunk system in every cubic world: own terrain asked for, and not kept. */
    public static boolean takesOverCubicWorlds() {
        return TAKES_OVER;
    }

    /** Whether Quadrature takes over the game's chunk system in this world. Asked every frame; cheap. */
    public static boolean takesOver(World world) {
        return TAKES_OVER && world != null && CubicWorlds.isCubic(world);
    }
}
