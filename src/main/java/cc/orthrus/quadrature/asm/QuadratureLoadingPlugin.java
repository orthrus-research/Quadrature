package cc.orthrus.quadrature.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/** Registers the call rewriter and applies the stored tiers before the game's classes load. */
@IFMLLoadingPlugin.Name("Quadrature")
@IFMLLoadingPlugin.TransformerExclusions("cc.orthrus.quadrature.asm.")
public final class QuadratureLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {"cc.orthrus.quadrature.asm.DepthCallRewriter"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        Object gameDir = data.get("mcLocation");
        TierStartup.apply(gameDir instanceof java.io.File ? (java.io.File) gameDir : null);
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
