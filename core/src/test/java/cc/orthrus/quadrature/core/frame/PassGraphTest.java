package cc.orthrus.quadrature.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

final class PassGraphTest {

    private static StageDefinition stage(String name, Set<String> reads, Set<String> writes, boolean external) {
        return new StageDefinition(name, StageKind.PIPELINE, reads, writes, external);
    }

    private static List<String> names(CompiledGraph graph) {
        return graph.stages().stream().map(StageDefinition::name).toList();
    }

    @Test
    void hostedStagesOnOneFrameRunInDeclarationOrder() {
        CompiledGraph graph = new PassGraph().importResource("frame")
            .add(StageDefinition.hosted("sky", "frame"))
            .add(StageDefinition.hosted("terrain", "frame"))
            .add(StageDefinition.hosted("entities", "frame"))
            .compile();
        assertEquals(List.of("sky", "terrain", "entities"), names(graph));
        assertEquals(1, graph.indexOf("terrain"));
        assertEquals(-1, graph.indexOf("clouds"));
    }

    @Test
    void aStageNothingReadsIsDropped() {
        CompiledGraph graph = new PassGraph().importResource("frame")
            .add(stage("debug view", Set.of("frame"), Set.of("debug"), false))
            .add(StageDefinition.hosted("terrain", "frame"))
            .compile();
        assertEquals(List.of("terrain"), names(graph));
        assertEquals(List.of("debug view"), graph.dropped());
    }

    @Test
    void aStageIsKeptWhenAKeptStageReadsItsOutput() {
        CompiledGraph graph = new PassGraph().importResource("frame")
            .add(stage("cull", Set.of(), Set.of("draw commands"), false))
            .add(stage("depth pyramid", Set.of("frame"), Set.of("pyramid"), false))
            .add(stage("draw", Set.of("draw commands", "frame"), Set.of("frame"), true))
            .compile();
        assertEquals(List.of("cull", "draw"), names(graph));
        assertEquals(List.of("depth pyramid"), graph.dropped());
    }

    @Test
    void keepingIsTransitive() {
        CompiledGraph graph = new PassGraph()
            .add(stage("a", Set.of(), Set.of("x"), false))
            .add(stage("b", Set.of("x"), Set.of("y"), false))
            .add(stage("c", Set.of("y"), Set.of(), true))
            .compile();
        assertEquals(List.of("a", "b", "c"), names(graph));
    }

    @Test
    void aReaderDependsOnTheLastWriterDeclaredBeforeIt() {
        CompiledGraph graph = new PassGraph()
            .add(stage("first writer", Set.of(), Set.of("x"), false))
            .add(stage("second writer", Set.of(), Set.of("x"), false))
            .add(stage("reader", Set.of("x"), Set.of(), true))
            .compile();
        assertEquals(List.of("second writer", "reader"), names(graph));
    }

    @Test
    void readingWhatNothingWritesIsAnError() {
        PassGraph graph = new PassGraph().add(stage("draw", Set.of("frame"), Set.of(), true));
        assertThrows(IllegalStateException.class, graph::compile);
    }

    @Test
    void duplicateNamesAreRejected() {
        PassGraph graph = new PassGraph().importResource("frame").add(StageDefinition.hosted("sky", "frame"));
        assertThrows(IllegalArgumentException.class, () -> graph.add(StageDefinition.hosted("sky", "frame")));
    }
}
