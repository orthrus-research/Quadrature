# Requirements

What each part of Quadrature must do, stated at the conceptual level, with the questions a
research pass has to answer before implementation. Nothing here is a chosen technique.
Technique names appear only as candidates to evaluate.

Each section has two parts: **Must** (the requirement) and **Research** (open questions).
Research answers cite a primary source (specification, vendor documentation, paper or
measurement) and its date.

## Design frame

### Taxonomy

| Term | Meaning |
|---|---|
| Platform profile | The graphics API, version, profile, vendor and driver the game is running on |
| Capability | One named thing a platform can do (for example: indirect multi-draw with a GPU-side count) |
| Technique | One implementation of a stage. A stage may have several; each declares the capabilities it needs |
| Stage | One unit of work in the frame with declared inputs and outputs |
| Pass graph | The ordered set of stages for a frame, and the resources that flow between them |
| Resource | A GPU object Quadrature owns: buffer, texture, framebuffer, program |
| Scene source | Where drawable content comes from: cubes, distant terrain, hosted game renderers |
| Material | Data describing how a surface responds to light, independent of any shader |
| Shader | A Quadrature-format program set that implements shading stages |
| Preset | A named selection of techniques and settings |
| Support tier | Supported (tested, promised) or unsupported (the game stops with a message) |

### Rules

1. **Code asks for capabilities, never for a vendor or version.** The platform profile is
   read once, turned into a capability set, and nothing else checks the vendor. Supporting
   only NVIDIA means only NVIDIA's capability set is tested and admitted, not that code
   branches on it.
2. **A stage is defined by its inputs and outputs, not by how it is done.** A technique may
   use any capability, including vendor extensions, as long as the stage contract holds.
3. **Specific beats general when general costs performance or quality.** A technique tuned
   to the supported platform is fine. The seam is the stage contract, so a second technique
   can be added later beside it. Exceptions are recorded in `DECISIONS.md`.
4. **No abstraction without a second case in sight.** A seam exists where a named future
   expansion needs it (table below). Elsewhere code is direct.

### Expansion seams

| Possible later expansion | Seam that absorbs it |
|---|---|
| Other GPU vendors | Capability set; alternative techniques per stage |
| Core profile, macOS | Hosted rendering stage (fixed-function emulation as another technique) |
| Another graphics API | Resource and command layer under the pass graph |
| Non-cubic worlds | Scene source |
| Third-party shader packs | Shader front end translating into the pass graph |
| Other mods' rendering needs | Mod interface in OrthLib |

**Research**
- Which of these seams have a measurable cost on the supported platform, and how large?
- How do current engines structure capability sets and technique selection without
  per-draw branching?

## 1. Platform and context

**Must**
- Verify at startup: OpenGL 4.6, compatibility profile, NVIDIA Turing generation or later
  (as a capability set and 8 GB of GPU memory, not a model list), minimum driver version.
  Otherwise stop with a message naming what was found and what is required.
- Read the platform profile once into a capability set.
- Run with a debug context in development, with driver messages treated as test failures.
- Define which thread owns the context and how other threads hand work to it.
- Define how native memory is allocated, bounded and freed under Java 26.

**Research**
- Cleanroom's context creation (SDL 3, its LWJGL layers): what Quadrature can rely on and
  what a player can reconfigure.
- The capability set that identifies Turing and later, the minimum driver branch on
  Windows and Linux, and how total GPU memory is read reliably at startup.
- NVIDIA driver behavior that affects design: threaded optimization, shader compile
  stutter, buffer orphaning, debug output cost.
- Java 26 options for native memory and GL calls (LWJGL 3 bindings, the foreign function
  and memory API) and their per-call cost.

## 2. Pass graph and frame

**Must**
- Represent the frame as stages with declared resource inputs and outputs, ordered by
  dependency.
- Reproduce the game's world pass order as the baseline graph: sky, opaque terrain,
  entities, block entities, outline and damage, particles, weather, translucent terrain,
  clouds, hand.
- Own all framebuffers, depth and color formats, resolution and presentation.
- Let shaders add, replace and reorder shading stages within declared limits.
- Keep Quadrature's GL state and the hosted renderers' GL state from corrupting each other.

**Research**
- Frame graph designs in current engines: resource lifetime, culling of unused stages,
  compile-once versus per-frame graphs.
- Depth precision for very large view distances: reversed depth, clip control, and how
  each interacts with fixed-function drawing that assumes conventional depth.
- Color pipeline: linear lighting, HDR targets, output transfer, and how unlit hosted draws
  enter it correctly.
- State isolation on a compatibility context: direct state access, state save and restore
  cost, state shadowing.

## 3. Scene sources

