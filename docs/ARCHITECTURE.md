# Architecture

How Quadrature is put together. Requirements are in [REQUIREMENTS.md](REQUIREMENTS.md),
decisions in [DECISIONS.md](DECISIONS.md), measurements in [BASELINE.md](BASELINE.md) and
the research behind this document in [research/](research/README.md).

The platform gate, the pass graph and the timed baseline graph of hosted stages exist in
code (sections 3, 4.1, 4.3 and 4.6). Everything else here is design. Each choice carries a
status:

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
- What terrain memory and submission use is named: persistently mapped buffers, sync
  objects, indirect multi-draw, shader storage buffers, and draw parameters in shaders.
  All are present on the supported platform.

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
observing the profiler sections the game already enters inside it, with one injection that
redirects nothing, so Forge's hooks, Cleanroom's headless Kirino
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

- **Entering a Quadrature stage: assume nothing.** Quadrature sets every piece of state
  its stage depends on, every time. A quarter of the mods in a surveyed pack change state
  with direct calls that cannot be observed, so nothing a hosted stage left is trusted.
  **Decided.**
- **Leaving a Quadrature stage: leave the context as the game believes it to be.** State
  the game's cache (`GlStateManager`) knows is changed only through the cache, and put
  back to what that place in the frame expects: the game relies on state set earlier in
  its own pass, such as face culling. State it
  does not know is returned to a fixed neutral value: no program, no vertex array object,
  the game's framebuffer, no array or element buffer, no sampler objects on the units the
  game uses, generic vertex attribute arrays disabled, default pixel storage, the matrix
  mode and active texture unit the game expects. **Decided.**
- **Neutral values, not saved ones.** Reading state back to save it is a synchronizing
  query, and the game's expected values at each boundary are known. The attribute stack is
  not used: it saves none of the state a modern renderer adds, and other mods already use
  its limited depth. **Decided**, by the specification.
- **Own objects.** Quadrature creates and modifies its buffers, textures and framebuffers
  through direct state access, which changes no binding, and uses texture units and
  binding points fixed-function drawing does not. **Decided.**
- **Mirroring.** Fixed-function enable flags are not readable from a shader. From Phase 5
  the host observes them where the game sets them and mirrors them into a small uniform
  block the hosted program reads. **Decided**, by the specification.
- **Audit.** In development builds, a fixed list of state is read back at each stage
  boundary and compared with the cache and the neutral values, naming the first stage that
  leaves a difference. It is a diagnostic and never runs in a normal frame. **Decided.**

The research behind these rules is R1, section 9a.

Until Phase 5, hosted stages run exactly as the game draws them, with no program bound.

### 4.5 Depth and color

- **Geometry buffer, attachment zero.** It carries alpha and is not an integer format,
  because the alpha test reads it. **Decided**, by the specification.
- **Color.** Lighting is computed in linear space into floating-point targets; the final
  stage applies exposure, tone mapping and display encoding. **Default** from Phase 5.
  Before that the frame stays in the game's display-encoded space.
- **Depth.** The whole frame uses reversed depth: the near plane at 1, the far plane at
  0, clip depth from 0 to 1 (`glClipControl`), a 32-bit floating-point depth buffer.
  **Decided** (D17). Hosted code is not asked to change. A class transformer points every
  call to a depth function, in the game and in every mod, at a translator that reverses
  the comparison, clear value, polygon offset and depth range on the way to the driver
  and restores them when read. The projection stack's base is a reversal matrix, so every
  projection hosted code builds on it is reversed without being touched, and reads of the
  projection return the conventional matrix. Quadrature's own code is not rewritten and
  works in reversed depth directly; its projections are derived from the matrix the
  driver holds, so its depth matches hosted depth. **Prototype built**, behind a switch;
  what it does not yet cover is listed in research R1, section 2.2.

### 4.6 Timing

Built. Every stage is bracketed by a GPU timer query and a CPU timer. Query results are read
only when the driver reports them available, from a ring a few frames deep, so reading
never stalls the frame. **Decided.** The baseline could not time the GPU at all; this
closes that gap from the first frame Quadrature owns.

