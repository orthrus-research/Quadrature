package cc.orthrus.quadrature.core.platform;

/** One named thing a platform can do. Techniques declare the capabilities they need. */
public enum Capability {
    OPENGL_4_6("OpenGL 4.6"),
    COMPATIBILITY_PROFILE("the OpenGL compatibility profile"),
    /** Satisfied by the vendor extension or the cross-vendor one; code never asks which. */
    MESH_SHADERS("mesh shaders");

    private final String label;

    Capability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
