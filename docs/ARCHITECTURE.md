# Architecture

How Quadrature is put together. Requirements are in [REQUIREMENTS.md](REQUIREMENTS.md),
decisions in [DECISIONS.md](DECISIONS.md), measurements in [BASELINE.md](BASELINE.md) and
the research behind this document in [research/](research/README.md).

Only the platform gate exists in code. Everything else here is design. Each choice carries
a status:

- **Decided**: fixed by a decision or by the specification.
- **Default**: what is built first; a named measurement can replace it.
- **Candidate**: one of several options; nothing is built until a prototype chooses.

## 1. Constraints that shape everything

| Constraint | Consequence |
|---|---|
| OpenGL 4.6 compatibility profile (D7) | Direct state access, compute, indirect multi-draw with a GPU-side count and persistent mapping are all core. Fixed-function drawing still works. |
| NVIDIA Turing or later, 8 GB (D11) | Mesh shaders are present on every supported card. Budgets are set against 8 GB. |
| The game's and mods' renderers are hosted, not rewritten | The frame must contain stages Quadrature does not author, and survive the GL state they leave. |
| Cubic worlds only (D3) | Terrain is addressed by cube, in three dimensions, with no column assumptions. |
| Java 25 on Cleanroom | One thread owns the context. Native memory and GL calls go through LWJGL 3.4's foreign-function backend. |
| Measured baseline (D14) | Client render work in flight is about 6 ms per frame on the vanilla renderer and 2.5 ms on Celeritas, with the GPU a quarter used. The room is on the CPU side of the frame. |

That last row sets the direction: move per-section decisions off the client thread and onto
the GPU, and keep the client thread's share of a frame small and constant.

## 2. Modules

| Module | Depends on | Holds |
|---|---|---|
| `core` | Nothing from Minecraft | Platform profile and gate, capability set, pass graph, resource layer, techniques, geometry formats, budgets, timing |
| root (host) | `core`, Cleanroom, Cubature's cube source | Mod entry, reading the context, scene sources, hosted stages, settings screens, state mirroring |
| `shaders` (to be added) | `core` | The shader format's loader and the shipped shaders |
| `testkit` (to be added) | `core` | Fixtures for running the graph and techniques without the game |
| OrthLib (separate repository) | Neither | The interface other mods compile against |

Rules:

- `core` never imports a Minecraft, Forge or Cleanroom class. What it needs from the game
  arrives through interfaces the host implements. **Decided.**
- Other mods never see `core` or the host. They see OrthLib's interface. **Decided.**
- A module is added when it has content, not before.

## 3. Platform layer

Built. `PlatformReader` reads the context once into a `PlatformProfile`; `CapabilitySet`
derives capabilities from it; `PlatformGate` admits or refuses the platform.

- Code asks `CapabilitySet` for a capability. Only the gate reads the vendor. **Decided.**
- A technique declares the capabilities it needs. Technique selection happens once, when
  the graph is compiled, never per draw. **Decided.**
- Mesh shaders are one capability, satisfied by the NVIDIA extension or the cross-vendor
  one added to the registry in October 2025. **Decided.**

## 4. The frame

### 4.1 Pass graph

The frame is a directed acyclic graph: stages are nodes, resources are edges. The design
follows Frostbite's FrameGraph and the engines that adopted it.

- **Setup.** Each stage declares the resources it reads, writes and creates.
- **Compile.** The graph orders stages by dependency, drops stages whose outputs nothing
  reads, picks a technique for each remaining stage from the capability set, and assigns
  physical resources. Transient resources whose lifetimes do not overlap share memory.
- **Execute.** Stages run in order on the context thread.

Compile happens when the graph's shape changes (a different shader, a setting, a resize),
not every frame. The compiled graph is cached and reused. **Default.** Engines differ on
this; per-frame compilation is simpler and costs CPU every frame, which is the resource
the baseline shows to be scarce.

A stage that has effects outside the graph, such as presenting the frame, is marked so
culling keeps it.

### 4.2 Stage kinds

| Kind | Authored by | Example |
|---|---|---|
| Pipeline stage | Quadrature | Terrain culling, terrain draw, depth pyramid |
| Hosted stage | The game or another mod | Entities, block entities, particles, sky, weather, hand |
| Shading stage | The selected shader | Shadows, lighting, composite, final |
| Mod stage | A mod, through OrthLib | Bloom contribution, an overlay |

### 4.3 Baseline graph