### 4.7 Views

A view is what terrain is drawn for: an origin in double precision, a model-view and a
projection matrix in single precision as the driver holds them, the planes of the volume
they see, the depth convention, a reach in cubes across and upward, whether terrain may
hide terrain, and a purpose (the frame the player sees, or depth alone). Visibility and
drawing take a view; nothing in them reads the player's camera. **Decided.** Built as
`View` in `core`.

- The player's view is filled once a frame from the matrices the game has just set for
  the world, at the point where the game is about to draw its first terrain layer. Its
  projection, fog, alpha test and textures are still the game's state at draw time; a
  view of Quadrature's own has to set them itself.
- A view along the light is derived from the player's view each frame, toward the sun or,
  while the sun is down, the moon. What it sees is found every frame.
- Hosted drawing is not yet drawn for any view but the player's.

**The light's depth.** Built behind `-Dquadrature.shadow=true`, off otherwise. The view
along the light is drawn for depth alone into a target of Quadrature's own, before the
player's first terrain layer. Nothing samples it yet, so the player's image is the same
with it on.

- **The box** is a plain orthographic cube of a fixed side centered on the player, never
  warped: at a reach of twelve chunks 544 blocks, the diagonal of the square the client
  holds cubes in, so every cube held is inside it at any hour and whichever way the player
  faces. It is moved to a whole texel along the light's own axes, measured from the
  world's origin, so that the map does not shift by part of a texel as the player moves.
- **The target** is an array texture of 24-bit depth, 4,096 texels a side, one layer,
  64 MiB, counted in the shading pool. A texel is 0.13 of a block. Further layers with
  boxes of other sizes can be added; none is built. It can be sampled through either of
  two sampler objects: compared with a reference and filtered, or as the depth itself.
- **What is drawn:** the three opaque layers of every meshed cube in the box, from the
  same pages and by the same indirect draws as the player's view, each cube under the
  same composition of matrices. A fragment of a cutout layer is dropped where the block
  texture's alpha fails the game's own threshold, read where the game sets it. Faces are
  not culled by facing. Translucent terrain is left out.
- **Which meshes are resident** does not follow the player's frustum: a mesh is held for
  every loaded cube that has its six neighbors, so what the light sees and the player
  does not is there to draw. Terrain beyond what the client holds, and the outermost row
  of cubes it holds, which is never meshed, cast nothing.
- **No bias** is applied but a polygon offset, none unless
  `quadrature.shadow.polygonOffset=factor,units` is set. A sampler biases as it sees fit.
- **Published each frame** (`ShadowMaps` in `core`, from `TerrainHook.shadowMaps()`): the
  texture and the two samplers, each layer's matrix from a position relative to the
  frame's origin to the layer's clip space, the frame before's matrix and origin, the
  box's side, the direction toward the light, and two generations. One changes whenever
  the texture is drawn. The other changes only when its content can differ apart from the
  light's own motion: a mesh was placed, replaced or released, or the box moved to another
  texel.
- **The map is kept between frames.** It is drawn again only when a mesh was placed,
  replaced or released, when the player has moved into another texel of it, or when the
  light has turned more than 0.1 degree since it was drawn
  (`quadrature.shadow.turn`, in degrees). A frame that keeps it costs nothing: the light's
  view is not searched and no commands are written for it. The matrices and origin
  published are always the ones the map was drawn with; the direction toward the light of
  the moment is published beside them, since shading uses that.
- **Entities move every frame**, which a kept map does not allow for. The plan, of which
  nothing is built: the terrain's depth stays one layer, drawn on the conditions above,
  and entities go either on top of a copy of it made each frame or into a second layer
  holding them alone, which a sampler compares against as well, taking the nearer.

Not covered yet: entities and block entities in a light's view, which reaches into hosted
drawing; jitter in the frame's contract.
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

### 5.1 The scene for lighting

Built behind `-Dquadrature.scene=true`, off otherwise: the world as lighting needs it,
independent of any view. `LightScene` in `core`, from `TerrainHook.lightScene()`.

