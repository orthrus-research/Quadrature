package cc.orthrus.quadrature.orthlib;

import cc.orthrus.quadrature.modinterface.ModInterface;

import dev.orthlib.api.render.ContributionShape;
import dev.orthlib.api.render.GameLayer;
import dev.orthlib.api.render.InterfaceVersion;
import dev.orthlib.api.render.LayerRole;
import dev.orthlib.api.render.Primitive;
import dev.orthlib.api.render.ProviderInfo;
import dev.orthlib.api.render.RenderCapability;
import dev.orthlib.api.render.RenderProvider;
import dev.orthlib.api.render.RenderSnapshot;
import dev.orthlib.api.render.VertexLayout;
import dev.orthlib.client.render.EmissiveSource;
import dev.orthlib.client.render.FrameView;
import dev.orthlib.client.render.ProviderHandle;
import dev.orthlib.client.render.RenderInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;

/**
 * Quadrature as the provider of OrthLib's render interface (D25, PLAN item P3-B2), and the translation of OrthLib's
 * snapshots into {@link ModInterface}'s terms for the rest of Quadrature. Loaded by name, only where OrthLib is
 * installed (D26).
 *
 * <p>What Quadrature states to OrthLib, its name, the interface version it was built for and the capabilities it
 * draws, is written here as literals, never read from OrthLib, which would report the version installed rather than
 * the one built against (research R5, 10.2). The provider OrthLib holds is an object of its own, so nothing of
 * {@link ModInterface} can stand in for a method a later version of {@link RenderProvider} adds. A value of OrthLib's
 * this bridge does not know, a layout, primitive, blend or role from a later version, is not drawn.
 *
 * <p>OrthLib's declared layers, bloom kinds, contributions and blends are named in full here: {@link ModInterface}'s
 * own types of the same names are members of this class.
 */
public final class OrthLibBridge implements ModInterface {

    /** The provider OrthLib holds: what Quadrature draws, as of the interface version it was built against. */
    private static final class Provider implements RenderProvider {
        private static final ProviderInfo INFO = new ProviderInfo("Quadrature", new InterfaceVersion(1, 0),
            Set.of(RenderCapability.DECLARED_LAYERS, RenderCapability.EMISSIVE_CONTRIBUTIONS, RenderCapability.BLOOM));

        @Override
        public ProviderInfo info() {
            return INFO;
        }
    }

    private final ProviderHandle handle;
    /**
     * The last snapshot's translation, replaced when the generation moves. Translations are made one at a time, each
     * from the last, so every thread finds one layer generation for one list of layers.
     */
    private volatile Declarations translated = new Declarations(-1, -1, List.of(), List.of(), List.of());
    private final Object translating = new Object();

    private OrthLibBridge(ProviderHandle handle) {
        this.handle = handle;
    }

    /**
     * Registers Quadrature as OrthLib's provider and returns the bridge. OrthLib refuses with an
     * {@link IllegalStateException} naming both where another provider holds the interface.
     */
    public static ModInterface start() {
        OrthLibBridge bridge = new OrthLibBridge(RenderInterface.registerProvider(new Provider()));
        InterfaceSelfTest.startIfAsked();
        return bridge;
    }

    @Override
    public Declarations declarations() {
        RenderSnapshot<IBlockState, EmissiveSource> snapshot = handle.snapshot();
        Declarations last = translated;
        if (last.generation() == snapshot.generation()) {
            return last;
        }
        synchronized (translating) {
            snapshot = handle.snapshot();
            last = translated;
            if (last.generation() != snapshot.generation()) {
                last = translate(snapshot, last);
                translated = last;
            }
            return last;
        }
    }

