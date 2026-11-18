package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.CubeKey;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A diagnostic, off unless {@code -Dquadrature.terrain.probeRect=x0,y0,x1,y1[,width,height]}: names
 * the cubes whose held geometry lies in a rectangle of the screen, nearest first, with the light each
 * face carries, and sets each held mesh beside one made afresh from the cube source. The rectangle is
 * in pixels of a captured frame, y down; width and height are the capture's size when it differs from
 * the viewport's.
 *
 * <p>It reads the held vertex buffers back from the driver, so it is slow, and runs on one settled
 * frame in 240.
 */
final class ScreenProbe {

    static final ScreenProbe INSTANCE = parse(System.getProperty("quadrature.terrain.probeRect"));

    private static final int EVERY_FRAMES = 240;
    private static final int CUBES = 12;
    private static final int QUADS = 6;
    private static final String[] AXES = {"x", "y", "z"};

    /** With {@code -Dquadrature.terrain.probePoints=x,y;x,y;...}: pixels, as the rectangle's, to read back. */
    private static final float[] POINTS = points(System.getProperty("quadrature.terrain.probePoints"));

    private final float[] rectangle;
    private final float[] modelView = new float[16];
    private final float[] projection = new float[16];
    private final int[] viewport = new int[4];
    private final float[] eye = new float[4];
    private final float[] clip = new float[4];
    private float left;
    private float top;
    private float right;
    private float bottom;
    private int frames;
    private boolean sampling;

    private ScreenProbe(float[] rectangle) {
        this.rectangle = rectangle;
    }