- **A cell holds one number**, 16 bits: the game's identity for the block state there,
  0 for air. It is taken from the snapshot the cube is meshed from, in the loop that
  already reads every cell; that adds 0.01 ms to the 1.11 ms a cube takes.
- **What a state does to light is kept once for the state**, not per cell: whether it
  blocks light as an opaque whole cube does, its emission and how much light it holds
  back, each 0 to 15. The values are the state's own; a block whose light depends on
  where it stands is taken at those. A state past the 65,536 that 16 bits name is kept as
  something solid and dark, and logged.
- **Three answers for a cell:** air, a block, or not available. A cube not taken, because
  it is not loaded or has not been meshed, is not available and never reads as empty.
  Only meshed cubes are taken: a loaded cube without its six neighbors is not meshed, so
  the outermost row of what the client holds is not available.
- **A cube of one state keeps no cells.** The others keep 8 KiB of cells and a 512-byte
  mask of the cells that block light, which is the mask the face links are found from.
- **Change.** Every change advances one counter and the cube takes its value as its
  generation. A consumer remembers the value it last saw and is told each cube changed
  since, once, as updated or removed; one further behind than the 16,384 changes
  remembered is told to take everything again. The unit of change is the cube.

**For a shader**, behind `-Dquadrature.scene.gpu=true` as well: `PublishedScene`, from
`TerrainHook.publishedScene()`. Two buffers of unsigned 32-bit integers, written through
the driver and never mapped, each also the store of a buffer texture read with
`texelFetch`.

- **A lookup** with an entry for every cube position in the box the client holds cubes
  in, 25 a side at a reach of twelve chunks, addressed by cube position wrapped by the
  box's size, so that moving the box moves nothing. An entry says that nothing is known
  of the cube, or that every cell holds the state named in it, or that the cube has a
  brick at a slot. 61 KiB.
- **Occupancy bricks**, one for each cube whose cells differ: a bit for each cell that
  blocks light, cells ordered x fastest, then z, then y, and after them a bit for each
  block of two cells a side in which any cell blocks light. 576 bytes; room is taken for
  a brick for every cube of the box, 8.6 MiB.
- **The state of each cell**, with `-Dquadrature.scene.states=true` as well, in three more
  buffers. For each brick slot an unsigned byte a cell, naming a place in the cube's own
  list of the states it holds, and the list, 256 state numbers of 16 bits: 4,096 and 512
  bytes a slot. A cube holding more than 256 states is clamped, the cells of states past
  the 255th naming the last place, and counted. For each of 65,536 states, four 16-bit
  numbers: the first holds what the state does to light and three are kept for color.
  Room is taken for every cube of the box: 61.0 MiB of cells, 7.6 MiB of lists and
  0.5 MiB for the table.
- **Room for every cube is more than is used.** With the lookup and occupancy bricks the
  buffers take 77.8 MiB, inside the 128 MiB the shading work allows for materials and
  visibility. The buffers are to be sized to about one and a half times the most slots
  seen in use, once that has been measured in different places: forest, a cave system, a
  built-up area, ocean. Only the forest tour has been measured: 6,406 slots at most. The
  most slots in use is published and logged.
- **Copying is bounded:** at most 64 bricks a frame, with their cells and lists, bricks
  before the entries that name them, and the whole lookup in one call when many entries
  change. Cubes are taken from the scene 512 a frame, so that a jump of the box's origin,
  as on a teleport, fills the box over some thirty frames; a cube that left the box or the
  scene is dropped at once. Each frame the box's origin cube, a generation and the entries
  copied are published.
- **The first copy is the dear one:** the first frame that writes into the new buffers
  takes about 9 ms on the client thread, all in the driver's writes; after it 64 bricks a
  frame cost a few hundredths of a millisecond. It happens once a run.

Not built: the color of light a state emits or passes, shapes smaller than a cell,
entities.

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
  **Decided.** Until that executor exists, builds run on the client thread within a time
  budget, nearest first.
- A build writes into native memory it owns and hands the result to the context thread.
  It allocates nothing on the Java heap per face. **Decided.**
