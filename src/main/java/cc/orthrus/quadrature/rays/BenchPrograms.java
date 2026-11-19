package cc.orthrus.quadrature.rays;

/**
 * The compute programs the ray benchmark prepares rays and counts results with. None of them is timed.
 *
 * <p>Probes: each probe of a grid casts its rays one after another, in directions spread evenly over
 * the sphere by a hash of the ray's number, so neighbors in a warp share an origin and nothing else.
 * Columns: a ray straight down from the top of the box at each point of a square, to find what the sun
 * would light there. Shadows: from just above what each column found, a ray toward the light, row by
 * row, so neighbors in a warp start side by side and run parallel. Census: counts each outcome, the rays
 * that began in a cell that blocks light, the nodes visited and the whole blocks to hits.
 */
final class BenchPrograms {

    private static final String RAY = """
        #version 460 core
        layout(local_size_x = 64) in;
        struct Ray { vec4 originLength; vec4 inverse; };
        """;

    /**
     * Uniforms: 0 probes along x, y and z and rays a probe; 1 the lowest probe, relative to the origin
     * cube's corner, and the spacing; 2 the first ray to write, the rays to write and a seed; 3 the length.
     */
    static final String PROBES_COMPUTE = RAY + """
        layout(std430, binding = 0) restrict writeonly buffer Rays { Ray rays[]; };
        layout(location = 0) uniform ivec4 grid;
        layout(location = 1) uniform vec4 corner;
        layout(location = 2) uniform uvec3 batch;
        layout(location = 3) uniform float rayLength;

        uint hash(uint x) {
            x = x * 747796405u + 2891336453u;
            uint w = ((x >> ((x >> 28u) + 4u)) ^ x) * 277803737u;
            return (w >> 22u) ^ w;
        }

        void main() {
            uint id = gl_GlobalInvocationID.x;
            if (id >= batch.y) {
                return;
            }
            uint probe = id / uint(grid.w);
            uint px = probe % uint(grid.x);
            uint pz = (probe / uint(grid.x)) % uint(grid.z);
            uint py = probe / uint(grid.x * grid.z);
            vec3 origin = corner.xyz + vec3(px, py, pz) * corner.w;
            uint first = hash((batch.x + id) ^ batch.z);
            uint second = hash(first ^ 0x9E3779B9u);
            float u = float(first >> 8) * (2.0 / 16777216.0) - 1.0;
            float phi = float(second >> 8) * (6.2831853 / 16777216.0);
            float r = sqrt(max(0.0, 1.0 - u * u));
            vec3 direction = vec3(r * cos(phi), u, r * sin(phi));
            rays[batch.x + id] = Ray(vec4(origin, rayLength), vec4(1.0 / direction, 0.0));
        }
        """;

    /**
     * Uniforms: 0 columns along x and along z and the rays to write; 1 the square's lowest corner in x
     * and z and the spacing along each; 2 the height to start at and the length down.
     */
    static final String COLUMNS_COMPUTE = RAY + """
        layout(std430, binding = 0) restrict writeonly buffer Rays { Ray rays[]; };
        layout(location = 0) uniform ivec3 columns;
        layout(location = 1) uniform vec4 area;
        layout(location = 2) uniform vec2 top;

        void main() {
            uint id = gl_GlobalInvocationID.x;
            if (id >= uint(columns.z)) {
                return;
            }
            uint i = id % uint(columns.x);
            uint j = id / uint(columns.x);
            vec3 origin = vec3(area.x + (float(i) + 0.5) * area.z, top.x, area.y + (float(j) + 0.5) * area.w);
            rays[id] = Ray(vec4(origin, top.y), vec4(1e30, -1.0, 1e30, 0.0));
        }
        """;

    /**
     * Uniforms: 0 columns along x and along z and the rays to write; 1 as for columns; 2 the direction
     * toward the light and the length; 3 the height the columns started at.
     */
    static final String SHADOWS_COMPUTE = RAY + """
        layout(std430, binding = 0) restrict writeonly buffer Rays { Ray rays[]; };
        layout(std430, binding = 1) restrict readonly buffer Results { uvec4 results[]; };
        layout(location = 0) uniform ivec3 columns;
        layout(location = 1) uniform vec4 area;
        layout(location = 2) uniform vec4 light;
        layout(location = 3) uniform float top;

        void main() {
            uint id = gl_GlobalInvocationID.x;
            if (id >= uint(columns.z)) {
                return;
            }
            uint i = id % uint(columns.x);
            uint j = id / uint(columns.x);
            uvec4 found = results[id];
            float y = top;
            if ((found.z & 15u) == 0u) {
                // Just above the top face of what the column hit.
                y = float(int((found.y >> 10) & 1023u) - 512) + 1.0 + 1.0 / 64.0;
            }
            vec3 origin = vec3(area.x + (float(i) + 0.5) * area.z, y, area.y + (float(j) + 0.5) * area.w);
            rays[id] = Ray(vec4(origin, light.w), vec4(1.0 / light.xyz, 0.0));
        }
        """;

    /** Census words: an outcome each, rays that began in a cell that blocks, nodes visited, whole blocks to hits, the most visits. */
    static final int CENSUS_WORDS = 9;
    static final int CENSUS_INSIDE = 5;
    static final int CENSUS_VISITS = 6;
    static final int CENSUS_DISTANCE = 7;
    static final int CENSUS_MOST = 8;

    /** Uniforms: 0 the results to count. */
    static final String CENSUS_COMPUTE = """
        #version 460 core
        layout(local_size_x = 64) in;
        layout(std430, binding = 1) restrict readonly buffer Results { uvec4 results[]; };
        layout(std430, binding = 5) restrict buffer Census { uint census[]; };
        layout(location = 0) uniform uint count;
        shared uint tally[9];

        void main() {
            uint lane = gl_LocalInvocationIndex;
            if (lane < 9u) {
                tally[lane] = 0u;
            }
            barrier();
            uint id = gl_GlobalInvocationID.x;
            if (id < count) {
                uvec4 result = results[id];
                uint outcome = result.z & 15u;
                uint visits = result.z >> 4;
                atomicAdd(tally[outcome], 1u);
                if (outcome == 0u) {
                    if (result.x == 0u) {
                        atomicAdd(tally[5], 1u);
                    }
                    atomicAdd(tally[7], uint(uintBitsToFloat(result.x)));
                }
                atomicAdd(tally[6], visits);
                atomicMax(tally[8], visits);
            }
            barrier();
            if (lane < 9u && tally[lane] != 0u) {
                if (lane == 8u) {
                    atomicMax(census[8], tally[8]);
                } else {
                    atomicAdd(census[lane], tally[lane]);
                }
            }
        }
        """;

    private BenchPrograms() {
    }
}