    /** The snapshot in Quadrature's terms; its layer generation is the last one's where the layers are the same. */
    private static Declarations translate(RenderSnapshot<IBlockState, EmissiveSource> snapshot, Declarations last) {
        Map<dev.orthlib.api.render.BloomKind, Integer> kindIndex = new IdentityHashMap<>();
        Map<String, Integer> kindByKey = new HashMap<>();
        List<ModInterface.BloomKind> kinds = new ArrayList<>();
        for (dev.orthlib.api.render.BloomKind kind : snapshot.kinds()) {
            var d = kind.definition();
            kindIndex.put(kind, kinds.size());
            kindByKey.put(d.key().key(), kinds.size());
            kinds.add(new ModInterface.BloomKind(kind, d.key().key(), d.strength(), d.base(), d.lowLimit(),
                d.highLimit(), d.radius()));
        }
        List<ModInterface.DeclaredLayer> layers = new ArrayList<>();
        for (dev.orthlib.api.render.DeclaredLayer<IBlockState> layer : snapshot.layers()) {
            var d = layer.definition();
            BlockRenderLayer fallback = fallback(d.fallback());
            // A role or fallback of a later version is kept in its place, so the layers' order holds, and never glows.
            boolean emissive = d.role() == LayerRole.EMISSIVE;
            int kind = emissive && d.bloomKind() != null ? kindByKey.getOrDefault(d.bloomKind().key(), -1) : -1;
            layers.add(new ModInterface.DeclaredLayer(layer, d.key().key(), emissive,
                fallback == null ? BlockRenderLayer.CUTOUT : fallback, kind, layer.membership()));
        }
        List<ModInterface.Contribution> contributions = new ArrayList<>();
        for (dev.orthlib.api.render.Contribution<EmissiveSource> contribution : snapshot.contributions()) {
            ContributionShape shape = contribution.shape();
            ModInterface.Blend blend = blend(shape.blend());
            boolean known = blend != null
                && (shape.layout() == VertexLayout.POSITION_COLOR || shape.layout() == VertexLayout.POSITION_TEXTURE_COLOR)
                && (shape.primitive() == Primitive.QUADS || shape.primitive() == Primitive.TRIANGLES);
            if (!known) {
                continue;
            }
            Integer kind = kindIndex.get(contribution.kind());
            contributions.add(new ModInterface.Contribution(contribution, kind == null ? -1 : kind,
                shape.layout() == VertexLayout.POSITION_TEXTURE_COLOR, shape.primitive() == Primitive.TRIANGLES, blend));
        }
        boolean sameLayers = layers.size() == last.layers().size();
        for (int i = 0; sameLayers && i < layers.size(); i++) {
            sameLayers = layers.get(i).handle() == last.layers().get(i).handle();
        }
        long layerGeneration = sameLayers && last.layerGeneration() >= 0 ? last.layerGeneration() : snapshot.generation();
        return new Declarations(snapshot.generation(), layerGeneration, layers, kinds, contributions);
    }

    private static BlockRenderLayer fallback(GameLayer layer) {
        if (layer == GameLayer.SOLID) {
            return BlockRenderLayer.SOLID;
        } else if (layer == GameLayer.CUTOUT_MIPPED) {
            return BlockRenderLayer.CUTOUT_MIPPED;
        } else if (layer == GameLayer.CUTOUT) {
            return BlockRenderLayer.CUTOUT;
        } else if (layer == GameLayer.TRANSLUCENT) {
            return BlockRenderLayer.TRANSLUCENT;
        }
        return null;
    }

    private static ModInterface.Blend blend(dev.orthlib.api.render.Blend blend) {
        if (blend == dev.orthlib.api.render.Blend.OPAQUE) {
            return ModInterface.Blend.OPAQUE;
        } else if (blend == dev.orthlib.api.render.Blend.ALPHA) {
            return ModInterface.Blend.ALPHA;
        } else if (blend == dev.orthlib.api.render.Blend.ADDITIVE) {
            return ModInterface.Blend.ADDITIVE;
        }
        return null;
    }

    @Override
    public AutoCloseable providing() {
        return handle.providing();
    }

    @Override
    public AutoCloseable meshing(ModInterface.DeclaredLayer layer) {
        return handle.meshing((dev.orthlib.api.render.DeclaredLayer<?>) layer.handle());
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean write(ModInterface.Contribution contribution, BufferBuilder buffer, Frame frame) {
        return handle.writeContribution((dev.orthlib.api.render.Contribution<EmissiveSource>) contribution.handle(), buffer,
            new FrameView(frame.partialTicks(), frame.cameraX(), frame.cameraY(), frame.cameraZ(), frame.viewer(),
                frame.world()));
    }

    @Override
    public void drawingContributions() {
        handle.drawingContributions();
    }
}
