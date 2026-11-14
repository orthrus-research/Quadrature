package cc.orthrus.quadrature.frame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * What a stage of Quadrature's own does on its way out, so the hosted stage after it finds the
 * context as the game believes it to be.
 *
 * <p>Two kinds of state are handled differently. State the game's cache does not know, which is
 * everything a modern renderer adds, goes back to one fixed neutral value: the game holds that value at
 * every boundary, so nothing is read to find it. State the cache does know is only ever changed
 * through the cache, and the stage puts back what its place in the frame expects, which the stage
 * declares; that is not this class's work.
 *
 * <p>Entering needs no method: a stage sets every piece of state it depends on, every time, and trusts
 * nothing it finds.
 *
 * <p>A stage never changes the matrix mode or the active texture unit, creates and fills its objects
 * through direct state access, and never uses the attribute stack.
 */
final class StateEnvelope {

    private StateEnvelope() {
    }

    /** Returns state the game's cache does not know to its neutral values. */
    static void leave() {
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GL33.glBindSampler(0, 0);
        GL33.glBindSampler(1, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        Minecraft minecraft = Minecraft.getMinecraft();
        int frame = OpenGlHelper.isFramebufferEnabled() ? minecraft.getFramebuffer().framebufferObject : 0;
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, frame);
        GlStateManager.viewport(0, 0, minecraft.displayWidth, minecraft.displayHeight);
    }
}
