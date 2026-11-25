# Techniques and their entry points

A reference for using or extending each technique Quadrature has built: how it is switched on, what it reads and
writes, which classes and methods are its entry points, its threads and lifetime, what it has been measured to
cost, and where its design and provenance are recorded. The design itself is in [ARCHITECTURE.md](ARCHITECTURE.md),
distant terrain's in [DISTANT.md](DISTANT.md), the runs behind every figure in [VERIFICATION.md](VERIFICATION.md),
each technique's sources in [PROVENANCE.md](PROVENANCE.md) and the standing decisions in
[DECISIONS.md](DECISIONS.md). Paths are relative to the repository's root; `core` is
`core/src/main/java/cc/orthrus/quadrature/core`, the host is `src/main/java/cc/orthrus/quadrature`.

**Switches** are JVM system properties, `-Dquadrature.<name>=<value>`. Each is read once, into a static field when
its class loads at startup, so a change takes a restart. A switch read with `Boolean.getBoolean` is on only for
`true`. A switch that is unset costs nothing. A cost given as "not measured" has no figure in the docs.

**Conventions that hold throughout.**

- *Reversed depth* (D17), on unless `-Dquadrature.reversedDepth=false`: the near plane at depth 1, the far plane at
  0, clip depth from 0 to 1 (`glClipControl`), a 32-bit floating-point depth buffer. A depth target cleared for
  nothing holds 0; a larger value is nearer; "nearer or equal" is `GL_GEQUAL`. Hosted code keeps conventional depth
  calls, which a class transformer points at a translator (`asm/DepthCallRewriter.java`, `depth/DepthCalls.java`,
  `core/depth/ReversedDepth.java`); Quadrature's own code is written for reversed depth directly. The frame block's
  inverse view-projection takes the stored depth as clip z (`core/stage/FrameBlock.java`). The light's depth is the
  exception: it runs from 0 nearest the light to 1 ([ARCHITECTURE.md](ARCHITECTURE.md) section 4.5).
