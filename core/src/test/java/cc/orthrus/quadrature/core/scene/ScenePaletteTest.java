package cc.orthrus.quadrature.core.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScenePaletteTest {

    @Test
    void eachCellNamesItsStateThroughTheCubesOwnList() {
        short[] cells = new short[LightScene.CELLS];
        for (int i = 0; i < LightScene.CELLS; i++) {
            cells[i] = (short) (i < 1000 ? 0 : i < 3000 ? 16 : i % 7 == 0 ? 40000 : 288);
        }
        byte[] indices = new byte[LightScene.CELLS];
        short[] states = new short[ScenePalette.STATES];
        ScenePalette palette = new ScenePalette();
        assertEquals(4, palette.build(cells, indices, states));
        for (int i = 0; i < LightScene.CELLS; i++) {
            assertEquals(cells[i], states[indices[i] & 0xFF]);
        }
        assertEquals(0, states[0]);
        assertEquals(16, states[1]);
        assertEquals(0, states[4]);

        // The same object builds another cube without remembering the first.
        java.util.Arrays.fill(cells, (short) 5);
        cells[77] = 9;
        assertEquals(2, palette.build(cells, indices, states));
        assertEquals(5, states[0]);
        assertEquals(9, states[1]);
        assertEquals(1, indices[77]);
        assertEquals(0, indices[78]);
        assertEquals(0, states[2]);
    }

    @Test
    void aCubeOfMoreThan256StatesIsClampedAndSaysSo() {
        short[] cells = new short[LightScene.CELLS];
        for (int i = 0; i < LightScene.CELLS; i++) {
            cells[i] = (short) (i % 300 + 1);
        }
        byte[] indices = new byte[LightScene.CELLS];
        short[] states = new short[ScenePalette.STATES];
        assertEquals(300, new ScenePalette().build(cells, indices, states));
        for (int i = 0; i < LightScene.CELLS; i++) {
            int state = i % 300 + 1;
            if (state <= 255) {
                assertEquals(state, states[indices[i] & 0xFF]);
            } else {
                assertEquals(255, indices[i] & 0xFF);
            }
        }
        assertEquals(256, states[255]);
        assertEquals(4096 + 512, ScenePalette.BYTES);
    }
}
