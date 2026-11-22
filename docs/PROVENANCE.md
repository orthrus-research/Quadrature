# Provenance

Where each technique in Quadrature came from, and on what terms. Quadrature's code is
its authors' own expression of ideas, never copied code (D2), licensed under the Apache
License 2.0 (D15). Material under another party's terms is kept apart and listed in
`NOTICE.md`; NVIDIA's runtimes are linked, never taken, and stay in their own module (D19).

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
  `CustomModLoadingErrorDisplayException` (the loading error screen); NVIDIA, the data center
  drivers' supported branches (R580 and R620 long-term support, R595 production, with their
  end-of-life dates, as of 2026-11-04) and the form of the driver's version in the context's
  version string, for the driver floor (D20); Minecraft 1.12.2, `SystemToast` (the warning's
  toast).
- Taken: the extension names, query tokens and branch numbers as published.
- Added: 2026-11-13; the driver floor 2026-11-21

### The light's depth drawn by tiles and spread over frames
- Code: `core/src/main/java/cc/orthrus/quadrature/core/view/LightBox.java`, `LightTiles.java`,
  `LightLayerPlan.java`, `ShadowMaps.java`, `View.throughLight`;
  `core/src/main/java/cc/orthrus/quadrature/core/terrain/CubeList.java`;
  `src/main/java/cc/orthrus/quadrature/terrain/LightLayer.java`, `ShadowTarget.java`, and
  `Terrain.lightFrame`, `drawLight`, `drawTiles`, `checkLayer`
- Source: original in its rules. Ideas used: keeping a shadow map between frames and drawing
  again only what has changed, from Insomniac's "CSM Scrolling: An acceleration technique for
  the rendering of cascaded shadow maps" (Mike Acton, SIGGRAPH 2012, Advances in Real-Time
  Rendering); refreshing the costliest part of a shadow map over several frames, from
  "Shadows Optimizations in Cyberpunk 2077" (Kamil Dybisz and Paweł Witanowski, SIGGRAPH 2021
  talks). OpenGL 4.6 specification: scissored framebuffer clears, `glCopyImageSubData` between
  textures of one depth format.
- Taken: that a kept map need be drawn again only where it changed, and that a full redraw
  can be spread over frames.
- Not taken: Insomniac's scrolling of a map by copying it across itself as the camera moves;
  here a box stays put while the player is near its center and is replaced, drawn into a
  scratch over several frames, only once they are not. The tiles, the rule that a cube
  covering a tile is drawn whole, the budget counted in cubes, the boxes fixed in the world
  and made into a view for each frame's origin, and the check against a box drawn whole are
  this project's own.
- Added: 2026-11-18

### State put back from a mirror of the game's cache, and targets prepared at world attach
- Code: `src/main/java/cc/orthrus/quadrature/frame/GlStateMirror.java`,
  `src/main/java/cc/orthrus/quadrature/mixin/MixinGlStateManager.java`, `ShadowTarget.begin`,
  `end` and `prepare` in `src/main/java/cc/orthrus/quadrature/terrain/ShadowTarget.java`
- Source: original. Facts used: the game's `GlStateManager` (Minecraft 1.12.2 as Cleanroom
  ships it), whose setters for depth function and mask, face culling and polygon offset keep
  the values the mirror copies; the OpenGL 4.6 specification for `glFinish` and framebuffer
  clears. Measured here: a driver put off making a texture's memory its own until its first
  use in a frame, and a clear of the texture alone did not count as one.
- Taken: the setters' names and meanings.
- Added: 2026-11-18

### GPU memory sources and the capability dump
- Code: `core/src/main/java/cc/orthrus/quadrature/core/platform/GpuMemory.java`,
  `GpuMemorySource.java`, `CapabilityReport.java`;
  `src/main/java/cc/orthrus/quadrature/platform/NvxMemorySource.java`, `CapabilityDump.java`
- Source: original. Facts used: OpenGL 4.6 compatibility specification (the state tables
  `glGet` reads, indexed queries, `glGetInternalformativ`, context flags and the list of
  shading language versions); Khronos registry, the specifications of `NVX_gpu_memory_info`,
  `NV_mesh_shader`, `KHR_shader_subgroup`, `NV_shading_rate_image`,
  `NV_primitive_shading_rate`, `NV_shader_thread_group`, `ARB_sparse_buffer`,
  `ARB_sparse_texture`, `EXT_external_objects` and `EXT_external_objects_win32` (their query
  tokens and what each returns); LWJGL 3.4.3, the token values as its bindings define them.
- Taken: the query tokens and their meanings as published. Asking only for limits whose
  extension is reported, and writing device identifiers as truncated digests, are this
  project's own.
- Added: 2026-11-18

### Frame comparison
- Code: Workbench's `frames.compare`, `frames.measure` and `frames.check`, ported from this
  project
- Source: original.
- Taken: nothing. Changed-pixel fraction and mean absolute difference are elementary
  measures, chosen from Quadrature's own repeatability measurements.
- Added: 2026-11-13

### Shader validation
- Code: `tools/shaders/DumpShaders.java`; `build.gradle` (`dumpShaders`, `validateShaders`)
- Source: original. Facts used: glslang, the Khronos reference compiler, run as a tool on the
  stages written out.
- Taken: nothing. Finding the stages by a version line among a class's constants and naming each
  stage by what it declares or writes are this project's own.
- Added: 2026-11-21

### Stage times by view
- Code: `tools/stage_views.py`
- Source: original. Facts used: the client log's own stage report and the tour launcher's
  capture line.
- Taken: nothing. Taking the last report before each capture as that view, and counting a
  view settled when no mesh waits, are this project's own.
- Added: 2026-11-18

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

### Notice of an out-of-memory error the game catches
- Code: `src/main/java/cc/orthrus/quadrature/platform/OutOfMemoryWatch.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/platform/OutOfMemoryNotice.java`, and the wrap of
  `runGameLoop` in `src/main/java/cc/orthrus/quadrature/mixin/MixinMinecraft.java`
- Source: original. Facts used: the game's `Minecraft.run` (Minecraft 1.12.2 as Cleanroom ships it),
  read from its class files, catches `OutOfMemoryError` around `runGameLoop`, then frees memory,
  shows `GuiMemoryErrorScreen` and runs a collection, logging nothing; MixinExtras' `WrapOperation`;
  the platform's `BufferPoolMXBean` for direct buffers.
- Taken: the names and the order of the game's handling. Noting the error where it is caught and
  logging it on the loop's next pass, once memory is freed, is this project's own.
- Added: 2026-11-18
### Meshes built on the cube source's build pool
- Code: `src/main/java/cc/orthrus/quadrature/terrain/Terrain.java` (`submit`, `sort`, `build`,
  `priority`, `reprioritize`, `collect`, `take`, `place`), `CubeMesher.java`, `BuiltCube.java`,
  `FaceBuilder.java`, `Materials.java`;
  `core/src/main/java/cc/orthrus/quadrature/core/terrain/AtlasRectangles.java`
- Source: original. Facts used: Cubature's cube source contract (its `docs/CUBE-SOURCE.md`,
  sections 2, 3 and 5): the build pool's tasks, priorities, stale masks and polling, a
  snapshot's versions per cube, and `cubeContent`; the game's block renderer and Forge's
  render layer, which keep their per-thread state per thread, as the game's own chunk build
  threads use them.
- Taken: the pool's interface as published, and the order its contract suggests: the
  squared distance from the camera, doubled outside the view, given anew as the camera
  moves or turns. The result's order number, dropping a result older than what a cube
  shows, the per-thread caches in front of shared numbering, and keeping the light's tiles
  for a mesh of the same shape are this project's own.
- Added: 2026-11-18

### Meshing a cube only among loaded neighbors
- Code: `src/main/java/cc/orthrus/quadrature/terrain/Terrain.java` (`surrounded`, `sort`)
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

