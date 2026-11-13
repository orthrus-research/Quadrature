package cc.orthrus.quadrature;

import net.minecraftforge.fml.common.Mod;

/** Mod entry point. Client only: a dedicated server never loads it. */
@Mod(modid = Quadrature.MOD_ID, name = "Quadrature", version = QuadratureBuild.VERSION,
    acceptedMinecraftVersions = "[1.12.2]",
    dependencies = "required-after:cleanroom@[" + QuadratureBuild.CLEANROOM + "]",
    clientSideOnly = true, acceptableRemoteVersions = "*")
public final class Quadrature {
    public static final String MOD_ID = "quadrature";
}
