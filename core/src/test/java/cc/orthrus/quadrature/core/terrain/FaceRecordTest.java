package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FaceRecordTest {

    @Test
    void aRecordGivesBackEveryFieldPutIn() {
        int[] words = new int[FaceRecord.WORDS + 2];
        int[] colors = {0x112233, 0xFFEEDD, 0x7F0080, 0xA1B2C3};
        int[] lights = {0xF000, 0x00F0, 0x7788, 0xF0F0};
        FaceRecord.pack(words, 1, 15, 0, 9, CubeConnectivity.EAST, 0b11_10_01_00, 0b00_01_11_10, 40000, 65535,
            0x79C05A, FaceRecord.TINTED | FaceRecord.SHADED | FaceRecord.FOLIAGE, colors, lights);
        assertEquals(15, FaceRecord.x(words, 1));
        assertEquals(0, FaceRecord.y(words, 1));
        assertEquals(9, FaceRecord.z(words, 1));
        assertEquals(CubeConnectivity.EAST, FaceRecord.face(words, 1));
        assertEquals(40000, FaceRecord.sprite(words, 1));
        assertEquals(65535, FaceRecord.state(words, 1));
        assertEquals(0x79C05A, FaceRecord.tint(words, 1));
        assertEquals(FaceRecord.TINTED | FaceRecord.SHADED | FaceRecord.FOLIAGE, FaceRecord.flags(words, 1));
        for (int vertex = 0; vertex < 4; vertex++) {
            assertEquals(vertex, FaceRecord.faceCorner(words, 1, vertex));
            assertEquals(colors[vertex], FaceRecord.color(words, 1, vertex));
            assertEquals(lights[vertex], FaceRecord.light(words, 1, vertex));
        }
        assertEquals(2, FaceRecord.spriteCorner(words, 1, 0));
        assertEquals(3, FaceRecord.spriteCorner(words, 1, 1));
        assertEquals(1, FaceRecord.spriteCorner(words, 1, 2));
        assertEquals(0, FaceRecord.spriteCorner(words, 1, 3));
        assertEquals(0, words[0]);
        assertEquals(0, words[9]);
        assertEquals(32, FaceRecord.BYTES);
    }

    @Test
    void theCornersOfEachFaceLieOnItsSideOfTheCell() {
        for (int corner = 0; corner < 4; corner++) {
            assertEquals(0, FaceRecord.cornerOffset(CubeConnectivity.DOWN, corner, 1));
            assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.UP, corner, 1));
            assertEquals(0, FaceRecord.cornerOffset(CubeConnectivity.NORTH, corner, 2));
            assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.SOUTH, corner, 2));
            assertEquals(0, FaceRecord.cornerOffset(CubeConnectivity.WEST, corner, 0));
            assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.EAST, corner, 0));
        }
        // Up and down: x then z. North and south: x then y. West and east: z then y.
        assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.UP, 1, 0));
        assertEquals(0, FaceRecord.cornerOffset(CubeConnectivity.UP, 1, 2));
        assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.UP, 2, 2));
        assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.SOUTH, 2, 1));
        assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.WEST, 1, 2));
        assertEquals(1, FaceRecord.cornerOffset(CubeConnectivity.WEST, 2, 1));
    }

    @Test
    void fourVerticesAreLocatedAsAFaceAndGoBackToTheSamePlaces() {
        // The top of cell (3, 7, 12), wound as a model might: x+z+, x+z-, x-z-, x-z+.
        float[] top = {4, 8, 13, 4, 8, 12, 3, 8, 12, 3, 8, 13};
        int found = FaceRecord.locate(top, 3, 7, 12);
        assertEquals(CubeConnectivity.UP, found >> 8);
        int[] cell = {3, 7, 12};
        for (int vertex = 0; vertex < 4; vertex++) {
            int corner = found >> vertex * 2 & 3;
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(top[vertex * 3 + axis], (float) (cell[axis] + FaceRecord.cornerOffset(CubeConnectivity.UP, corner, axis)));
            }
        }
        // Every face of a cell, each vertex order a quad can have.
        int[][] orders = {{0, 1, 3, 2}, {1, 3, 2, 0}, {2, 3, 1, 0}, {3, 1, 0, 2}};
        for (int face = 0; face < 6; face++) {
            for (int[] order : orders) {
                float[] positions = new float[12];
                for (int vertex = 0; vertex < 4; vertex++) {
                    for (int axis = 0; axis < 3; axis++) {
                        positions[vertex * 3 + axis] = cell[axis] + FaceRecord.cornerOffset(face, order[vertex], axis);
                    }
                }
                int located = FaceRecord.locate(positions, 3, 7, 12);
                assertEquals(face, located >> 8);
                for (int vertex = 0; vertex < 4; vertex++) {
                    assertEquals(order[vertex], located >> vertex * 2 & 3);
                }
            }
        }
    }

    @Test
    void aQuadThatIsNotAWholeFaceOfTheCellIsNotLocated() {
        // A slab's top, half way up the cell.
        assertEquals(-1, FaceRecord.locate(new float[] {4, 7.5f, 13, 4, 7.5f, 12, 3, 7.5f, 12, 3, 7.5f, 13}, 3, 7, 12));
        // Half a face.
        assertEquals(-1, FaceRecord.locate(new float[] {3.5f, 8, 13, 3.5f, 8, 12, 3, 8, 12, 3, 8, 13}, 3, 7, 12));
        // A plant's crossed quad.
        assertEquals(-1, FaceRecord.locate(new float[] {3, 7, 12, 4, 7, 13, 4, 8, 13, 3, 8, 12}, 3, 7, 12));
        // A face of the cell beside it.
        assertEquals(-1, FaceRecord.locate(new float[] {5, 8, 13, 5, 8, 12, 4, 8, 12, 4, 8, 13}, 3, 7, 12));
        // The same corner twice.
        assertEquals(-1, FaceRecord.locate(new float[] {4, 8, 13, 4, 8, 13, 3, 8, 12, 3, 8, 13}, 3, 7, 12));
    }

    @Test
    void theRectangleOfTextureIsTheQuadsOwn() {
        // As the game bakes a quad: each corner of the sprite drawn a little toward its middle.
        float minU = 0.25f;
        float maxU = 0.28125f;
        float minV = 0.5f;
        float maxV = 0.53125f;
        float midU = (minU + maxU) / 2;
        float midV = (minV + maxV) / 2;
        float lowU = minU + (midU - minU) * 0.001f;
        float highU = maxU + (midU - maxU) * 0.001f;
        float lowV = minV + (midV - minV) * 0.001f;
        float highV = maxV + (midV - maxV) * 0.001f;
        assertTrue(lowU != minU && highU != maxU && lowV != minV && highV != maxV);
        float[] rectangle = new float[4];
        int corners = FaceRecord.rectangle(new float[] {highU, lowV, highU, highV, lowU, highV, lowU, lowV}, rectangle);
        assertEquals(1, corners & 3);
        assertEquals(3, corners >> 2 & 3);
        assertEquals(2, corners >> 4 & 3);
        assertEquals(0, corners >> 6 & 3);
        assertEquals(Float.floatToRawIntBits(lowU), Float.floatToRawIntBits(rectangle[0]));
        assertEquals(Float.floatToRawIntBits(lowV), Float.floatToRawIntBits(rectangle[1]));
        assertEquals(Float.floatToRawIntBits(highU), Float.floatToRawIntBits(rectangle[2]));
        assertEquals(Float.floatToRawIntBits(highV), Float.floatToRawIntBits(rectangle[3]));
    }

    @Test
    void coordinatesThatAreNotARectanglesFourCornersAreRefused() {
        float[] rectangle = new float[4];
        // A third value of v.
        assertEquals(-1, FaceRecord.rectangle(new float[] {0.3f, 0.5f, 0.3f, 0.52f, 0.25f, 0.53f, 0.25f, 0.5f}, rectangle));
        // A corner twice.
        assertEquals(-1, FaceRecord.rectangle(new float[] {0.3f, 0.5f, 0.3f, 0.5f, 0.25f, 0.53f, 0.25f, 0.5f}, rectangle));
        // No width.
        assertEquals(-1, FaceRecord.rectangle(new float[] {0.3f, 0.5f, 0.3f, 0.53f, 0.3f, 0.53f, 0.3f, 0.5f}, rectangle));
    }
}