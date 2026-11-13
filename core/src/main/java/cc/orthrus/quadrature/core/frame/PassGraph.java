package cc.orthrus.quadrature.core.frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The frame as declared: stages in the order they were added, each naming the resources it reads and
 * writes. {@link #compile} turns it into the stages that run, in an order that honors every dependency.
 *
 * <p>A stage that reads a resource depends on the last stage declared before it that writes that
 * resource. Declaration order is therefore the tie-breaker and the meaning of "last writer": two stages
 * that write the same resource in turn, as hosted stages do with the frame, run in the order declared.
 */
public final class PassGraph {

    private final List<StageDefinition> stages = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private final Set<String> imported = new HashSet<>();

    /** Declares a resource that exists before the frame starts, such as the game's framebuffer. */
    public PassGraph importResource(String resource) {
        imported.add(resource);
        return this;
    }

    public PassGraph add(StageDefinition stage) {
        if (!names.add(stage.name())) {
            throw new IllegalArgumentException("Two stages are named " + stage.name());
        }
        stages.add(stage);
        return this;
    }

    /**
     * Orders the stages and drops those whose results nothing uses.
     *
     * @throws IllegalStateException if a stage reads a resource that nothing before it writes and that was
     *                               not imported
     */
    public CompiledGraph compile() {
        int count = stages.size();
        // dependsOn[i] holds the indexes of the stages whose output stage i reads.
        List<Set<Integer>> dependsOn = new ArrayList<>(count);
        Map<String, Integer> lastWriter = new HashMap<>();
        for (int i = 0; i < count; i++) {
            StageDefinition stage = stages.get(i);
            Set<Integer> dependencies = new HashSet<>();
            for (String resource : stage.reads()) {
                Integer writer = lastWriter.get(resource);
                if (writer != null) {
                    dependencies.add(writer);
                } else if (!imported.contains(resource)) {
                    throw new IllegalStateException("Stage " + stage.name() + " reads " + resource
                        + ", which no earlier stage writes and which was not imported");
                }
            }
            dependsOn.add(dependencies);
            for (String resource : stage.writes()) {
                lastWriter.put(resource, i);
            }
        }

        // A stage is kept if it has an external effect or a kept stage depends on it. Dependencies always
        // point backward, so one pass from the end settles it.
        boolean[] kept = new boolean[count];
        for (int i = count - 1; i >= 0; i--) {
            if (stages.get(i).externalEffect()) {
                kept[i] = true;
            }
            if (kept[i]) {
                for (int dependency : dependsOn.get(i)) {
                    kept[dependency] = true;
                }
            }
        }

        // Dependencies point backward, so declaration order is already a valid execution order.
        List<StageDefinition> order = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (kept[i]) {
                order.add(stages.get(i));
            } else {
                dropped.add(stages.get(i).name());
            }
        }
        return new CompiledGraph(order, dropped);
    }
}
