package cc.orthrus.quadrature.core.frame;

import java.util.Set;

/**
 * One unit of work in the frame, defined by what it reads and writes, never by how it is done.
 *
 * @param externalEffect true when the stage does something the graph cannot see, such as drawing to the
 *                       screen through the game's own code; such a stage is never dropped as unused
 */
public record StageDefinition(String name, StageKind kind, Set<String> reads, Set<String> writes,
                              boolean externalEffect) {

    public StageDefinition {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A stage needs a name");
        }
        reads = Set.copyOf(reads);
        writes = Set.copyOf(writes);
    }

    /** A hosted stage: it reads and writes the frame the game draws into, and always runs. */
    public static StageDefinition hosted(String name, String frameResource) {
        return new StageDefinition(name, StageKind.HOSTED, Set.of(frameResource), Set.of(frameResource), true);
    }
}
