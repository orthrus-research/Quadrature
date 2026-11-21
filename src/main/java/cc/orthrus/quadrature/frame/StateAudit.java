package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A diagnostic, off unless {@code -Dquadrature.stateAudit=true}: reads a fixed list of OpenGL state at
 * every stage boundary for a window of frames and reports what each stage leaves different from what
 * it found. It answers two questions the design depends on: which state the game and other mods really
 * use, and which stage leaves state behind.
 *
 * <p>Every read is a query that synchronizes with the driver, so this never runs in a normal frame.
 */
final class StateAudit {

    static final boolean ENABLED = Boolean.getBoolean("quadrature.stateAudit");

    /** Frames to skip first, so loading and the first chunk builds are over. */
    private static final int WARM_UP_FRAMES = 240;
    private static final int SAMPLED_FRAMES = 120;
    /**
     * With {@code -Dquadrature.stateAudit.every=<frames>}, another window starts that many frames after each one
     * started (at least one window's length plus a frame), each reported on its own with where the viewer stood: an
     * audit of what a tour shows later, such as a mod's geometry that appears at one place only. Unset or 0, the first
     * window alone is reported.
     */
    private static final int EVERY = Math.max(0, Integer.getInteger("quadrature.stateAudit.every", 0));

    private interface Reader {
        String read();
    }

    private static final Map<String, Reader> STATE = new LinkedHashMap<>();

    private static void integer(String name, int pname) {
        STATE.put(name, () -> Integer.toString(GL11.glGetInteger(pname)));
    }

    private static void symbol(String name, int pname) {
        STATE.put(name, () -> "0x" + Integer.toHexString(GL11.glGetInteger(pname)));
    }

    private static void enabled(String name, int capability) {
        STATE.put(name, () -> GL11.glIsEnabled(capability) ? "on" : "off");
    }

    private static void decimal(String name, int pname) {
        STATE.put(name, () -> Float.toString(GL11.glGetFloat(pname)));
    }

