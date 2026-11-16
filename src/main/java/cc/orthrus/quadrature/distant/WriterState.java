package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.core.view.StateGuard;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * The driver's state that the pass into near terrain's targets binds or switches, as slots for a
 * {@link StateGuard}: everything on this list reads after the pass as it did before it. The list
 * is the pass's whole footprint outside its own objects and the targets it writes; docs/DISTANT.md
 * section 16.9 gives it in words.
 *
 * <p>Order matters in three places. A buffer bound to an indexed shader storage point also becomes
 * the general binding, so the general one is restored after the five points. The element buffer
 * binding belongs to the vertex array bound, so it follows the vertex array. The viewport's four
 * numbers are set together, each write setting all four as last read and written.
 */
final class WriterState implements StateGuard.Access {

    static final int DRAW_FRAMEBUFFER = 0;
    static final int READ_FRAMEBUFFER = 1;
    static final int VIEWPORT_X = 2;
    static final int VIEWPORT_Y = 3;
    static final int VIEWPORT_WIDTH = 4;
    static final int VIEWPORT_HEIGHT = 5;
    static final int DEPTH_TEST = 6;
    static final int DEPTH_FUNCTION = 7;
    static final int DEPTH_MASK = 8;
    static final int BLEND = 9;
    static final int ALPHA_TEST = 10;
    static final int CULL_FACE = 11;
    static final int PROGRAM = 12;
    static final int VERTEX_ARRAY = 13;
    static final int ELEMENT_BUFFER = 14;
    static final int ARRAY_BUFFER = 15;
    static final int INDIRECT_BUFFER = 16;
    /** Shader storage points 0 to 4, then the general binding. */
    static final int STORAGE_POINT_0 = 17;
    static final int STORAGE_GENERAL = 22;
    /** The cover map's unit, a 2D texture, and the images' unit, a 2D array. */
    static final int COVER_TEXTURE = 23;
    static final int IMAGES_TEXTURE = 24;
    static final int SAMPLER_0 = 25;
    static final int SAMPLER_1 = 26;
    static final int UNPACK_ALIGNMENT = 27;
    static final int PACK_ALIGNMENT = 28;
    static final int ACTIVE_TEXTURE = 29;

    /** Every slot, in the order they are put back. */
    static int[] all() {
        int[] slots = new int[ACTIVE_TEXTURE + 1];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private final int coverUnit;
    private final int imagesUnit;
    private final java.nio.IntBuffer four = BufferUtils.createIntBuffer(16);
    private final int[] viewport = new int[4];

    WriterState(int coverUnit, int imagesUnit) {
        this.coverUnit = coverUnit;
        this.imagesUnit = imagesUnit;
    }

    @Override
    public int read(int slot) {
        if (slot >= VIEWPORT_X && slot <= VIEWPORT_HEIGHT) {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, four);
            for (int i = 0; i < 4; i++) {
                viewport[i] = four.get(i);
            }
            return viewport[slot - VIEWPORT_X];
        }
        if (slot >= STORAGE_POINT_0 && slot < STORAGE_GENERAL) {
            return GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, slot - STORAGE_POINT_0);
        }
        switch (slot) {
            case DRAW_FRAMEBUFFER:
                return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            case READ_FRAMEBUFFER:
                return GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            case DEPTH_TEST:
                return GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0;
            case DEPTH_FUNCTION:
                return GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            case DEPTH_MASK:
                return GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0;
            case BLEND:
                return GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0;
            case ALPHA_TEST:
                return GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 1 : 0;
            case CULL_FACE:
                return GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0;
            case PROGRAM:
                return GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            case VERTEX_ARRAY:
                return GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            case ELEMENT_BUFFER:
                return GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            case ARRAY_BUFFER:
                return GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            case INDIRECT_BUFFER:
                return GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
            case STORAGE_GENERAL:
                return GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
            case COVER_TEXTURE:
                return GL30.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, coverUnit);
            case IMAGES_TEXTURE:
                return GL30.glGetIntegeri(GL30.GL_TEXTURE_BINDING_2D_ARRAY, imagesUnit);
            case SAMPLER_0:
                return GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0);
            case SAMPLER_1:
                return GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1);
            case UNPACK_ALIGNMENT:
                return GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            case PACK_ALIGNMENT:
                return GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            case ACTIVE_TEXTURE:
                return GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            default:
                throw new IllegalArgumentException("No such slot: " + slot);
        }
    }

    @Override
    public void write(int slot, int value) {
        if (slot >= VIEWPORT_X && slot <= VIEWPORT_HEIGHT) {
            // The read before this write filled the other three.
            viewport[slot - VIEWPORT_X] = value;
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            return;
        }
        if (slot >= STORAGE_POINT_0 && slot < STORAGE_GENERAL) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, slot - STORAGE_POINT_0, value);
            return;
        }
        switch (slot) {
            case DRAW_FRAMEBUFFER:
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, value);
                break;
            case READ_FRAMEBUFFER:
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, value);
                break;
            case DEPTH_TEST:
                enable(GL11.GL_DEPTH_TEST, value);
                break;
            case DEPTH_FUNCTION:
                GL11.glDepthFunc(value);
                break;
            case DEPTH_MASK:
                GL11.glDepthMask(value != 0);
                break;
            case BLEND:
                enable(GL11.GL_BLEND, value);
                break;
            case ALPHA_TEST:
                enable(GL11.GL_ALPHA_TEST, value);
                break;
            case CULL_FACE:
                enable(GL11.GL_CULL_FACE, value);
                break;
            case PROGRAM:
                GL20.glUseProgram(value);
                break;
            case VERTEX_ARRAY:
                GL30.glBindVertexArray(value);
                break;
            case ELEMENT_BUFFER:
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, value);
                break;
            case ARRAY_BUFFER:
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, value);
                break;
            case INDIRECT_BUFFER:
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, value);
                break;
            case STORAGE_GENERAL:
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, value);
                break;
            case COVER_TEXTURE:
                GL45.glBindTextureUnit(coverUnit, value);
                break;
            case IMAGES_TEXTURE:
                GL45.glBindTextureUnit(imagesUnit, value);
                break;
            case SAMPLER_0:
                GL33.glBindSampler(0, value);
                break;
            case SAMPLER_1:
                GL33.glBindSampler(1, value);
                break;
            case UNPACK_ALIGNMENT:
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, value);
                break;
            case PACK_ALIGNMENT:
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, value);
                break;
            case ACTIVE_TEXTURE:
                GL13.glActiveTexture(value);
                break;
            default:
                throw new IllegalArgumentException("No such slot: " + slot);
        }
    }

    private static void enable(int capability, int value) {
        if (value != 0) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }
}
