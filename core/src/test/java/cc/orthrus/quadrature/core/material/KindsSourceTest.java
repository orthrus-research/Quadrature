package cc.orthrus.quadrature.core.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

class KindsSourceTest {

    private static final String SHIPPED = "# Kinds. Revision 4 of a day.\nblock.130= a:glass\n";
    private static final Supplier<InputStream> SHIPS = () -> new ByteArrayInputStream(SHIPPED.getBytes(StandardCharsets.UTF_8));

    @Test
    void theFolderNamedAtStartIsReadBeforeWhatIsShipped() throws Exception {
        Path folder = folder("named");
        Files.write(folder.resolve(KindsSource.FILE),
            "# Kinds. Revision 6 of another day.\nblock.140= a:iron\n".getBytes(StandardCharsets.UTF_8));
        KindsSource source = KindsSource.read(folder, SHIPS);
        assertEquals("Kinds. Revision 6 of another day.", source.revision());
        assertTrue(source.from().startsWith("the file "));
        MaterialKinds kinds = MaterialKinds.parse(source.lines());
        assertEquals(140, kinds.kind("a:iron", property -> null));
        assertEquals(MaterialKinds.NONE, kinds.kind("a:glass", property -> null));
        // The hash is of the bytes as they lie in the file.
        assertEquals(64, source.sha256().length());
    }

    @Test
    void whatIsShippedIsReadWhereNoFolderIsNamedOrItHasNoList() throws Exception {
        Path folder = folder("empty");
        KindsSource none = KindsSource.read(null, SHIPS);
        assertEquals("the list shipped", none.from());
        assertEquals("Kinds. Revision 4 of a day.", none.revision());
        assertEquals(130, MaterialKinds.parse(none.lines()).kind("a:glass", property -> null));
        // A folder without the file.
        KindsSource empty = KindsSource.read(folder, SHIPS);
        assertEquals("the list shipped", empty.from());
        assertEquals(none.sha256(), empty.sha256());
    }

    @Test
    void theHashIsTheOneAnyToolGivesTheSameBytes() {
        KindsSource source = KindsSource.read(null, () -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", source.sha256());
    }

    @Test
    void withNothingToReadThereIsAnEmptyList() {
        KindsSource source = KindsSource.read(null, () -> null);
        assertEquals("no list", source.from());
        assertTrue(source.lines().isEmpty());
        assertEquals(0, MaterialKinds.parse(source.lines()).names());
    }

    /** A folder of the build's own, emptied: the system's temporary folder is not always one that can be cleared. */
    private static Path folder(String name) throws Exception {
        Path folder = java.nio.file.Paths.get("build", "kinds-source-test", name);
        Files.createDirectories(folder);
        Files.deleteIfExists(folder.resolve(KindsSource.FILE));
        return folder;
    }
}
