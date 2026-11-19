package cc.orthrus.quadrature.nvidia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemoryLayout.PathElement;
import java.nio.file.Path;
import java.util.List;

class NgxTest {

    @Test
    void imageLayoutMatchesTheShim() {
        // ngx_shim.cpp's static_assert states the same numbers for its Image.
        assertEquals(32, Ngx.IMAGE.byteSize());
        assertEquals(0, Ngx.IMAGE.byteOffset(PathElement.groupElement("image")));
        assertEquals(8, Ngx.IMAGE.byteOffset(PathElement.groupElement("view")));
        assertEquals(16, Ngx.IMAGE.byteOffset(PathElement.groupElement("format")));
        assertEquals(20, Ngx.IMAGE.byteOffset(PathElement.groupElement("aspect")));
        assertEquals(24, Ngx.IMAGE.byteOffset(PathElement.groupElement("width")));
        assertEquals(28, Ngx.IMAGE.byteOffset(PathElement.groupElement("height")));
    }

    @Test
    void namesSplitsTheShimsList() {
        assertEquals(List.of("VK_KHR_a", "VK_NVX_b"), Ngx.names("VK_KHR_a\nVK_NVX_b\n"));
        assertEquals(List.of(), Ngx.names(""));
        assertEquals(List.of("VK_KHR_a"), Ngx.names("\nVK_KHR_a\r\n\n"));
    }

    @Test
    void presetsAreNgxNumbers() {
        assertEquals(0, Ngx.preset("default"));
        assertEquals(10, Ngx.preset("J"));
        assertEquals(11, Ngx.preset("k"));
        assertEquals(12, Ngx.preset("L"));
        assertEquals(13, Ngx.preset(" M "));
        assertThrows(IllegalArgumentException.class, () -> Ngx.preset("E"));
    }

    @Test
    void qualityModesAreNgxNumbers() {
        assertEquals(0, Ngx.quality("performance"));
        assertEquals(1, Ngx.quality("balanced"));
        assertEquals(2, Ngx.quality("quality"));
        assertEquals(3, Ngx.quality("ultraPerformance"));
        assertEquals(5, Ngx.quality("DLAA"));
        assertThrows(IllegalArgumentException.class, () -> Ngx.quality("ultraQuality"));
    }

    @Test
    void failuresAreTheBadRange() {
        assertTrue(Ngx.failed(0xBAD00001));
        assertTrue(Ngx.failed(0xBAD10004));
        assertEquals(false, Ngx.failed(1));
        assertEquals(false, Ngx.failed(0));
    }

    @Test
    void aMissingShimIsRefusedByName() {
        Path nowhere = Path.of("build", "no-such-shim.dll");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new Ngx(nowhere, Path.of("build"), null));
        assertTrue(refused.getMessage().contains("no-such-shim.dll"), refused.getMessage());
    }
}
