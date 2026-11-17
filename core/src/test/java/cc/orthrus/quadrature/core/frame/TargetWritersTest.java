package cc.orthrus.quadrature.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class TargetWritersTest {

    @Test
    void writersAreCalledInTheOrderTheyWereAddedOnceAPass() {
        TargetWriters writers = new TargetWriters();
        List<String> called = new ArrayList<>();
        Runnable first = () -> called.add("first");
        Runnable second = () -> called.add("second");
        Runnable third = () -> called.add("third");
        writers.add(second);
        writers.add(first);
        writers.add(third);
        // One that is there already keeps its place and is not called twice.
        writers.add(second);
        assertEquals(3, writers.size());
        List<Integer> places = new ArrayList<>();
        writers.run(places::add, null);
        assertEquals(List.of("second", "first", "third"), called);
        assertEquals(List.of(0, 1, 2), places);
        writers.remove(first);
        writers.remove(first);
        called.clear();
        writers.run(null, null);
        assertEquals(List.of("second", "third"), called);
        assertThrows(IllegalArgumentException.class, () -> writers.add(null));
        // With none there, nothing is called.
        writers.remove(second);
        writers.remove(third);
        writers.run(place -> called.add("before"), null);
        assertEquals(List.of("second", "third"), called);
    }

    @Test
    void aWriterThatThrowsIsRemovedAndSaidOnceAndTheRestAreStillCalled() {
        TargetWriters writers = new TargetWriters();
        List<String> called = new ArrayList<>();
        IllegalStateException fault = new IllegalStateException("no");
        Runnable throwing = () -> {
            called.add("throwing");
            throw fault;
        };
        writers.add(() -> called.add("first"));
        writers.add(throwing);
        writers.add(() -> called.add("last"));
        List<Throwable> said = new ArrayList<>();
        List<Runnable> who = new ArrayList<>();
        writers.run(null, (writer, thrown) -> {
            who.add(writer);
            said.add(thrown);
        });
        assertEquals(List.of("first", "throwing", "last"), called);
        assertEquals(1, said.size());
        assertSame(fault, said.get(0));
        assertSame(throwing, who.get(0));
        assertEquals(2, writers.size());
        // The next pass does not call it, and nothing more is said.
        called.clear();
        writers.run(null, (writer, thrown) -> said.add(thrown));
        assertEquals(List.of("first", "last"), called);
        assertEquals(1, said.size());
        // An error is caught as an exception is.
        writers.add(() -> {
            throw new AssertionError("an error");
        });
        writers.run(null, (writer, thrown) -> said.add(thrown));
        assertEquals(2, said.size());
        assertEquals(2, writers.size());
        // With nobody to tell, a writer that throws is still removed.
        writers.add(throwing);
        writers.run(null, null);
        assertEquals(2, writers.size());
    }

    @Test
    void addingOrRemovingFromInsideAWriterTakesEffectFromTheNextPass() {
        TargetWriters writers = new TargetWriters();
        List<String> called = new ArrayList<>();
        Runnable late = () -> called.add("late");
        Runnable last = () -> called.add("last");
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            called.add("changer");
            writers.add(late);
            writers.remove(last);
            writers.remove(self[0]);
        };
        writers.add(self[0]);
        writers.add(last);
        writers.run(null, null);
        // This pass calls those that were there when it began: the one removed is still called, the one added is not.
        assertEquals(List.of("changer", "last"), called);
        called.clear();
        writers.run(null, null);
        assertEquals(List.of("late"), called);
        assertEquals(1, writers.size());
    }
}