- The context thread spends a bounded time per frame placing finished builds into GPU
  memory. **Decided.**

### 6.3 GPU memory

**Built** for the game's vertex format, which is what cubes are meshed in until the two
streams of 6.1 exist.

- **Pages.** Terrain meshes lie in an arena of pages, each an immutable buffer of 64 MiB
  shared by the three opaque layers; a page is added when no block is free, up to 24, the
  near-terrain budget. A mesh takes one block, its layers one after another, so a cube is
  one allocation and a layer is a range of it.
- **Pages are not mapped.** A mesh is written into its page through the driver. The driver
  keeps storage that stays mapped for writing in system memory and draws from it there:
  drawing from mapped pages cost 2.4 to 3 ms of GPU time per million vertices, against
  0.3 ms from pages that are never mapped, which the driver reports it keeps in video
  memory ([VERIFICATION.md](VERIFICATION.md)). Writing through the driver costs the client
  thread what copying into a mapped page did.
- **Blocks.** Sizes step by a granule of 146 vertices, 4,088 bytes, so every block starts
  on a whole vertex of its page. A mesh takes the smallest free range that holds it and
  leaves the rest free; a block given back joins the free ranges beside it in its page.
  Space never handed out is a free range like any other. Nothing is moved.
- **Pages ahead of need.** When the largest free range falls below 8 MiB a page is made
  after the frame's terrain is drawn, so that making one is never in the way of placing a
  mesh. Making a page takes 0.06 ms.
- **Placing within a frame.** Meshing stops for a frame once placing has taken 1 ms; the
  cubes left keep the meshes they have and come first in the next frame. That bounds the
  sum of a frame's placements and not one of them. The budget is met but for one
  placement a run: the first mesh placed takes 1.6 to 3.5 ms, about half in the driver's
  first write into the first page and half in the placement code running for the first
  time. Both are costs of first use and do not recur. Touching the first page and placing
  one mesh of nothing before a world appears would move them out of the first frames; it
  is not built.
- **A mesh is written straight into its page.** Staging a frame's meshes in one buffer and
  copying them into their pages on the GPU was measured and is not used: it took longer,
  and the driver moved the pages from video memory to host memory
  ([VERIFICATION.md](VERIFICATION.md)).
- **Reuse waits for the driver.** A freed block is not reused until a fence placed after
  the frame's last terrain draw has signalled, one fence per frame. Mapped pages need
  that, since a write to mapped memory is not ordered against draws already issued; pages
  written through the driver may not, which is not yet measured.
- **Slots and records.** Every loaded cube holds a slot that stays the same while it is
  loaded, naming its block and each layer's range. Each frame a record per visible cube,
  its model-view matrix and room for the previous frame's, is written to one shader
  storage buffer; a draw names its record by base instance.

Memory taken follows the most that was in use: over the forest tour 10 pages, 640 MiB,
for 592 MiB at the end and 594.5 MiB at most, where reuse by exact size took 14 pages.

Not built: compaction that moves a bounded amount per frame, and giving a page back when
use falls; between the tour's views use falls to a few tens of mebibytes and the pages
stay. The slot table and the budget cover terrain only.

### 6.4 Visibility and submission

Visibility is found once per view per frame, and every layer drawn for that view reads
the same result. Per-cube work on the client thread is confined to the coarse step, whose
cost is bounded by the cubes within the view's reach; every step after it is on the GPU.
**Default**, following Haar and Aaltonen's GPU-driven pipeline.

All views read one table: for each loaded cube, which pairs of its six faces see each
other through blocks that are not opaque, fifteen bits found when the cube is meshed. A
cube not yet meshed, or holding nothing, joins every pair. A cube that is not loaded is
known as such: a search passes through it as though it held nothing and lists nothing
for it.