    static {
        // State the game's cache does not know: what a modern renderer adds.
        integer("program", GL20.GL_CURRENT_PROGRAM);
        integer("vertex array object", GL30.GL_VERTEX_ARRAY_BINDING);
        integer("draw framebuffer", GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        integer("read framebuffer", GL30.GL_READ_FRAMEBUFFER_BINDING);
        integer("array buffer", GL15.GL_ARRAY_BUFFER_BINDING);
        integer("element buffer", GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        STATE.put("sampler, unit 0", () -> Integer.toString(GL45.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0)));
        STATE.put("sampler, unit 1", () -> Integer.toString(GL45.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1)));
        integer("unpack alignment", GL11.GL_UNPACK_ALIGNMENT);
        integer("pack alignment", GL11.GL_PACK_ALIGNMENT);
        symbol("matrix mode", GL11.GL_MATRIX_MODE);
        integer("attribute stack depth", GL11.GL_ATTRIB_STACK_DEPTH);
        integer("modelview stack depth", GL11.GL_MODELVIEW_STACK_DEPTH);
        integer("projection stack depth", GL11.GL_PROJECTION_STACK_DEPTH);
        // State the game's cache tracks.
        symbol("active texture unit", GL13.GL_ACTIVE_TEXTURE);
        symbol("client active texture unit", GL13.GL_CLIENT_ACTIVE_TEXTURE);
        STATE.put("texture, unit 0", () -> Integer.toString(GL45.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, 0)));
        STATE.put("texture, unit 1", () -> Integer.toString(GL45.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, 1)));
        enabled("depth test", GL11.GL_DEPTH_TEST);
        symbol("depth function", GL11.GL_DEPTH_FUNC);
        integer("depth mask", GL11.GL_DEPTH_WRITEMASK);
        decimal("depth clear value", GL11.GL_DEPTH_CLEAR_VALUE);
        enabled("blend", GL11.GL_BLEND);
        symbol("blend source", GL14.GL_BLEND_SRC_RGB);
        symbol("blend destination", GL14.GL_BLEND_DST_RGB);
        enabled("alpha test", GL11.GL_ALPHA_TEST);
        symbol("alpha function", GL11.GL_ALPHA_TEST_FUNC);
        decimal("alpha reference", GL11.GL_ALPHA_TEST_REF);
        enabled("cull face", GL11.GL_CULL_FACE);
        enabled("lighting", GL11.GL_LIGHTING);
        enabled("fog", GL11.GL_FOG);
        enabled("color material", GL11.GL_COLOR_MATERIAL);
        enabled("polygon offset fill", GL11.GL_POLYGON_OFFSET_FILL);
        decimal("polygon offset factor", GL11.GL_POLYGON_OFFSET_FACTOR);
        enabled("stencil test", GL11.GL_STENCIL_TEST);
        enabled("scissor test", GL11.GL_SCISSOR_TEST);
        enabled("texturing, active unit", GL11.GL_TEXTURE_2D);
        enabled("rescale normal", GL12.GL_RESCALE_NORMAL);
        enabled("normalize", GL11.GL_NORMALIZE);
        symbol("shade model", GL11.GL_SHADE_MODEL);
        decimal("line width", GL11.GL_LINE_WIDTH);
    }

    private final String[] names = STATE.keySet().toArray(new String[0]);
    private final Reader[] readers = STATE.values().toArray(new Reader[0]);
    private final String[] before = new String[names.length];
    /** stage -> state name -> "found -> left" -> frames in which the stage did that. */
    private final Map<String, Map<String, Map<String, Integer>>> changes = new LinkedHashMap<>();
    /** state name -> values seen at the start of a pass. */
    private final Map<String, Map<String, Integer>> atPassStart = new LinkedHashMap<>();
    private int frame;
    private String currentStage;
    /** The frame the current window starts at; -1 once the last window is reported. */
    private int windowStart = WARM_UP_FRAMES;

    boolean sampling() {
        return windowStart >= 0 && frame >= windowStart;
    }

    void beginPass() {
        frame++;
        if (!sampling()) {
            return;
        }
        read(before);
        for (int i = 0; i < names.length; i++) {
            atPassStart.computeIfAbsent(names[i], key -> new TreeMap<>()).merge(before[i], 1, Integer::sum);
        }
        currentStage = "before the first stage";
    }

    /** A new stage begins: whatever differs from the last reading was left by the stage that just ended. */
    void boundary(String nextStage) {
        if (!sampling() || currentStage == null) {
            return;
        }
        String[] after = new String[names.length];
        read(after);
        changes.computeIfAbsent(currentStage, key -> new LinkedHashMap<>());
        for (int i = 0; i < names.length; i++) {
            if (!after[i].equals(before[i])) {
                changes.computeIfAbsent(currentStage, key -> new LinkedHashMap<>())
                    .computeIfAbsent(names[i], key -> new TreeMap<>())
                    .merge(before[i] + " -> " + after[i], 1, Integer::sum);
            }
        }
        System.arraycopy(after, 0, before, 0, after.length);
        currentStage = nextStage;
    }

    void endPass() {
        if (!sampling()) {
            return;
        }
        boundary(null);
        currentStage = null;
        if (frame >= windowStart + SAMPLED_FRAMES) {
            report();
            changes.clear();
            atPassStart.clear();
            windowStart = EVERY > 0 ? windowStart + Math.max(EVERY, SAMPLED_FRAMES + 1) : -1;
        }
    }

    private void read(String[] into) {
        for (int i = 0; i < readers.length; i++) {
            into[i] = readers[i].read();
        }
    }

    private void report() {
        List<String> lines = new ArrayList<>();
        net.minecraft.entity.Entity viewer = net.minecraft.client.Minecraft.getMinecraft().getRenderViewEntity();
        String where = viewer == null ? "no viewer"
            : String.format(java.util.Locale.ROOT, "viewer at %.1f, %.1f, %.1f", viewer.posX, viewer.posY, viewer.posZ);
        lines.add("State audit over " + SAMPLED_FRAMES + " frames (" + windowStart + " to " + frame + "; " + where
            + "), " + names.length + " items.");
        lines.add("State at the start of the world pass (value x frames):");
        atPassStart.forEach((name, values) -> lines.add("  " + name + ": " + values));
        // One depth pixel at the middle of the window, as the driver holds it and as hosted code reads it.
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int x = viewport[0] + viewport[2] / 2;
        int y = viewport[1] + viewport[3] / 2;
        float[] driver = new float[1];
        float[] hosted = new float[1];
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, driver);
        cc.orthrus.quadrature.depth.DepthCalls.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
            hosted);
        lines.add("Depth at the middle of the window: driver " + driver[0] + ", as hosted code reads it "
            + hosted[0]);
        lines.add("State each stage left different from what it found (found -> left x frames):");
        changes.forEach((stage, items) -> {
            lines.add("  " + stage + ":" + (items.isEmpty() ? " nothing" : ""));
            items.forEach((name, transitions) -> lines.add("    " + name + ": " + transitions));
        });
        Quadrature.LOGGER.info(String.join(System.lineSeparator(), lines));
    }
}
