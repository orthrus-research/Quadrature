package cc.orthrus.quadrature.core.material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which kind of surface each block is, read from a list: lines of {@code block.<kind>=} followed by
 * block names, where {@code name:property=value}, with further {@code :property=value} after it, names
 * only the states of a block that have every value named. A block or state the list does not name has
 * no kind from here; a rule decides for it.
 */
public final class MaterialKinds {

    /** The kinds a list may give. */
    public static final int NONE = 0;
    public static final int WATER = 100;
    public static final int LAVA = 101;
    public static final int LEAVES = 110;
    public static final int PLANT = 111;
    public static final int TALL_PLANT_LOWER = 112;
    public static final int TALL_PLANT_UPPER = 113;
    public static final int HANGING_PLANT = 114;
    public static final int FLOATING_PLANT = 115;
    /** Stiff thin plants and fungi. */
    public static final int STIFF_PLANT = 116;
    public static final int SUBMERGED_PLANT = 117;
    /** Litter and other cover lying on the ground: no foliage. */
    public static final int GROUND_COVER = 118;
    public static final int DIM_EMITTER = 120;
    public static final int BRIGHT_EMITTER = 121;
    public static final int GLASS = 130;
    public static final int ICE = 131;
    public static final int METAL = 140;
    /** Named so that no rule gives it a kind: the defaults, and no emission whatever light it gives. */
    public static final int PLAIN = 199;

    /** What the game's own account says a block is, for {@link #fallback}. */
    public enum Family {
        OTHER, LAVA, WATER_ITSELF, WATER_LIKE_FLUID, LEAVES, HANGING_PLANT, FLOATING_PLANT, PLANT, GLASS, ICE, METALLIC
    }

    /**
     * The kind of a block the list does not name, by the game's own account of it. It never calls a
     * block a metal: a block of the game's iron or anvil material is no bare conductor merely for
     * that, and has no kind until the list names it, whatever light it gives. Water is only the game's
     * own water: another block of the game's water material has no kind, whatever light it gives.
     * Anything else that gives light is an emitter, bright from light 12.
     */
    public static int fallback(Family family, int light) {
        switch (family) {
            case LAVA:
                return LAVA;
            case WATER_ITSELF:
                return WATER;
            case WATER_LIKE_FLUID:
            case METALLIC:
                return NONE;
            case LEAVES:
                return LEAVES;
            case HANGING_PLANT:
                return HANGING_PLANT;
            case FLOATING_PLANT:
                return FLOATING_PLANT;
            case PLANT:
                return PLANT;
            case GLASS:
                return GLASS;
            case ICE:
                return ICE;
            default:
                return light >= 12 ? BRIGHT_EMITTER : light > 0 ? DIM_EMITTER : NONE;
        }
    }
    /**
     * The light an unnamed block is taken to give for its emission: none for a block of the game's
     * iron or anvil material, so that a machine or a casing does not glow over its whole face.
     */
    public static int fallbackLight(Family family, int light) {
        return family == Family.METALLIC ? 0 : light;
    }

    /** Says what value a state has for a property, or null when it has none of that name. */
    public interface Properties {
        String value(String property);
    }

    private static final class Entry {
        final int kind;
        /** The properties a state must have, and the value of each; none for every state of the block. */
        final String[] properties;
        final String[] values;

        Entry(int kind, String[] properties, String[] values) {
            this.kind = kind;
            this.properties = properties;
            this.values = values;
        }

        boolean matches(Properties state) {
            for (int i = 0; i < properties.length; i++) {
                if (!values[i].equals(state.value(properties[i]))) {
                    return false;
                }
            }
            return true;
        }
    }

    private final Map<String, List<Entry>> blocks = new HashMap<>();
    private int names;

    /** Reads a list. A line that is empty, a comment, or not of the form given is passed over. */
    public static MaterialKinds parse(Iterable<String> lines) {
        MaterialKinds kinds = new MaterialKinds();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.startsWith("block.")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                continue;
            }
            int kind;
            try {
                kind = Integer.parseInt(trimmed.substring(6, equals).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            for (String name : trimmed.substring(equals + 1).trim().split("\\s+")) {
                if (name.isEmpty()) {
                    continue;
                }
                // A name is domain:block, with :property=value after it, once or more, for some states
                // only: the states that have every value named.
                String[] parts = name.split(":");
                if (parts.length < 2) {
                    continue;
                }
                String[] properties = new String[parts.length - 2];
                String[] values = new String[parts.length - 2];
                boolean formed = true;
                for (int part = 2; part < parts.length; part++) {
                    int at = parts[part].indexOf('=');
                    if (at <= 0 || at == parts[part].length() - 1) {
                        formed = false;
                        break;
                    }
                    properties[part - 2] = parts[part].substring(0, at);
                    values[part - 2] = parts[part].substring(at + 1);
                }
                if (!formed) {
                    continue;
                }
                kinds.blocks.computeIfAbsent(parts[0] + ':' + parts[1], key -> new ArrayList<>())
                    .add(new Entry(kind, properties, values));
                kinds.names++;
            }
        }
        return kinds;
    }

    /**
     * The kind the list gives a state of a block, or {@link #NONE} when it names neither the block nor
     * that state of it.
     *
     * @param block      the block's name, domain and all
     * @param properties the state's property values
     */
    public int kind(String block, Properties properties) {
        List<Entry> entries = blocks.get(block);
        if (entries == null) {
            return NONE;
        }
        for (Entry entry : entries) {
            if (entry.matches(properties)) {
                return entry.kind;
            }
        }
        return NONE;
    }

    /** Whether the list names a block at all. */
    public boolean names(String block) {
        return blocks.containsKey(block);
    }

    /** How many names the list holds. */
    public int names() {
        return names;
    }

    /** Whether a kind is foliage: a thin surface light passes through. */
    public static boolean foliage(int kind) {
        return kind >= LEAVES && kind <= SUBMERGED_PLANT;
    }
}