- *The shading targets*, behind `-Dquadrature.gbuffer=true`: four targets of 8 bits a channel and a reversed depth
  of Quadrature's own, the size of the window. A holds the world normal and perceptual roughness; B sky light, the
  emission strength and block light, with a response class in alpha; C the square root of linear albedo and a
  conductor flag; D reflectance and subsurface (a metal's colored reflectance) and occlusion
  ([ARCHITECTURE.md](ARCHITECTURE.md) section 6.6; the full encoding is in section 2 below).
- *The client thread owns the graphics context.* Every GL call, every published GL object and every `TerrainHook`
  read that names a GL object is for the client thread, inside a frame.
- *State envelope.* Entering a Quadrature stage assumes nothing of GL state; leaving it puts back what the game's
  cache believes, through the cache (`frame/GlStateMirror.java`, `mixin/MixinGlStateManager.java`), and neutral
  values for the rest, without reading state back from the driver ([ARCHITECTURE.md](ARCHITECTURE.md) section 4.4).
- *Threads.* Quadrature starts no threads of its own for terrain work: near terrain's builds run on Cubature's
  build pool, distant tiles on Cubature's client preview pool. The one exception is distant terrain's fallback
  pool, used only where the cube source offers no preview pool (section 9).

## 1. Near terrain

**What it is.** Cubes from Cubature's cube source, meshed on Cubature's build pool into face records and kept
vertices, held in an arena of GPU pages, found visible by a search over per-cube face links, and drawn by indirect
multi-draw per layer and page. In cubic worlds it takes over the game's chunk system (D24) and draws translucent
terrain, entities and block entities for the cubes it sees.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.terrain` | `own` | `game` leaves terrain to the game; own terrain then draws nothing |
| `quadrature.faces` | on | `false` draws the walk's vertices alone, with no face records |
| `quadrature.terrain.cull` | `off` | `gpu` culls on the GPU in two phases against a depth pyramid; `alternate` chooses frame by frame for a comparison. Reversed depth only |
| `quadrature.terrain.cull.depth` | `blit` | How the frame's depth reaches the pyramid: `copy`, `blit` or `alternate` |
| `quadrature.terrain.occlusion` | unset | `measure` builds the pyramid and counts what it would hide, leaving nothing out |
| `quadrature.terrain.translucent` | own | `game` leaves the translucent layer to the game's meshing and draw |
| `quadrature.terrain.translucentSorts` | 15 | Translucent cubes sorted again a frame at most |
| `quadrature.terrain.gameChunks` | unset | `keep` keeps the game's chunk system running beside own terrain; needs Cubature's reference build |
| `quadrature.terrain.neighbors` | unset | `all` rebuilds a cube on every change of a touching cube, not only where its last build read it |
| `quadrature.terrain.fade` | `false` | Near terrain gives its pixels up across a band before its edge (`TerrainHook.fadeBand`) |
| `quadrature.coverage` | `false` | Works out the coverage map of chunk columns (`TerrainHook.coverage()`) |

The diagnostics of own terrain (`quadrature.terrain.compare`, `.visibility`, `.draw`, `.pages`, `.storage` and the
rest) are listed in [VERIFICATION.md](VERIFICATION.md), Diagnostics.

**Entry points.**

- `terrain/TerrainHook.java`, the published face of near terrain: `requested()`, `active()`, `start()`,
  `mainView()`, `frame()`, `seen()`, `coverage()`, `searchEdge()`, `fadeBand(float, float)`, `fadeBandInUse()`,
  `fadeOrigin()`, `summary()`.
- `terrain/Terrain.java`: the drawer behind `TerrainHook` (builds, placement, the light's depth, the draws).
- Package-private in `terrain/`: `CubeMesher`, `QuadWalk` and `FaceBuilder` (meshing, the walk over a model's quads,
  face records), `TerrainArena` (pages), `TerrainProgram` (the programs), `GpuCulling`, `TranslucentCube`,
  `AddedLayerCube`. Public: `GameChunks` and `EntityPass` (the chunk system taken over).
- `core/view/View.java` (a view: origin, matrices, volume, reach, purpose); `core/terrain/`: `Visibility` and
  `CubeConnectivity` (the search), `ArenaAllocator`, `CubeSlots`, `DrawCommands`, `FaceRecord`, `QuadOrder`,
  `TouchingSides`, `CoverageMap`, `SearchEdge`, `DepthPyramid`; `core/fade/`: `FadePattern`, `NearFade`.

**Inputs and outputs.**

- *In:* snapshots of a cube and its neighbors from Cubature's cube source, safe to read on any thread
  ([CUBE-SOURCE.md](CUBE-SOURCE.md)). A cube is meshed only once its six neighbors are loaded.
- *Held:* pages of 64 MiB, immutable buffers written through the driver and never mapped, up to 24; a mesh takes
  one block of a page, its three opaque layers one after another. Face records are 32 bytes, drawn with no vertex
  attributes from pages of their own. Every loaded cube holds a slot naming its block and each layer's range.
- *Each frame:* a record per visible cube with its model-view matrix in one shader storage buffer; a draw names its
  record by base instance; one indirect multi-draw per layer and page.
- *Published:* `mainView()` gives the projection and model-view read from the driver when the frame's first opaque
  layer began, column-major, relative to the viewer's place in double precision; one instance refilled each frame,
  to be read and not kept. `frame()` counts the world passes own terrain has begun. `seen()` lists the cubes the
  last search saw, nearest first. `coverage()` and `searchEdge()` say what lies at the edge of the box.

**Threads and lifetime.** Builds run on Cubature's build pool, prioritized by squared distance and reprioritized when
the camera changes cube or turns 30 degrees; a mesher belongs to one build thread at a time. The client thread
places finished builds into pages within 1 ms a frame and sorts changed cubes within 0.5 ms, both budgets
([ARCHITECTURE.md](ARCHITECTURE.md) section 6.2). A freed block is reused only after a fence placed after the
frame's last terrain draw. Pages are kept for the session; none is given back when use falls.

**Costs measured.**

- Opaque terrain drawn forward: 0.96, 1.81, 2.23 and 2.32 ms of GPU time at radii 8, 12, 16 and 20, at 1280 by 720
  on an RTX 3060 ([ARCHITECTURE.md](ARCHITECTURE.md) section 12.1).
- Commands written on the client thread: 0.11 ms a frame; 35 draws a settled frame on average, where one call a
  cube and layer made 938 (section 6.4).
- Meshing a cube: 1.11 ms, to which noting the light scene's cells adds 0.01 ms (section 5.1). The first mesh placed
  in a run takes 1.6 to 3.5 ms; later placements stay within the budget (section 6.3).
- Memory over the forest tour: 10 pages, 640 MiB (section 6.3). Records take 165 MiB and kept vertices 44 MiB where
  the game's vertices alone take 592 MiB (section 6.4).
- Side-aware rebuilds: rebuilds that drew what they replaced fell by three quarters (section 6.2).
- Culling on the GPU: about 0.3 ms more than the drawing it saves, at 2560 by 1440 and radius 16 (section 6.4), so
  it is off by default.
- Translucent sorting: 0.054 ms a frame on average ([VERIFICATION.md](VERIFICATION.md), Translucent terrain).
- The coverage map and the near fade: not measured.

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) sections 4.7 (views), 6.1 to 6.5 and 6.7; D14, D24.
[PROVENANCE.md](PROVENANCE.md): "Near terrain, first form", "Meshes built on the cube source's build pool",
"Visibility from face connectivity", "Terrain arena and indirect drawing", "Free blocks split and joined", "The walk
over a model's quads, and face records", "Side-aware rebuilds", "Translucent terrain meshed, sorted and drawn by
Quadrature", "The game's chunk system taken over in cubic worlds", "Depth pyramid, a cube's test against it, and
culling in two phases", "Coverage of chunk columns and the search's edge", "The fade at the edge of near terrain".

## 2. The shading targets and the target writer contract

**What it is.** Each opaque layer of near terrain drawn a second time, from the commands and records the player's
view already made, into four targets and a depth of Quadrature's own, with no lit color; a material table of eight
bytes a block state; and a contract through which other passes write the same targets before a stage reads them.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.gbuffer` | `false` | Draws the targets. Needs face records (on by default) and reversed depth |
| `quadrature.gbuffer.image` | `false` | Writes the targets as images into the client's `quadrature-gbuffer/` where the player stands still |
| `quadrature.gbuffer.audit` | `false` | Counts the occlusion the targets are given, by the way a face is turned and by what stands in front of it |
| `quadrature.materials.audit` | `false` | Logs where the list of kinds and the rule for unnamed blocks differ |
| `quadrature.stage.dir` | unset | A stage folder whose `material_kinds.properties` replaces the list of kinds the build ships (section 3) |

**Entry points.**

- `terrain/TerrainHook.java`: `shadingTargets()`, `materials()`, `material(IBlockState)`, `materialsBuffer()`,
  `frame()`, `mainView()`, `addTargetWriter(Runnable)`, `removeTargetWriter(Runnable)`, `targetsEdge(float)`,
  `targetsFarEdge()`.
- `core/view/ShadingTargets.java`: `framebuffer()`, `texture(int)`, `depthTexture()`, `width()`, `height()`,
  `generation()`, `drawnFrame()`.
- `core/material/MaterialTable.java` (eight bytes a state: `roughness`, `reflectance`, `subsurface`, `emission`,
  `conductor`, `foliage`, `kind`, `version`), `MaterialKinds.java` (the list), `KindEmission.java` (the emission
  rule, `of`, `stored`, and its GLSL twin `GLSL`).
- `core/frame/TargetWriters.java` (the writers' list), `core/view/ShadedGate.java` (`draws(ShadingTargets, long)`:
  whether the targets were drawn in this pass), `core/view/StateGuard.java` (state read before a writer and written
  back after), `core/view/NearEdge.java`.
- Package-private: `terrain/GBufferTarget.java`, the targets' stages in `terrain/TerrainProgram.java`,
  `terrain/Materials.java`.

**Inputs and outputs.**

| Target | Color | Alpha |
|---|---|---|
| A | World normal as n * 0.5 + 0.5 | Perceptual roughness |
| B | Red sky light and blue block light, each 0 to 1 over the game's 0 to 15; green the emission strength | 0.375 on terrain, 0 where nothing was written |
| C | The square root of linear albedo after tint, decoded by the exact sRGB curve | 1 for a conductor |
| D | Reflectance and subsurface; for a metal its colored reflectance, the texel's linear albedo after tint (D18) | The game's vertex occlusion |

- Depth: reversed, 32-bit float, 1 nearest and 0 farthest or unwritten. A cutout fragment is dropped at or under
  an alpha of 0.5. Only terrain is written: entities and what mods draw are not in the targets.
- The material table is indexed by the game's 16-bit number for a state (block identity and variant), the number
  the light scene uses. `materialsBuffer()` is the storage buffer it lies in, brought up to the table's version when
  asked: ask after the last row is filled and before drawing.
- Emission in B's green (`KindEmission`), with m the greatest channel of the linear albedo: lava (kind 101) 0.10;
  a dim emitter (120) 0.65 * smoothstep(0.20, 0.70, m); a bright emitter (121) 0.90 * smoothstep(0.12, 0.65, m);
  every other kind 0. Stored to the nearest of 255.
- `generation()` moves only when the textures are made anew; `drawnFrame()` equals `TerrainHook.frame()` in a pass
  whose targets were drawn. A reader that must know the targets are this pass's compares those two
  (`ShadedGate.draws`).

**The writer contract.** `addTargetWriter(Runnable)` adds a pass that writes the targets besides near terrain:

- It is called on the client thread, in the order added, once in every world pass in which the targets were drawn,
  after near terrain's cutout layer and before a shading stage reads them, with the stage on or off; twice in a
  frame for the game's two-colored stereo picture. One added or removed from inside a call takes effect from the
  next pass. A writer that throws is removed and logged once.
- On entry: the game's framebuffer bound and the viewport the window's; no program, no vertex array, no array or
  element buffer, no sampler objects on units 0 and 1, shader storage points 0 to 3 unbound. The depth test and
  depth writes are on as the game holds them in front of its entities. Nothing else is promised.
- The writer binds `shadingTargets().framebuffer()`, sets every state it depends on and puts back everything it
  changed; nothing that draws after the writers trusts a binding. It tests depth nearer-or-equal against near
  terrain's (`GL_GEQUAL`), never clears, and turns the alpha test off, since A's alpha is roughness.
- From inside its call a writer may give `targetsEdge(float)`: the horizontal distance to the far edge of what it
  wrote, for that pass. The largest of several stands; `targetsFarEdge()` gives the frame's value, which the stage's
  frame block carries.
- The state audit lists each writer as a stretch of its own.

**Threads and lifetime.** Client thread. The targets are made when needed at the window's size, cleared and drawn
once in a world pass, and made again on a resize, which moves `generation()`.

**Costs measured.** Opaque terrain with the targets drawn beside the forward picture: 2.15 to 3.74 ms of GPU time
over radii 8 to 20 at 1280 by 720, 6.73 ms at the worst view at radius 20 ([ARCHITECTURE.md](ARCHITECTURE.md)
section 12.1); the tier model carries the targets as half the forward draw (section 12.1.1). Distant terrain's
writer stretch, its forward layers and its pass into the targets together: 4.3 ms ([DISTANT.md](DISTANT.md)
section 16.9, the thirteenth run). The material table's lookups: not measured.

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) section 6.6; [DISTANT.md](DISTANT.md) sections 14.3,
14.4, 16.7 and 16.9; D18. [PROVENANCE.md](PROVENANCE.md): "Targets for shading", "The material table and the list of
kinds", "Emission from a surface's kind and its texel", "Writers into the targets, and how far they reach", "The
depth target written from the frame that is shaded", "Audit of the occlusion in the targets".