**Must**
- **Cube source:** immutable snapshots of cube contents and light, with neighbors, safe to
  read off the main thread; change notifications for blocks and light; load and unload
  events. Owned by Cubature, consumed by Quadrature.
- **Distant source:** terrain at reduced detail without building cubes, from Riven's
  sampler for ungenerated terrain and from stored cubes for generated terrain.
- **Hosted source:** entities, block entities, particles and the hand, drawn by the game's
  and mods' own renderers inside stages Quadrature controls.
- In multiplayer, the client holds the seed and definitions and computes ungenerated
  distant terrain itself (decision D12); define what the server sends for changed terrain.
- **Scene for lighting:** provide the scene to lighting independently of the camera. For
  every cell of a resident cube: whether it blocks light, its material identity, its
  emission and its transmission, taken from the same cube snapshots that feed meshing.
- Keep three states apart for every cell: known air, resident solid, and not available.
  Lighting treats a cube that is not loaded as unknown, never as empty.
- Give each cube's scene data a generation and report the regions that changed, so a
  consumer updates only what changed. The unit of change is the cube, 16 cells a side.

**Research**
- Snapshot designs that avoid copying: versioned sections, copy-on-write, borrow rules.
- What the server must send for distant terrain with 6 to 12 players, and its bandwidth
  and storage cost.

## 4. Terrain meshing

**Must**
- Turn cube snapshots into geometry: block models, fluids, smooth lighting, ambient
  occlusion, biome tint.
- Support blocks whose models come from other mods: baked models, extended states, render
  layers, and block renderers that draw outside the model system.
- Schedule builds on Cubature's executor by priority, cancel stale work, and bound memory.
- Emit a vertex format small enough for the memory budget and rich enough for materials.
- Carry in that format, for every face, what shading needs and the game's 28-byte vertex
  lacks: the face normal, a material identity, and the position within the sprite, so
  the top of a plant can be told from its base. They may be stored per face or derived
  from a block identity; a shader does not recover them from the texture.

**Research**
- Current meshing approaches for voxel terrain: per-face, greedy merging, meshlets, and
  their effect on vertex count, build time and culling granularity.
- Vertex compression and vertex pulling on OpenGL 4.6.
- How Forge 1.12.2 and common mods (connected textures, CodeChickenLib, multipart, fluid
  logging) supply block geometry, and which of those paths can be consumed off-thread.
- Smooth lighting and ambient occlusion formulations and their artifacts.

## 5. Visibility and submission

**Must**
- Decide what is drawn each frame: view frustum, occlusion through solid terrain in three
  dimensions, and distance.
- Submit terrain with a draw-call count that does not grow with visible section count.
- Draw the same terrain, with the same vertex deformation, into more than one view in a
  frame: the camera and at least one light view. Which meshes are resident is not decided
  by the camera's frustum alone.
- In a light view, draw cutout geometry with its texture's alpha, and draw entities, so
  foliage and creatures cast shadows.
- Near terrain owns a coverage map: for each chunk column, whether its surface band is
  loaded and meshed. It reports whether a view's search reaches the edge of the loaded
  box.
- Sort or otherwise resolve translucent geometry correctly.
- Manage GPU memory for geometry: allocation, reuse, defragmentation, budget.

**Research**
- GPU-driven rendering on OpenGL 4.6: indirect multi-draw, GPU-side counts, compute-shader
  culling, persistent mapped buffers.
- Occlusion techniques for cave-heavy voxel worlds: connectivity graphs in three
  dimensions, hierarchical depth, software rasterization.
- Translucency: per-section sorting, per-triangle sorting cost, order-independent methods.
- NVIDIA extensions that change the answer (mesh shaders, command lists, bindless
  resources, variable-rate shading), each with its non-extension fallback cost.
- A measurement on a Turing card: the same terrain drawn with mesh shaders and with
  compute culling plus indirect multi-draw, comparing frame time, memory and build cost.
  The result decides whether one terrain technique or two is built.

## 6. Levels of detail

**Must**
- Draw terrain beyond the near field to the target view distance of a 32 km world.
- Represent overhangs, caves and floating terrain; cubic worlds are not heightfields.
- Build, store, stream and evict detail levels within stated memory and disk budgets.
- Transition between levels and into near terrain without visible seams or popping.
- Light and shade distant terrain through the same stages as near terrain.
- Show coarse detail everywhere first and refine outward as time allows.

**Research**
- Representations for volumetric distant terrain: column runs, sparse voxel structures,
  simplified meshes, hybrids; storage size per square kilometer for each.
- Transition methods: geomorphing, dithered crossfade, skirts, overlap.
- Streaming and prioritization under camera motion at flying speeds.
- Lighting for terrain with no stored light data.

## 7. Lighting and shading

**Must**
- Define a material model of Quadrature's own that blocks, fluids, entities and distant
  terrain all map into.
