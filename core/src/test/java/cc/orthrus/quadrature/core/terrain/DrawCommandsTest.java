package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

class DrawCommandsTest {

    private static float[] matrix(float seed) {
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) {
            m[i] = seed + i;
        }
        return m;
    }

    private static int[] command(ByteBuffer commands, long offset) {
        int at = (int) offset;
        return new int[] {commands.getInt(at), commands.getInt(at + 4), commands.getInt(at + 8),
            commands.getInt(at + 12)};
    }

    @Test
    void commandsAreGroupedByLayerAndPageInTheOrderAdded() {
        DrawCommands list = new DrawCommands();
        list.begin(2);
        int a = list.record(matrix(100), 0);
        list.add(0, 1, 500, 40, a);
        list.add(1, 0, 900, 8, a);
        int b = list.record(matrix(200), 0);
        list.add(0, 0, 0, 12, b);
        int c = list.record(matrix(300), 0);
        list.add(0, 1, 700, 16, c);
        list.finish();

        assertEquals(4, list.commandCount());
        assertEquals(3, list.recordCount());
        assertEquals(1, list.runCount(0, 0));
        assertEquals(2, list.runCount(0, 1));
        assertEquals(1, list.runCount(1, 0));
        assertEquals(0, list.runCount(1, 1));
        assertEquals(0, list.runCount(2, 0));

        ByteBuffer commands = list.commands();
        assertEquals(4 * DrawCommands.COMMAND_BYTES, commands.limit());
        assertArrayEquals(new int[] {12, 1, 0, b}, command(commands, list.runOffset(0, 0)));
        assertArrayEquals(new int[] {40, 1, 500, a}, command(commands, list.runOffset(0, 1)));
        assertArrayEquals(new int[] {16, 1, 700, c},
            command(commands, list.runOffset(0, 1) + DrawCommands.COMMAND_BYTES));
        assertArrayEquals(new int[] {8, 1, 900, a}, command(commands, list.runOffset(1, 0)));
    }

    @Test
    void aRecordHoldsTheMatrixAndLeavesRoomForThePreviousOne() {
        DrawCommands list = new DrawCommands();
        list.begin(1);
        float[] two = new float[32];
        for (int i = 0; i < 32; i++) {
            two[i] = i * 0.5f;
        }
        list.record(two, 0);
        int second = list.record(two, 16);
        list.finish();
        assertEquals(1, second);
        ByteBuffer records = list.records();
        assertEquals(2 * DrawCommands.RECORD_BYTES, records.limit());
        for (int i = 0; i < 16; i++) {
            assertEquals(two[i], records.getFloat(i * 4));
            assertEquals(two[16 + i], records.getFloat(DrawCommands.RECORD_BYTES + i * 4));
        }
    }

    @Test
    void aListIsReusedAndGrows() {
        DrawCommands list = new DrawCommands();
        for (int frame = 0; frame < 3; frame++) {
            int cubes = 700 * (frame + 1);
            list.begin(3);
            for (int i = 0; i < cubes; i++) {
                int record = list.record(matrix(i), 0);
                for (int layer = 0; layer < 3; layer++) {
                    list.add(layer, i % 3, i * 4, 4, record);
                }
            }
            list.finish();
            assertEquals(cubes * 3, list.commandCount());
            assertEquals(cubes, list.recordCount());
            int total = 0;
            for (int layer = 0; layer < 3; layer++) {
                for (int page = 0; page < 3; page++) {
                    total += list.runCount(layer, page);
                    int[] first = command(list.commands(), list.runOffset(layer, page));
                    assertEquals(page * 4, first[2]);
                    assertEquals(page, first[3]);
                }
            }
            assertEquals(cubes * 3, total);
            assertEquals((float) (cubes - 1), list.records().getFloat((cubes - 1) * DrawCommands.RECORD_BYTES));
        }
    }

    @Test
    void theCubeMatrixIsTheModelViewMovedThenEnlarged() {
        float[] modelView = new float[16];
        modelView[0] = modelView[5] = modelView[10] = modelView[15] = 1;
        modelView[12] = 3;
        modelView[13] = -2;
        modelView[14] = 7;
        float[] out = new float[20];
        CubeMatrix.compose(modelView, 16, 32, -48, out, 4);
        assertEquals(CubeMatrix.CHUNK_SCALE, out[4]);
        assertEquals(CubeMatrix.CHUNK_SCALE, out[4 + 5]);
        assertEquals(CubeMatrix.CHUNK_SCALE, out[4 + 10]);
        assertEquals(19 + CubeMatrix.CHUNK_OFFSET, out[4 + 12]);
        assertEquals(30 + CubeMatrix.CHUNK_OFFSET, out[4 + 13]);
        assertEquals(-41 + CubeMatrix.CHUNK_OFFSET, out[4 + 14]);
        assertEquals(1f, out[4 + 15]);
        assertEquals(7.62939453e-06f, CubeMatrix.CHUNK_OFFSET);
        assertEquals(1.00000095f, CubeMatrix.CHUNK_SCALE);
    }

    @Test
    void tagsRunsAndStartsDescribeTheListForTheGpu() {
        DrawCommands list = new DrawCommands();
        list.begin(2);
        int a = list.record(matrix(100), 0, 41);
        list.add(0, 1, 500, 40, a);
        list.add(1, 0, 900, 8, a);
        int b = list.record(matrix(200), 0);
        list.add(0, 0, 0, 12, b);
        int c = list.record(matrix(300), 0, 7);
        list.add(0, 1, 700, 16, c);
        list.finish();

        ByteBuffer tags = list.tags();
        assertEquals(3 * 4, tags.remaining());
        assertArrayEquals(new int[] {41, DrawCommands.NO_TAG, 7}, new int[] {tags.getInt(0), tags.getInt(4), tags.getInt(8)});
        ByteBuffer runs = list.runs();
        ByteBuffer starts = list.starts();
        assertEquals(list.runSlots() * 4, starts.remaining());
        for (int layer = 0; layer < CubeSlots.LAYERS; layer++) {
            for (int page = 0; page < 2; page++) {
                int run = list.run(layer, page);
                int start = starts.getInt(run * 4);
                assertEquals(list.runOffset(layer, page), (long) start * DrawCommands.COMMAND_BYTES);
                for (int j = start; j < start + list.runCount(layer, page); j++) {
                    assertEquals(run, runs.getInt(j * 4));
                }
            }
        }
        assertEquals(list.commandCount() * 4, runs.remaining());
    }

    @Test
    void aLargerFrameAfterASmallerOneIsWrittenWhole() {
        DrawCommands list = new DrawCommands();
        list.begin(1);
        list.add(0, 0, 0, 4, list.record(matrix(1), 0, 3));
        list.finish();
        list.begin(3);
        for (int i = 0; i < 40; i++) {
            int record = list.record(matrix(i), 0, i);
            list.add(i % CubeSlots.LAYERS, i % 3, i * 4, 4, record);
        }
        list.finish();
        assertEquals(40 * 4, list.tags().remaining());
        assertEquals(39, list.tags().getInt(39 * 4));
        assertEquals(40 * 4, list.runs().remaining());
        assertEquals(list.runSlots() * 4, list.starts().remaining());
    }
}