## 3. The stage host

**What it is.** A shading stage handed over in a folder of data: a manifest, program sources, assets and files that
stand for the host's inputs and the expected outputs. The host runs its passes as a stage of its own in the frame,
entered in front of the game's entities, after the target writers.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.stage` | `false` | Runs the stage; needs the shading targets |
| `quadrature.stage.dir` | unset | The folder; required with `quadrature.stage` or `.check` |
| `quadrature.stage.show` | `false` | Writes the `display` pass's color over the game's whole picture, the game's depth untouched |
| `quadrature.stage.check` | `false` | Runs the passes once before the first frame on the folder's files and logs PASS or FAIL |
| `quadrature.stage.image` | `false` | Writes the display and diagnostic outputs into the client's `quadrature-stage/` and probes the light's depth |
| `quadrature.stage.probePixels` | eight edge pixels | `x,y;x,y` from the bottom left: where the light's depth is probed with `.image` |
| `quadrature.stage.targetsRaw` | `false` | With `.image`: targets B, C and D written as raw floats |
| `quadrature.stage.skip` | empty | `group,group`: leaves out every pass of the named groups |
| `quadrature.stage.selfTest` | `false` | Runs the host's own test of a kept target smaller than the window |
| `quadrature.stage.shadowRows` | unset | `flip`: a diagnostic that hands the light's depth over with its rows reversed |
| `quadrature.sun.tilt` | 0 | Degrees the sun's and moon's path leans south, in the directions published |

**Entry points.**

- `frame/StageHost.java`: `REQUESTED`, `SHOW`, `IMAGES`, `TARGETS_RAW`, `writeImage(String)`, `summary()`.
  Package-private: `StageRunner` (compiles and runs the plan), `StageCheck`, `StageSelfTest`.
- `core/stage/StageManifest.java`: `read(Path)`, `version()`, `plan()`, `NEWEST`, `PLANNED_FROM`;
  `StagePlan.java` (passes, targets, samplers, checks); `FrameBlock.java` (`BYTES`, the `OFFSET_*` constants);
  `FrameClock.java`, `FrameHistory.java`, `StageCompare.java`, `Json.java`.

**Manifest versions.** The host reads versions 1 to 3, 5 and 6 (`StageManifest.NEWEST` is 6; version 4 is refused).
From version 5 (`PLANNED_FROM`) a manifest says what is to be run; `StageCheck` checks version 5. A folder of version
3 or lower is read but not run: the stage then logs that it failed and is off, and the frame is drawn without it, so
a session that shows the stage needs a folder of version 5 or 6. A file is read only by a plain name the
manifest gives and only with the manifest's size and hash; anything in a manifest the host does not do (a format, a
filter, a uniform block other than the frame's, vertex buffers, depth or blending in a pass) is refused by name.

**Inputs and outputs.**

- *What a stage reads.* `host:` textures: `targetA` to `targetD`, `depth`, and the light's depth twice,
  `lightViewDepth` to be read and `lightViewCompare` to be compared. Where no light's depth is drawn the host gives
  one texel at the farthest and counts no light view. `asset:` files the folder brings; `target:` a target an earlier
  pass wrote in the frame; `history:` a kept target as the frame before left it (two textures, swapped each frame).
- *Targets.* Each with a format, a divisor n of the window (ceil(width / n) by ceil(height / n)) and whether it is
  kept from frame to frame. A pass may not read a target before it is written, nor read and write one.
- *The frame block* (`FrameBlock`): a std140 uniform block of 592 bytes: the inverse view-projection with the eye at
  the origin; four light-view matrices (`qShadowViewProjection`) and scales (`qShadowScale`, blocks a texel in x, the
  depth's span in y); the directions toward the sun and toward the shadow-casting light; the eye's block and place;
  the targets' size; rain, wetness, exposure and a time that stands still while paused; the frame's index, whether
  history is dropped, the dimension and the count of light views; the previous view-projection; the eye's movement
  and, in its w, the far edge of what the targets hold; the clouds' drift and height.
- *Bindings.* The host sets no uniform of a stage's program. Where each sampler and the block are bound is the
  program's to declare; at load the host asks the driver how it lays out the block for each program and refuses one
  whose size, member offsets or binding differ from what it fills, or whose sampler sits on another unit than the
  manifest says.
- *Out.* A pass that writes `display` writes a texture of the host's, shown only with `quadrature.stage.show`.
- History is dropped on the first frame, when the window's size or the dimension changes, when the stage's targets
  are made anew, and when the eye moves farther in a frame than the manifest allows (`FrameHistory`).

**Threads and lifetime.** Client thread. Programs compile and link at load, never during a frame. Targets are made
anew on a resize.

**Costs measured.** A manifest of version 5 with two passes at 1280 by 720: 0.043 ms on the CPU and 0.176 ms on the
GPU, medians ([VERIFICATION.md](VERIFICATION.md), A stage handed over in a folder); 0.28 ms for the stage's passes
in a later run with distant terrain ([DISTANT.md](DISTANT.md) section 16.9). The tier model carries shading at
0.19 ms a render megapixel ([ARCHITECTURE.md](ARCHITECTURE.md) section 12.1.1).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) sections 9 and 9.1; D5. [PROVENANCE.md](PROVENANCE.md):
"A shading stage handed over in a folder", "Timing each pass of a shading stage", "The host's self-test of kept and
smaller targets", "The probe of the light's depth at chosen pixels".

## 4. Lighting resources

**What it is.** What Quadrature publishes for lighting, near and far: the light's depth view, drawn from a box fixed
in the world along the sun or moon; the scene for lighting, a state number per cell of every meshed cube; its copy on
the GPU as a lookup and occupancy bricks; and a compute trace of rays through them. By D30 and D31 Quadrature
produces every shadow and every source of rays: a stage reads the light's depth view and the ray results as stage
resources and draws no shadow pass of its own. Light levels that play reads are Cubature's and are never changed.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.shadow` | `false` | Draws the light's depth (the wide box); not with `quadrature.terrain.draw=direct` |
| `quadrature.shadow.near` | `false` | Adds the near layer, 192 blocks across |
| `quadrature.shadow.spread` | 32 | Frames a new box is drawn over |
| `quadrature.shadow.turn` | 0.1 | Degrees the light turns before a new box is made |
| `quadrature.shadow.polygonOffset` | `0,0` | `factor,units` of polygon offset; no other bias is applied |
| `quadrature.shadow.check`, `.image` | `false` | Diagnostics of the light's depth |
| `quadrature.scene` | `false` | Keeps the scene for lighting |
| `quadrature.scene.gpu` | `false` | With the scene: copies it to the GPU as the lookup and occupancy bricks |
| `quadrature.scene.states` | `false` | With the copy: each cell's state, the states' lists and the state table |
| `quadrature.scene.check` | `false` | Checks the copy against the scene by reading it back |
| `quadrature.rays.bench`, `.check` | `false` | The ray benchmark and its check on the CPU |
| `quadrature.rays.fetch` | storage | `texture` reads the lookup and bricks through buffer textures |