Phase 1 builds this graph. It reproduces the game's world pass order and draws nothing new;
its terrain stages call the game's own terrain renderer.

```
sky -> opaque terrain -> entities -> block entities -> outline and damage
    -> particles -> weather -> translucent terrain -> clouds -> hand -> present
```

Phase 1 does not replace the game's world pass. It takes its stage boundaries by
injecting between the calls inside it, so Forge's hooks, Cleanroom's headless Kirino
phases and other mods' injections there keep working (research R1, section 2.5).
**Default.** Kirino's graphics state replaces the game's world render and is refused by
the platform gate as another renderer. **Decided**, by D6.

Later phases replace the terrain stages (Phase 2), insert mod stages (Phase 3), add
distant terrain stages before and after near terrain (Phase 4), and wrap the whole in the
shader's stages (Phase 5). The graph's shape changes; the stages that remain do not.

### 4.4 Hosted stages and GL state

A hosted stage runs code Quadrature did not write. The specification settles what is
possible (research R1, section 9.1): with a program bound, the game's immediate-mode and
display-list drawing runs through that program, and its vertices, colors, normals and
texture coordinates are declared inputs. Fixed-function lighting, texturing and fog do not
run; the alpha test still does, on output zero.

- **Envelope.** Each hosted stage runs inside an envelope that establishes known state
  before it and re-establishes Quadrature's state after it. Quadrature never assumes a
  hosted stage left state clean. **Decided.**
- **Mirroring.** Fixed-function enable flags are not readable from a shader. The host
  observes them where the game sets them (`GlStateManager`) and mirrors them into a small
  uniform block the hosted program reads. **Decided**, by the specification.
- **Direct calls.** A mod that calls the GL binding directly cannot be observed. The
  envelope's reset covers it; correctness inside that stage is the mod's. **Decided.**
- **Own objects.** Quadrature creates and modifies its buffers, textures and framebuffers
  through direct state access, so it does not disturb the bindings the game caches.
  **Decided.**

Until Phase 5, hosted stages run exactly as the game draws them, with no program bound.

### 4.5 Depth and color

- **Geometry buffer, attachment zero.** It carries alpha and is not an integer format,
  because the alpha test reads it. **Decided**, by the specification.
- **Color.** Lighting is computed in linear space into floating-point targets; the final
  stage applies exposure, tone mapping and display encoding. **Default** from Phase 5.
  Before that the frame stays in the game's display-encoded space.
- **Depth.** Two candidates, chosen by prototype (research R1, section 2.2): reversed
  depth for the whole frame with the game's depth state translated where it is set, or
  conventional depth near and a separate range for distant terrain. **Candidate.**

### 4.6 Timing

Every stage is bracketed by a GPU timer query and a CPU timer. Query results are read
only when the driver reports them available, from a ring a few frames deep, so reading
never stalls the frame. **Decided.** The baseline could not time the GPU at all; this
closes that gap from the first frame Quadrature owns.

## 5. Scene sources

| Source | Owner | Gives Quadrature |
|---|---|---|
| Cube source | Cubature | Immutable snapshots of a cube and its neighbors (blocks, block entities, light, implicit sky light), change notifications, load and unload events |
| Distant source | Riven and Cubature | Terrain at reduced detail: computed from the seed and definitions where ungenerated (D12), read from storage where generated |
| Hosted source | The game and mods | Their own drawing, run inside hosted stages |

- A snapshot is safe to read on any thread and presents the block access interface the
  game's model code expects, so existing block models work against it unchanged.
  **Decided.**
- Cubature owns the cube source contract (D16); it is a design draft in Cubature's
  repository. [CUBE-SOURCE.md](CUBE-SOURCE.md) records what Quadrature relies on.
- Each source reports when it is settled: no build, load or refinement outstanding for
  the current view. The capture harness waits on this instead of discarding a first run
  ([VERIFICATION.md](VERIFICATION.md)). **Decided.**

## 6. Near terrain

### 6.1 Geometry

Two streams (research R2, sections 4.1 and 4.2). **Default.**

| Stream | Content | Format |
|---|---|---|
| Compact | Faces of full cubes | One small record per face: position in the cube, face direction, texture layer, light and occlusion for four corners. Vertices are generated in the shader. |
| General | Any other model quad | Quantized vertices: position, texture coordinate, normal, color, light |

- Block textures live in a texture array, one layer per sprite, so a face names a layer
  and tiling needs no atlas arithmetic. **Default.**
- Faces are not merged across blocks at first. Merging discards per-corner light, and the
  measured gain is unknown for this game. **Default**; measured in Phase 2.
