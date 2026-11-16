package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.material.KindsSource;
import cc.orthrus.quadrature.core.material.MaterialKinds;
import cc.orthrus.quadrature.core.material.MaterialTable;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

import java.util.List;
import java.util.TreeMap;

/**
 * What every block state's surface is like where no texture says, filled as states are met. A state's
 * kind comes from the list shipped with the mod; a block the list does not name gets its kind from
 * what the game says of it: its material, and for a few its own class.
 */
final class Materials {

    private static final String LIST = "/assets/quadrature/material_kinds.properties";

    private final MaterialKinds kinds;
    private final MaterialTable table = new MaterialTable();

    Materials() {
        // A folder named at start holds the list where a newer one than the build's is wanted.
        String stage = System.getProperty("quadrature.stage.dir");
        KindsSource source = KindsSource.read(stage == null || stage.isEmpty() ? null : java.nio.file.Paths.get(stage),
            () -> Materials.class.getResourceAsStream(LIST));
        kinds = MaterialKinds.parse(source.lines());
        Quadrature.LOGGER.info("Material kinds: {} names read from {} ({}; sha256 {}).", kinds.names(), source.from(),
            source.revision().isEmpty() ? "no revision line" : source.revision(), source.sha256());
        if (Boolean.getBoolean("quadrature.materials.audit")) {
            audit();
        }
    }

    MaterialTable table() {
        return table;
    }

    /** Sets a state's values the first time it is met. */
    void define(IBlockState state, int number) {
        int listed = listed(state);
        if (listed != MaterialKinds.NONE) {
            table.set(number, listed, state.getLightValue());
        } else {
            MaterialKinds.Family family = family(state);
            table.set(number, MaterialKinds.fallback(family, state.getLightValue()),
                MaterialKinds.fallbackLight(family, state.getLightValue()));
        }
    }

    /** The kind of a state: by the list, else by the rule. */
    int kind(IBlockState state) {
        int listed = listed(state);
        return listed != MaterialKinds.NONE ? listed : ruled(state);
    }

    /** The kind the list gives a state, or none. */
    int listed(IBlockState state) {
        Block block = state.getBlock();
        if (block.getRegistryName() == null) {
            return MaterialKinds.NONE;
        }
        return kinds.kind(block.getRegistryName().toString(), property -> value(state, property));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String value(IBlockState state, String property) {
        for (IProperty key : state.getPropertyKeys()) {
            if (key.getName().equals(property)) {
                return key.getName(state.getValue(key));
            }
        }
        return null;
    }

    /**
     * The kind the game's own account of a block gives it, where the list does not name it: leaves,
     * plants and vines by material; glass and ice by material; the game's own water, and lava; and
     * anything else that gives light as an emitter. Never a metal, and no fluid but the game's water
     * as water: see {@link MaterialKinds#fallback}.
     */
    int ruled(IBlockState state) {
        return MaterialKinds.fallback(family(state), state.getLightValue());
    }

    private static MaterialKinds.Family family(IBlockState state) {
        Material material = state.getMaterial();
        Block block = state.getBlock();
        if (material == Material.LAVA) {
            return MaterialKinds.Family.LAVA;
        }
        if (material == Material.WATER) {
            return block == Blocks.WATER || block == Blocks.FLOWING_WATER ? MaterialKinds.Family.WATER_ITSELF
                : MaterialKinds.Family.WATER_LIKE_FLUID;
        }
        if (material == Material.LEAVES) {
            return MaterialKinds.Family.LEAVES;
        }
        if (block == Blocks.VINE) {
            return MaterialKinds.Family.HANGING_PLANT;
        }
        if (block == Blocks.WATERLILY) {
            return MaterialKinds.Family.FLOATING_PLANT;
        }
        if (material == Material.PLANTS || material == Material.VINE) {
            return MaterialKinds.Family.PLANT;
        }
        if (material == Material.GLASS) {
            return MaterialKinds.Family.GLASS;
        }
        if (material == Material.ICE || material == Material.PACKED_ICE) {
            return MaterialKinds.Family.ICE;
        }
        if (material == Material.IRON || material == Material.ANVIL) {
            return MaterialKinds.Family.METALLIC;
        }
        return MaterialKinds.Family.OTHER;
    }
    /**
     * A diagnostic: for every block the game knows, sets the list's kind beside the rule's, and logs
     * each block where they differ, and each block the rule gives a kind that the list does not name. The
     * rule never gives the kind of a metal, and gives water only to the game's own.
     */
    void audit() {
        TreeMap<String, String> differing = new TreeMap<>();
        TreeMap<String, String> unlisted = new TreeMap<>();
        int blocks = 0;
        for (Block block : Block.REGISTRY) {
            blocks++;
            for (IBlockState state : block.getBlockState().getValidStates()) {
                int listed = listed(state);
                int ruled = ruled(state);
                String name = String.valueOf(block.getRegistryName());
                if (listed != MaterialKinds.NONE && ruled != listed) {
                    differing.putIfAbsent(name, "list " + listed + ", rule " + ruled);
                } else if (listed == MaterialKinds.NONE && ruled != MaterialKinds.NONE) {
                    unlisted.putIfAbsent(name, "rule " + ruled);
                }
            }
        }
        Quadrature.LOGGER.info("Material audit: {} blocks; {} where the list and the rule differ; {} the rule gives a kind "
            + "and the list does not name.", blocks, differing.size(), unlisted.size());
        // Blocks that hold a material in each state: what each state is, so that true metals can be named.
        for (Block block : Block.REGISTRY) {
            String name = String.valueOf(block.getRegistryName());
            // And one lamp, whose lit states the list names by properties read from its class.
            boolean lamp = name.equals("gregtech:white_lamp");
            if (lamp || name.startsWith("gregtech:meta_block_compressed_") || name.startsWith("gregtech:meta_block_frame_")) {
                StringBuilder states = new StringBuilder();
                for (IBlockState state : block.getBlockState().getValidStates()) {
                    states.append(lamp ? " [" : "");
                    for (IProperty<?> key : state.getPropertyKeys()) {
                        states.append(' ').append(key.getName()).append('=').append(value(state, key.getName()));
                    }
                    if (lamp) {
                        states.append(" light=").append(state.getLightValue()).append(" kind=").append(listed(state)).append(" ]");
                    }
                }
                Quadrature.LOGGER.info("Material audit: states: {}:{}", name, states);
            }
        }
        differing.forEach((name, what) -> Quadrature.LOGGER.info("Material audit: differ: {} ({})", name, what));
        unlisted.forEach((name, what) -> Quadrature.LOGGER.info("Material audit: not listed: {} ({})", name, what));
    }
}
