package cc.orthrus.quadrature.core.view;

/**
 * What is published of the targets the opaque terrain pass writes for shading: four textures of 8
 * bits a channel and a depth texture, the size of the window. They are Quadrature's; a shading stage
 * reads them and writes none.
 *
 * <ul>
 * <li>A: world normal as n * 0.5 + 0.5; alpha, perceptual roughness.</li>
 * <li>B: red sky light, green emission strength, blue block light, each 0 to 1 over the game's 0 to
 * 15; alpha a response class: 0.375 on terrain, 0 where nothing was written.</li>
 * <li>C: the square root of linear albedo after tint; alpha 1 for a conductor.</li>
 * <li>D: reflectance in red and subsurface in green for a surface that is not a metal, colored
 * reflectance for a metal; alpha, the material's occlusion times the game's vertex occlusion.</li>
 * </ul>
 *
 * <p>Depth is reversed: 1 nearest, 0 farthest and where nothing was written.
 */
public final class ShadingTargets {

    public static final int TARGETS = 4;

    private final int[] textures = new int[TARGETS];
    private int depth;
    private int framebuffer;
    private int width;
    private int height;
    private long bytes;
    private long generation;

    /** Sets what the host has made and drawn. */
    public void set(int framebuffer, int a, int b, int c, int d, int depth, int width, int height, long bytes,
        long generation) {
        this.framebuffer = framebuffer;
        textures[0] = a;
        textures[1] = b;
        textures[2] = c;
        textures[3] = d;
        this.depth = depth;
        this.width = width;
        this.height = height;
        this.bytes = bytes;
        this.generation = generation;
    }

    /**
     * The framebuffer the four targets and the depth are attached to, for a stage that draws more
     * opaque surfaces into them after near terrain; 0 before the first frame they are drawn in. Such a
     * stage draws with the depth test nearer-or-equal in reversed depth and leaves the draw buffers as
     * it found them: all four.
     */
    public int framebuffer() {
        return framebuffer;
    }

    /** The name of target 0 to 3, A to D; 0 before the first frame they are drawn in. */
    public int texture(int target) {
        return textures[target];
    }

    /** The depth texture, 32-bit float, reversed. */
    public int depthTexture() {
        return depth;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The memory the four targets and the depth take. */
    public long bytes() {
        return bytes;
    }

    /** Moves on each frame the targets are drawn. */
    public long generation() {
        return generation;
    }
}