- **Capture.** A block that only draws by writing vertices into the game's chunk buffer
  is meshed by running the game's block dispatcher into a capturing buffer and converting
  the result into the general stream. This is the path that keeps other mods' blocks
  working. **Decided.**

### 6.2 Building

- Builds run on Cubature's executor (Quadrature starts no threads), ordered by distance
  and by whether the cube is in view, and cancelled when their snapshot is superseded.
  **Decided.**
- A build writes into native memory it owns and hands the result to the context thread.
  It allocates nothing on the Java heap per face. **Decided.**
- The context thread spends a bounded time per frame placing finished builds into GPU
  memory. **Decided.**

### 6.3 GPU memory

One persistently mapped arena per stream, sub-allocated per cube, with a free list and
compaction that moves at most a bounded amount per frame. Per-cube records (bounds,
arena offsets, counts, state) live in one shader storage buffer. **Default.**

### 6.4 Visibility and submission

Nothing here is decided per cube on the client thread. **Default**, following Haar and
Aaltonen's GPU-driven pipeline.

1. **Coarse, on the CPU.** A connectivity walk from the camera over cubes, using
   precomputed face-to-face visibility per cube, as the game does per section. It is cheap
   and removes sealed caves. Its output is a bit per cube.
2. **Fine, on the GPU.** A compute stage tests each surviving cube against the frustum
   and against a depth pyramid, and writes draw commands and their count.
3. **Two phases.** Cubes visible last frame are drawn first; the depth pyramid is built
   from them; cubes rejected in the first phase are retested against it and the survivors
   drawn. This removes the one-frame lag of testing against last frame's depth.
4. **Draw.** One indirect multi-draw per stream and layer, with the count read on the GPU.

The client thread's cost is then independent of how many cubes are visible.

**Technique choice.** Two techniques implement the draw: compact faces expanded in the
vertex shader with indirect multi-draw, and meshlets with task and mesh shaders, where the
task shader does the per-cluster culling. Both sit behind the same stage contract.
Compact faces with indirect multi-draw are built first: published throughput on Turing,
the floor, puts the two within about 6% of each other, and this is the technique another
vendor would need. **Default.** Meshlets are measured as a second technique where
triangles get small, which is distant terrain (research R2, section 5.6). NVIDIA's
guidance for meshlets is at most 64 vertices and 126 primitives. **Candidate.**

Grouping a cube's faces by direction, so groups facing away from the camera are skipped
without testing them, is a further candidate. Published results put the gain from
back-face rejection at under a tenth of geometry time, so it is not in the default.

### 6.5 Translucency

Translucent faces are a separate layer, drawn after opaque geometry and hosted stages.
Per-cube back-to-front order is established on the CPU from cube positions; faces within a
cube are sorted when the camera crosses into a different octant of it. **Default.** A
weighted blended method that needs no sorting is the alternative if sorting cost shows up.

## 7. Distant terrain

- **Structure.** Rings of cubes at doubling scale around the camera: level L covers twice
  the extent of level L-1 at half the resolution, so every level costs about the same.
  **Default.** This is the structure published voxel LOD systems converge on.
- **Content.** Each level is meshed by the same mesher as near terrain from a coarser
  snapshot, so it keeps the blocky look and shares formats, visibility and shading.
  **Default.**
- **Sources.** Ungenerated terrain comes from Riven's sampler, which already emits each
  column as runs; generated terrain comes from stored cubes, reduced. The two
  representations in research R3 (voxel mips, column runs) are both reachable from here,
  and the choice between them for the far field waits for memory per square kilometer.
  **Candidate.**
- **Refinement.** The coarsest level for the whole view is built first and is always
  available; finer levels replace it outward from the camera as time allows. **Decided.**
- **Transitions.** A dithered crossfade between levels over a band, which needs no seam
  geometry. Geomorphing is the alternative. **Default.**
- **Budget and eviction.** Levels are held in the same arenas as near terrain under one
  GPU memory budget, evicted farthest and coarsest-covered first. **Default.**

## 8. Lighting and shading

Phase 5. The shape is chosen now because the pass graph must leave room for it.

- **Deferred geometry buffer.** Terrain, distant terrain and hosted draws all write the
  same outputs: color, normal, material, light inputs, depth. **Default.** Hosted draws
  cannot supply primitive identifiers, which rules out a visibility buffer.
- **Lights.** Block lights are gathered into clusters by view-space position; clustered
  shading handles translucent layers far better than screen tiles (Olsson et al.).
  **Default.**
