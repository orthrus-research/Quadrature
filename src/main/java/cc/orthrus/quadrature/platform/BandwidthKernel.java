package cc.orthrus.quadrature.platform;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * The tier benchmark's bandwidth kernel (D22): a compute program that reads a buffer through once, each
 * thread a few 16-byte elements a dispatch's width apart so that neighbors read neighbors, and folds them
 * into a word it writes to one of 256 places, so nothing it reads can be left out. The buffer is filled
 * with a pattern first, so no part of it is known to the driver to hold one value.
 *
 * <p>The working set is the buffer. Read again and again, a buffer that fits the last-level cache is
 * read from the cache after the first pass, one that does not from memory; at the benchmark's 8 MiB the
 * first holds on Ada and Blackwell, the second on Turing and Ampere.
 */
final class BandwidthKernel {

    /** Invocations a work group. */
    private static final int GROUP = 256;
    /** Elements each invocation reads a dispatch. */
    private static final int STEPS = 8;
    private static final int ELEMENT = 16;

    private static final String READ = String.join("\n",
        "#version 460",
        "layout(local_size_x = " + GROUP + ") in;",
        "layout(std430, binding = 0) readonly buffer Source { uvec4 source[]; };",
        "layout(std430, binding = 1) writeonly buffer Sink { uint sink[]; };",
        "uniform uint stride;",
        "void main() {",
        "    uint i = gl_GlobalInvocationID.x;",
        "    uvec4 folded = uvec4(0u);",
        "    for (uint k = 0u; k < " + STEPS + "u; k++) {",
        "        folded ^= source[i + k * stride];",
        "    }",
        "    sink[gl_LocalInvocationID.x] = folded.x ^ folded.y ^ folded.z ^ folded.w;",
        "}");

    private static final String FILL = String.join("\n",
        "#version 460",
        "layout(local_size_x = " + GROUP + ") in;",
        "layout(std430, binding = 0) writeonly buffer Target { uvec4 target[]; };",
        "uniform uint count;",
        "void main() {",
        "    for (uint i = gl_GlobalInvocationID.x; i < count; i += gl_NumWorkGroups.x * " + GROUP + "u) {",
        "        target[i] = uvec4(i * 2654435761u, i ^ 0x5bd1e995u, i + 0x68e31da4u, ~i);",
        "    }",
        "}");

    /**
     * The settling load: chains of fused multiply-adds held in registers, so its time follows the shader
     * clock and nothing else. About 1.8 ms on an RTX 3060 at its top clock.
     */
    private static final String SPIN = String.join("\n",
        "#version 460",
        "layout(local_size_x = " + GROUP + ") in;",
        "layout(std430, binding = 1) writeonly buffer Sink { uint sink[]; };",
        "uniform uint rounds;",
        "void main() {",
        "    vec4 a = vec4(float(gl_GlobalInvocationID.x) * 1e-7) + vec4(0.1, 0.2, 0.3, 0.4);",
        "    vec4 b = vec4(0.999, 0.998, 0.997, 0.996);",
        "    vec4 c = vec4(1e-4);",
        "    for (uint k = 0u; k < rounds; k++) {",
        "        a = a * b + c;",
        "        b = b * a + c;",
        "    }",
        "    sink[gl_LocalInvocationID.x] = floatBitsToUint(a.x + a.y + a.z + a.w + b.x + b.y + b.z + b.w);",
        "}");
    private static final int SPIN_GROUPS = 1024;
    private static final int SPIN_ROUNDS = 6000;

    private int read;
    private int fill;
    private int spin;
    private int roundsLocation;
    private int strideLocation;
    private int sink;
    private final int[] buffers = new int[2];
    private final long[] sizes = new long[2];

    /** Makes the programs and fills a buffer of each working set. */
    void create(long small, long large) {
        read = link(READ);
        fill = link(FILL);
        spin = link(SPIN);
        strideLocation = GL20.glGetUniformLocation(read, "stride");
        roundsLocation = GL20.glGetUniformLocation(spin, "rounds");
        sink = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(sink, GROUP * 4L, 0);
        sizes[0] = small;
        sizes[1] = large;
        GL20.glUseProgram(fill);
        int countLocation = GL20.glGetUniformLocation(fill, "count");
        for (int i = 0; i < 2; i++) {
            buffers[i] = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(buffers[i], sizes[i], 0);
            int elements = (int) (sizes[i] / ELEMENT);
            GL30.glUniform1ui(countLocation, elements);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffers[i]);
            GL43.glDispatchCompute(Math.min(4096, elements / GROUP), 1, 1);
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        unbind();
    }

    /**
     * Reads working set {@code which} (0 the small, 1 the large) through {@code passes} times, as one stretch
     * of GPU work for a timer query to bracket.
     */
    void read(int which, int passes) {
        int elements = (int) (sizes[which] / ELEMENT);
        int stride = elements / STEPS;
        GL20.glUseProgram(read);
        GL30.glUniform1ui(strideLocation, stride);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffers[which]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, sink);
        for (int pass = 0; pass < passes; pass++) {
            GL43.glDispatchCompute(stride / GROUP, 1, 1);
        }
        unbind();
    }

    /** One dispatch of the settling load, as one stretch of GPU work for a pair of timestamps to bracket. */
    void spin() {
        GL20.glUseProgram(spin);
        GL30.glUniform1ui(roundsLocation, SPIN_ROUNDS);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, sink);
        GL43.glDispatchCompute(SPIN_GROUPS, 1, 1);
        unbind();
    }

    /** Bytes a pass over working set {@code which} reads. */
    long bytes(int which) {
        return sizes[which];
    }

    void delete() {
        GL20.glDeleteProgram(read);
        GL20.glDeleteProgram(fill);
        GL20.glDeleteProgram(spin);
        spin = 0;
        for (int name : new int[] {sink, buffers[0], buffers[1]}) {
            if (name != 0) {
                GL15.glDeleteBuffers(name);
            }
        }
        read = 0;
        fill = 0;
        sink = 0;
        buffers[0] = 0;
        buffers[1] = 0;
    }

    private static void unbind() {
        GL20.glUseProgram(0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
    }

    private static int link(String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Bandwidth kernel: " + log);
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Bandwidth kernel: " + GL20.glGetProgramInfoLog(program));
        }
        return program;
    }
}
