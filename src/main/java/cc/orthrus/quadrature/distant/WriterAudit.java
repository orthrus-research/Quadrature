package cc.orthrus.quadrature.distant;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import cc.orthrus.quadrature.Quadrature;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * A diagnostic, off unless {@code -Dquadrature.distant.writerAudit=true}: the first few times the
 * pass into near terrain's targets draws, a list of the driver's state is read just before it and
 * just after it has put back what it touched, and what reads differently is logged. The list is
 * made here, apart from the one the pass restores by ({@link WriterState}) and wider than it, so a
 * slot the pass changes and does not know of shows. The frame's own state audit reads at stage
 * boundaries in a window a few seconds after a world is joined, before a cold client has a tile
 * to draw, and so does not see this pass.
 *
 * <p>Every read waits on the driver; the audited passes are slower for it and no others are.
 */
final class WriterAudit {

    static final boolean ENABLED = Boolean.getBoolean("quadrature.distant.writerAudit");

    /** How many drawing passes are audited. */
    private static final int PASSES = 3;

    private final List<String> names = new ArrayList<>();
    private final List<IntSupplier> readers = new ArrayList<>();
    private final IntBuffer four = BufferUtils.createIntBuffer(16);
    private int[] before;
    private int audited;

    WriterAudit() {
        integer("program", GL20.GL_CURRENT_PROGRAM);
        integer("vertex array", GL30.GL_VERTEX_ARRAY_BINDING);
        integer("draw framebuffer", GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        integer("read framebuffer", GL30.GL_READ_FRAMEBUFFER_BINDING);
        integer("renderbuffer", GL30.GL_RENDERBUFFER_BINDING);
        integer("array buffer", GL15.GL_ARRAY_BUFFER_BINDING);
        integer("element buffer", GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        integer("draw-indirect buffer", GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        integer("shader storage buffer, general", GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        integer("uniform buffer, general", GL31.GL_UNIFORM_BUFFER_BINDING);
        for (int point = 0; point < 8; point++) {
            int at = point;
            add("shader storage point " + point, () -> GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, at));
            add("uniform buffer point " + point, () -> GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, at));
        }
        for (int unit = 0; unit < 10; unit++) {
            int at = unit;
            add("2D texture, unit " + unit, () -> GL30.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, at));
            add("2D array texture, unit " + unit, () -> GL30.glGetIntegeri(GL30.GL_TEXTURE_BINDING_2D_ARRAY, at));
            add("sampler, unit " + unit, () -> GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, at));
        }
        integer("active texture unit", GL13.GL_ACTIVE_TEXTURE);
        integer("client active texture unit", GL13.GL_CLIENT_ACTIVE_TEXTURE);
        integer("unpack alignment", GL11.GL_UNPACK_ALIGNMENT);
        integer("pack alignment", GL11.GL_PACK_ALIGNMENT);
        for (int part = 0; part < 4; part++) {
            int at = part;
            add("viewport " + "xywh".charAt(part), () -> {
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, four);
                return four.get(at);
            });
            add("color mask " + "rgba".charAt(part), () -> {
                GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, four);
                return four.get(at);
            });
        }
        enabled("depth test", GL11.GL_DEPTH_TEST);
        integer("depth function", GL11.GL_DEPTH_FUNC);
        integer("depth mask", GL11.GL_DEPTH_WRITEMASK);
        enabled("blend", GL11.GL_BLEND);
        integer("blend source color", GL14.GL_BLEND_SRC_RGB);
        integer("blend destination color", GL14.GL_BLEND_DST_RGB);
        integer("blend source alpha", GL14.GL_BLEND_SRC_ALPHA);
        integer("blend destination alpha", GL14.GL_BLEND_DST_ALPHA);
        enabled("alpha test", GL11.GL_ALPHA_TEST);
        integer("alpha function", GL11.GL_ALPHA_TEST_FUNC);
        enabled("cull face", GL11.GL_CULL_FACE);
        integer("cull face mode", GL11.GL_CULL_FACE_MODE);
        integer("front face", GL11.GL_FRONT_FACE);
        enabled("stencil test", GL11.GL_STENCIL_TEST);
        enabled("scissor test", GL11.GL_SCISSOR_TEST);
        enabled("polygon offset fill", GL11.GL_POLYGON_OFFSET_FILL);
        enabled("fog", GL11.GL_FOG);
        enabled("lighting", GL11.GL_LIGHTING);
        enabled("texturing, active unit", GL11.GL_TEXTURE_2D);
        integer("matrix mode", GL11.GL_MATRIX_MODE);
        integer("modelview stack depth", GL11.GL_MODELVIEW_STACK_DEPTH);
        integer("projection stack depth", GL11.GL_PROJECTION_STACK_DEPTH);
        integer("attribute stack depth", GL11.GL_ATTRIB_STACK_DEPTH);
        integer("draw buffer", GL11.GL_DRAW_BUFFER);
        integer("read buffer", GL11.GL_READ_BUFFER);
        integer("polygon mode", GL11.GL_POLYGON_MODE);
    }

    private void add(String name, IntSupplier reader) {
        names.add(name);
        readers.add(reader);
    }

    private void integer(String name, int pname) {
        add(name, () -> GL11.glGetInteger(pname));
    }

    private void enabled(String name, int capability) {
        add(name, () -> GL11.glIsEnabled(capability) ? 1 : 0);
    }

    /** Whether a pass about to draw is to be audited. */
    boolean due() {
        return audited < PASSES;
    }

    /** Just before the pass saves and binds. */
    void before() {
        before = read();
    }

    /** Just after the pass has put back what it touched. */
    void after() {
        int[] after = read();
        StringBuilder changed = new StringBuilder();
        int count = 0;
        for (int i = 0; i < after.length; i++) {
            if (after[i] != before[i]) {
                count++;
                changed.append(String.format("%n  %s: found %d (0x%x), left %d (0x%x)", names.get(i), before[i],
                    before[i], after[i], after[i]));
            }
        }
        audited++;
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < after.length; i++) {
            found.append(i == 0 ? "" : ", ").append(names.get(i)).append(' ').append(before[i]);
        }
        Quadrature.LOGGER.info("Writer audit, pass {} of {}: {} items read before and after the pass into the targets, "
            + "{} left changed.{}", audited, PASSES, after.length, count, changed);
        if (audited == 1) {
            Quadrature.LOGGER.info("Writer audit, state found on entry: {}", found);
        }
    }

    private int[] read() {
        int[] values = new int[readers.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = readers.get(i).getAsInt();
        }
        return values;
    }
}