**Entry points.**

- *The light's depth.* `TerrainHook.shadowMaps()` gives `core/view/ShadowMaps.java`: `texture()`,
  `compareSampler()`, `depthSampler()`, `layers()`, `side()`, `viewProjection(int)`, `previousViewProjection(int)`,
  `boxSide(int)`, `boxDepth(int)`, `origin()`, `previousOrigin()`, `towardLight()`, `drawnTowardLight(int)`,
  `generation()`, `contentGeneration()`. In `core/view/`: `LightBox`, `LightLayerPlan`, `LightTiles`, `ShadowLayers`,
  `SunPath`, `View.throughLight`. Package-private in the host: `terrain/ShadowTarget.java`, `terrain/LightLayer.java`.
- *The scene.* `TerrainHook.lightScene()` gives `core/scene/LightScene.java` (`cell`, `state`, `emission`,
  `transmission`, `blocksLight`, `version`, `changesSince`); `TerrainHook.publishedScene()` gives
  `core/scene/PublishedScene.java` (`lookupBuffer`, `lookupTexture`, `brickBuffer`, `brickTexture`, `cellTexture`,
  `listTexture`, `stateTexture`, `sizeX` to `sizeZ`, `originX` to `originZ`, `generation`, `changedEntries`).
  `TerrainHook.sceneCopyWaiting()` says what the copy has yet to take. Package-private: `terrain/SceneBuffers.java`.
- *Rays.* `rays/RayTracer.java`: `get()`, the one instance, implementing `rays/RayTrace.java`: `scene()`,
  `trace(int rayBuffer, int resultBuffer, int count)`, returning `RayTrace.Timed` (`done()`, `gpuMillis()`,
  `count()`). `core/scene/TraceFormat.java` gives the layouts and outcomes; `core/scene/BrickTrace.java` is the CPU
  reference the GPU's results are compared with word for word.
- *Emission.* `core/material/KindEmission.java` (section 2).

**Inputs and outputs.**

- *The light's depth.* An array texture of 24-bit depth, 4,096 texels a side, 64 MiB a layer, with a scratch of as
  many layers. With the near layer it is layer 0 and the wide box layer 1; without it the wide box is layer 0. Depth
  runs from 0 nearest the light to 1. Each layer's matrix takes a place relative to the frame's origin to that
  layer's clip space. `generation()` moves whenever texels or a box change; `contentGeneration()` only when content
  can differ apart from the light's own motion. Until a layer's first box is drawn whole the layer is clear and
  nothing is in shadow. Entities are not in it. The stage host hands it over as `lightViewDepth` and
  `lightViewCompare`, with the frame block's light-view matrices and scales.
- *The scene.* A cell is 16 bits, the game's number for the state, 0 for air; a cell reads as air, a block or not
  available, and a cube not meshed is never empty. Per state: whether it blocks light, its emission and how much light
  it holds back, 0 to 15. A consumer remembers `version()` and calls `changesSince` to hear each cube changed since,
  once; one more than 16,384 changes behind is told to take everything again.
- *The copy.* Buffers of unsigned 32-bit integers, written through the driver and never mapped, each also the store
  of a buffer texture read with `texelFetch`: the lookup, an entry for every cube of the box addressed by cube
  position wrapped by the box's size, 61 KiB; a brick of 576 bytes for each cube whose cells differ. At most 64
  bricks are copied a frame.
- *A ray* is 32 bytes: its origin relative to the lowest corner of the box's origin cube, its length and its
  inverse direction. *A result* is 16 bytes: the distance at which it entered the cell that ended it, that cell, how
  it ended (`HIT`, `MISS`, `LEFT`, `NOT_AVAILABLE`, `STEPS`) and how many nodes it visited. The trace binds nothing it
  does not put back and needs no state from the caller.
- The CPU never reads back a buffer the GPU reads: read a staging copy (`platform/Staging.java`). A buffer read back
  moves to host memory and traced three to twelve times slower ([ARCHITECTURE.md](ARCHITECTURE.md) section 5.2).
- Not yet published to a stage: the ray results. The host's inputs are the targets, depth and the light's depth
  alone (`StageHost.HOST_INPUTS`). Indirect light and far shadows are not built.

**Threads and lifetime.** Client thread. The light's targets are made when a world is attached, behind the screen
that waits for it, and put through a first frame's work then. A frame draws at most a share of a layer's tiles; a
frame with nothing due draws and searches nothing. `RayTracer` is made on first use; a trace's time is read in a
later frame without waiting.

**Costs measured.**

- The light's depth on a frame that draws it: 0.17 ms for one layer, 0.49 ms for both
  ([ARCHITECTURE.md](ARCHITECTURE.md) section 12.1.1). With a new box spread over 32 frames, 10 of 565 frames of a
  forest tour with both layers passed 2 ms of the light's GPU time (section 4.7). Preparing the targets at attach:
  8 ms of CPU, about 80 ms behind the loading screen (section 4.7). Memory: 128 MiB with its scratch, 256 MiB with
  the near layer (section 12).
- The scene's copy: the first frame that writes the new buffers about 9 ms on the client thread, once a run; then a
  few hundredths of a millisecond for 64 bricks a frame. The lookup and bricks take 8.6 MiB at radius 12, 77.8 MiB
  with the states (section 5.1); 15.0 and 23.1 MiB for the lookup and bricks at radii 16 and 20 (section 5.2).
- Rays on an RTX 3060: two million probe rays 1.1, 4.9 and 12.2 ms at 16, 64 and 256 blocks; two million rays toward
  a low sun 1.4, 3.6 and 5.4 ms; MEDIUM's probe update 0.22 ms, HIGH's 1.45 ms, MAX's two cascades about 8.5 ms and
  its far shadows 2.6 ms a million rays (section 5.2).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) sections 4.7, 5.1, 5.2 and 8; D30, D31.
[PROVENANCE.md](PROVENANCE.md): "The light's depth", "The light's depth drawn by tiles and spread over frames", "The
near layer of the light's depth", "The sun's and the moon's directions", "The scene for lighting", "Rays walked
through the occupancy bricks, cell by cell and node by node", "The ray benchmark and its check", "Emission from a
surface's kind and its texel".

## 5. Bloom and emissive geometry

