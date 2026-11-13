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

## Design sources

Techniques chosen in `docs/ARCHITECTURE.md` but not yet written have their sources listed
there and in `docs/research/`. Each gets a record above when its code is added.