### Terrain arena and indirect drawing
- Code: `core/src/main/java/cc/orthrus/quadrature/core/terrain/ArenaAllocator.java`,
  `CubeSlots.java`, `CubeMatrix.java`, `DrawCommands.java`, and
  `src/main/java/cc/orthrus/quadrature/terrain/TerrainArena.java`, `TerrainProgram.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification (immutable
  buffer storage and persistent mapping, sync objects, indirect multi-draw of arrays and
  its command layout, shader storage blocks with std430 layout, the base instance in the
  vertex stage); Everitt, Sellers, McDonald and Foley, "Approaching Zero Driver Overhead",
  GDC 2014, for one large mapped buffer drawn from with indirect commands and a fence
  before reuse; Haar and Aaltonen, "GPU-Driven Rendering Pipelines", SIGGRAPH 2015.
- Taken: the ideas. Geometry in a few large buffers; a freed range reused only after a
  fence placed after the draws that read it has signalled; one indirect multi-draw over
  commands that each name a range and, by base instance, a record.
- Not taken: any allocator or renderer source. Buffers that stay mapped, which the talk
  recommends, are built and not the default: measured here, the driver keeps them in
  system memory and draws from them several times slower, by its own messages. Staging
  writes in one buffer and copying on the GPU, a use of the specification's buffer copy,
  is likewise built and not the default.
- Added: 2026-11-15

### Free blocks split and joined
- Code: `core/src/main/java/cc/orthrus/quadrature/core/terrain/ArenaAllocator.java`
- Source: Knuth, "The Art of Computer Programming", volume 1, section 2.5, on dynamic
  storage allocation; Wilson, Johnstone, Neely and Boles, "Dynamic Storage Allocation: A
  Survey and Critical Review", 1995.
- Taken: the ideas. The smallest free range that holds a request is taken and the
  remainder stays free; a range given back is joined to the free ranges that touch it.
- Not taken: any allocator's source. Ranges are kept in two ordered sets, by start within
  a page and by length, which is the plainest structure that does both.
- Added: 2026-11-15

### The light's depth
- Code: `src/main/java/cc/orthrus/quadrature/terrain/ShadowTarget.java`, the depth program
  in `TerrainProgram.java`, `core/src/main/java/cc/orthrus/quadrature/core/view/LightBox.java`
  and `ShadowMaps.java`
- Source: Williams, "Casting Curved Shadows on Curved Surfaces", SIGGRAPH 1978, for depth
  drawn from the light; Valient, "Stable Rendering of Cascaded Shadow Maps", ShaderX6,
  2008, for a box of fixed size moved by whole texels; the OpenGL 4.6 compatibility
  specification (framebuffers without color, array textures, sampler objects and depth
  comparison, depth clamp, timestamps). Facts used: the game's alpha threshold for its
  cutout layers, read from the running game.
- Taken: the ideas. No renderer's source was read.
- Added: 2026-11-15

### The scene for lighting
- Code: `core/src/main/java/cc/orthrus/quadrature/core/scene/`,
  `src/main/java/cc/orthrus/quadrature/terrain/SceneBuffers.java`, and the cells noted in
  `CubeMesher.java`
- Source: original. Facts used: the game's public interfaces for a block state's
  identity, light value, light opacity and whether it is an opaque cube; the OpenGL 4.6
  compatibility specification (buffer textures, integer formats). A coarse lookup over
  bricks of cells is common to sparse voxel structures, as in Crassin, Neyret, Lefebvre
  and Eisemann, "GigaVoxels", I3D 2009.
- Taken: the idea of a lookup that names a brick or says a region is uniform. A cell's
  state as a short index into its cube's own list of states is the palette commonly used
  to store voxel volumes, written anew.
- Not taken: any structure's source, and no hierarchy beyond one coarser level a brick.
- Added: 2026-11-16

### Driver messages
- Code: `src/main/java/cc/orthrus/quadrature/terrain/DriverMessages.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification (debug output,
  its callback, message control and debug groups).
- Added: 2026-11-15

### Coverage of chunk columns and the search's edge
- Code: `core/src/main/java/cc/orthrus/quadrature/core/terrain/CoverageMap.java`,
  `SearchEdge.java`, `SurfaceBand.java`, the edge counts in `Visibility.java`;
  `src/main/java/cc/orthrus/quadrature/terrain/SurfaceHeights.java`
- Source: original. Facts used: the cube source's public methods for surface heights,
  surface version and cube content, looked up by name and used only where present.
- Added: 2026-11-16

### The walk over a model's quads, and face records
- Code: `src/main/java/cc/orthrus/quadrature/terrain/QuadWalk.java`, `FaceBuilder.java`;
  `core/src/main/java/cc/orthrus/quadrature/core/terrain/FaceRecord.java`; the record
  pages in `TerrainArena.java` and the stages that draw from records in
  `TerrainProgram.java`
