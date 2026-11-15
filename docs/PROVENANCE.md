# Provenance

Where each technique in Quadrature came from, and on what terms. Quadrature is offered
under NRR 1.0, a public-domain dedication, so everything in it must be something its
authors can dedicate: their own expression of ideas, never copied code.

## Rules

1. **Sources that may be used:** the OpenGL and GLSL specifications; vendor documentation,
   samples' documentation and presentations; published papers, talks and articles; the
   public interfaces and source of the game, Forge and Cleanroom, which Quadrature runs
   on; measurements.
2. **Sources that may not be used for implementation:** the source code of other
   renderers, shader loaders and level-of-detail mods. Their public documentation of
   behavior, requirements and known conflicts may be read.
3. **Ideas, not expression.** A technique is taken as a description and written anew. No
   code is transcribed or adapted from any source, whatever its license.
4. **Data formats** defined by others (for example LabPBR's texture channels) may be read
   and written; that is interoperability, recorded here like any other source.
5. **One record per technique**, added in the commit that introduces the code.

## Record format

```
### <technique>
- Code: <paths>
- Source: <title, author, date, link>, or "original"
- Taken: <the idea used, in one or two sentences>
- Not taken: <anything deliberately left, where it matters>
- Added: <date>
```

## Records

### Platform profile, capability set and gate
- Code: `core/src/main/java/cc/orthrus/quadrature/core/platform/`,
  `src/main/java/cc/orthrus/quadrature/platform/`
- Source: original. Facts used: OpenGL 4.6 compatibility specification (context version
  and profile queries); NVIDIA, `GL_NVX_gpu_memory_info` specification (token `0x9047`,
  total dedicated memory in kilobytes); Khronos registry, `NV_mesh_shader` and
  `EXT_mesh_shader` (extension names); Forge 1.12.2,
  `CustomModLoadingErrorDisplayException` (the loading error screen).
- Taken: the extension names and query tokens as published.
- Added: 2026-11-13

### Frame comparison
- Code: `tools/frames.py`
- Source: original.
- Taken: nothing. Changed-pixel fraction and mean absolute difference are elementary
  measures, chosen from Quadrature's own repeatability measurements.
- Added: 2026-11-13

### Pass graph
- Code: `core/src/main/java/cc/orthrus/quadrature/core/frame/PassGraph.java`,
  `CompiledGraph.java`, `StageDefinition.java`, `StageKind.java`
- Source: O'Donnell, "FrameGraph: Extensible Rendering Architecture in Frostbite", GDC
  2017; Unity's render graph documentation.
- Taken: a frame declared as stages that name the resources they read and write, ordered
  by those dependencies, with stages whose results nothing reads dropped.
- Not taken: transient resource allocation and aliasing, which nothing needs yet.
- Added: 2026-11-13

### Stage timeline and statistics
- Code: `core/src/main/java/cc/orthrus/quadrature/core/frame/FrameTimeline.java`,
  `StageStatistics.java`, `GpuTimer.java`
- Source: original.
- Added: 2026-11-13

### GPU stage timing
- Code: `src/main/java/cc/orthrus/quadrature/frame/GlGpuTimer.java`
- Source: OpenGL 4.6 specification (timer queries, `TIME_ELAPSED`, result availability);
  Lighthouse3D, "OpenGL Timer Query".
- Taken: reading a query only once the driver reports its result available, from queries
  issued some frames earlier, so reading never stalls.
- Added: 2026-11-13

### Stage boundaries from the game's world pass
- Code: `src/main/java/cc/orthrus/quadrature/mixin/MixinEntityRenderer.java`,
  `src/main/java/cc/orthrus/quadrature/frame/BaselineGraph.java`, `FrameHost.java`
- Source: original. Facts used: the profiler section names the game passes in
  `EntityRenderer.renderWorldPass`, and Cleanroom's `crl.dev.mixin` and `MixinConnector`
  conventions for registering a Mixin configuration.
- Added: 2026-11-13

### Reversed depth
- Code: `core/src/main/java/cc/orthrus/quadrature/core/depth/ReversedDepth.java`,
  `src/main/java/cc/orthrus/quadrature/depth/DepthCalls.java`,
  `src/main/java/cc/orthrus/quadrature/asm/`, `MixinFramebuffer.java`, `MixinMinecraft.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification, sections 13.7
  and 13.8 (clip control and the depth mapping); NVIDIA's published description of
  reversed depth; Forge's loading plugin and class transformer interfaces. Redirecting
  OpenGL calls by rewriting call sites is a known technique in other renderers for this
  game; none of their source was read for it.
- Added: 2026-11-13

### Near terrain, first form
- Code: `src/main/java/cc/orthrus/quadrature/terrain/`, `MixinRenderGlobal.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/terrain/CubeKey.java`
- Source: original. It calls the game's own block model renderer against a snapshot, as
  the game's chunk builder calls it against its render cache. Facts used: the game's world
  pass (where each layer is drawn and what state is set for it, including the light map),
  its block vertex format, and Cubature's cube source interfaces and render distance bound.
- Added: 2026-11-14

### Meshing a cube only among loaded neighbors
- Code: `src/main/java/cc/orthrus/quadrature/terrain/Terrain.java` (`surrounded`, `rebuild`)
- Source: original. Facts used: the running game's behavior in a cubic world, observed in
  what it draws: beyond 24 blocks from the player it meshes a cube only once the six cubes
  sharing a face with it are loaded, and a cube that cannot be meshed keeps the mesh it
  has.
- Not taken: no source code, the game's or another renderer's, was used for it.
- Added: 2026-11-14

### Placing a cube's vertices as the game places a chunk's
- Code: `src/main/java/cc/orthrus/quadrature/terrain/TerrainProgram.java`
- Source: original. Facts used: the running game's behavior and values. It moves to a
  chunk by translating its model-view matrix in single precision, then multiplies by a
  matrix each render chunk carries; that matrix was read from the running game through
  the render chunk's public method: a scale of 1.00000095 and an offset of 7.62939453e-06
  on each axis. Frames were compared with and without it.
- Taken: the order of the two steps and the matrix's values, composed anew in single
  precision.
- Not taken: no source code, the game's or another renderer's, was used for it. The
  fixed-function transform, which reproduced the game's image more closely in a test, is
  not used.
- Added: 2026-11-14

### Views
- Code: `core/src/main/java/cc/orthrus/quadrature/core/view/View.java`,
  `src/main/java/cc/orthrus/quadrature/terrain/MainView.java`
- Source: original. Facts used: the planes of a clip volume read from the rows of the
  combined projection and model-view matrix (Gribb and Hartmann, "Fast Extraction of
  Viewing Frustum Planes from the World-View-Projection Matrix", 2001); the OpenGL 4.6
  compatibility specification for the two clip depth ranges; fitting an orthographic
  volume along a light to what a camera sees, as cascaded shadow maps are commonly
  described; the game's behavior for where its world matrices stand when terrain is drawn
  and for the sun's path.
- Added: 2026-11-15

### Visibility from face connectivity
- Code: `core/src/main/java/cc/orthrus/quadrature/core/terrain/CubeConnectivity.java`,
  `Visibility.java`, and their use in `src/main/java/cc/orthrus/quadrature/terrain/`
- Source: Tommaso Checchi, "The Advanced Cave Culling Algorithm", parts 1 and 2, 2014
  (https://tomcc.github.io/2014/08/31/visibility-1.html), and the running game's behavior
  as the selection comparison reports it.
- Taken: the idea. Each section records which pairs of its faces are joined by a flood
  fill through blocks that are not opaque; a breadth-first search from the camera's
  section passes from face to face only where they are joined and never doubles back.
- Not taken: the game's code, and its rule that the first way into a section decides
  where the search may go from it. Here a cube entered by several faces is left through
  all that any of them allows, and doubling back is judged by position relative to the
  eye's cube, not by the path taken. No other renderer's source was read.
- Added: 2026-11-15
### Terrain diagnostics
- Code: `src/main/java/cc/orthrus/quadrature/terrain/SelectionComparison.java`,
  `ScreenProbe.java`, and the accessors `RenderGlobalAccess.java`, `RenderInfoAccess.java`
  and `ViewFrustumAccess.java`
- Source: original. Facts used: the game's public interfaces for what it selected to draw
  in a frame and for a render chunk's visibility between faces; the OpenGL 4.6
  compatibility specification (buffer and pixel reads, the matrix queries).
- Taken: nothing from another renderer. The comparison evaluates the game's own conditions
  on the game's own data.
- Added: 2026-11-14
### State envelope and probe stage
- Code: `src/main/java/cc/orthrus/quadrature/frame/StateEnvelope.java`, `Probe.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification (direct state
  access; the state the attribute stack does not save) and the state audit's measurements.
- Added: 2026-11-13

### State audit
- Code: `src/main/java/cc/orthrus/quadrature/frame/StateAudit.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification's state query
  names.
- Added: 2026-11-13

### Mod survey
- Code: `tools/mod_survey.py`
- Source: original. Facts used: the Java class file format (constant pool), from the Java
  Virtual Machine Specification.
- Taken: nothing from the surveyed mods. The tool reads the names a class refers to; it
  does not read, decompile or execute mod code.
- Added: 2026-11-13

## Design sources

Techniques chosen in `docs/ARCHITECTURE.md` but not yet written have their sources listed
there and in `docs/research/`. Each gets a record above when its code is added.
