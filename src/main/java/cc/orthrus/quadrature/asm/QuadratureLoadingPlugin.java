package cc.orthrus.quadrature.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/** Registers the call rewriter before the game's classes load. */
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
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
