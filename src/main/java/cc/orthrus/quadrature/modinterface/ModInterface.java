package cc.orthrus.quadrature.modinterface;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.world.World;

/**
 * What Quadrature reads of the render interface mods declare through (OrthLib's, D25; PLAN item P3-B2), in
 * Quadrature's own terms: declared layers, bloom kinds and emissive contributions, and the calls a provider makes
 * back. It names nothing of OrthLib's, whose classes need Java 26: the bridge that implements it is compiled apart
 * and loaded only where OrthLib is installed (D26). {@link #NONE} declares nothing.
 */
public interface ModInterface {

    /** The interface where OrthLib is absent: nothing declared, nothing to draw. */
    ModInterface NONE = new ModInterface() {
        @Override
        public Declarations declarations() {
            return Declarations.EMPTY;
        }

        @Override
        public AutoCloseable providing() {
            return () -> { };
        }

        @Override
        public AutoCloseable meshing(DeclaredLayer layer) {
            return () -> { };
        }

        @Override
        public boolean write(Contribution contribution, BufferBuilder buffer, Frame frame) {
            return false;
        }
    };

    /** What is declared now, as one immutable snapshot; any thread. */
    Declarations declarations();

    /**
     * Opens, on the calling thread, the scope of a build in which Quadrature meshes declared layers apart: inside it a
     * declared layer is not current in the game layer it falls back to, so the mods' own code leaves its geometry out
     * of the game's layers. Outside every such scope, on every other thread and in every other draw of blocks (the
     * mods' own previews among them), the interface gives its absence answers.
     */
    AutoCloseable providing();

    /**
     * Opens, on the calling thread and inside {@link #providing}, the scope in which the declared layer is current for
     * the mod's own code while it is meshed; the caller sets Forge's current layer to none for the scope's length.
     */
    AutoCloseable meshing(DeclaredLayer layer);

    /**
     * Has a contribution write its vertices into a buffer begun in its layout and primitive, positions relative to
     * the frame's camera; client thread. False where it drew nothing, or failed and was retired by the interface.
     */
    boolean write(Contribution contribution, BufferBuilder buffer, Frame frame);

    /**
     * One snapshot of the declarations. The generation changes with every declaration or retirement; the layer
     * generation only when the declared layers do, so meshes, which depend on the layers alone, are kept while
     * contributions come and go.
     */
    record Declarations(long generation, long layerGeneration, List<DeclaredLayer> layers, List<BloomKind> kinds,
        List<Contribution> contributions) {

        public static final Declarations EMPTY = new Declarations(0, 0, List.of(), List.of(), List.of());

        public Declarations {
            layers = List.copyOf(layers);
            kinds = List.copyOf(kinds);
            contributions = List.copyOf(contributions);
        }
    }

    /**
     * A declared layer: its key; whether its geometry glows; the game layer it falls back to, where the game would
     * draw it without Quadrature; its bloom kind's index in the snapshot's kinds, or -1; and which block states draw
     * in it, asked from any thread. The handle is the interface's own object.
     */
    record DeclaredLayer(Object handle, String key, boolean emissive, BlockRenderLayer fallback, int kind,
        Predicate<IBlockState> membership) {
    }

    /** A bloom kind: the parameters its glow is computed with. */
    record BloomKind(Object handle, String key, float strength, float base, float lowLimit, float highLimit,
        float radius) {
    }

    /** How a contribution's vertices are laid out, drawn and blended. */
    enum Blend { OPAQUE, ALPHA, ADDITIVE }

    /** An emissive contribution: its bloom kind's index, whether it samples the blocks atlas, its primitive and blend. */
    record Contribution(Object handle, int kind, boolean textured, boolean triangles, Blend blend) {
    }

    /** The frame a contribution is drawn for. */
    record Frame(float partialTicks, double cameraX, double cameraY, double cameraZ, Entity viewer,
        @Nullable World world) {
    }
}