1. **Coarse, on the CPU.** **Built.** Two functions over the table.
   - *A view from a point inside the world* is searched outward from the cube its eye is
     in, breadth first. The search leaves a cube through a face only if that moves away
     from the eye's cube on that axis and the face is joined to one the cube was entered
     by; it enters only cubes within reach and inside the view's volume. A cube entered
     by several faces is left through all that any of them allows. This admits every
     path the game's own search takes, so it draws no less than the game; the game lets
     only the first way into a section decide, which leaves out sections that can be
     seen. The reach bounds a search through cubes that have not arrived. A search is
     kept while the view and the table stand still.
   - *A view with no such point*, as a light's, takes every meshed cube within its volume
     and reach. Occlusion from a point does not apply to it.
2. **Fine, on the GPU.** A compute stage tests each surviving cube against the frustum
   and against a depth pyramid, and writes draw commands and their count.
3. **Two phases.** Cubes visible last frame are drawn first; the depth pyramid is built
   from them; cubes rejected in the first phase are retested against it and the survivors
   drawn. This removes the one-frame lag of testing against last frame's depth.
4. **Draw.** One indirect multi-draw per stream and layer, with the count read on the GPU.
   **Built in part:** the commands are written on the client thread from the list step 1
   gives, grouped by layer and page, and each layer is drawn with one indirect multi-draw
   per page: 35 draws a settled frame on average where one call per cube and layer made
   938.

**Coverage, and the edge of the box.** What draws terrain beyond the box near terrain
holds, or in place of what has not arrived, is told two things.

- *Whether a search came to the edge.* A search stops where its way on would leave the
  box. It notes through which of the box's six faces that happened with the view still
  seeing the cube beyond, how many times, and how many cubes inside the box it passed
  through that are not loaded. Only where it came to the edge can anything beyond be
  seen. Where terrain is drawn without a search, nothing is known and everything beyond
  counts as seen. `SearchEdge` in `core`, from `TerrainHook.searchEdge()`.
- *Coverage*, behind `-Dquadrature.coverage=true`: for every chunk column of the box,
  whether its surface is loaded and meshed. `CoverageMap` in `core`, from
  `TerrainHook.coverage()`. The cube source says nothing of where a column's surface is,
  so it is found from what is known of the column's cubes, from the top of the box down:
  a cube that has been meshed and draws nothing is taken as air, and the first cube under
  such air that is not one is the surface cube. The surface band is that cube and the one
  beneath it. A column is *not loaded* when none of its cubes is held; has *no surface*
  when all that is known is air, so the ground lies below the box, or when nothing is
  known above the highest cube that holds something, so the ground may lie above it; has
  its *surface waiting* when the surface cube or the one beneath is held and not meshed,
  whether it waits its turn or lacks a neighbor and cannot be meshed; and has its
  *surface meshed* when the surface cube holds a mesh and the cube beneath does not wait.
  The map also gives each column's surface cube. It is worked out again when what is held
  changes, no oftener than every twenty frames.
- *The highest cubes held are never meshed*, for want of a neighbor above, so nothing
  would be known to be air and every column would read as having no surface. With
  coverage on, a cube that cannot be meshed is therefore asked whether it is empty, and
  an empty one counts as air.
- *Where the cube source says more, it is used.* The host looks once for the cube
  source's surface heights, surface version and cube content by name. Where they are
  present a column's band comes from the heights, a column is judged by the band's cubes,
  and emptiness is one look-up; where they are absent the scan above stands and emptiness
  is asked of a snapshot. The cube source pinned has them; coverage is verified so far
  only against a cube source without them.
- *What the rule cannot tell:* a cube that is solid and wholly enclosed draws nothing and
  reads as air, which matters only above the surface, where there is none; and a roof
  over a column, an overhang or a tree's crown, is taken for its surface. A height for
  each column from the cube source settles both.

**The walk over a model's quads.** The game's dispatcher writes a block's vertices and
keeps nothing of the quad they came from. Behind `-Dquadrature.faces=true` or
`-Dquadrature.faces.check=true` the mesher walks a model's quads itself, in the game's
order, and hands each to the game's lighter and consumer, so the vertices are the game's
and each four are known by sprite, face, tint, layer and block state. A block the game
would not draw by its model through its lighting pipeline is left to the dispatcher. With
the check on every cube is meshed both ways and the two compared byte for byte. A builder
then sorts the quads into records of 32 bytes for whole faces of a cell and kept vertices
for the rest, expanding each record to see that it gives the game's vertices back.

