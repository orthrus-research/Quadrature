package cc.orthrus.quadrature.terrain;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;

import net.minecraft.world.World;

/** What Quadrature asks of Cubature about a world. Loaded only where Cubature is installed. */
final class CubicWorlds {

    private CubicWorlds() {
    }

    static boolean isCubic(World world) {
        return world instanceof ICubicWorld && ((ICubicWorld) world).isCubicWorld();
    }

    /** The loaded cube at these cube coordinates, or null. */
    static ICube loadedCube(World world, int x, int y, int z) {
        return ((ICubicWorld) world).getCubeCache().getLoadedCube(x, y, z);
    }
}
