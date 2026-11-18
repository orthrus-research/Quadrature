package cc.orthrus.quadrature.core.platform;

/**
 * Where GPU memory figures come from on one vendor's driver (decision D8): the gate and the
 * quality tiers read memory through this, so a second vendor adds a source rather than a branch.
 * Read on the thread that owns the graphics context.
 */
public interface GpuMemorySource {

    /** For a platform whose driver reports no memory. */
    GpuMemorySource NONE = new GpuMemorySource() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public GpuMemory read() {
            return GpuMemory.NOT_REPORTED;
        }
    };

    /** A short name for the log, such as the extension the source reads. */
    String name();

    GpuMemory read();
}