- Source: original. Facts used: the public interfaces of the game and Forge for a baked
  model's quads, the order the game's block model renderer takes them in, and Forge's
  vertex lighters and buffer consumer, which are called and not reproduced; the game's
  default face test as Forge patches it (a side the block's bounds stop short of is drawn,
  otherwise one the neighbor's `doesSideBlockRendering` does not block), read from the
  game's class files, and the way its buffer adds a consumer's offset and its own
  translation before a vertex's coordinate; the OpenGL 4.6 and GLSL 4.60 specifications
  (shader storage blocks, `gl_VertexID`, `gl_BaseInstance`).
- Taken: the idea of building a face's vertices in the vertex stage from a record indexed
  by vertex number, as described in research R2; the default face test's rule, made with
  one kept position for blocks whose classes keep the game's own test. No code from
  another renderer.
- Added: 2026-11-16; the face test and the offset 2026-11-18

### The material table and the list of kinds
- Code: `core/src/main/java/cc/orthrus/quadrature/core/material/`,
  `src/main/java/cc/orthrus/quadrature/terrain/Materials.java`,
  `src/main/resources/assets/quadrature/material_kinds.properties`
- Source: original. Facts used: the game's public interfaces for a block's material, the
  light it gives and its states' properties; published values for the reflectance of
  common non-metals (about 0.04) from the literature on physically based shading. The list
  of kinds and its numbers are a data format agreed with the shading stage that reads the
  table; the names in it are the registry names of the game's and of mods' blocks.
- Taken: nothing from another renderer or shader pack.
- Added: 2026-11-16
### Targets for shading
- Code: `src/main/java/cc/orthrus/quadrature/terrain/GBufferTarget.java`, the stages that
  write the targets in `TerrainProgram.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/view/ShadingTargets.java`
- Source: original. Facts used: the OpenGL 4.6 and GLSL 4.60 specifications (framebuffers
  with several color attachments, derivatives in a fragment stage, shader storage
  blocks); the sRGB transfer function, IEC 61966-2-1; the game's own shading of a face by
  the way it is turned (0.5, 0.6, 0.8, 1), read from its public behavior.
- Taken: the idea of writing surface properties to several targets for a later shading
  pass, which is common knowledge in rendering. The layout of the four targets is a
  contract agreed with the shading stage that reads them.
- Added: 2026-11-16
### The fade at the edge of near terrain
- Code: `core/src/main/java/cc/orthrus/quadrature/core/fade/NearFade.java`, the fade in
  the stages of `TerrainProgram.java`, and what `TerrainHook.java` publishes of it
- Source: original, on `FadePattern` in `core`. Facts used: the GLSL 4.60 specification
  (`gl_FragCoord`, `discard`).
- Added: 2026-11-16
### The sun's and the moon's directions
- Code: `core/src/main/java/cc/orthrus/quadrature/core/view/SunPath.java`, used by
  `src/main/java/cc/orthrus/quadrature/terrain/MainView.java`
- Source: original. Facts used: the game's celestial angle, from its public interface, and
  spherical trigonometry. The tilt's convention, degrees toward the south at noon, is
  agreed with the shading stage that reads the directions.
- Added: 2026-11-16

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

### Cross-mod diagnostics
- Code: `tools/diagnostics.py`
- Source: original. Facts used: the JDK's JFR event names and fields as its `jfr` tool prints
  them (`jdk.ExecutionSample`, `jdk.ThreadCPULoad`, `jdk.ObjectAllocationSample`,
  `jdk.Compilation`, `jdk.Deoptimization` and the rest) and the HotSpot options
  `DebugNonSafepoints` and `FlightRecorderOptions`; Mixin's documented naming of merged handler
  methods; the logs and frame records of Cubature, Quadrature, Riven's launcher and the motion
  helper.
- Taken: nothing. Charging a sample to the nearest owned frame with libraries transparent, keeping
  the engine's time with the mod frame below it, and splitting each thread's measured CPU by its
  samples are this project's own.
- Added: 2026-11-19

### Mod index
- Code: `tools/mod_index.py`
- Source: original. Facts used: the Java class file format (constant pool, members, the
  `RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations` attributes), from the Java Virtual
  Machine Specification; the names and members of Mixin's, MixinExtras' and Forge's annotations
  and the Mixin configuration and refmap formats, as published.
- Taken: nothing from the indexed mods. The tool reads names and annotation values; it does not
  decompile or execute mod code.
- Added: 2026-11-19

### Mod survey
- Code: `tools/mod_survey.py`
- Source: original. Facts used: the Java class file format (constant pool), from the Java
  Virtual Machine Specification.
- Taken: nothing from the surveyed mods. The tool reads the names a class refers to; it
  does not read, decompile or execute mod code.
- Added: 2026-11-13

### A shading stage handed over in a folder
- Code: `core/src/main/java/cc/orthrus/quadrature/core/stage/` (`Json.java`,
  `StageManifest.java`, `StagePlan.java`, `StageCompare.java`, `FrameBlock.java`,
  `FrameClock.java`, `FrameHistory.java`);
  `src/main/java/cc/orthrus/quadrature/frame/StageRunner.java`, `StageHost.java`,
  `StageCheck.java`
- Source: original. Facts used: the OpenGL 4.6 and GLSL 4.60 specifications (the std140
  layout of a uniform block, the queries for a block's size and its members' offsets,
  sampler objects, depth comparison, direct state access); the JSON grammar of RFC 8259;
  SHA-256 as the Java platform provides it. The manifest's fields and the block's layout
  were agreed with the author of the stage's programs and are this project's own.
- Taken: nothing from another renderer or from a shader pack's format.
- Added: 2026-11-16

### Writers into the targets, and how far they reach
- Code: `core/src/main/java/cc/orthrus/quadrature/core/frame/TargetWriters.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/view/NearEdge.java`, `addTargetWriter` and
  `targetsEdge` in `src/main/java/cc/orthrus/quadrature/terrain/TerrainHook.java`, the edge and
  the cloud height in `core/src/main/java/cc/orthrus/quadrature/core/stage/FrameBlock.java`
- Source: original. Facts used: the game's public interface for the height at which a world
  keeps its clouds.
- Taken: nothing from another renderer. Writers are a list called in order on the thread that
  draws, and the reach is the distance from the eye to the nearest side of the box of cubes
  that can be meshed.
- Added: 2026-11-16

### Timing each pass of a shading stage
- Code: the pass timers in `src/main/java/cc/orthrus/quadrature/frame/StageRunner.java`
- Source: OpenGL 4.6 specification (timestamp queries, `glQueryCounter`, result availability).
- Taken: two timestamps a pass, kept in a ring of four frames' slots and read only once the
  driver reports them available, so reading never stalls.
- Added: 2026-11-16

### The depth target written from the frame that is shaded
- Code: the depth image in `src/main/java/cc/orthrus/quadrature/terrain/GBufferTarget.java` and
  the images that wait for the shading stage in `TerrainHook.java` and `StageHost.java`
- Source: original. Facts used: the OpenGL 4.6 specification (reading a depth texture's image
  as floats).
- Taken: the stored depth written as the raw floats and as a gray image of its fourth root, so
  that near depths stay apart, in the same frame the stage's images are written of.
- Added: 2026-11-16

### Audit of the occlusion in the targets
- Code: `core/src/main/java/cc/orthrus/quadrature/core/material/OcclusionAudit.java`, its use in
  `src/main/java/cc/orthrus/quadrature/terrain/QuadWalk.java` and `FaceBuilder.java`
- Source: original. Facts used: the game's smooth lighting as its public behavior shows it (a
  corner's occlusion is the mean of four cells in front of the face, 0.2 where closed) and its
  public interfaces for a state's ambient occlusion light value and material.
- Taken: nothing from another renderer. The occlusion is the vertex color divided by the tint
  and the face's shading, counted by direction and by what stands in front.
- Added: 2026-11-16

### The host's self-test of kept and smaller targets
- Code: `src/main/java/cc/orthrus/quadrature/frame/StageSelfTest.java`
- Source: original. Facts used: the OpenGL 4.6 and GLSL 4.60 specifications (a triangle that
  covers the viewport from `gl_VertexID`, reading a texture's image).
- Taken: nothing from another renderer. A stage of its own adds an eighth to a kept target each
  frame, so its value after each frame shows the history swapped, read and zeroed.
- Added: 2026-11-16

### Quads drawn by something other than a model
- Code: `foreign` in `src/main/java/cc/orthrus/quadrature/terrain/QuadWalk.java`, its calls in
  `CubeMesher.java`, and the header written for such quads in `FaceBuilder.java`
- Source: original. Facts used: the game's public interfaces for a block's render type and for
  drawing a fluid or a block with a renderer of its own into a buffer builder, and the buffer
  builder's vertex count.
- Taken: nothing from another renderer. The quads a block wrote outside the walk over a model
  are noted with the block's state, so that lava and other fluids read their own row of the
  material table rather than the first.
- Added: 2026-11-16

### Emission from a surface's kind and its texel
- Code: `core/src/main/java/cc/orthrus/quadrature/core/material/KindEmission.java`, its use in
  the stage that writes the targets in `src/main/java/cc/orthrus/quadrature/terrain/TerrainProgram.java`
- Source: original. Facts used: the GLSL 4.60 specification (`smoothstep`, its definition as
  t * t * (3 - 2 t) of the clamped share). The kinds and the values for each are a contract
  agreed with the shading stage that reads target B.
- Taken: nothing from another renderer or shader pack. The emission of an emitter rises with
  the greatest channel of the texel's linear color, so a lamp's bright face glows and its frame
  does not.
- Added: 2026-11-17

### The near layer of the light's depth
- Code: `core/src/main/java/cc/orthrus/quadrature/core/view/ShadowLayers.java`, `keep` and
  `boxDepth` in `ShadowMaps.java`, the box of its own depth in `View.java`; the near layer in
  `src/main/java/cc/orthrus/quadrature/terrain/Terrain.java` and `ShadowTarget.java`
- Source: Valient, "Stable Rendering of Cascaded Shadow Maps", ShaderX6, 2008, for boxes of
  fixed size moved by whole texels; Dimitrov, "Cascaded Shadow Maps", NVIDIA, 2007, and Engel,
  "Cascaded Shadow Maps", ShaderX5, 2006, for several boxes of different size about the eye,
  drawn into layers of one texture; the OpenGL 4.6 specification (attaching one layer of an
  array texture to a framebuffer).
- Taken: the ideas. A smaller box near the player in a second layer, each box drawn again only
  when its own conditions are met; the near box narrow across the light and extended along it
  to the wide box's side toward the light, so that casters far toward a low light are drawn into
  it, as cascades are commonly extended toward the light to hold their casters. A kept layer's
  matrix is carried to the frame's origin by translating it, which is plain linear algebra.
- Not taken: any renderer's source, and boxes fitted to the camera's frustum: both boxes are
  fixed cubes centered on the player.
- Added: 2026-11-17

### The probe of the light's depth at chosen pixels
- Code: `fromLight` in `core/src/main/java/cc/orthrus/quadrature/core/stage/FrameBlock.java`;
  the probe and the raw targets in `src/main/java/cc/orthrus/quadrature/frame/StageHost.java`;
  `cubeReport` in `src/main/java/cc/orthrus/quadrature/terrain/Terrain.java` and `TerrainHook.java`
- Source: original. Facts used: the inverse of a light view's matrix, plain linear algebra; the
  OpenGL 4.6 specification (reading a texture's image as floats); the game's public interface
  for the block state at a place.
- Added: 2026-11-17

### Run tiles
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/RunTile.java`
- Source: original. Facts used: Cubature's terrain preview interface, which gives a column
  as contiguous vertical runs; `docs/research/R3-levels-of-detail.md` on column runs as a
  representation; Quadrature's own size survey of Riven's sampler (runs per column, and how
  many no face can touch).
- Taken: nothing beyond the interface's shape.
- Added: 2026-11-15

### Run mesher
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/RunMesher.java`, `TileMesh.java`
- Source: original, from the distant terrain design and the size survey. Joining coplanar
  faces into rectangles is widely described, for example Lysenko, "Meshing in a Minecraft
  Game", 0fps, 2012: the volume is swept in each direction and each slice's faces are
  covered greedily by rectangles, the first in an order of position and size taken each
  time. That article leaves levels of detail aside. Skirts that hide the cracks between
  terrain levels are common in terrain rendering writing.
- Taken: the idea of joining a slice's coplanar faces into rectangles. Faces come from the
  overlap of one column's solid stretches with its neighbor's stretches of space; tops are
  joined row by row from bit masks, not by the article's ordered search; the skirt is the
  part of a tile's outer sides that its neighbor hides, so a tile closes itself.
- Not taken: any mesher's source.
- Added: 2026-11-15

### Corner occlusion
- Code: `RunMesher.java` (`corner`, `topOcclusion`, `sideOcclusion`)
- Source: Lysenko, "Ambient occlusion for Minecraft-like worlds", 0fps, 2013.
- Taken: the rule for a corner from the two cells beside it and the one diagonal to it. The
  article gives the light left, 0 when both sides are closed and otherwise 3 less the count
  of closed cells; the mesher keeps the occlusion, its complement. Also the article's
  condition for joining: faces are joined only where their corners' values agree.
- Not taken: code, and the article's turning of a quad's diagonal by its corner values:
  faces are split along one fixed diagonal. Applying the rule to faces many blocks tall, at
  their two ends, and joining along each axis separately by whether the corners agree along
  it, are original.
- Added: 2026-11-15

### Face record
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/FaceRecord.java`
- Source: original. Facts used: the survey's measurements (12 bits of height suffice for
  Riven's world, and fewer than 400 block states appear in a window).
- Added: 2026-11-15

### Rings of tiles
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/Rings.java`, `TileKey.java`
- Source: Losasso and Hoppe, "Geometry Clipmaps: Terrain Rendering Using Nested Regular
  Grids", SIGGRAPH 2004.
- Taken: the idea. Square levels nested round the viewer, each with twice the spacing and
  twice the extent of the one inside it; a level's edge kept on the coarser level's even
  vertices (the paper's third constraint on its regions), which is why a level moves in
  steps of two of its cells; each level drawn as its square with the finer square cut out
  (the paper's render region).
- Not taken: the paper's heightfield grids, wrapped vertex buffers, upsampling, morphing of
  heights across a transition region and zero-area triangles at level edges. Levels here
  are tiles of run columns, meet by the shared fade pattern and by skirts, and are clipped
  to a world's bounds; those are original.
- Added: 2026-11-15

### Vertex stage that builds faces from records
- Code: `src/main/java/cc/orthrus/quadrature/distant/DistantProgram.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/lod/TileDraws.java`
- Source: the OpenGL 4.6 compatibility specification (shader storage blocks and std430
  layout, `gl_VertexID` and `gl_BaseInstance`, indirect multi-draw of arrays). Building
  vertices in the vertex stage from a buffer the stage indexes itself is widely described as
  programmable vertex pulling, for example Rakos, "Programmable Vertex Pulling", chapter 21
  of OpenGL Insights, 2012. The chapter's sample shaders read each vertex's attributes from
  buffer textures with `texelFetch` at `gl_VertexID`, directly or through an index read the
  same way.
- Taken: the idea. The vertex number names the face and its corner; the draw's base instance
  names the tile, as near terrain's names a cube. Faces are read from shader storage blocks,
  not buffer textures.
- Not taken: code.
- Added: 2026-11-15

### One far distance for the whole world pass
- Code: `src/main/java/cc/orthrus/quadrature/mixin/MixinEntityRendererDistant.java`,
  `DistantHook.java`
- Source: original. Facts used: the game's world pass, read from its public classes: the
  four methods that build a projection and the profiler section that follows the opaque
  terrain layers; the OpenGL specification's mapping from clip depth to window depth.
- Taken: nothing. Two projections with different far distances map one distance to two
  depths; a single far distance for every projection of the pass avoids it.
- Added: 2026-11-15

### One fog for near and distant terrain, the near box and the debug views
- Code: `src/main/java/cc/orthrus/quadrature/mixin/MixinEntityRendererFog.java`,
  `DistantHook.java` (`fog`), `DistantSettings.java`, `DistantProgram.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/lod/NearCover.java`
- Source: original. Facts used: the game's world pass, read from its public classes: where it
  sets its fog, with which distances, and which call is the sky's; the level at which the game
  draws a still water surface; the OpenGL compatibility specification's fog state.
- Taken: nothing. The fog formula is near terrain's own program's.
- Added: 2026-11-16

### Flat material colors and biome tints
- Code: `src/main/java/cc/orthrus/quadrature/distant/DistantMaterials.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/lod/MaterialTable.java`
- Source: original. Facts used: the game's public interfaces for a block's baked model, its
  quads' images and tint indices, its block color handlers and a biome's colors.
- Added: 2026-11-15

### Tile schedule, distant arena and timing
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/TileSchedule.java`,
  `TileCulling.java`, `BuiltTile.java`, and `src/main/java/cc/orthrus/quadrature/distant/`
  (`Distant.java`, `DistantWorkers.java`, `DistantArena.java`)
- Source: original. The arena is near terrain's (its record above) with other sizes. Facts
  used: Cubature's cube source and terrain preview interfaces; the OpenGL 4.6 compatibility
  specification (timestamp queries, sync objects, buffer storage).
- Added: 2026-11-15

### Writer audit
- Code: `src/main/java/cc/orthrus/quadrature/distant/WriterAudit.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification's state query
  names (indexed buffer bindings, texture unit bindings, blend, mask, test and stack state).
- Added: 2026-11-17

### The handoff seam and its module
- Code: `core/src/main/java/cc/orthrus/quadrature/core/handoff/Handoff.java`,
  `src/main/java/cc/orthrus/quadrature/frame/HandoffHost.java`, the hook in `FrameHost.endPass`,
  `quadrature-nvidia/build.gradle`, `quadrature-nvidia/NOTICE.md`
- Source: original. Facts used: Java's `ServiceLoader`; the class loader the game's mods load
  in (Foundation 0.22.2, read from its public classes: it defines `org.lwjgl` classes itself and
  lets a URL be added); Forge 1.12.2's `WorldEvent.Unload`; the game's `Framebuffer` fields.
  The module's Vulkan binding is LWJGL's own (`org.lwjgl:lwjgl-vulkan`, BSD 3-Clause), embedded
  in the module's JAR at the release platform.json names.
- Taken: nothing beyond the interfaces named.
- Added: 2026-11-18

### Choosing the Vulkan device by the OpenGL context's identity
- Code: `core/src/main/java/cc/orthrus/quadrature/core/handoff/DeviceIdentity.java`,
  `quadrature-nvidia/src/main/java/cc/orthrus/quadrature/nvidia/VulkanDevice.java`
- Source: Khronos, `EXT_external_objects` 4.2.1 and issue 20, and `EXT_external_objects_win32`
  (device and driver UUIDs, LUID, node mask); Vulkan specification, `VkPhysicalDeviceIDProperties`
  and the compatibility tables of `VkExternalMemoryHandleTypeFlagBits` and
  `VkExternalSemaphoreHandleTypeFlagBits`.
- Taken: that opaque Win32 handles import only where both UUIDs match, and that a valid LUID
  names the same adapter to both APIs on Windows.
- Added: 2026-11-18

### Images and semaphores shared between OpenGL and Vulkan
- Code: `quadrature-nvidia/src/main/java/cc/orthrus/quadrature/nvidia/SharedImage.java`,
  `SharedSemaphores.java`, `StandIn.java`, `Win32.java`, `Gl.java`, `Enumerations.java`, and the
  round trip in `VulkanHandoff.java`; `core/src/main/java/cc/orthrus/quadrature/core/handoff/SharedImageSizes.java`
- Source: Khronos, `EXT_external_objects` (memory objects, textures from memory, tiling, the
  dedicated parameter, semaphore signal and wait with layouts, issues 9, 13, 17 to 19),
  `EXT_external_objects_win32` (handle import, ownership), `NV_timeline_semaphore` (type,
  values, wait before signal); Vulkan specification (external memory and semaphore export,
  dedicated allocations, `VK_QUEUE_FAMILY_EXTERNAL` release and acquire, timeline semaphores);
  NVIDIA, DLSS Super Resolution Programming Guide 310.6.0, sections 3.3 and 3.4 (the inputs'
  and output's formats and usages).
- Taken: the import and synchronization rules as specified: every supported usage and the
  mutable-format bit on a shared image, dedicated memory flagged on both sides, NT handles
  closed after import, acquire and release through the external queue family in the layouts
  each side names, one timeline semaphore with rising values for both directions; DLSS's image
  usages for the stand-in's images.
- Not taken: any code from NVIDIA's or anyone's samples; none were opened.
- Added: 2026-11-18

### Measuring the handoff
- Code: `core/src/main/java/cc/orthrus/quadrature/core/handoff/HandoffSchedule.java`,
  `Samples.java`, `RenderSize.java`, `quadrature-nvidia/src/main/glsl/standin.comp`, and the
  timing, check and report in `VulkanHandoff.java`
- Source: original. Facts used: OpenGL 4.6 timestamp queries and `GL_TIMESTAMP`; Vulkan
  timestamp queries, `timestampPeriod` and `vkGetCalibratedTimestampsKHR` with the performance
  counter's time domain; Windows `QueryPerformanceCounter`, which Java's `nanoTime` counts on
  Windows. The stand-in is a bilinear upscale reading the three inputs DLSS reads.
- Taken: nothing beyond the interfaces named.
- Added: 2026-11-18

### DLSS Super Resolution through NGX and the shim
- Code: `quadrature-nvidia/src/main/native/ngx_shim.cpp`, the `ngxShim` task in
  `quadrature-nvidia/build.gradle`, `quadrature-nvidia/src/main/java/cc/orthrus/quadrature/nvidia/Ngx.java`,
  the DLSS batch in `StandIn.java`, the extension and loader hand-over in `VulkanDevice.java`,
  the options and timing in `VulkanHandoff.java`
- Source: NVIDIA, DLSS Super Resolution Programming Guide 310.6.0 (Vulkan initialization with a
  project identifier and engine type, the instance and device extension requirements asked
  before creation, capability parameters, optimal settings, feature creation and evaluation,
  render presets, feature flags, resource states and shutdown) and the declarations of the SDK
  310.9.16's headers the shim includes; Java's Foreign Function and Memory API (Java 25).
- Taken: NGX's interface as declared, called as the guide describes; nothing of the SDK is in
  the tree (D19): the shim is compiled against a copy the builder supplies, and what it builds
  is never committed or put in the JAR.
- Not taken: any code from NVIDIA's samples or anyone's integration; none were opened.
- Added: 2026-11-19
### DLSS taking the scaler's picture
- Code: `Handoff.Evaluation`, `evaluates` and `evaluate` in
  `core/src/main/java/cc/orthrus/quadrature/core/handoff/Handoff.java`, `SharedImageSizes.of` with a
  render size, `Jitter.phasesFor` in `core/src/main/java/cc/orthrus/quadrature/core/scale/Jitter.java`,
  the evaluation in `src/main/java/cc/orthrus/quadrature/frame/TemporalScaler.java` and
  `HandoffHost.java`, the scaled copies and mode choice in
  `quadrature-nvidia/src/main/java/cc/orthrus/quadrature/nvidia/VulkanHandoff.java`, `Ngx.qualityFor`,
  the offset and optional stamp in `StandIn.java`, and the render-size range in `ngx_shim.cpp`
- Source: NVIDIA, DLSS Super Resolution Programming Guide 310.6.0: 3.1.2 (LDR color), 3.2.2
  (the optimal settings' render-size range), 3.6.1 and 3.6.2 (motion vectors from the current
  pixel to its place in the previous frame, in render pixels from the top left, unjittered unless
  flagged), 3.7.1.1 (eight phases times the scale's square), 3.7.3 (the offset in render pixels,
  -0.5 to 0.5, in the motion vectors' axes) and 3.8.1 (inverted depth); the OpenGL 4.6 and Vulkan
  specifications for which rows each API counts first.
- Taken: those conventions, which the scaler's motion target and offsets already met; the
  phase rule; the quality modes' ratios.
- Not taken: any code from NVIDIA's samples or anyone's integration; none were opened.
- Added: 2026-11-19
### Side-aware rebuilds: a touching cube's change rebuilds only what read it
- Code: `src/main/java/cc/orthrus/quadrature/terrain/SideReads.java`, the reads kept in `BuiltCube` and
  `CubeMesher`, the following of touching changes in `Terrain.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/terrain/TouchingSides.java`
- Source: original. Facts used: Cubature's cube source contract (`ICubeSourceListener`'s change kinds and
  bounds, and its rule, in CUBE-SOURCE.md section 3, that a change in a cube's outermost layer or its arrival
  or removal raises a change for each loaded cube touching it); that the block renderer lights a block only
  for a face that passes the face test, as Quadrature's own `QuadWalk` does in following it.
- Taken: the touching rule as the contract states it.
- Added: 2026-11-19

### The game's chunk rebuild without the opaque layers own terrain draws
- Code: `src/main/java/cc/orthrus/quadrature/mixin/MixinRenderChunkLayers.java`,
  `src/main/java/cc/orthrus/quadrature/terrain/GameChunkRebuilds.java`, the attached world in `Terrain.java`
- Source: original. Facts used: the game's `RenderChunk.rebuildChunk` as Forge and Cleanroom 0.7.14 patch it
  (a loop over every layer that asks the block's `canRenderInLayer` before beginning, rendering and uploading a
  layer; the visibility graph and the block entity lists found before that test), read from the decompiled
  sources and Cleanroom's patch; the readers of a compiled chunk's layers in `RenderGlobal` (the layer draw
  own terrain replaces, the translucency resort, and the debug screen's count); MixinExtras' `WrapOperation`
  and `Share`, as documented by MixinExtras.
- Taken: nothing beyond the facts. No code from another renderer.
- Added: 2026-11-19

### The walk as the default mesher for model blocks
- Code: `src/main/java/cc/orthrus/quadrature/terrain/CubeMesher.java`, `QuadWalk.java`
- Source: original: the walk of "The walk over a model's quads", used for plain vertex meshes as well, noting
  nothing for face records. Its vertices were shown the same as the game's dispatcher's by the face check.
- Added: 2026-11-19

### The light matrix from a table of the cube
- Code: `src/main/java/cc/orthrus/quadrature/terrain/TableLighter.java`
- Source: original. Facts used: Forge's smooth lighter's interface (`VertexLighterSmoothAo`, the matrix its
  `BlockInfo` exposes to it, which it reads per vertex and which is not reproduced); the rule by which
  `BlockInfo.updateLightMatrix` fills that matrix from the 27 cells around a block, read from Forge's source
  for Cleanroom 0.6.13 and checked against Cleanroom 0.7.14's class file: the four questions asked of each cell
  (opacity below 15, packed light, ambient occlusion value), the raise beyond a side the block does not cover,
  and, per corner and axis, the mean of a face cell, two edge cells and a corner cell with dark opaque cells
  filled in a fixed order. Written in Quadrature's own terms and structure: each cell asked once per cube
  through the world the walk reads, the rule as one function of four cells.
- Taken: the rule, as behavior, so that the matrix is the same to the bit; checked offline against Forge's own
  lighter over 491,520 blocks of random worlds and in the game by the face check over 92,566 cubes
  (VERIFICATION, the light matrix from a table of the cube). No code from another renderer.
- Since 2026-11-20 the same class lights a quad straight into the buffer (`putQuad`). Facts used: how Forge's
  pipeline reads an item-format quad and writes a block-format vertex, read from Forge's source for Cleanroom
  0.6.13 (`LightUtil.putBakedQuad`, `unpack` and `pack`, `QuadGatheringTransformer`, `VertexLighterFlat.processQuad`
  and `VertexBufferConsumer.put`): each element's conversion and rounding, the order of tint, occlusion and diffuse
  shading, and the buffer's `addVertexData` and `putPosition`. The per-vertex light and occlusion are Forge's own
  methods, called and not reproduced. Checked offline against Forge's pipeline over 60,000 random quads in random
  worlds, every quad written directly the same byte for byte, and with a rounding factor or a factor of the
  occlusion changed slightly, 116 and 431 quads differ.
- Added: 2026-11-19

### Translucent terrain meshed, sorted and drawn by Quadrature
- Code: `src/main/java/cc/orthrus/quadrature/terrain/TranslucentCube.java`, the translucent builder in
  `CubeMesher.java`, the translucent store and draw in `Terrain.java`, `GameChunkRebuilds.leftOut`, the
  translucent case in `MixinRenderGlobal`; `core/src/main/java/cc/orthrus/quadrature/core/terrain/QuadOrder.java`
- Source: original. Facts used, read from the game's decompiled sources as Forge and Cleanroom 0.7.14 patch them:
  that `RenderChunk.rebuildChunk` meshes a block's translucent layer through `BlockRendererDispatcher.renderBlock`
  with the layer set by `ForgeHooksClient.setRenderLayer`, as for the other layers; that `BufferBuilder.sortVertexData`
  orders a chunk's quads by the squared distance from the eye to the mean of each quad's four corners, the farthest
  first, by a stable sort; that `RenderGlobal.renderBlockLayer` draws the translucent layer's render chunks in the
  reverse of its visible list, nearest first, and has the 15 nearest render chunks holding translucent quads sorted
  again once the player has moved more than a block; and the state `EntityRenderer` sets before that draw, which
  is kept as the game set it.
- Taken: the order, as behavior, so that blending composes the same. Where the game's chunk workers sort from the
  viewer's position at the last tick, in single precision, Quadrature sorts from the camera the frame is drawn from;
  standing still the two differ only by rounding. The per-cube buffer, sorting each cube by
  its own distance moved rather than once for all, and the sort itself (a bottom-up merge sort checked against the
  library's stable sort over 200 random cubes with many equal distances) are Quadrature's own. No code from another
  renderer.
- Added: 2026-11-20

### The game's chunk system taken over in cubic worlds
- Code: `src/main/java/cc/orthrus/quadrature/terrain/GameChunks.java`, `EntityPass.java`, `EntityPassHost.java`,
  `CubicWorlds.java`, `AddedLayerCube.java`, the added layers in `CubeMesher.java`, `BuiltCube.java` and
  `Terrain.java`; `mixin/MixinRenderGlobalChunks.java`, `mixin/MixinViewFrustumChunks.java`, the added-layer case in
  `mixin/MixinRenderGlobal.java`; the reference-build check in `Quadrature.java`
- Source: original. Facts used, read from the game's decompiled sources as Forge and Cleanroom patch them: that
  `RenderGlobal.setupTerrain` positions, searches and queues the render chunks and fills the list `renderEntities` and
  `renderBlockLayer` read; that `renderEntities` finds entities through each listed render chunk's section and block
  entities through its compiled chunk, under the tests kept here (the render pass, `RenderManager.shouldRender` or
  riding the player, the view entity in first person, the old height limits with `isBlockLoaded`, the frustum test of
  a block entity's render box), and draws global block entities from a set its rebuild fills through
  `updateTileEntities`; that `ChunkRenderDispatcher` makes ten rebuild buffer sets a core up to a share of the heap;
  that `ViewFrustum.setCountChunksXYZ` sizes the grid before its render chunks are made; and that a layer a mod adds
  to `BlockRenderLayer` is meshed by the rebuild like any other and drawn through `renderBlockLayer`. Cubature's API
  (`ICube.getEntitySet`, `getTileEntityMap`) gives each cube's entities and block entities. GTCEu: Reloaded's bloom
  layer, read in its own sources, is the added layer met in the surveyed pack.
- Taken: the tests of the game's two loops and its set of global block entities, as behavior. The entity and
  block-entity passes over Quadrature's own view, the global set kept as cubes are placed and forgotten, and the added
  layers' meshing and draw are Quadrature's own. No code from another renderer.
- Added: 2026-11-21

### Entities' own motion for the temporal pass
- Code: `core/src/main/java/cc/orthrus/quadrature/core/scale/EntityMotion.java`,
  `src/main/java/cc/orthrus/quadrature/frame/EntityMotions.java`, the entity path of `MOTION_COMPUTE` in `ScalerShaders.java`
- Source: original, as proposed in research R4, 7b.5. Facts used: how the game places an entity it draws (its last two
  tick positions interpolated by the partial tick, a living entity's body yaw interpolated the short way, turned by
  180 minus the yaw about the vertical axis, read from the game's `RenderLivingBase` and `RenderManager`), and its
  bounding box.
- Patent look: R4, 7b.5 (2026-11-20): the nearest family claims motion estimation over candidate vector sets; no claim
  found reads on it.
- Taken: nothing beyond the facts. No code from another renderer.
- Added: 2026-11-20

### Layers kept per state
- Code: `src/main/java/cc/orthrus/quadrature/terrain/CubeMesher.java` (`layers`), the reload listener in `TerrainHook.start`
- Source: original. Facts used: Forge's `Block.canRenderInLayer(IBlockState, BlockRenderLayer)`, answered per state, and
  the game's `IReloadableResourceManager.registerReloadListener`; that GTCEu Reloaded's handler of the call reads the
  mod list and the block's model each call, from the cross-mod diagnostics' samples.
- Taken: nothing beyond the facts. Checked in game by asking every kept answer again (VERIFICATION, layers kept per state).
- Added: 2026-11-20

### Rays walked through the occupancy bricks, cell by cell and node by node
- Code: `core/src/main/java/cc/orthrus/quadrature/core/scene/BrickTrace.java`, `TraceFormat.java`;
  `src/main/java/cc/orthrus/quadrature/rays/TraceProgram.java`, `RayTracer.java`, `RayTrace.java`,
  `ComputeState.java`
- Source: the walk through a uniform grid that crosses, each step, the nearest of the next cell
  boundaries along the three axes, from Amanatides and Woo, "A Fast Voxel Traversal Algorithm for Ray
  Tracing", Eurographics 1987 (https://www.eecs.yorku.ca/~amana/research/grid.pdf). Passing empty
  space a whole node at a time in a hierarchy of grids, from Museth, "Hierarchical Digital
  Differential Analyzer for Efficient Ray-Marching in OpenVDB", SIGGRAPH 2014 talks, and from the
  two-level grids of Kalojanov, Billeter and Slusallek, Eurographics 2011. OpenGL 4.6 specification:
  compute shaders, shader storage blocks, buffer textures, `precise`, timestamp queries.
- Taken: the step to the nearest boundary, and skipping a node known to be empty. The nodes are the
  scene's own: a cube of one state, named so by its lookup entry, and a cell.
- Not taken: skipping a block of two cells a side by the bricks' coarser bits. It was built first and
  set aside on 2026-11-19 by the user's decision, after a patent search found a family in force that
  claims rays looked up in occupancy bits at several levels of detail (research R4, 7.8a); the walk
  now reads only the lookup's entries and the cell bits.
- Not taken: the incremental form of the walk, which adds each axis's step to a running distance.
  Here each distance is worked out afresh from the origin and a whole-numbered boundary, and the cell
  on the other axes after a node is passed is found by the same crossing test a walk of single cells
  makes, x before y before z on equal distances, so that the walk by nodes ends exactly where the walk
  by cells does and the GPU's results can be compared with the CPU's to the bit. That form, the
  ordering rule and the comparison are original. No code from OpenVDB, NanoVDB or any renderer was read.
- Added: 2026-11-18

### The ray benchmark and its check
- Code: `src/main/java/cc/orthrus/quadrature/rays/RayBench.java`, `BenchPrograms.java`, `RayCheck.java`;
  `src/main/java/cc/orthrus/quadrature/platform/Staging.java`, which the light scene's check in
  `SceneBuffers` reads through too; `DriverMessages.moves`, `TerrainHook.driverMoves`
- Source: original in its classes and schedule. Ideas used: probes about the camera casting rays
  in directions spread over the sphere, as in Majercik, Guertin, Nowrouzezahrai, McGuire, "Dynamic
  Diffuse Global Illumination with Ray-Traced Irradiance Fields", JCGT 8(2), 2019; that coherent and
  incoherent rays differ in throughput on NVIDIA hardware through divergence within a warp and
  scattered memory access, from Aila and Laine, "Understanding the Efficiency of Ray Traversal on
  GPUs", HPG 2009, and its Kepler and Fermi addendum, NVIDIA NVR-2012-02. Directions are spread by
  the single-round permuted congruential hash evaluated in Jarzynski and Olano, "Hash Functions for
  GPU Rendering", JCGT 9(3), 2020, after O'Neill's PCG (2014); its published constants are used.
- Taken: the shape of the work measured: probe grids, rays a probe, and rays toward the sun from
  surfaces.
- Original: timing in interleaved rounds after an untimed warm-up; timing each batch from the
  scene's buffers and from a copy made on the GPU; reading back only staging buffers filled by GPU
  copies; counting the driver's reported buffer moves per set. Facts used: the OpenGL 4.6
  specification (`glCopyNamedBufferSubData`, `GL_CLIENT_STORAGE_BIT`, timestamp queries, sync
  objects) and the driver's own debug messages.
- Not taken: any probe update, irradiance storage or shading; only the trace is measured.

### Tier benchmark: bandwidth kernel and timed probes
- Code: `src/main/java/cc/orthrus/quadrature/platform/TierBench.java`, `BandwidthKernel.java`,
  `TraceProbe.java`; `TerrainHook.java` (`Probes`, `afterOpaque`, `sceneCopyWaiting`), `Terrain.java`
  (`probes`) and `SceneBuffers.java` (`waiting`);
  `core/src/main/java/cc/orthrus/quadrature/core/tier/BenchmarkRun.java`, `BenchmarkResult.java`,
  `TerrainFit.java`, `ClockSettle.java`, `TerrainSettle.java`, `Samples.java`, `TierBenchmark.java`
- Source: original. Facts used: the OpenGL 4.6 compatibility specification (timestamp, primitives
  generated and samples passed queries, their availability, the one time-elapsed query a target
  may have open, compute dispatch, storage buffers, framebuffer clears, the fixed-function alpha
  test after a program); `GL_EXT_memory_object` (the device and driver identifiers); the game's
  public classes (the block texture's mipmap switch and the alpha state around its opaque layers).
- Taken: nothing. The probes issue the frame's own commands again; the kernel reads a buffer once
  a dispatch, threads a dispatch's width apart; a register-bound load runs until its own times
  agree, so the clocks have settled without any clock being asked for; a probe waits for the view's
  search to meet no cube that is not loaded, or for that count to stand still, and for the light
  scene's copy on the GPU to have nothing left to take or copy; a probe's time is
  set against a model of the reference card fitted over many places, by primitives and pixels.
- Added: 2026-11-18

### Tier selection: predicted cost, a band at each boundary, memory and overrides
- Code: `core/src/main/java/cc/orthrus/quadrature/core/tier/TierSelection.java`,
  `TierCalibration.java`, `TierCalibrationTable.java`, `TierChoice.java`, `StoredTiers.java`,
  `Tier.java`, `TierGroup.java`
- Source: original, from D22. A band around each boundary is ordinary hysteresis, as in a
  Schmitt trigger.
- Taken: nothing. A tier's cost on a machine is the reference card's, group by group, over the
  ratio of the probe standing for the group; the band is a share of the budget, and the last tier
  on the same GPU, kept by digest, is the one the band holds to; a run whose trace stands past a
  factor of two from its other probes chooses nothing.
- Added: 2026-11-18

### Memory tier lowered at run time by evictions and 1% lows
- Code: `core/src/main/java/cc/orthrus/quadrature/core/tier/EvictionWatch.java`, `TierBench.java`
  (`watchFrame`)
- Source: original, from D22. Facts used: NVIDIA, `GL_NVX_gpu_memory_info` (the eviction count
  since the system started).
- Taken: nothing.
- Added: 2026-11-18

### Rendering below output resolution: the drawn framebuffer, the offset and motion from depth
- Code: `src/main/java/cc/orthrus/quadrature/frame/TemporalScaler.java` (the drawn framebuffer
  standing in for the game's, the view, the composite's order and state), `ScalerShaders.java`
  (`MOTION_COMPUTE`), `src/main/java/cc/orthrus/quadrature/mixin/MixinEntityRendererScale.java`,
  `MinecraftScaleAccess.java`, `MixinFramebufferScale.java`, `MixinRenderGlobalScale.java`,
  `RenderGlobalScaleAccess.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/scale/`
  (`RenderScale`, `Jitter`, `Reprojection`); hooks in `FrameHost` and `BaselineGraph`
- Source: original in its placement. Ideas used: a sub-pixel offset of the projection each frame
  from a low-discrepancy sequence, and motion for what stands still from depth and the previous
  camera, as published for temporal anti-aliasing (Karis, "High Quality Temporal Supersampling",
  SIGGRAPH 2014; Pedersen, "Temporal Reprojection Anti-Aliasing in INSIDE", GDC 2016); the
  Halton sequence (Halton, 1960). Facts used: the game's world pass read from its public classes
  (where the viewport is set, which calls build each world projection, where Forge's last world
  drawing runs, where the game's framebuffer and display size are read) and the lab's capture in
  that event; what GTCEu Reloaded's bloom does inside the pass, from its classes' calls; the
  OpenGL 4.6 specification (clip-space
  translation, `glCopyImageSubData` between a renderbuffer and a texture of one depth format,
  nearest depth blits, `glMatrixLoadfEXT` from `EXT_direct_state_access`).
- Taken: that the offset belongs on the left of the perspective, and that a point's depth and
  two cameras give its motion.
- Not taken: any engine's code or constants; motion for moving hosted draws, which D21 still
  needs.
- Added: 2026-11-18

### Temporal pass
- Code: `src/main/java/cc/orthrus/quadrature/frame/ScalerShaders.java` (`TEMPORAL_COMPUTE`),
  `TemporalScaler.composite`
- Source: Salvi, "An Excursion in Temporal Supersampling", GDC 2016 (an exponential moving
  average of resolved color with about a tenth of the current frame; clipping the history
  toward the current neighborhood's box; bicubic reads of the history);
  Karis, SIGGRAPH 2014 as above (YCoCg, the motion of the nearest depth in the neighborhood,
  filtering the current frame by each sample's distance after its offset, luma weighting
  against flicker, a raised current weight where motion is unreliable); Malan, 2012, as Salvi
  credits him for neighborhood clipping; Catmull and Rom, "A class of local interpolating
  splines", 1974, for the history spline.
- Taken: the structure of accumulation and rectification and the ideas above, written anew. The
  nine-read spline is derived here from the cubic's weights; the Gaussian of half a pixel, the
  motion term, the depth-change test for a raised weight and every constant are Quadrature's.
- Not taken: variance clipping, the box from the neighborhood's mean and deviation, which
  NVIDIA patents describe (D23; research R4, 7b.1).
- Added: 2026-11-18

### Spatial scaler
- Code: `src/main/java/cc/orthrus/quadrature/frame/ScalerShaders.java` (`SCALE_COMPUTE` and
  `COPY_COMPUTE`)
- Source: Keys, "Cubic convolution interpolation for digital image processing", IEEE
  Transactions on Acoustics, Speech and Signal Processing 29(6), 1981 (the kernel, a = -1/2).
- Taken: Keys' kernel as published. The hold to the central texels' range and the sharpening
  boost are Quadrature's.
- Not taken: a kernel steered along the edge the structure tensor shows. It was built and
  measured against this one at 67% and 77%, where it scored no better and its pass cost more,
  and the user declined it on 2026-11-19 with US 7,889,950 in view (D23; research R4, 7b.2).
  Nor steering kernel regression or any part of its procedure; any code or constant of NVIDIA
  Image Scaling or AMD FidelityFX Super Resolution, neither of which was opened. NVIDIA Image
  Scaling is a comparison reference only (D23).
- Added: 2026-11-18

### Picture quality measures
- Code: Workbench's `frames.quality`, with its frames module's `ssim`, `psnr`, `edge_detail` and
  `shift`, ported from this project
- Source: Wang, Bovik, Sheikh and Simoncelli, "Image quality assessment: from error visibility to
  structural similarity", IEEE Transactions on Image Processing 13(4), 2004 (the index, its
  Gaussian window of deviation 1.5 and its constants for 8-bit values); Guizar-Sicairos, Thurman
  and Fienup, "Efficient subpixel image registration algorithms", Optics Letters 33(2), 2008 (a
  correlation evaluated near its peak on a finer grid by a matrix DFT).
- Taken: the index's formula and constants; the refinement as published. The edge-detail ratio,
  the cross-correlation of luma gradients under a Hann window and the grid's extent are
  Quadrature's.
- Added: 2026-11-18

### Depth pyramid, a cube's test against it, and culling in two phases
- Code: `core/src/main/java/cc/orthrus/quadrature/core/terrain/DepthPyramid.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/terrain/DrawCommands.java` (tags, runs and starts),
  `src/main/java/cc/orthrus/quadrature/terrain/OcclusionMeasure.java`,
  `src/main/java/cc/orthrus/quadrature/terrain/GpuCulling.java`,
  `src/main/java/cc/orthrus/quadrature/terrain/TerrainProgram.java` (`drawIndirectCount`, `drawFacesCount`)
- Source: Greene, Kass and Miller, "Hierarchical Z-Buffer Visibility", SIGGRAPH 1993 (a pyramid
  of depth, each level the farthest of the level below, and a box rejected where its nearest
  depth lies beyond the farthest under its screen extent); Haar and Aaltonen, "GPU-Driven
  Rendering Pipelines", SIGGRAPH 2015 (the test made in compute, a level chosen so a box
  covers a few texels; two phases, the objects visible the frame before drawn first, the
  pyramid built from them, and the rest tested against it). Facts used: the OpenGL 4.6
  compatibility specification (the sizes of a texture's levels, image load and store,
  `glCopyImageSubData` between a depth renderbuffer and a texture, `glBlitFramebuffer` of depth
  alone, memory barriers, timestamp queries, compute shared memory, `glMultiDrawArraysIndirectCount`
  and the parameter buffer); Vigna's splitmix64 finalizer (the hash that orders conditions frame by
  frame in a comparison).
- Taken: the ideas. The levels sized as OpenGL sizes a texture's, odd rows and columns folded
  into the last texel so a texel holds the farthest of exactly the pixels under it, the level
  chosen as the finest where a rectangle lies under two texels each way, and a box at the
  eye's plane kept untested are Quadrature's. So are the pyramid built from half resolution
  four levels a dispatch, with the texels at the last row or column reading their whole
  footprint so that every level equals the reference; the commands the client thread wrote
  sorted by compute into runs of their own; a bit per cube found by a tag its record carries;
  and phase 2 drawn at the last opaque layer under each earlier layer's state.
- Not taken: any culling or renderer source, and any downsampler's source.
- Added: 2026-11-20

### The render interface's provider: declared layers, scopes and the emissive stage
- Code: `src/main/java/cc/orthrus/quadrature/modinterface/ModInterface.java`, `ModInterfaces.java`;
  `src/orthlib/java/cc/orthrus/quadrature/orthlib/OrthLibBridge.java`, `InterfaceSelfTest.java` (a diagnostic consumer of
  the interface's public calls); the declared layers in `CubeMesher.java`,
  `BuiltCube.java` and `Terrain.java` (`followDeclarations`, `drawEmissive`, `drawContributions`); the emissive stage's
  points in `TerrainHook.java`; the registration in `Quadrature.java`
- Source: original, to OrthLib's render interface (its design document) and research R5, 10.2 (handles, capabilities
  stated as literals, answers held inside scopes on the provider's own thread). Facts used: that Forge's current layer is
  a per-thread value (`ForgeHooksClient.renderLayer`, read with `javap` in Cleanroom 0.7.14-alpha); the game's world pass
  as Forge and Cleanroom patch it, read from its decompiled sources: what it sets just before translucent terrain
  (blending on with source alpha over the rest and alpha kept from the source, depth writes off, the blocks atlas bound),
  and that `WorldVertexBufferUploader` resets the cache's color after a draw with colors; GTCEu: Reloaded's own bloom
  path at commit `8ca6b98c9a14531c4d49cb242ae28769766396c1`, read as behavior (its emissive layer and tickets drawn
  after entities at the translucent draw, the layer writing depth, its glow's source the picture's color where the
  emissive geometry covers it, and its fusion ring and cable heat left undrawn outside the view's frustum); OrthLib's
  mark for a world pass whose contributions the provider draws (`ProviderHandle.drawingContributions()`, its design
  document's amendment at commit `4c9e42a`).
- Taken: the point in the frame, the source of the glow's color and leaving what lies outside the view undrawn, as
  behavior. The snapshot's translation and its layer generation, the scopes opened around each build, the meshes kept a
  layer, the stage's state rule, the pass's mark, and the test of a contribution by the box around the vertices it
  wrote against the view's planes (`View.boxVisible`) are Quadrature's own. No code from another renderer or from GTCEu:
  Reloaded.
- Added: 2026-11-21

### Bloom
- Code: `core/src/main/java/cc/orthrus/quadrature/core/bloom/BloomChain.java`,
  `src/main/java/cc/orthrus/quadrature/terrain/BloomStage.java`, the emissive variant in `TerrainProgram.java`, the
  weight's resolve in `frame/ScalerShaders.java` (`TEMPORAL_COMPUTE`) and the glow's place in `frame/TemporalScaler.java`
- Source: Mittring, "The Technology Behind the Unreal Engine 4 Elemental Demo", SIGGRAPH 2012 (halvings blurred as
  they are made; the levels recombined from the coarsest, each blurred and added to the sum below). Jimenez, "Next
  Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014, and his replies on his site (a
  thirteen-read halving over 36 texels in five weighted groups of four, the inner one half and the others an eighth; a
  three-by-three tent while doubling; a weight on each group by brightness on the first halving; no threshold), its
  filters as Christensen's LearnOpenGL article of 2022 reports them. Karis, "Tone mapping", 2013 (the weight 1/(1 +
  luma)). James and O'Rorke, "Real-Time Glow", GPU Gems, 2004 (glow from marked sources). GTCEu: Reloaded's generated
  configuration documentation (the composite rule and its three groups' values), and its own bloom programs at
  8ca6b98c9 read as behavior (the background's brightness as HSL lightness, its levels weighed 0.2 to 1.0 from the
  finest, the strength applied twice). Research R4, 7c.
- Taken: the chain, its filters and the groups' weights; GTCEu: Reloaded's rule for the level weights (equal steps up
  to 1 at the coarsest), its brightness measure and its response to a source of one color (its level weights summed
  times the strength applied a second time, the calibration's reference), as behavior, no code. The two weights a kind
  becomes and the algebra that gives the composite rule from them, the weight target drawn beside the color, the
  products formed a texel at a time so that the first halving's bilinear reads filter them exactly, the weight
  resolved in the temporal history's alpha, the levels sized from the output, their number, and every constant are
  Quadrature's.
- Not taken: dual filtering, Kawase's passes, separable Gaussians per level, convolution by transform, a brightness
  threshold. Kept apart from the patents research R4, 7c.6 read: no previous frame's blur reused (US 12,086,957),
  every level made by its own dispatch (US 11,915,337), no level chosen against a kernel size (US 8,687,011), and no
  stencil data, the opaque and translucent scene drawn into one framebuffer with the glow laid over it there
  (US 11,100,701, whose claims draw the opaque parts through stencil data into one frame buffer and the transparent
  ones with its content into another). No code from another renderer.
- Added: 2026-11-21

## Design sources

Techniques chosen in `docs/ARCHITECTURE.md` but not yet written have their sources listed
there and in `docs/research/`. Each gets a record above when its code is added.