**What it is.** Quadrature is the one provider of OrthLib's render interface (D25, D28): mods declare emissive and
plain layers, bloom kinds (a strength, a base, two brightness limits and a radius) and emissive contributions, and
Quadrature meshes, draws and glows them. The glow is one chain of halvings and doublings over a weighted source
(D27). Without a provider, OrthLib's absence answers draw everything plainly: everything appears, nothing glows.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.bloom` | `on` | `off` draws the emissive stage without glow; `alternate` glows on frames a mix of the frame's number chooses |
| `quadrature.bloom.levels` | 6 | Levels of the chain, 2 to 6 |
| `quadrature.bloom.gain` | calibrated | The calibrated gain for the levels, 9/7 at six |
| `quadrature.bloom.brightnessWeighted` | `false` | Weighting against single bright samples, for high dynamic range |
| `quadrature.bloom.passTimes` | `false` | The glow's GPU time split by pass in the summary |
| `quadrature.interface.selfTest` | unset | `x,y,z`: the interface's self-test squares drawn at that place |
| `quadrature.declarations.log` | `false` | Logs each change of the bloom kinds and contributions as well |

**Entry points.**

- `modinterface/ModInterfaces.java`: `start()` (registers the provider while mods load, once, where OrthLib is
  installed), `current()`, `provided()`.
- `modinterface/ModInterface.java`: what the rest of Quadrature reads, in its own terms: `declarations()` (one
  immutable snapshot, `Declarations`, whose layer generation moves only when the declared layers do), `providing()`,
  `meshing(DeclaredLayer)`, `write(Contribution, BufferBuilder, Frame)`, `drawingContributions()`; the nested types
  `DeclaredLayer`, `BloomKind`, `Blend`, `Contribution`, `Frame`. `ModInterface.NONE` declares nothing.
- The bridge, a source set of its own (D26): `src/orthlib/java/cc/orthrus/quadrature/orthlib/OrthLibBridge.java`
  (`start()`), reached by name only where OrthLib is installed, and `InterfaceSelfTest.java`. Nothing else names an
  OrthLib type. Inside the bridge, OrthLib's `BloomKind`, `Contribution`, `DeclaredLayer` and `Blend` are written in
  full, since `ModInterface`'s nested types of those names shadow the imports.
- `core/bloom/BloomChain.java` (weights, levels, the calibrated gain, the composite and its GLSL); package-private
  `terrain/BloomStage.java`; the emissive variant in `terrain/TerrainProgram.java`.

**Inputs and outputs.** Each cube keeps a buffer for each declared layer it has geometry in, at most 31 layers. The
emissive stage runs before translucent terrain, after entities and particles: declared layers, writing depth, then
each contribution, untextured or from the blocks atlas and blended as it states. What glows writes its bloom kind's
two weights, its alpha their cover, into a weight target the size of the drawn picture that shares its depth. The
chain forms the source a texel at a time, halves into six levels sized from the output, doubles by a tent at the
first drawn kind's radius and composites by the rule of the background's lightness. Without the temporal scaler the
glow is laid over the picture after translucent terrain; with it, the temporal pass resolves the weight in its
history's alpha and the glow is laid over the output after the scale pass, never in the history. A layer none of
whose cubes the view saw is skipped, as is a contribution wholly outside the view's volume.

**Threads and lifetime.** Declared layers are meshed on build threads inside the provider's scopes; every other
draw of blocks gets the absence answers. A new layer generation drops every declared mesh and builds the loaded cubes
again. The stage and the chain run on the client thread, change only state the game's cache knows, through the cache,
and read nothing back; the chain's objects are made on first use and remade when a size changes.

**Costs measured.** The glow, translucent terrain's median with glow less without: 0 to 0.83 ms, median 0.43 ms,
and nothing where nothing glowing is in view; within one run the glow's passes 0.513 ms, of which the first halving
and the products at full size 0.247 ms; under the temporal pass 0.327 ms ([VERIFICATION.md](VERIFICATION.md), Bloom
and emissive geometry through the render interface).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) section 10; D25 to D28. [PROVENANCE.md](PROVENANCE.md):
"The render interface's provider: declared layers, scopes and the emissive stage", "Bloom".

## 6. Temporal anti-aliasing and the spatial scaler

**What it is.** The world drawn at a fraction of the output's resolution into a framebuffer of the game's own kind
that stands in for the game's, with a sub-pixel offset each frame, then made whole by compute passes: motion from
depth, a temporal pass with neighborhood clipping, and Keys' separable cubic scaler (D23). Every tier draws through
the temporal pass.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.scale` | unset | `0.5` to `1`: the world's fraction of the output's resolution |
| `quadrature.taa` | `false` | The temporal pass and the sub-pixel offset |
| `quadrature.taa.phases` | 8 | Offsets a cycle, from the Halton sequence in bases 2 and 3 |
| `quadrature.taa.blend` | 0.1 | The least share of this frame blended into history |
| `quadrature.taa.moving` | 1/16 | Pixels of motion over which the share rises toward a third |
| `quadrature.taa.reactive` | 0.5 | The share where the depth changed after opaque terrain |
| `quadrature.scale.sharpness` | 0.2 | The scaler's boost of the difference from a bilinear read |
| `quadrature.taa.entityMotion` | `false` | Entities that moved or turned get motion of their own |
| `quadrature.taa.entityMotion.measure` | `false` | Logs how entity motion moves the history read |
| `quadrature.taa.check` | `false` | Reads the motion target back every 600 composites and logs its mean and greatest length |

**Entry points.** `frame/TemporalScaler.java`: `SCALE`, `TAA`, `makesPicture()`, `offsetProjection()`,
`framebufferMade(int, int)`, `summary()`; package-private `ScalerShaders` (`MOTION_COMPUTE`, `TEMPORAL_COMPUTE`,
`SCALE_COMPUTE`, `COPY_COMPUTE`) and `EntityMotions`. In `core/scale/`: `RenderScale` (`parse`), `Jitter`,
`Reprojection`, `EntityMotion`. Mixins: `MixinEntityRendererScale`, `MixinFramebufferScale`,
`MixinRenderGlobalScale`, `MinecraftScaleAccess`.

**Inputs and outputs.** `Minecraft.getFramebuffer()` returns the drawn framebuffer, `round(side * scale)` pixels each
way, from the start of the world pass to the composite, so hosted draws and Quadrature's own stages work on one
smaller picture. The composite is a stage of its own, `scaler`, at the boundary in front of the mods' last world
drawing: the drawn depth copied, motion into two 16-bit floats a pixel at the drawn size, the temporal pass into one
of two 16-bit float histories, the scale pass into the game's picture, the drawn depth stretched over the game's by a
nearest blit, and the offset taken out of the projection for the hand and the interface. Motion is the camera's, in
pixels without the offset; what has no motion (particles, weather, animated textures, translucent surfaces) is held
by the neighborhood's box. Read on units 4 to 8 through sampler objects, written through image unit 0. History is
dropped after a teleport of 8 blocks in a frame, a change of dimension, new targets, or a frame without a composite.

**Threads and lifetime.** Client thread. Targets are made with the game's first framebuffer and remade on a resize.

**Costs measured.** At P0 the scaler's passes take 0.98 ms at 2560 by 1440 output from 0.67, 0.97 ms from 0.77 and
0.96 ms for the temporal pass alone at the output's size; 1.52, 1.78 and 2.01 ms at 3840 by 2160
([ARCHITECTURE.md](ARCHITECTURE.md) section 12.1.1). Memory at 2560 by 1440 and 0.67: 56.8 MiB (section 4.8).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) section 4.8; D21, D23. [PROVENANCE.md](PROVENANCE.md):
"Rendering below output resolution: the drawn framebuffer, the offset and motion from depth", "Temporal pass",
"Spatial scaler", "Entities' own motion for the temporal pass".

## 7. The tier benchmark

**What it is.** A versioned startup benchmark that places the machine on two axes (D22): speed, by a bandwidth
kernel and timed probes of opaque terrain, the light view's wide box and the indirect trace against a reference
card's calibration; and memory, by what the driver reports free. Groups that hold memory take the lower tier.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.tierBench` | `off` | `once` runs it once at the tiers' render scales of the window; `calibrate` runs it at every still place and appends each result to the client's `quadrature-tier-runs.jsonl` |
| `quadrature.tierBench.firstLaunch` | `false` | The player's path: runs once where nothing is stored for this GPU, driver and benchmark version, and applies the stored tiers before the game's classes load |
| `quadrature.tierBench.repeat` | 3 | Runs at each place under `calibrate` |
| `quadrature.tierBench.heights` | `720,1080,1440,2160` | Heights calibrated at, at the window's aspect |
| `quadrature.tierBench.stillFrames` | 240 | Frames the view holds still before a run |
| `quadrature.tier.<group>` | unset | The player's tier for a group: `renderScale`, `nearTerrain`, `distantReach`, `lightDepth`, `shadowFilter`, `ambientOcclusion`, `indirectLight`, `fogAndClouds` |
| `quadrature.tier.memoryLimitMiB` | unset | An enforced memory budget for a test |

