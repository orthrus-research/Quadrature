package cc.orthrus.quadrature.mixin;

import com.cleanroommc.cleanmix.ConditionalMixin;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

/**
 * Registers Quadrature's Mixin configuration. CleanMix calls it from the JAR's {@code MixinConnector}
 * manifest attribute before the game's classes load; it touches no Quadrature class.
 */
public final class QuadratureMixinConnector implements IMixinConnector {

    @Override
    public void connect() {
        ConditionalMixin.of("quadrature.mixins.json").register();
    }
}
