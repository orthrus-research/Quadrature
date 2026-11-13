package cc.orthrus.quadrature.core.frame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The stages that run, in order, and the names of those dropped because nothing used their results. */
public final class CompiledGraph {

    private final List<StageDefinition> stages;
    private final List<String> dropped;
    private final Map<String, Integer> indexByName = new HashMap<>();

    CompiledGraph(List<StageDefinition> stages, List<String> dropped) {
        this.stages = List.copyOf(stages);
        this.dropped = List.copyOf(dropped);
        for (int i = 0; i < this.stages.size(); i++) {
            indexByName.put(this.stages.get(i).name(), i);
        }
    }

    public List<StageDefinition> stages() {
        return stages;
    }

    public List<String> dropped() {
        return dropped;
    }

    public int size() {
        return stages.size();
    }

    /** The position of a stage in execution order, or -1 if it is not in the compiled graph. */
    public int indexOf(String name) {
        return indexByName.getOrDefault(name, -1);
    }
}
