package cc.orthrus.quadrature.terrain;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;

/**
 * What the game's renderer offers {@link EntityPass}: its own outline test and its count of entities drawn. The game's
 * renderer implements it, so it names nothing of Cubature's.
 */
public interface EntityPassHost {

    boolean quadrature$outlined(Entity entity, Entity viewer, ICamera camera);

    void quadrature$counted();
}
