# R2: terrain meshing, visibility and submission

Requirement sections 4 and 5. First pass, 2026-11-13. Basis marks are defined in
[README.md](README.md).

## 4. Terrain meshing

**4.1 Compact faces and greedy merging. [V]**
A published binary greedy mesher reports 50 to 200 microseconds per chunk (self-reported,
its own hardware) and stores each merged quad in a few bytes: 6 bits each for position,
width and height in the first word. The vertex shader expands quads from that data
("vertex pulling") and every chunk is drawn in one indirect multi-draw call. Other reports
put packed vertices at 4 bytes against 28 unpacked. No head-to-head benchmark was found.

**4.2 Limits of merging for this game. [K]**
Merging faces across blocks needs textures that tile in the shader (a texture array, not
an atlas) and discards per-vertex differences in light and ambient occlusion. Modded
1.12.2 also has many blocks that are not full cubes.
*Implication:* two geometry streams are likely: a compact stream for full-cube faces and a
general stream for arbitrary model quads. Whether to merge at all in the compact stream is
a quality tradeoff (smooth lighting) to measure.

**4.3 Meshlets. [V]**
NVIDIA recommends meshlets of at most 64 vertices and 126 primitives; the driver
advertises limits of 256 and 512 through `NV_mesh_shader`. Later NVIDIA guidance lists 40
and 84 as intermediate sizes. Limits are queried at runtime.

**4.4 How blocks supply geometry in Forge 1.12.2. [K]**
- Baked models: `IBakedModel.getQuads(state, side, rand)`, with `getActualState` and
  `getExtendedState` reading neighbors through an `IBlockAccess`.
- Layers: `Block.canRenderInLayer` and a thread-local current layer in `ForgeHooksClient`.
- Fluids: the game's fluid renderer and Forge's fluid model.
- Renderers that write vertices directly into the chunk's `BufferBuilder` instead of
  returning quads (CodeChickenLib and older mods).
The game already runs all of these on its chunk build threads against a `ChunkCache`, so
off-thread access is the existing convention, including block entity lookups.
*Implication:* the cube snapshot must present an `IBlockAccess` view with block entities,
and the mesher needs a capture technique that runs the game's block dispatcher into a
buffer and converts the result, for blocks that only draw that way.

**4.5 Smooth lighting and ambient occlusion. [K]**
The game's per-vertex scheme averages light and occlusion from neighbors; the known
artifact is diagonal anisotropy, fixed by choosing the quad's split diagonal.

## 5. Visibility and submission

**5.1 GPU-driven rendering. [V]**
Haar and Aaltonen (SIGGRAPH 2015) is the reference: geometry in clusters, culling in
compute, one indirect multi-draw, and two-phase occlusion against a depth pyramid built
from the previous frame's visible set. Aaltonen's follow-up notes indirect multi-draw wins
for larger objects and shader-side clustering for small ones. A 2025 voxel rendering paper
(Aokana) uses the same depth-pyramid occlusion. NVIDIA publishes an OpenGL occlusion
culling sample built on indirect multi-draw.
OpenGL 4.6 adds the GPU-side draw count to core. [K]

**5.2 Occlusion for cave-heavy worlds. [K]**
The game's own method, published by its developers in 2014, precomputes which faces of a
16-cubed section can see each other and walks sections from the camera. It is already
three-dimensional per section, so it extends to cubes without change. The depth-pyramid
method handles what connectivity cannot (large open caverns).
*Implication:* the two are complementary; connectivity is cheap on the CPU and can seed
the GPU pass.

**5.3 Translucency. [K]**
Candidates: sorting per section when the camera crosses a threshold; weighted blended
order-independent transparency (McGuire and Bavoil, 2013), which needs no sorting but
approximates. Water is the dominant case.

**5.4 Buffers. [K]**
Persistent mapped buffers and one large arena per geometry stream ("approaching zero
driver overhead", GDC 2014). Defragmentation strategy is open.

**5.5 NVIDIA extensions. [V for availability, K otherwise]**
Mesh and task shaders (Turing). Variable-rate shading as `NV_shading_rate_image`. Bindless
textures. `NV_command_list`: current status not checked.

**5.6 Mesh shaders against compute culling with indirect multi-draw. [M]**
Not measured. It needs the repository scaffold and a terrain prototype. The hardware
available so far is an RTX 3060 with 12 GB (Ampere); the floor is an 8 GB Turing card, so
the decisive run needs that hardware or an enforced 8 GB budget.

## Follow-ups

1. Build the measurement in 5.6 once the scaffold exists.
2. Measure greedy merging against per-face output with smooth lighting on (4.2).
3. List mods that draw blocks only through direct buffer writes (4.4), with R5's survey.
4. Check `NV_command_list` status and whether it adds anything over 4.6 core (5.5).

## Sources

- Binary greedy meshing, published implementation notes: https://github.com/cgerikj/binary-greedy-meshing
- "Voxel Meshing in Exile": https://thenumbat.github.io/Voxel-Meshing-in-Exile
- NVIDIA, "Introduction to Turing Mesh Shaders": https://developer.nvidia.com/blog/introduction-turing-mesh-shaders
- NVIDIA, "Advanced API Performance: Mesh Shaders": https://developer.nvidia.com/blog/advanced-api-performance-mesh-shaders/
- Kapoulkine, "Meshlet size tradeoffs" (2023): https://zeux.io/2023/01/16/meshlet-size-tradeoffs/
- Haar and Aaltonen, "GPU-Driven Rendering Pipelines", SIGGRAPH 2015 course:
  https://advances.realtimerendering.com/s2015/
- Aaltonen, follow-up discussion: https://forum.beyond3d.com/threads/gpu-driven-rendering-siggraph-2015-follow-up.57240
- "Aokana: A GPU-Driven Voxel Rendering Framework for Open World Games" (2025): https://arxiv.org/abs/2505.02017