- Convert material inputs into that model at load: LabPBR textures from resource packs
  (decision D13), per-block definitions from Quadrature and from mods, and defaults
  derived from a block's own properties. State the precedence among them.
- Light from sun and moon, sky, and block light sources, with shadows.
- Provide atmosphere, fog, clouds and water as stages a shader can implement.
- Produce the final image: exposure, tone mapping, anti-aliasing.
- Keep a baseline shader that reproduces the game's own look, as the reference for tests.

**Research**
- Deferred, forward and clustered designs for scenes with many small lights and heavy
  translucency.
- Shadow methods at long range: cascades, virtual shadow maps, and their cost with LODs.
- Ambient occlusion and indirect light without hardware ray tracing on OpenGL.
- Block light beyond the game's 16-level lightmap: colored light, light volumes.
- Anti-aliasing and upscaling available on OpenGL.
- Sourcing material data for blocks from other mods that supply none.
- The mapping from each LabPBR channel into the material model, including what the model
  carries that LabPBR cannot express and what is lost in the other direction.

## 8. Shader system

**Must**
- Define Quadrature's shader format: which stages a shader implements, its inputs, its
  options, its metadata.
- Compile, validate, cache and hot-reload shaders, with errors that name file and line.
- Let a player choose among installed shaders and set their options in game.
- Version the format so shaders state which pipeline versions they run on.

**Research**
- Shader module, include and permutation systems in current engines.
- Compile cost and caching on NVIDIA: program binaries, SPIR-V input on OpenGL 4.6.
- Interface conventions: uniform buffers, shader storage buffers, binding assignment.

## 9. Hosted rendering

**Must**
- Run the game's and mods' entity, block entity, particle, sky, weather and hand drawing
  unmodified, inside Quadrature's stages.
- Bring those draws into the lighting pipeline so they are lit and shadowed consistently
  with terrain.
- Detect and contain GL state they leave behind.

**Research**
- How fixed-function draws can be redirected into custom shading on a compatibility
  context: built-in vertex attributes and matrices in compatibility-profile shading
  language, and which fixed-function state must be mirrored into uniforms.
- The fixed-function state Minecraft 1.12.2 and common mods actually use.
- Cost of state tracking per draw.

## 10. Post-processing and mod interface

**Must**
- Provide bloom and emissive rendering as pipeline features.
- Give mods a stable, versioned interface: declare an extra terrain layer, mark emissive
  geometry, add a stage at a named point, read frame resources, query capabilities.
- Keep that interface in OrthLib so mods do not depend on Quadrature's internals.
- GTCEu: Reloaded's bloom and post-processing is the first client.

**Research**
- Interface designs that stay stable while internals change: handles, capability queries,
  versioned contracts.
- Bloom and emissive methods in an HDR pipeline.

## 11. Exclusivity and other mods

**Must**
- Recognize other renderers before their classes load and stop with a clear message.
- State a policy for mods that inject into the game's chunk rendering, which Quadrature
  replaces: which are supported through the mod interface, which are reported.
- Retire Cubature's renderer integrations phase by phase.

**Research**
- Which widely used 1.12.2 mods inject into `RenderChunk`, `RenderGlobal`,
  `BlockRendererDispatcher` or `EntityRenderer`, and what each needs.
- When in Cleanroom's startup a stop message can be shown.

## 12. Budgets and measurement

**Must**
- State targets before building: frame time, heap, GPU memory, disk, build latency, at a
  32 km world with 6 to 12 players, an 8 GB heap floor and an 8 GB GPU.
- Measure with real players flying, not spectators, and record every capture.
- Compare against the current stack on the canonical world as the baseline.

**Research**
- GPU memory and timing queries on NVIDIA under OpenGL.
- Frame pacing and latency measurement methods.

## 13. Verification

**Must**
- Compare rendered frames against references automatically, with a stated tolerance.
- Capture frames reproducibly: fixed world, camera path, time and settings.
- Fail tests on GL errors and driver debug messages.
- Record provenance for every technique: its source and its license standing.

**Research**
- Image comparison metrics suited to rendering regressions.
- Running GPU tests unattended on NVIDIA hardware.

## 14. Settings and player experience

**Must**
- Video settings, shader selection and options, presets, and a debug overlay.
- Stop messages and error reports a player can act on.

**Research**
- How Cleanroom's and the game's settings screens can be extended or replaced.

## 15. Repository and build

**Must**
- Separate modules: a core with no Minecraft dependency, the Minecraft host, shaders, and
  test tooling.
- Build on Java 26 with CleanroomGradle; pin every platform version in one manifest.
- Depend on Cubature and OrthLib through their published interfaces only.

**Research**
- CleanroomGradle's current conventions for multi-module mods.
