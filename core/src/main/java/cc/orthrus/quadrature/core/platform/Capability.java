package cc.orthrus.quadrature.core.platform;

/** One named thing a platform can do. Techniques declare the capabilities they need. */
public enum Capability {
    OPENGL_4_6("OpenGL 4.6"),
    COMPATIBILITY_PROFILE("the OpenGL compatibility profile"),
    /** Satisfied by the vendor extension or the cross-vendor one; code never asks which. */
    MESH_SHADERS("mesh shaders"),
    /** Buffers that stay mapped while they are drawn from: the terrain arena. */
    PERSISTENT_MAPPING("persistently mapped buffers"),
    /** Fences, to learn when the draws of a frame have run: reuse of the arena's freed memory. */
    SYNC_OBJECTS("sync objects"),
    /** Many draws from one buffer of commands: terrain submission. */
    MULTI_DRAW_INDIRECT("indirect multi-draw"),
    /** Buffers a shader reads as arrays of records: the per-cube records. */
    SHADER_STORAGE_BUFFERS("shader storage buffers"),
    /** The base instance of a draw, readable in the vertex stage: which record a draw uses. */
    DRAW_PARAMETERS("draw parameters in shaders");

    private final String label;

    Capability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
