package cc.orthrus.quadrature.rays;

import cc.orthrus.quadrature.Quadrature;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/**
 * The compute program that walks rays through the light scene's bricks, step for step as
 * {@link cc.orthrus.quadrature.core.scene.BrickTrace} does, in the same single-precision arithmetic:
 * every distance is {@code (face - origin) * inverse} from a whole-numbered face, marked precise so the
 * compiler neither fuses nor reorders it, and compared exactly.
 *
 * <p>Bindings: shader storage 0 the rays, 1 the results, 2 the lookup, 3 the bricks, 4 a bit for each
 * state that blocks light. The lookup and bricks are read from storage blocks, or with
 * {@code -Dquadrature.rays.fetch=texture} through the buffer textures over them, on units 14 and 15.
 * Uniforms: 0 the box's reach across and upward and the count of rays; 1 the origin cube wrapped by the
 * box's size; 2 the box's size in cubes.
 */
final class TraceProgram {

    /** Invocations to a work group: two warps. */
    static final int GROUP = 64;
    static final int LOOKUP_UNIT = 14;
    static final int BRICK_UNIT = 15;

    static final String TRACE_COMPUTE = source(false);
    static final String TRACE_TEXTURE_COMPUTE = source(true);

    private TraceProgram() {
    }

    private static String source(boolean textures) {
        String fetch = textures
            ? """
            layout(binding = 14) uniform usamplerBuffer lookupTexture;
            layout(binding = 15) uniform usamplerBuffer brickTexture;
            uint lookupAt(int i) { return texelFetch(lookupTexture, i).r; }
            uint brickAt(uint i) { return texelFetch(brickTexture, int(i)).r; }
            """
            : """
            layout(std430, binding = 2) restrict readonly buffer Lookup { uint lookup[]; };
            layout(std430, binding = 3) restrict readonly buffer Bricks { uint bricks[]; };
            uint lookupAt(int i) { return lookup[i]; }
            uint brickAt(uint i) { return bricks[i]; }
            """;
        return """
            #version 460 core
            layout(local_size_x = 64) in;

            struct Ray { vec4 originLength; vec4 inverse; };
            layout(std430, binding = 0) restrict readonly buffer Rays { Ray rays[]; };
            layout(std430, binding = 1) restrict writeonly buffer Results { uvec4 results[]; };
            layout(std430, binding = 4) restrict readonly buffer Blocking { uint blocking[]; };
            """ + fetch + """
            layout(location = 0) uniform ivec3 reach;
            layout(location = 1) uniform ivec3 wrapped;
            layout(location = 2) uniform ivec3 size;

            const uint HIT = 0u;
            const uint MISS = 1u;
            const uint LEFT = 2u;
            const uint NOT_AVAILABLE = 3u;
            const uint STEPS = 4u;
            const uint MOST_VISITS = 4096u;

            bool crossed(float at, float leave, bool first) {
                return at < leave || (at == leave && first);
            }

            // Along an axis other than the one a node is left by, the cell the ray stands in when it leaves.
            int within(float o, float inv, float d, int s, int start, int n, int cells, float leave, bool first) {
                int end = s > 0 ? n + cells - 1 : n;
                // An estimate, which need not be exact: the crossings below decide.
                float p = clamp(o + leave * d, -1e6, 1e6);
                int e = int(floor(p));
                e = s > 0 ? clamp(e, start, end) : clamp(e, end, start);
                while (e != start) {
                    precise float at = (float(s > 0 ? e : e + 1) - o) * inv;
                    if (crossed(at, leave, first)) {
                        break;
                    }
                    e -= s;
                }
                while (e != end) {
                    precise float at = (float(s > 0 ? e + 1 : e) - o) * inv;
                    if (!crossed(at, leave, first)) {
                        break;
                    }
                    e += s;
                }
                return e;
            }

            void main() {
                uint id = gl_GlobalInvocationID.x;
                if (id >= uint(reach.z)) {
                    return;
                }
                Ray ray = rays[id];
                vec3 o = ray.originLength.xyz;
                float len = ray.originLength.w;
                vec3 inv = clamp(ray.inverse.xyz, vec3(-1e30), vec3(1e30));
                ivec3 s = ivec3(greaterThan(inv, vec3(0.0))) * 2 - 1;
                vec3 d = 1.0 / inv;
                ivec3 c = ivec3(floor(o));
                float t = 0.0;
                uint outcome = STEPS;
                uint visits = 0u;
                while (visits < MOST_VISITS) {
                    visits++;
                    ivec3 q = c >> 4;
                    if (abs(q.x) > reach.x || abs(q.z) > reach.x || abs(q.y) > reach.y) {
                        outcome = LEFT;
                        break;
                    }
                    ivec3 w = (wrapped + q + size) % size;
                    uint entry = lookupAt((w.y * size.z + w.z) * size.x + w.x);
                    uint kind = entry >> 30;
                    int cells;
                    if (kind == 1u) {
                        uint state = entry & 0xFFFFu;
                        if (((blocking[state >> 5] >> (state & 31u)) & 1u) != 0u) {
                            outcome = HIT;
                            break;
                        }
                        cells = 16;
                    } else if (kind == 2u) {
                        uint base = (entry & 0xFFFFFFu) * 144u;
                        ivec3 l = c & 15;
                        uint coarse = uint((l.y >> 1) << 6 | (l.z >> 1) << 3 | l.x >> 1);
                        if (((brickAt(base + 128u + (coarse >> 5)) >> (coarse & 31u)) & 1u) == 0u) {
                            cells = 2;
                        } else {
                            uint cell = uint(l.y << 8 | l.z << 4 | l.x);
                            if (((brickAt(base + (cell >> 5)) >> (cell & 31u)) & 1u) != 0u) {
                                outcome = HIT;
                                break;
                            }
                            cells = 1;
                        }
                    } else {
                        outcome = NOT_AVAILABLE;
                        break;
                    }

                    // Leave the node: the nearest of its far faces, x before y before z where two are equal.
                    ivec3 n = c & ivec3(-cells);
                    ivec3 face = n + ivec3(greaterThan(s, ivec3(0))) * cells;
                    precise vec3 exit = (vec3(face) - o) * inv;
                    int axis = 0;
                    float leave = exit.x;
                    if (exit.y < leave) {
                        axis = 1;
                        leave = exit.y;
                    }
                    if (exit.z < leave) {
                        axis = 2;
                        leave = exit.z;
                    }
                    if (leave > len) {
                        outcome = MISS;
                        t = len;
                        break;
                    }
                    if (cells > 1) {
                        // An axis before the one left by is crossed first where the two faces are at one distance.
                        if (axis != 0) {
                            c.x = within(o.x, inv.x, d.x, s.x, c.x, n.x, cells, leave, true);
                        }
                        if (axis != 1) {
                            c.y = within(o.y, inv.y, d.y, s.y, c.y, n.y, cells, leave, 1 < axis);
                        }
                        if (axis != 2) {
                            c.z = within(o.z, inv.z, d.z, s.z, c.z, n.z, cells, leave, false);
                        }
                    }
                    c[axis] = s[axis] > 0 ? n[axis] + cells : n[axis] - 1;
                    t = leave;
                }
                // Not "packed": NVIDIA's compiler keeps the word reserved, as GLSL 1.20 did.
                uvec3 biased = uvec3(c + 512) & 1023u;
                results[id] = uvec4(floatBitsToUint(t), biased.x | biased.y << 10 | biased.z << 20, outcome | visits << 4, 0u);
            }
            """;
    }

    /** Compiles and links a compute program; null, with the driver's log, where it fails. */
    static int link(String name, String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            Quadrature.LOGGER.error("Ray program {} did not compile: {}", name, GL20.glGetShaderInfoLog(shader, 8192));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, shader);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            Quadrature.LOGGER.error("Ray program {} did not link: {}", name, GL20.glGetProgramInfoLog(program, 8192));
            GL20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }
}
