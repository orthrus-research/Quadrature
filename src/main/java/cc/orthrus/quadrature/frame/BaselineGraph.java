package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.core.frame.CompiledGraph;
import cc.orthrus.quadrature.core.frame.PassGraph;
import cc.orthrus.quadrature.core.frame.StageDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * The game's world pass as a graph of hosted stages. It draws nothing new: each stage is a stretch of
 * the game's own {@code renderWorldPass}, which marks its parts with profiler sections. Those section
 * names are the boundaries; this maps each to the stage it starts.
 */
public final class BaselineGraph {

    /** The framebuffer the game draws the world into. */
    public static final String FRAME = "frame";

    /** Stages in the order the game runs them. A section named twice starts a different stage each time. */
    private static final String[][] SECTIONS = {
        {"clear", "setup"},
        {"camera", "setup"},
        {"frustum", "setup"},
        {"culling", "setup"},
        {"sky", "sky"},
        {"prepareterrain", "terrain setup"},
        {"terrain_setup", "terrain setup"},
        {"updatechunks", "terrain upload"},
        {"terrain", "opaque terrain"},
        {"entities", "entities"},
        {"outline", "outline and damage"},
        {"destroyProgress", "outline and damage"},
        {"litParticles", "particles"},
        {"particles", "particles"},
        {"weather", "weather"},
        {"translucent", "translucent terrain"},
        {"entities", "translucent entities"},
        {"aboveClouds", "clouds"},
        {"forge_render_last", "render last"},
        {"hand", "hand"},
    };

    private BaselineGraph() {
    }

    public static CompiledGraph compile() {
        PassGraph graph = new PassGraph().importResource(FRAME);
        String previous = null;
        for (String[] section : SECTIONS) {
            if (!section[1].equals(previous)) {
                graph.add(StageDefinition.hosted(section[1], FRAME));
                previous = section[1];
            }
        }
        return graph.compile();
    }

    /**
     * Names the stage a profiler section starts.
     *
     * @param occurrence how many times this section name was already seen in the current pass
     * @return the stage's name, or null for a section that starts no stage of its own
     */
    public static String stageFor(String section, int occurrence) {
        String[] stages = STAGES_BY_SECTION.get(section);
        if (stages == null) {
            return null;
        }
        return stages[Math.min(occurrence, stages.length - 1)];
    }

    private static final Map<String, String[]> STAGES_BY_SECTION = new HashMap<>();

    static {
        Map<String, java.util.List<String>> grouped = new HashMap<>();
        for (String[] section : SECTIONS) {
            grouped.computeIfAbsent(section[0], key -> new java.util.ArrayList<>()).add(section[1]);
        }
        grouped.forEach((section, stages) -> STAGES_BY_SECTION.put(section, stages.toArray(new String[0])));
    }
}