- **Translucents.** Shaded forward, after opaque lighting, against the same clusters.
  **Default.**
- **Materials.** Quadrature's own model, with LabPBR mapped into it at load (D13).
  **Decided.**
- **Shadows.** Cascaded shadow maps near the camera. Beyond them, shadowing from the
  terrain's own coarse data instead of rendering distant terrain into shadow maps;
  CryEngine documents this for heightfields at about 8 km with no extra memory.
  **Candidate.** Virtual shadow maps are documented by Epic but unpriced.
- **Indirect light.** A voxel world already is the voxelization that voxel lighting
  methods build at runtime. Cascaded voxel volumes around the camera are the first
  candidate. Radiance cascades reached three dimensions in a 2026 preprint at 11.5 ms for
  its own stages on a laptop RTX 3080, which is too much of an 8.33 ms frame as published.
  **Candidate.**
- **Motion vectors.** A geometry buffer output from the start, for terrain and hosted
  draws, because temporal methods need them every frame. **Default.**
- **Anti-aliasing.** Temporal, written in-house. Spatial upscaling is a shader pass and
  runs on OpenGL; DLSS has no OpenGL path. **Default.**

A baseline shader that reproduces the game's look ships first and is the reference for
image comparison.

## 9. Shader system

A shader implements named shading stages against declared inputs and outputs, with
options and metadata. The format is drafted against two concrete shaders, the baseline
and one with shadows and atmosphere (research R4). **Candidate.**

- Programs compile and link when a shader is loaded or an option changes, never during a
  frame, and their binaries are cached keyed by the driver version. Only the programs the
  selected options need are compiled; an option that can be a uniform is one. **Decided.**
- The interface a shader sees is the pass graph's: it names stages and resources, not GL
  objects. **Decided.**
- Third-party shader pack formats are not loaded (D5).

## 10. Mod interface

In OrthLib. A mod declares an extra terrain layer, marks emissive geometry, adds a stage at
a named point in the graph, reads a frame resource, or queries a capability. It holds
handles, not objects, and the contract is versioned. GTCEu: Reloaded's bloom is the first
client (research R5). The design is drafted in Phase 3.

## 11. Measurement built in

| Signal | Source | Used by |
|---|---|---|
| CPU and GPU time per stage | Section 4.6 | Baseline comparison, debug overlay |
| Settled | Each scene source | Capture harness |
| Counters: cubes visible, drawn, built, pending; arena use | Pipeline stages | Debug overlay, budgets |
| GL errors and driver debug messages | Debug context | Tests, which fail on any |

## 12. Budgets

Frame time is fixed by the frame cap: 8.33 ms at 120 frames per second. The bar for
client render work is the vanilla baseline, about 6 ms per frame in flight (D14).

GPU memory follows published engine practice (research R1, section 2.6): one budget taken
from what the driver reports, a fixed reserve for what is not streamed, and low and high
watermarks that drive refinement and eviction, degrading detail when demand exceeds the
budget. **Default.**

The apportionment below is provisional. It is a starting split to build against, not a
measurement; each line is revised when the phase that fills it measures its real use.

| Pool, on an 8 GiB card | Provisional budget | Revised in |
|---|---|---|
| Left for the desktop, other programs and the driver | 2.0 GiB | |
| Reserve: framebuffers, geometry buffer, depth pyramid, texture arrays, the game's own textures | 1.5 GiB | Phase 1 |
| Near terrain arenas | 1.5 GiB | Phase 2 |
| Distant terrain arenas | 2.0 GiB | Phase 4 |
| Shading: shadow maps, light volumes | 1.0 GiB | Phase 5 |

The first line is not arbitrary: the test machine had 2.2 GiB in use before the game
started. The budget is computed at startup from the memory the driver reports as free, so
a card with more memory, or a quieter desktop, gets larger pools in the same proportions.
Streamed pools load freely below half their budget, evict unused data above it, and stop
loading at nine tenths.

| Other budgets | Provisional value | Basis |
|---|---|---|
| Client render work per frame, bar | 6 ms | The vanilla baseline in flight (D14) |
| Client render work per frame, aim | 2.5 ms | Celeritas on the same flight |
| Client-thread time placing finished builds per frame | 1 ms | Leaves the rest of the bar for drawing |
| Native memory outside the Java heap | 1 GiB | Mesh staging before upload; revised in Phase 2 |
| Java heap added by Quadrature | 256 MiB | The 8 GiB heap floor is shared with the game and other mods |