    private static ScreenProbe parse(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",");
        if (parts.length != 4 && parts.length != 6) {
            Quadrature.LOGGER.error("quadrature.terrain.probeRect takes x0,y0,x1,y1[,width,height]: {}", text);
            return null;
        }
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return new ScreenProbe(values);
    }

    /** One face of a mesh that lies in the rectangle. */
    private static final class Face {
        float x;
        float y;
        float z;
        float distance;
        int layer;
        String normal;
        float blockLight;
        float skyLight;
        float shade;
    }

    /** What one mesh has in the rectangle. */
    private static final class Found {
        CubeMesh mesh;
        int faces;
        float nearest = Float.MAX_VALUE;
        final float[] low = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        final float[] high = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        final List<Face> near = new ArrayList<>();
    }

    private static float[] points(String text) {
        if (text == null) {
            return new float[0];
        }
        String[] parts = text.split("[,;]");
        float[] values = new float[parts.length / 2 * 2];
        for (int i = 0; i < values.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    /**
     * On a frame the probe ran, reads the color and depth the frame holds at each of the probe points
     * once a layer has been drawn, so that what a layer leaves there can be set beside the capture.
     */
    void sample(String after, boolean last) {
        if (!sampling) {
            return;
        }
        sampling = !last;
        float captureWidth = rectangle.length == 6 ? rectangle[4] : viewport[2];
        float captureHeight = rectangle.length == 6 ? rectangle[5] : viewport[3];
        ByteBuffer color = BufferUtils.createByteBuffer(4);
        float[] depth = new float[1];
        StringBuilder out = new StringBuilder("Screen probe, after " + after + ":");
        for (int i = 0; i < POINTS.length; i += 2) {
            int x = (int) (POINTS[i] * viewport[2] / captureWidth);
            int y = viewport[3] - 1 - (int) (POINTS[i + 1] * viewport[3] / captureHeight);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            float along = -projection[14] / (depth[0] * 2 - 1 + projection[10]);
            out.append(String.format(Locale.ROOT, "%n  point %.0f,%.0f: color %d %d %d, depth %.7f, %.2f blocks "
                    + "along the view axis", POINTS[i], POINTS[i + 1], color.get(0) & 255, color.get(1) & 255,
                color.get(2) & 255, depth[0], -along));
        }
        Quadrature.LOGGER.info(out.toString());
    }

    void probe(Long2ObjectOpenHashMap<CubeMesh> meshes, LongOpenHashSet selected, ICubeSource source,
        CubeMesher mesher, double cameraX, double cameraY, double cameraZ) {
        if (++frames % EVERY_FRAMES != 0) {
            return;
        }
        sampling = POINTS.length > 0;
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float captureWidth = rectangle.length == 6 ? rectangle[4] : viewport[2];
        float captureHeight = rectangle.length == 6 ? rectangle[5] : viewport[3];
        left = rectangle[0] / captureWidth;
        top = rectangle[1] / captureHeight;
        right = (rectangle[2] + 1) / captureWidth;
        bottom = (rectangle[3] + 1) / captureHeight;

        List<Found> all = new ArrayList<>();
        int unselected = 0;
        for (CubeMesh mesh : meshes.values()) {
            if (!boxTouches(mesh, cameraX, cameraY, cameraZ)) {
                continue;
            }
            Found found = scan(mesh, cameraX, cameraY, cameraZ);
            if (found.faces > 0) {
                all.add(found);
                if (!selected.contains(CubeKey.of(mesh.x, mesh.y, mesh.z))) {
                    unselected++;
                }
            }
        }
        all.sort(Comparator.comparingDouble(found -> found.nearest));
        int cameraCubeX = (int) Math.floor(cameraX) >> 4;
        int cameraCubeY = (int) Math.floor(cameraY) >> 4;
        int cameraCubeZ = (int) Math.floor(cameraZ) >> 4;
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "Screen probe: rectangle %.0f,%.0f to "
                + "%.0f,%.0f of a %.0f by %.0f capture, viewport %d by %d, camera %.2f %.2f %.2f in cube %d %d %d; "
                + "%d cubes hold faces in it, %d of them not selected by the game; the nearest %d:", rectangle[0],
            rectangle[1], rectangle[2], rectangle[3], captureWidth, captureHeight, viewport[2], viewport[3],
            cameraX, cameraY, cameraZ, cameraCubeX, cameraCubeY, cameraCubeZ, all.size(), unselected,
            Math.min(CUBES, all.size())));
        for (int i = 0; i < all.size() && i < CUBES; i++) {
            Found found = all.get(i);
            CubeMesh mesh = found.mesh;
            out.append(String.format(Locale.ROOT, "%n  cube %d %d %d (%d %d %d from the camera), %s, vertices "
                    + "%d/%d/%d, %d faces in the rectangle from %.1f blocks away, mesh spans x %.2f..%.2f y "
                    + "%.2f..%.2f z %.2f..%.2f", mesh.x, mesh.y, mesh.z, mesh.x - cameraCubeX, mesh.y - cameraCubeY,
                mesh.z - cameraCubeZ, selected.contains(CubeKey.of(mesh.x, mesh.y, mesh.z)) ? "selected"
                    : "NOT selected", mesh.count(0), mesh.count(1), mesh.count(2), found.faces, found.nearest,
                found.low[0], found.high[0], found.low[1], found.high[1], found.low[2], found.high[2]));
            faces(out, found);
            afresh(out, found, source, mesher, cameraX, cameraY, cameraZ);
        }
        Quadrature.LOGGER.info(out.toString());
    }

    private void faces(StringBuilder out, Found found) {
        for (Face face : found.near) {
            out.append(String.format(Locale.ROOT, "%n      face at %.2f %.2f %.2f facing %s, layer %d, %.1f blocks, "
                    + "sky light %.1f, block light %.1f, shade %.0f", face.x, face.y, face.z, face.normal, face.layer,
                face.distance, face.skyLight, face.blockLight, face.shade));
        }
    }

    /** Meshes the cube again from the source as it is now and says how the held mesh differs from that. */
    private void afresh(StringBuilder out, Found found, ICubeSource source, CubeMesher mesher, double cameraX,
        double cameraY, double cameraZ) {
        CubeMesh held = found.mesh;
        if (!source.isLoaded(held.x, held.y, held.z)) {
            out.append(String.format("%n      the cube source no longer has this cube loaded"));
            return;
        }
        ICubeSnapshot snapshot = source.snapshot(held.x, held.y, held.z);
        int present = 0;
        StringBuilder missing = new StringBuilder();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (snapshot.present(dx, dy, dz)) {
                        present++;
                    } else {
                        missing.append(' ').append(dx).append(',').append(dy).append(',').append(dz);
                    }
                }
            }
        }
        CubeMesh fresh = null;
        try {
            BuiltCube built = mesher.build(snapshot, held.x, held.y, held.z, 0, null);
            if (built != null && built.any) {
                fresh = CubeMesh.owned(held.x, held.y, held.z, built);
            }
        } catch (InterruptedException never) {
            // Without a task nothing waits.
            Thread.currentThread().interrupt();
        }
        snapshot.release();
        out.append(String.format("%n      %d of 27 cubes of its neighborhood loaded%s", present,
            missing.length() == 0 ? "" : "; missing at offsets" + missing));
        if (fresh == null) {
            out.append("; meshed afresh it is empty");
            return;
        }
        boolean sameCounts = true;
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            sameCounts &= fresh.count(layer) == held.count(layer);
        }
        if (!sameCounts) {
            out.append(String.format("; meshed afresh it has vertices %d/%d/%d", fresh.count(0), fresh.count(1),
                fresh.count(2)));
        } else {
            int position = 0;
            int color = 0;
            int light = 0;
            for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
                if (held.count(layer) == 0) {
                    continue;
                }
                ByteBuffer a = read(held, layer);
                ByteBuffer b = read(fresh, layer);
                for (int at = 0; at < a.capacity(); at += CubeMesh.STRIDE) {
                    if (a.getFloat(at) != b.getFloat(at) || a.getFloat(at + 4) != b.getFloat(at + 4)
                        || a.getFloat(at + 8) != b.getFloat(at + 8)) {
                        position++;
                    }
                    if (a.getInt(at + 12) != b.getInt(at + 12)) {
                        color++;
                    }
                    if (a.getInt(at + 24) != b.getInt(at + 24)) {
                        light++;
                    }
                }
            }
            if (position + color + light == 0) {
                out.append("; meshed afresh it is identical");
                fresh.delete();
                return;
            }
            out.append(String.format("; meshed afresh the vertex counts are the same, and %d vertices differ in "
                + "position, %d in color, %d in light", position, color, light));
        }
        Found again = scan(fresh, cameraX, cameraY, cameraZ);
        out.append(String.format("%n      afresh it has %d faces in the rectangle:", again.faces));
        faces(out, again);
        fresh.delete();
    }

    /** Whether the cube's box, projected, touches the rectangle. A box partly behind the camera counts. */
    private boolean boxTouches(CubeMesh mesh, double cameraX, double cameraY, double cameraZ) {
        float minX = 1;
        float minY = 1;
        float maxX = 0;
        float maxY = 0;
        int behind = 0;
        for (int corner = 0; corner < 8; corner++) {
            if (!project((float) ((mesh.x << 4) + (corner & 1) * 16 - cameraX),
                (float) ((mesh.y << 4) + (corner >> 1 & 1) * 16 - cameraY),
                (float) ((mesh.z << 4) + (corner >> 2) * 16 - cameraZ))) {
                behind++;
                continue;
            }
            minX = Math.min(minX, clip[0]);
            maxX = Math.max(maxX, clip[0]);
            minY = Math.min(minY, clip[1]);
            maxY = Math.max(maxY, clip[1]);
        }
        if (behind == 8) {
            return false;
        }
        return behind > 0 || minX <= right && maxX >= left && minY <= bottom && maxY >= top;
    }

    private Found scan(CubeMesh mesh, double cameraX, double cameraY, double cameraZ) {
        Found found = new Found();
        found.mesh = mesh;
        float baseX = (float) ((mesh.x << 4) - cameraX);
        float baseY = (float) ((mesh.y << 4) - cameraY);
        float baseZ = (float) ((mesh.z << 4) - cameraZ);
        float[] px = new float[4];
        float[] py = new float[4];
        float[] pz = new float[4];
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            if (mesh.count(layer) == 0) {
                continue;
            }
            ByteBuffer data = read(mesh, layer);
            for (int at = 0; at + 4 * CubeMesh.STRIDE <= data.capacity(); at += 4 * CubeMesh.STRIDE) {
                float x = 0;
                float y = 0;
                float z = 0;
                float block = 0;
                float sky = 0;
                float shade = 0;
                for (int vertex = 0; vertex < 4; vertex++) {
                    int v = at + vertex * CubeMesh.STRIDE;
                    px[vertex] = data.getFloat(v);
                    py[vertex] = data.getFloat(v + 4);
                    pz[vertex] = data.getFloat(v + 8);
                    x += px[vertex] / 4;
                    y += py[vertex] / 4;
                    z += pz[vertex] / 4;
                    shade += ((data.get(v + 12) & 255) + (data.get(v + 13) & 255) + (data.get(v + 14) & 255)) / 12f;
                    block += data.getShort(v + 24) / 64f;
                    sky += data.getShort(v + 26) / 64f;
                    found.low[0] = Math.min(found.low[0], px[vertex]);
                    found.low[1] = Math.min(found.low[1], py[vertex]);
                    found.low[2] = Math.min(found.low[2], pz[vertex]);
                    found.high[0] = Math.max(found.high[0], px[vertex]);
                    found.high[1] = Math.max(found.high[1], py[vertex]);
                    found.high[2] = Math.max(found.high[2], pz[vertex]);
                }
                if (!project(baseX + x, baseY + y, baseZ + z) || clip[0] < left || clip[0] > right || clip[1] < top
                    || clip[1] > bottom) {
                    continue;
                }
                float distance = (float) Math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]);
                found.faces++;
                found.nearest = Math.min(found.nearest, distance);
                int slot = found.near.size();
                if (slot == QUADS && found.near.get(slot - 1).distance <= distance) {
                    continue;
                }
                Face face = slot == QUADS ? found.near.remove(slot - 1) : new Face();
                face.x = (mesh.x << 4) + x;
                face.y = (mesh.y << 4) + y;
                face.z = (mesh.z << 4) + z;
                face.distance = distance;
                face.layer = layer;
                face.normal = normal(px, py, pz);
                face.blockLight = block;
                face.skyLight = sky;
                face.shade = shade;
                int into = found.near.size();
                while (into > 0 && found.near.get(into - 1).distance > distance) {
                    into--;
                }
                found.near.add(into, face);
            }
        }
        return found;
    }

    /** The axis the face looks along, from its first three corners. */
    private static String normal(float[] x, float[] y, float[] z) {
        float ax = x[1] - x[0];
        float ay = y[1] - y[0];
        float az = z[1] - z[0];
        float bx = x[2] - x[0];
        float by = y[2] - y[0];
        float bz = z[2] - z[0];
        float[] n = {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
        int axis = Math.abs(n[0]) >= Math.abs(n[1]) && Math.abs(n[0]) >= Math.abs(n[2]) ? 0
            : Math.abs(n[1]) >= Math.abs(n[2]) ? 1 : 2;
        float length = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        boolean square = length > 0 && Math.abs(n[axis]) > 0.99f * length;
        return (n[axis] < 0 ? "-" : "+") + AXES[axis] + (square ? "" : " (slanted)");
    }

    /**
     * Projects a point given relative to the camera. Leaves the eye-space point in {@link #eye} and the
     * screen position, each axis 0 to 1 with y down, in {@link #clip}.
     *
     * @return false when the point is behind the camera
     */
    private boolean project(float x, float y, float z) {
        for (int row = 0; row < 4; row++) {
            eye[row] = modelView[row] * x + modelView[4 + row] * y + modelView[8 + row] * z + modelView[12 + row];
        }
        for (int row = 0; row < 4; row++) {
            clip[row] = projection[row] * eye[0] + projection[4 + row] * eye[1] + projection[8 + row] * eye[2]
                + projection[12 + row] * eye[3];
        }
        if (clip[3] <= 0) {
            return false;
        }
        clip[0] = clip[0] / clip[3] * 0.5f + 0.5f;
        clip[1] = 0.5f - clip[1] / clip[3] * 0.5f;
        return true;
    }

    private static ByteBuffer read(CubeMesh mesh, int layer) {
        ByteBuffer data = BufferUtils.createByteBuffer(mesh.count(layer) * CubeMesh.STRIDE);
        GL45.glGetNamedBufferSubData(mesh.buffer(layer), (long) mesh.first(layer) * CubeMesh.STRIDE, data);
        return data;
    }
}