**Entry points.** `platform/TierBench.java`; package-private `platform/BandwidthKernel.java`,
`platform/BrickTraceProbe.java` (the trace probe) and `asm/TierStartup.java`; `platform/TraceProbe.java`.
`TerrainHook.probes()` gives `TerrainHook.Probes` (`waiting()`, `held()`, `opaqueAgain(BlockRenderLayer)`,
`lightAgain(Runnable, Runnable)`), and `TerrainHook.afterOpaque(Runnable)` sets what runs after the last opaque
layer. In `core/tier/`: `TierBenchmark`, `BenchmarkRun`, `BenchmarkResult`, `TierSelection`, `TierChoice`,
`TierSettings` (the properties a tier sets), `TierCalibration`, `StoredTiers`, `EvictionWatch`, `Tier`, `TierGroup`.

**Inputs and outputs.** It runs at the first settled moment and abandons a run if the view moves or terrain changes.
Times are pairs of timestamps, read only once the driver reports them ready. The result, the tiers and their figures
go to the client's `quadrature-tiers.txt`, the GPU and driver as digests only. A tier sets the temporal pass and the
render scale, distant terrain's reach (256, 384, 512 or 768 blocks) and the light's near layer on HIGH and MAX; a
property given on the command line stands. At run time two-second windows are watched and the memory tier lowered a
step where evictions rise and the 1% low falls (`EvictionWatch`).

**Threads and lifetime.** Client thread, one probe repetition a frame after near terrain's last opaque layer.

**Costs measured.** A run takes about 75 frames; its settling load ran 13 to 40 frames on the RTX 3060
([ARCHITECTURE.md](ARCHITECTURE.md) section 12.1.1).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) sections 12.1 and 12.1.1; D22.
[PROVENANCE.md](PROVENANCE.md): "Tier benchmark: bandwidth kernel and timed probes", "Tier selection: predicted
cost, a band at each boundary, memory and overrides", "Memory tier lowered at run time by evictions and 1% lows".

## 8. The handoff to Vulkan (DLSS)

**What it is.** The frame stays OpenGL; where DLSS is chosen it is evaluated by Vulkan on images the two APIs share
(D21), in the separately licensed module `quadrature-nvidia` (D19). Built in measurement form; the bridge as a stage
of the pass graph is not.

**Switches.** `-Dquadrature.handoff=measure` opens the handoff; `-Dquadrature.handoff.evaluate=dlss` evaluates DLSS
Super Resolution in place of the stand-in. Further options are read as `quadrature.handoff.<name>`. Without the
module the switch logs that it is missing and the frame is unchanged.

**Entry points.** `core/handoff/Handoff.java` (the seam, with `Handoff.Evaluation`), `frame/HandoffHost.java`
(`REQUESTED`, `endOfWorld()`), which finds an implementation through `ServiceLoader`; the implementation is in
`quadrature-nvidia`. No NVIDIA SDK material is in the repository.

**Costs measured.** The round trip alone 0.36 to 0.37 ms of GPU time at P0 at 1080p and 1440p; DLSS in Performance
mode 1.54 and 2.20 ms with preset K at 1080p and 1440p ([ARCHITECTURE.md](ARCHITECTURE.md) section 4.9).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) section 4.9; D19, D21. [PROVENANCE.md](PROVENANCE.md):
"The handoff seam and its module" and the records after it, to "DLSS taking the scaler's picture".

## 9. Distant terrain

**What it is.** Columns of vertical runs sampled from the terrain preview Cubature's cube source gives, in tiles of
64 by 64 columns at levels of doubling cell width, selected in rings around the camera, meshed into 64-bit face
records, held in an arena of its own and drawn after near terrain's opaque layers, leaving out what near terrain
covers. It replaces Distant Horizons, which stops loading where installed (D29).

**Switches.** Off unless `-Dquadrature.distant=own`, which needs own terrain and Cubature.

| Switch | Default | Effect |
|---|---|---|
| `quadrature.distant` | unset | `own` draws distant terrain |
| `quadrature.distant.reach` | 384 | Ring 0's reach in blocks, a multiple of 128 |
| `quadrature.distant.levels` | 7 | Levels 0 to 6, which reach 24.5 km |
| `quadrature.distant.world` | none | Half the side of the world's square; no tile is built outside it |
| `quadrature.distant.threads` | 2 | The fallback pool's threads, 1 to 8; no effect on Cubature's pool |
| `quadrature.distant.fog` | `own` | One fog for near and distant terrain; `game` leaves the game's |
| `quadrature.distant.fogStart`, `.fogEnd` | 384; 20,480 | Where the shared fog begins and is complete |
| `quadrature.distant.crossfade` | `on` | Two levels drawn over a band and faded into each other; a new tile fades in |
| `quadrature.distant.fill` | `on` | Each level also draws the tiles inside its hole until finer tiles are stored |
| `quadrature.distant.order` | `near` | The coarsest level's fill tiles, then the finest level outward; `coarse` the coarsest first |
| `quadrature.distant.shaded` | `off` | `on` also draws the opaque layers into the shading targets; needs `quadrature.gbuffer` |
| `quadrature.distant.shaded.leaves` | `far` | `plain` writes the leaves' own albedo to the targets |
| `quadrature.distant.joinFade` | `off` | `on` fades distant terrain in across near terrain's band; needs `quadrature.terrain.fade` |
| `quadrature.distant.detail` | `images` | Level 0's faces show their materials' images near the join; `flat` flat colors |
| `quadrature.distant.detailStart`, `.detailEnd` | 224; 352 | Where images are whole, and where flat color begins |
| `quadrature.distant.materialsAFrame` | 32 | New materials colored a frame at most (section 10) |
| `quadrature.distant.debug` | unset | `box`, `level` or `join`: debug views |
| `quadrature.distant.joinFadeBand`, `.travelCapture`, `.shaded.image` | unset | Debug views and captures |
| `quadrature.distant.writerAudit` | `false` | Reads 80-odd items of state before and after the first three passes into the targets and logs what changed |
| `quadrature.distant.frameTail` | `false` | Once a second, the tail of the last 120 frame intervals as Cubature's preview governor reads it |
| `quadrature.test.allowDistantHorizons` | `false` | Lets Distant Horizons load beside Quadrature, with a warning, for comparison runs |