For orientation, the current stack uses about 0.55 GiB of GPU memory in flight and
0.82 GiB with a shader pack.

## 13. What is decided, and what is not

| Area | Status | Settled by |
|---|---|---|
| Module boundaries, capability rule, technique selection at compile | Decided | |
| Pass graph with cached compilation | Default | Frame cost of recompiling, if graphs change often |
| Hosted stage envelope and state mirroring | Decided | |
| Depth convention | Candidate | Prototype with hosted entity draws |
| Two geometry streams, texture array, no merging | Default | Phase 2 measurements |
| GPU-driven visibility in two phases | Default | Phase 2 measurements |
| Compact faces with indirect multi-draw, first | Default | Phase 2 measurements |
| Meshlets as a second technique | Candidate | The mesh shader measurement, on distant terrain |
| Ringed distant terrain with shared mesher | Default | Memory per square kilometer |
| Far-field representation | Candidate | The same |
| Deferred shading with clustered lights | Default | Phase 5 prototype |
| Far shadows, indirect light | Candidate | Phase 5 prototypes |
| Shader format | Candidate | Two concrete shaders |

## Sources

Read for this document on 2026-11-13, in addition to those in the research files. No
renderer's source code was read; implementations are cited for their published
descriptions only.

- O'Donnell, "FrameGraph: Extensible Rendering Architecture in Frostbite", GDC 2017:
  https://www.slideshare.net/DICEStudio/framegraph-extensible-rendering-architecture-in-frostbite
- Unity, render graph fundamentals (pass culling, resource lifetimes):
  https://docs.unity3d.com/Packages/com.unity.render-pipelines.core@14.0/manual/render-graph-fundamentals.html
- Epic Games, Render Dependency Graph:
  https://dev.epicgames.com/documentation/en-us/unreal-engine/render-dependency-graph-in-unreal-engine
- Myth Engine, render graph notes (cached against per-frame compilation):
  https://panxinmiao.github.io/myth/en/architecture/render-graph
- Haar and Aaltonen, "GPU-Driven Rendering Pipelines", SIGGRAPH 2015:
  https://advances.realtimerendering.com/s2015/aaltonenhaar_siggraph2015_combined_final_footer_220dpi.pdf
- Krupitskas, "Modern rendering culling techniques" (two-phase occlusion):
  https://krupitskas.com/posts/modern_culling_techniques/
- Kitware, hierarchical two-pass occlusion culling:
  https://kitware.com/webgpu-occlusion-culling-in-vtk
- vkguide, "High-performance voxel and mesh rendering":
  https://vkguide.dev/docs/ascendant/ascendant_geometry/
- McDonald, "High Performance Voxel Engine: Vertex Pooling" (2021):
  https://nickmcd.me/2021/04/04/high-performance-voxel-engine/
- NVIDIA, "OpenGL beyond porting" and "How to make OpenGL usage Vulkan like":
  https://developer.nvidia.com/opengl-vulkan
- Everitt and McDonald, "Approaching Zero Driver Overhead", GDC 2014:
  https://fr2.slideshare.net/CassEveritt/approaching-zero-driver-overhead
- Fang, Wang and Wang, "Aokana: A GPU-Driven Voxel Rendering Framework for Open World
  Games" (2025): https://arxiv.org/abs/2505.02017
- Voxel Tools, `VoxelLodTerrain` documentation (doubling LOD rings, fade on split):
  https://voxel-tools.readthedocs.io/en/latest/api/VoxelLodTerrain/
- bevy_voxel_world documentation (constant samples per level, skirts): https://lib.rs/crates/bevy_voxel_world
- CryEngine, ray traced shadows from terrain height data:
  https://www.cryengine.com/docs/static/engines/cryengine-5/categories/23756816/pages/36339974
- "Split Radiance Cascades: Real-Time Global Illumination via Sparse Radiance Probes"
  (2026 preprint): https://arxiv.org/abs/2607.20384
- "Dynamic Voxel-Based Global Illumination", Computer Graphics Forum (2025):
  https://onlinelibrary.wiley.com/doi/10.1111/cgf.15262
- Khronos, `glQueryCounter` reference: https://registry.khronos.org/OpenGL-Refpages/gl4/html/glQueryCounter.xhtml
- Lighthouse3D, OpenGL timer query: https://www.lighthouse3d.com/tutorials/opengl-timer-query/
- OpenJDK, JDK 25: https://openjdk.org/projects/jdk/25
