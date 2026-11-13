package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.ForeignRenderer;

import java.util.List;

/** The renderers Quadrature recognizes and does not run beside. */
public final class ForeignRenderers {

    private ForeignRenderers() {
    }

    public static final List<ForeignRenderer> KNOWN = List.of(
        // OptiFine is a tweaker, not a mod: only its classes identify it.
        new ForeignRenderer("OptiFine", null, "optifine/OptiFineForgeTweaker.class"),
        new ForeignRenderer("Celeritas", "celeritas", null),
        new ForeignRenderer("Demonica", "demonica", null),
        new ForeignRenderer("Actinium", "actinium", null),
        // Relictium and the other Vintagium forks keep Vintagium's mod ID.
        new ForeignRenderer("Vintagium or Relictium", "vintagium", null),
        new ForeignRenderer("Nothirium", "nothirium", null));
}