- **A record's texture is a rectangle of the atlas, the quad's own.** The game does not
  give a quad its sprite's corners: it draws each a little toward the sprite's middle. So
  the rectangle is taken from the quad's four coordinates, which must be two values of u
  and two of v with each of the four corners once, and kept in a table of rectangles the
  vertex stage reads. Quads of one sprite baked alike share a rectangle.
- **Records are drawn with no vertex attributes.** The vertex stage finds its record by
  vertex number and builds the vertex; the main pass and the light's depth pass both draw
  so, with indirect multi-draw of quads from pages of records of their own.
- **What it holds.** Over the forest tour 92.1% of quads are records. They take 165 MiB
  and the kept vertices 44 MiB, where the game's vertices alone take 592 MiB.
- **A guard.** Once a million quads are sorted, a share of records under half is logged
  as a failure: a run that draws nothing from records proves nothing about them.

The table serves visibility only. Light that reaches past what is seen needs more of each
cube than fifteen bits: opacity per block, which the mesher finds on its way to those bits
and does not keep, material and emission, a generation per cube, and the regions that
changed. None of that is kept yet.

Steps 2 and 3 are not built, and the commands of step 4 are still written on the client
thread, in 0.11 ms a frame. Once a compute stage writes them, the client thread's cost no
longer grows with how many cubes are visible.

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

### 6.6 Targets for shading

Behind `-Dquadrature.gbuffer=true`, with faces and reversed depth on, each opaque layer is
drawn a second time into four targets of 8 bits a channel and a depth of Quadrature's own,
the size of the window, from the commands and records the player's view already made. No
lit color is written.

| Target | Color | Alpha |
|---|---|---|
| A | world normal as n * 0.5 + 0.5 | perceptual roughness |
| B | sky light, emission strength, block light, each 0 to 1 over the game's 0 to 15 | 0.375 on terrain, 0 where nothing was written |
| C | the square root of linear albedo after tint; the texel is decoded by the exact sRGB curve | 1 for a conductor |
| D | reflectance and subsurface, or for a metal its colored reflectance | the game's vertex occlusion |

- Depth is reversed, 32-bit float: 1 nearest, 0 farthest and where nothing was written.
- A fragment of a cutout layer is dropped where the texel's alpha is at or under 0.5.
- A face that is a record has its own normal; a quad that is none takes the normal of the
  surface as drawn, turned toward the viewer.
- The game's shading of a face by the way it is turned, and its tint, are divided out of
  the vertex color to leave the game's occlusion.
- Only terrain is written: entities and what mods draw are not in the targets.

**The material table.** What a state's surface is like where no texture says, eight bytes a
state: roughness, reflectance, subsurface, emission, flags for a conductor and for foliage,
and the state's kind. A state's row is at the game's own number for it, the block's
identity and its variant in 16 bits, the number the scene for lighting uses. The kind
comes from a list, `block.<kind>=` followed by block names, a name
taking `:property=value` once or more for the states that have every value named. The
list is the one the build ships, or the file `material_kinds.properties` of the folder
named by `-Dquadrature.stage.dir` where that is set and holds one, so that a new revision
of the list needs no new build; which was read, its revision line and its hash are logged.
A block
the list does not name is given a kind by what the game says of it: leaves, plants, glass
and ice by material, the game's own water, lava, and anything else that gives light as an
emitter. That rule never calls a block a metal, and a block of the game's iron or anvil
material that the list does not name has no kind and no emission.

| Kind | Meaning | What it sets |
|---|---|---|
| 100, 101 | water, lava | lava: emission 0.10, low since it is also lit by the light it gives |
| 110 to 117 | leaves; single, tall (lower, upper), hanging, floating, stiff and submerged plants | subsurface 0.55, foliage |
| 118 | ground cover | the defaults |
| 120, 121 | dim and bright emitters | nothing of their own |
| 130, 131 | glass, ice | roughness 0.12 |
| 140 | metals | roughness 0.32, conductor |
| 199 | plain | the defaults, and no emission whatever light is given |