**Entry points.**

- `distant/DistantHook.java`, the published face: `requested()`, `draw(float)` (once a world pass, after near
  terrain's opaque layers), `far(float)` and `FAR_PLANE`, `fog()`, `farthest()`, `farthestGeneration()`,
  `nearestEdge()`, `shaded()`, `settledPasses()`, `unsettled()`.
- Package-private in `distant/`: `Distant` (`start()`, `frame(float)`, and the private `writeTargets`, held as the
  registered writer), `DistantWorkers`, `DistantArena`, `DistantProgram`, `DistantCover`, `DistantMaterials`,
  `DistantSettings`, `WriterAudit`, `WriterState`, `FrameTailLog`, `TravelCapture`, `TargetImages`.
- `core/lod/`: `RunTile`, `RunMesher`, `TileMesh`, `FaceRecord`, `Rings`, `TileKey`, `TileSchedule`, `TileDraws`,
  `TileCulling`, `Cover`, `NearCover`, `FarEdge`, `BuiltTile`, `MaterialTable`. Mixins:
  `MixinEntityRendererDistant` (the draw and the far distance), `MixinEntityRendererFog`.

**Inputs and outputs.**

- *In:* the terrain preview, looked for once a frame (`ICubeSource.terrainPreview()`); a new world or a preview
  that comes or goes lets every tile go. The near box from `ICubeSource.heldBox()`, or near terrain's coverage map
  where `quadrature.coverage` is on.
- *Tiles:* 64 by 64 columns with a ring of one more column, 66 by 66; at level k a cell is `1 << k` blocks wide and
  height stays one block. A face record is 64 bits (position, extent, direction, 13 bits of material, sky light,
  occlusion). Four mesh layers (opaque, cutout, water, skirt), each in six groups by direction.
- *Arena:* 64 MiB pages written through the driver, never mapped, eight at most (512 MiB). A tile's page is bound as
  a shader storage buffer and the vertex stage builds each face from its record; the draw's base instance names the
  tile's record. Three draw layers: opaque with the skirt, leaves drawn solid, water blended.
- *Depth:* while distant terrain is on, the far distance of every projection of the world pass is raised to 65,536
  blocks (`DistantHook.FAR_PLANE`), so near and distant terrain share one mapping of distance to depth.
- *The shaded path:* while it is on and a preview is attached, `Distant.writeTargets` is registered with
  `TerrainHook.addTargetWriter` and taken off when the preview goes. In the writer's call the forward opaque and
  leaf layers are drawn into the framebuffer bound on entry, then the same layers into the targets with the
  encodings of [DISTANT.md](DISTANT.md) section 16.8, reading near terrain's table through
  `TerrainHook.materialsBuffer()` and its emission rule through `KindEmission.GLSL`. It binds storage points 0 to 4
  (tile records, each page in turn, distant materials, biome tints, near terrain's table) and texture units 6 and 7,
  and puts back every listed state through `StateGuard`. It gives `TerrainHook.targetsEdge(DistantHook.nearestEdge())`.
  Water stays at the Mixin's place, drawn after the targets with depth writes off.
- *Crossfade:* each level's band is the last of its tiles inside its hole; a pixel is kept by one level by the
  ordered 4 by 4 pattern of `FadePattern`; a newly stored tile fades in over 24 frames.
- *Published:* `farthest()` and `farthestGeneration()`, the nominal reach drawn to; `nearestEdge()`, the distance to
  the nearest side of the outermost square, a value of the pass; `shaded()`, whether the targets hold distant
  terrain this pass.
- *Settled:* `settledPasses()` counts world passes in a row whose picture is settled: every wanted tile that can
  arrive stored and faded in, none building or kept past its rings, near terrain caught up, and the cube source's
  box settled. 0 while distant terrain draws nothing. `unsettled()` says what the last pass waited on. A capture that
  must repeat waits for some passes of it.

**Threads and lifetime.** Tiles are sampled and meshed on Cubature's client preview pool
(`ICubeSource.previewWork()`), whose governor lowers how many run at once while frames or server ticks run long; a
tile that leaves every ring is cancelled. Where the cube source has no such pool a fallback pool of
`quadrature.distant.threads` threads at the lowest priority is used, with a warning. The client thread stores
finished tiles for at most a millisecond a frame. A stored tile that leaves the rings is kept and drawn until the
tiles that replace it are stored.

**Costs measured.**

- The tier model's line: 0.973 ms, plus 0.928 ms a million faces and 0.181 ms a million render pixels; about 2.4,
  3.2, 4.3 and 5.6 ms at 2560 by 1440 for the reaches of LOW to MAX on the reference card
  ([VERIFICATION.md](VERIFICATION.md), Distant terrain's cost; [ARCHITECTURE.md](ARCHITECTURE.md) section 12.1.1).
- With the shaded path and the stage: 2.0 ms of GPU time forward, 4.3 ms for the writers' stretch holding the
  forward layers and the pass into the targets ([DISTANT.md](DISTANT.md) section 16.9).
- Memory: 28 to 112.5 MiB at the default reach, 200 MiB at the high setting, about a quarter more for the crossfade
  ([ARCHITECTURE.md](ARCHITECTURE.md) section 12).
- The crossfade's and the fill tiles' fragment cost in a game: not measured ([DISTANT.md](DISTANT.md) sections 14.1
  and 18.2 give estimates).

**Design and provenance.** [ARCHITECTURE.md](ARCHITECTURE.md) section 7; [DISTANT.md](DISTANT.md) sections 1 to 3, 8,
14, 16.8 to 16.10, 18 to 21; D4, D29. [PROVENANCE.md](PROVENANCE.md): "Run tiles" to "Writer audit" (run tiles, the
run mesher, corner occlusion, the face record, rings, the vertex stage, the far distance, the fog and the near box,
flat material colors and biome tints, the tile schedule and arena, the writer audit).

## 10. Distant materials uploads

**What it is.** Each block state a tile meets gets a material index on the sampling threads; on the client thread
each new index gets two flat colors (top and sides) and two image layers of 16 texels a side, and its entry in the
table the draw reads. Since the fix for the fill spikes of [DISTANT.md](DISTANT.md) section 23 (its fix 1, with
fixes 3 and 4), a refresh no longer regenerates the levels of the whole image array nor sends the whole table again.

**Switches.**

| Switch | Default | Effect |
|---|---|---|
| `quadrature.distant.materialsAFrame` | 32 | New materials colored in one frame at most, at least one; the rest wait for the next frames |

**Entry points.**

- `distant/DistantMaterials.java` (package-private): `refresh()` (client thread, from `Distant`'s preparation of a
  pass), `index(IBlockState)` and `kind(IBlockState)` (any thread), and privately `image` (one layer, every level)
  and `upload` (entries written in place); `COLORED_A_FRAME` holds the switch.
- `core/lod/MaterialTable.java`: `IMAGE_SIDE` (16), `IMAGE_LEVELS` (16 texels down to one, five levels),
  `halve(int[], int)` (the next smaller copy, each texel the mean of the two by two below it, each channel rounded to
  nearest), `image`, `entry`, `put`, `seenFromAfar`, `ENTRY_WORDS` (three words a material: top, side, the state's
  number).

**Inputs and outputs.** The images are one array texture of `RGBA8`, five levels, 2,048 layers or as many as the
driver has, layer `2 * index` a top and the next a side; a material past half the layers draws its flat color.
Each new material's two layers are written with all five levels from the CPU (`glTextureSubImage3D` per level);
nothing is generated over the array. The table's buffer, three words a material, is written in place with
`glNamedBufferSubData`; it is made again, twice as large and zeroed, only when the entries outgrow it, so an entry
not yet colored reads as black. On the shaded path the state's number is near terrain's (`TerrainHook.material`),
which fills that state's row in near terrain's table.

**Threads and lifetime.** Indices are handed out on any thread; colors, images and uploads are the client thread's.
A material waiting its turn draws black for the frames the bound keeps it waiting.

**Not built.** Fix 2 of [DISTANT.md](DISTANT.md) section 23, filling the table at the join before tiles arrive: it
needs the preview to list its states, which Cubature's preview interface does not.

**Costs measured.** Before the fix, spike seconds held 12 native samples, about 240 ms, of `refresh` in
`glGenerateTextureMipmap` ([DISTANT.md](DISTANT.md) section 23). After it: not measured.

**Design and provenance.** [DISTANT.md](DISTANT.md) sections 8 (materials and images near the join) and 23.
[PROVENANCE.md](PROVENANCE.md): "Flat material colors and biome tints", "Distant materials uploaded a layer at a
time".

## 11. The edit overlay

**Designed, not built.** Nothing of this exists in code; it is the design of [DISTANT.md](DISTANT.md) section 22
(work order P4-R1, services S2 and S3 in [PLAN.md](PLAN.md)).

**What it is.** Distant tiles are sampled from the terrain preview, a pure function of the seed and the world's
definitions, so terrain that players change is not seen from afar. The overlay carries what differs: a cube is
edited when a block in it changes after it is fully populated, by any cause; the server reduces edited cubes to the
preview's run vocabulary, with the exposure rule applied so sealed space is sent as rock; a client asks for an index
of edited tiles at join and on approach and for patches of the tiles it wants; and a patch replaces vertical spans of
a kept run tile, which is collapsed and meshed again. The patches are also to carry emitters (D31).

**What Cubature must add** (section 22.8): the edited bit in a cube's record and an index of edited cubes per region
file with a version each; the cached reduction to runs with the exposure rule; the index and patch messages and the
change notices; and a client call through which Quadrature receives patches in the preview's run vocabulary.

**What Quadrature is to add:** per-tile versions in `TileSchedule` in place of the whole world's generation, kept
run tiles, the merge, and the request queue (section 22.5).

**Open.** Which rule reduces a cell wider than a block on the server, so that an edited cell meets an unedited one
with no seam: the preview's footprint rule belongs to the world generator (section 22.5).

**Costs.** Estimated in section 22.6; not measured.

## 12. Diagnostics

Each is a system property, read once, and costs nothing when unset ([VERIFICATION.md](VERIFICATION.md),
Diagnostics, which lists them all).

| Switch | What it does | Entry point |
|---|---|---|
| `quadrature.stateAudit` | Reads a fixed list of GL state at every stage boundary for a window of 120 frames from 240 frames after joining, and reports what each stage leaves changed; every read synchronizes, so it never runs in a normal frame. `quadrature.stateAudit.every=<frames>` starts further windows | `frame/StateAudit.java`, `FrameHost.auditBoundary(String)` |
| `quadrature.frameLog` | Logs each stage's CPU and GPU time every five seconds, with near and distant terrain's summaries | `frame/FrameHost.java`, `TerrainHook.summary()` |
| `quadrature.glDebug` | Logs the driver's messages of every severity, each when first seen and at counts of ten, a hundred and so on, marked by the terrain layer or distant pass they arrived in; counts buffers the driver moved | `terrain/DriverMessages.java`, `TerrainHook.driverMoves(int, String)` |
| `quadrature.capabilities` | At startup, before the gate, logs the limits and extensions the tiers depend on and writes them to the client's `quadrature-capabilities.txt`; device and driver identifiers as hash prefixes | `platform/CapabilityDump.java` |
| `quadrature.probe` | `offscreen` or `visible`: a development stage of Quadrature's own between two hosted stages | `frame/Probe.java` |
| `quadrature.depthSelfTest` | Checks the projection translations of reversed depth against the driver | `depth/DepthSelfTest.java` |
| `quadrature.textures.still` | Animated textures stay at their first frame, for comparing runs | `frame/StillTextures.java` |
| `quadrature.exitOnOutOfMemory` | The client quits after the out-of-memory notice | `platform/OutOfMemoryWatch.java` |
| `quadrature.distant.writerAudit`, `.frameTail` | Distant terrain's own audit of its writer and its frame tail (section 9) | `distant/WriterAudit.java`, `distant/FrameTailLog.java` |

The gate's test switches `quadrature.test.driverFloorBranch` and `quadrature.test.driverWarnBelowBranch` raise the
driver floor's numbers for a test and never lower them ([ARCHITECTURE.md](ARCHITECTURE.md) section 3).

## Where things are

| Package | Area |
|---|---|
| `cc.orthrus.quadrature` | The mod's entry, registration of the provider, the reference-build check |
| `cc.orthrus.quadrature.asm` | The loading plugin, the depth-call rewriter, tiers applied at startup |
| `cc.orthrus.quadrature.depth` | Reversed depth's translation of hosted depth calls |
| `cc.orthrus.quadrature.distant` | Distant terrain's host: sampling, materials, arena, programs, the writer |
| `cc.orthrus.quadrature.frame` | The frame host, stage boundaries, timing, the state envelope and audit, the stage host, the scaler, the handoff host |
| `cc.orthrus.quadrature.mixin` | Injections into the game's renderer, framebuffer, state cache and chunk system |
| `cc.orthrus.quadrature.modinterface` | The render interface as Quadrature reads it |
| `cc.orthrus.quadrature.orthlib` (`src/orthlib/java`) | The bridge to OrthLib, a source set of its own |
| `cc.orthrus.quadrature.platform` | Reading the context, the gate's handler, GPU memory, the capability dump, the tier benchmark, staging |
| `cc.orthrus.quadrature.rays` | Rays through the occupancy bricks and their benchmark |
| `cc.orthrus.quadrature.terrain` | Near terrain: meshing, arena, views, the light's depth, the scene's copy, targets, bloom, the chunk system |
| `core.bloom`, `core.fade` | The bloom chain; the fade pattern and the near fade |
| `core.frame` | The pass graph, stage timeline and statistics, target writers, the frame tail |
| `core.handoff` | The handoff seam and its schedule |
| `core.lod` | Distant terrain without graphics: tiles, mesher, records, rings, schedule, cover, materials |
| `core.material` | The material table, the list of kinds, the emission rule |
| `core.platform` | Platform profile, capability set, gate, driver floor, GPU memory sources |
| `core.scale` | Render scale, offsets, reprojection, entity motion |
| `core.scene` | The scene for lighting, its published copy, the trace's format and reference |
| `core.stage` | The stage manifest, plan, frame block, clock and history |
| `core.terrain` | Near terrain without graphics: keys, slots, arena allocator, visibility, coverage, draw commands |
| `core.tier` | The tier benchmark's runs, calibration, selection and settings |
| `core.view` | Views, the light's boxes and layers, shadow maps, shading targets, the state guard |
| `cc.orthrus.quadrature.nvidia` (`quadrature-nvidia`) | The separately licensed bridge to Vulkan and NGX (D19) |