The defaults are roughness 0.85 and reflectance 0.04. Emission is the light a state gives
over 15, whatever its kind, but for lava and plain states.

**What is published.** `TerrainHook.shadingTargets()` gives the framebuffer, the five
textures, their size and a generation that moves each frame they are drawn.
`TerrainHook.materials()` gives the table and `TerrainHook.material(state)` a state's
number, filling its row if near terrain has not met the state.

**The near edge.** Behind `-Dquadrature.terrain.fade=true` the player's view gives its
pixels up, across a band before the edge of what near terrain holds, to what is drawn
beyond: by the pattern of `FadePattern`, near terrain the complement side, frame 0. The
measure is the fragment's horizontal distance from the view's position, the greater of
that along x and that along z, in blocks; the band is set with `TerrainHook.fadeBand` and
is otherwise the last 16 blocks before the last chunk of the view distance. The depth
drawn along the light takes no part. Off, the stages are unchanged to the character.
`TerrainHook.fadeBandInUse()` gives the band a frame was drawn with and
`TerrainHook.fadeOrigin()` the place its distance was measured from, the camera's, which in
a view from behind is not the player's; both are null where no fade was drawn. The sums
are `NearFade`'s, in `core`. Near translucent terrain is still drawn by the game and does
not fade. It is built and has not been exercised with distant terrain.

**Not built**, and where each belongs in the frame:

- *Distant opaque terrain* draws into the targets after near terrain's last cutout layer
  and before anything reads them, through the published framebuffer, with the depth test
  nearer-or-equal in reversed depth, writing the same four targets by the same contract
  and the same table.
- *Shading* reads the targets and the depth and writes lit color. Until it exists the
  player's picture is the forward one and the targets are a second, unused image.
- *Water and other translucent surfaces* draw after shading, reading the lit opaque color
  and the opaque depth beneath them; both are to be published as textures once shading
  writes them.
- *A frame index*, moving by one each frame, is to be published beside the targets for
  stages that accumulate over frames and for the fade's pattern.

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
| Distant terrain arenas | 512 MiB | Phase 4 |
| Shading: shadow maps, light volumes. The light's depth takes 64 MiB of it | 1.0 GiB | Phase 5 |
| Not assigned | 1.5 GiB | |

The first line is not arbitrary: the test machine had 2.2 GiB in use before the game
started. The distant terrain line was 2.0 GiB until a survey for its design measured its
use: 28 to 112.5 MiB at the default reach, about 123 MiB unclipped, 200 MiB at the high
setting, and about a quarter more for crossfade. What that freed is not given to another
line. The budget is computed at startup from the memory the driver reports as free, so
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
| State envelope: assume nothing on entry, neutral values on leaving; state mirroring | Decided; built and measured with a development stage (research R1, 9a.7) | Mirroring, from Phase 5 |
| Reversed depth for the whole frame, with hosted depth calls translated where they are made | Decided (D17); prototype built | Depth read back by mods, cost, a larger pack (research R1, 2.2) |
| Two geometry streams, texture array, no merging | Default | Phase 2 measurements |
| A view as the input to visibility and drawing | Decided; built | |
| Coverage of chunk columns, and whether a search came to the edge of the box | Built; coverage behind a switch | A surface height from the cube source |
| The scene for lighting: a state number per cell, light properties per state, occupancy and state bricks under a wrapped lookup | Built, behind switches | Color; shapes smaller than a cell |
| The light's depth: one fixed, texel-snapped orthographic box drawn into a layer of an array texture, kept between frames | Built, behind a switch | Entities in it; further layers; what samples it |
| Visibility's coarse step: a search per view over shared per-cube face links, on the CPU | Built | |
| Terrain memory: an arena of pages written through the driver, never mapped, free blocks split and joined; draws by indirect multi-draw per layer and page | Built | Compaction, and pages given back; a single write that stalls; commands written on the GPU |
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
