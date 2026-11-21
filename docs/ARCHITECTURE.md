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
| NVIDIA Turing or later, 8 GB (D11) | Mesh shaders are present on every supported card. Budgets are set against 8 GB and the slowest qualifying card, the RTX 3050 8 GB. |
| NVIDIA driver R580 or later (D20) | Every capability Quadrature uses is present. Only the gate reads the driver number. |
| One capability set for every supported card (D22) | Cards differ in speed, cache and memory, not in what they can do; quality tiers are measured on the player's machine. |
| NVIDIA-supported, vendor-neutral by design (D8) | Every stage has a core OpenGL 4.6 technique; a vendor technique is an optional one beside it, and no tier needs one. GPU memory and the driver's version are read through interfaces with a source per vendor. |
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
| `quadrature-nvidia` (separately licensed, D19) | `core`, LWJGL, its own Vulkan binding | The bridge to NVIDIA's RTX runtimes: today the GL to Vulkan handoff's measurement form (4.9) |
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
  one registered in 2025. No NVIDIA driver through 621.14 exposes the cross-vendor one, so
  mesh programs are written for `GL_NV_mesh_shader`. **Decided.**
- The gate keeps a driver floor per admitted vendor and applies it after the capability
  check. For NVIDIA it stops below the R580 branch and warns below the current production
  branch, R595 (D20). **Built:** `DriverFloor` and `DriverVersion` in `core`, the branch read
  as the leading number of the driver's version in the context's version string ("4.6.0
  NVIDIA 610.88", "4.6.0 NVIDIA 610.57.04"), the numbers and a list of known-bad builds (empty)
  in `platform.json`. A driver below the floor or on the list is refused with the remedy named;
  one below the warning branch, or whose version cannot be read, is logged and shown once as a
  toast, and the game goes on. `quadrature.test.driverFloorBranch` and
  `quadrature.test.driverWarnBelowBranch` raise either number for a test, never lower it.
- GPU memory is read through `GpuMemorySource`, with a source per vendor (D8); NVIDIA's,
  `NvxMemorySource`, reads `GL_NVX_gpu_memory_info`, and a driver that reports nothing gets
  `GpuMemorySource.NONE`. **Built.**
- Behind `-Dquadrature.capabilities=true`, `CapabilityDump` reads at startup, before the
  gate, the limits and extensions the quality tiers and the gap matrix depend on: context
  flags and shading language versions, memory, core and compatibility limits, program
  binary formats, and the mesh, subgroup, shading-rate, sparse-resource and interop limits
  where their extensions are reported. It logs them and writes them with the full extension
  list to the client's `quadrature-capabilities.txt`. It only queries; device and driver
  identifiers are written as the first 8 hexadecimal digits of their SHA-256. **Built.**
- `GL_NV_primitive_shading_rate` (Ampere and later) and `GL_EXT_shader_realtime_clock`
  are optional capabilities with fallbacks; neither marks a quality tier (D22). **Decided.**
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
| Mod geometry | A mod declares it through OrthLib; Quadrature draws it | An emissive layer, a machine's beam, a glowing particle (section 10) |

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

Later phases replace the terrain stages (Phase 2), add the emissive stage and its glow for
geometry mods declare (Phase 3), add distant terrain stages before and after near terrain
(Phase 4), and wrap the whole in the shader's stages (Phase 5). The graph's shape changes;
the stages that remain do not.

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
- **What the cache believes, kept as it changes.** Where a stage changes state the cache
  knows and its place in the frame expects back whatever the game last set, the value comes
  from `GlStateMirror`, which `MixinGlStateManager` keeps level with the cache's depth
  function and mask, face culling and polygon offset as each setter is called. It holds the
  game's values as passed, before reversed depth translates them, so they are put back
  through `DepthCalls`. The light's depth works so (section 4.7); with it, its frames'
  CPU time is all in the work they measure, where reading state back left up to 5 ms in a
  frame unaccounted for. **Decided; built.**
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
- A view along the light, toward the sun or, while the sun is down, the moon, is made each
  frame from a box fixed in the world (`LightBox` in `core`) for the frame's origin. It exists
  only while the light's depth is drawn, and what it holds is found only in frames that draw.
- Hosted drawing is not yet drawn for any view but the player's.

**The light's depth.** Built behind `-Dquadrature.shadow=true`, off otherwise. The view
along the light is drawn for depth alone into a target of Quadrature's own, before the
player's first terrain layer. Nothing samples it yet, so the player's image is the same
with it on.

- **The box** is a plain orthographic cube of a fixed side, never warped: at a reach of
  twelve chunks 544 blocks, the diagonal of the square the client holds cubes in, so every
  cube held is inside it at any hour and whichever way the player faces. It is fixed in the
  world, centered at a whole texel along the light's own axes measured from the world's
  origin, and a view through it is made each frame for that frame's origin, so a map drawn in
  one frame lines up with any later frame to the precision of one frame's matrices. A box is
  not moved with the player: it stands until the player has come a sixty-fourth of its side
  from its center, 8.5 blocks for the wide box and 3 for the near one, which the outermost
  row of cubes held, never meshed, leaves room for.
- **The light's direction** is the sun's while it is above the horizon and the moon's
  otherwise. `-Dquadrature.sun.tilt=<degrees>`, 0 unless set, leans their path toward the
  south: with east +x, up +y, south +z, `a` the game's celestial angle as an angle (0 at
  noon, a quarter turn at sunset) and `t` the tilt, the sun stands at
  `(-sin a, cos a * cos t, cos a * sin t)` and the moon opposite. It leans the directions
  published and the box the map is drawn in; the game draws its own sky without it.
  `SunPath` in `core`.
- **The target** is an array texture of 24-bit depth, 4,096 texels a side, 64 MiB a layer,
  and a scratch texture of as many layers, both counted in the shading pool. Both are made
  when a world is attached, behind the screen that waits for it, and put through the work a
  first frame does to them, every layer cleared through the framebuffer and copied, with a
  wait for the driver: a driver puts off taking a texture's memory until its first use, which
  otherwise fell in the first frame of play (42 ms of CPU; 8 ms now, at about 80 ms behind
  the loading screen). A texel of the
  wide box is 0.13 of a block. The published texture can be sampled through either of two
  sampler objects: compared with a reference and filtered, or as the depth itself.
- **The near layer**, behind `-Dquadrature.shadow.near=true` (off unless set), adds a box
  192 blocks across, centered on the player and moved to a whole texel of its own, 0.047
  of a block a texel. Along the light it is as deep as the wide box, 544 blocks, so its
  side toward the light is the wide box's: what stands far toward a low light, outside
  the 192-block square, still casts onto the ground in it. 24 bits of depth over 544
  blocks are steps of a thirty-thousandth of a block. It is layer 0 and the wide box
  layer 1, 128 MiB in all; with it off the wide box is the only layer, layer 0. Each
  layer's side across and its depth are published (`boxSide`, `boxDepth`), and the frame
  block's `qShadowScale[i]` holds blocks a texel in x and the depth's span in y. Each layer
  has a box and a plan of its own (`ShadowLayers` and `LightLayerPlan` in `core`) and is
  drawn by the same rules, measured from its own box.
- **Drawn by tiles.** A layer is 16 by 16 tiles of 256 texels. No frame draws a whole layer:
  - A mesh placed, replaced or released makes the tiles its cube may cover dirty, the cube
    taken a block wider each way since models can reach past their cell (the game moves
    flowers and grass a quarter of a block aside). They are cleared through the scissor and
    every cube that may cover them is drawn whole, in place. A new alpha threshold for cutout
    faces, read where the game sets it, makes every tile dirty.
    What such a cube draws beyond the tiles is what is there already, the same geometry at
    the same depth.
  - When the light has turned more than 0.1 degree since a layer's box was made
    (`quadrature.shadow.turn`, in degrees), the player has come too far from its center, or
    the reach changes, a new box is made where the player stands and drawn tile by tile into
    the scratch, nearest its center first. Once every tile is drawn the scratch layer is
    copied over the published one, which stays whole and published until then. A mesh that
    changes meanwhile makes its tiles due in the new box too, and the published layer's own
    dirty tiles wait, since the box that replaces it holds the change; after a teleport they
    would cover the whole of a box about to be replaced. A box being drawn is begun again
    where the player has gone once they are an eighth of its side from it.
  - A frame spends on a layer's tiles at most a share of what covers the whole layer,
    counted in cubes: a new box is drawn over about 32 frames (`quadrature.shadow.spread`),
    and dirty tiles are drawn nearest the center first within the same share a frame. At 8
    frames, 565 frames of a forest tour with both layers passed 2 ms of the light's GPU time,
    at 32 frames 10 (VERIFICATION, the light's depth by tiles): a box then takes about a
    quarter of a second at 120 frames a second to be whole, which nothing that reads the
    light's depth yet shows.
  - A frame with nothing due draws nothing and searches nothing.
- **What is drawn:** the three opaque layers of every meshed cube in the box, from the
  same pages and by the same indirect draws as the player's view, each cube under the
  same composition of matrices. A fragment of a cutout layer is dropped where the block
  texture's alpha fails the game's own threshold, read where the game sets it. Faces are
  not culled by facing. Translucent terrain is left out.
- **Which meshes are resident** does not follow the player's frustum: a mesh is held for
  every loaded cube that has its six neighbors, so what the light sees and the player
  does not is there to draw. A view through a light's box has no reach of its own: every
  mesh held inside the box is drawn, whichever cube the player stands in, so what a layer
  holds depends on the meshes held alone and a mesh placed, replaced or released is the
  only change that can make its tiles wrong. Terrain beyond what the client holds, and the outermost row
  of cubes it holds, which is never meshed, cast nothing.
- **No bias** is applied but a polygon offset, none unless
  `quadrature.shadow.polygonOffset=factor,units` is set. A sampler biases as it sees fit.
- **Published each frame** (`ShadowMaps` in `core`, from `TerrainHook.shadowMaps()`): the
  texture and the two samplers, each layer's matrix from a position relative to the
  frame's origin to the layer's clip space, made that frame from the layer's box, the frame
  before's matrix and origin, each box's side and depth, the direction toward the light of
  the moment and, for each layer, the direction its box was made along, which trails it by
  up to the turning angle; and two generations. One changes whenever the texture's texels
  or a layer's box change. The other changes only when the content can differ apart from
  the light's own motion: tiles were drawn again for changed meshes, or a box for another
  place was published. Until a layer's first box is drawn whole the layer is clear, so
  nothing is in shadow.
- **Entities move every frame**, which a kept map does not allow for. The plan, of which
  nothing is built: the terrain's depth stays one layer, drawn on the conditions above,
  and entities go either on top of a copy of it made each frame or into a second layer
  holding them alone, which a sampler compares against as well, taking the nearer.

Not covered yet: entities and block entities in a light's view, which reaches into hosted
drawing.

### 4.8 Rendering below output resolution

Built behind two switches, both off unless set: `-Dquadrature.scale=<0.5 to 1>` draws the world
at that fraction of the output's resolution, and `-Dquadrature.taa=true` turns on the temporal
pass and the sub-pixel offset. LOW is `scale=0.67` with the temporal pass, MEDIUM `0.77` with it,
HIGH `taa=true` alone (12.1). With both off nothing is drawn, read or changed (D23; research R4,
7b). `TemporalScaler`, `ScalerShaders`, and `RenderScale`, `Jitter` and `Reprojection` in
`core`.

- **Where the world is drawn.** In a framebuffer of the game's own kind and formats,
  `round(side * scale)` pixels each way, that stands in for the game's from the start of the
  world pass to the composite: `Minecraft.getFramebuffer()` returns it (`MinecraftScaleAccess`)
  and `displayWidth` and `displayHeight` report its size. The game and every hosted draw then
  work on one consistent smaller picture, a mod's own post-processing through framebuffers of
  the game's size included, and so do Quadrature's own stages and targets; no viewport is
  rewritten. The world's projections keep the output's shape (`MixinEntityRendererScale`), so
  rounding the drawn size to whole pixels stretches nothing once scaled back. Rewriting the
  viewports of display size inside the pass was built first and failed on the lab's own pack:
  GTCEu Reloaded's bloom copies the game's picture into framebuffers of its own and draws it
  back every frame, and each of those display-sized draws was shrunk again (VERIFICATION, own
  temporal anti-aliasing and scaler). Each is put back only where it still holds the stand-in: a
  resize during the pass sets the display's new size, which stands, the game's own framebuffer
  takes it, and that frame makes no picture. Framebuffers of the game's kind made while the
  scaler is on are counted in the timing line, and the first thirty named with the class that
  asked for them (`MixinFramebufferScale`), so a mod remaking its framebuffers each frame shows.
- **The offset.** Each frame the projection of every world view the game builds is moved by a
  sub-pixel offset of the drawn picture, from the Halton sequence in bases 2 and 3, 8 offsets a cycle
  (`quadrature.taa.phases`). The clip-space translation is multiplied in just before each
  `gluPerspective` call of the world, the sky and the clouds (`MixinEntityRendererScale`), so it
  stands on the left of the perspective, inside reversed depth's reversal, with which it
  commutes. Entities, particles, block entities, weather and own terrain draw under it. The
  hand's projection is never offset.
- **Glowing entities' outlines.** The game draws them into a framebuffer of its own, binding it
  without setting the viewport, runs its outline shader group over it and binds the game's
  framebuffer back, again without a viewport; after the world pass it lays the result over the
  picture. The shader group's passes draw in pixels of their own targets through a projection
  made from the framebuffer the group was created with, the game's own, so its targets stay at
  the output's size. While the drawn picture stands in, the viewport is that framebuffer's whole
  size and the projection loses the frame's offset once it is bound (`MixinRenderGlobalScale`),
  so the outlines are drawn at the output's size and in place without shimmering; the drawn
  viewport and the offset return when the game's framebuffer is bound back, so block entities,
  drawn after the outlines in the same stage, stay in the drawn picture.
- **The view.** Where the frame enters opaque terrain, the driver's projection and model-view
  are read, the offset is taken out of the projection, and the viewer's interpolated position
  is kept in double precision as the frame's origin. Before entities draw, the depth of what
  opaque terrain left is copied (`glCopyImageSubData`, renderbuffer to a texture of its format).
- **The composite** runs as a stage of its own, `scaler`, at the boundary in front of the mods'
  last world drawing (`render last`): the game's framebuffer and display size are put back, and
  the picture is made whole at the output's size before anything there reads it, the lab's
  capture included. Every pass is a compute program writing images, so blending, the alpha test,
  culling and the masks hosted code leaves set do not touch it:
  1. the drawn depth is copied to a texture;
  2. *motion*, at the drawn size, into a target of two 16-bit floats a pixel: one matrix,
     composed in double precision from the two frames' views and the origin's movement, takes
     this frame's clip space to the previous frame's, and each pixel's depth gives where its
     point stood, minus where it stands now, in pixels without the offset. Whether that is the
     convention DLSS reads, so the Vulkan bridge (D21) can take the target as it is, is for PLAN
     item 7 to confirm against NVIDIA's programming guide. A point behind the previous eye gets a
     motion past any picture;
  3. *temporal*, at the drawn size, into one of two 16-bit float histories: this frame's
     color filtered at the pixel's center from its 3 by 3 neighborhood by each sample's distance
     after its offset (a Gaussian of half a pixel's deviation); the history read where the
     nearest point of the neighborhood stood, by a Catmull-Rom spline in nine bilinear reads;
     moved toward the center of the box of the neighborhood's minimum and maximum in YCoCg until
     it lies inside it (variance clipping is not used, D23; research R4, 7b.1); and blended
     with at least `quadrature.taa.blend` (0.1) of this frame, rising
     toward a third with motion (`quadrature.taa.moving`, a sixteenth a pixel of motion) and to
     `quadrature.taa.reactive` (0.5) where the depth changed after opaque terrain, both weighted
     by the inverse of their luma. History is dropped after a teleport (8 blocks in a frame),
     another dimension, new targets, or a frame without a composite;
  4. *scale*, at the output's size, into the game's picture: the 4 by 4 texels around each
     output pixel's source point under the separable Keys cubic kernel; a boost of
     `quadrature.scale.sharpness` (0.2) of the difference from a bilinear read; held inside the
     four central texels' range. No kernel is steered along edges (D23; research R4, 7b.2). A
     work group of 8 by 8 output pixels reads the 12 by 12 source texels it reaches once into
     shared memory. At scale 1 a copy takes its place. A barrier for framebuffer access, texture
     fetches and texture updates, what follows, makes the picture's writes visible;
  5. the drawn depth stretched over the game's whole depth buffer by a nearest blit, for what
     mods draw last against the world;
  6. the offset taken out of the projection on the stack, so what is drawn after it, the hand
     and the interface are drawn at the output's size without it.
- **State.** Read on units 4 to 8 through sampler objects and written through image unit 0;
  uniforms at fixed locations set with `glProgramUniform`. On the way out the units, the image
  unit and the program are emptied and the game's framebuffer and viewport put back; nothing is
  read back from the driver to find what to restore. `-Dquadrature.taa.check=true` reads the
  motion target back every 600 composites and logs its mean and greatest length.
- **Memory**, at 2560 by 1440 and scale 0.67: the drawn framebuffer, two histories, motion and
  two depth copies at the drawn size, 1715 by 965, 36 bytes a pixel, 56.8 MiB, in the reserve's
  pool (12).
- **What has no motion** is held by the box: entities, particles, falling weather, the clouds'
  drift, animated textures and translucent surfaces, whose pixels keep the depth behind them.
  With `-Dquadrature.taa.entityMotion=true` entities have motion of their own (research R4,
  7b.5): each frame every entity within 96 blocks that moved or turned since the frame before is
  given its box, grown by a quarter block, and the matrix taking a point on it to where its
  position and yaw had it the frame before; the motion pass moves a pixel whose depth changed after
  terrain and whose point lies in a box with that entity, and projects it by the previous view.
  A limb's own swing, particles and block entities are left to the box. Verified to move a
  walking golem's pixels and nothing else, to read history half as far from the frame as the
  camera's motion would there, and to bring a moving body 0.22 to 0.45 dB nearer the native
  picture (VERIFICATION, entities' own motion).
- **Not covered:** a mod that keeps the game's framebuffer from before the pass and draws into
  it there; anaglyph, which is drawn at the output's size.

### 4.9 Handing the frame to another API

The frame stays OpenGL; where DLSS is chosen, it is evaluated by Vulkan on images the two
APIs share (D21), in a separately licensed module (D19). The handoff is built in measurement
form behind `-Dquadrature.handoff=measure` (PLAN, capability and tier work, item 7), with a
stand-in where DLSS would evaluate or, with `-Dquadrature.handoff.evaluate=dlss`, DLSS Super
Resolution itself; the bridge itself is not. Research R1, 1.9 has the rules it follows.
**Built, behind a switch.**

- **The seam.** `Handoff` in `core`: it opens beside the current context, runs one round trip
  a frame at the end of the world pass, after the hand and before the interface, where DLSS's
  guide places it (early in post-processing, before tone mapping), hears when the client's
  world changes, and closes. `HandoffHost` finds an implementation through `ServiceLoader`;
  Quadrature holds none, and without the module the switch logs that it is missing and the
  frame is unchanged. The host's hook runs after the frame's stages are timed. **Decided.**
- **The module.** `quadrature-nvidia` compiles against `core` and Cleanroom's LWJGL core and
  OpenGL binding, and embeds LWJGL's Vulkan binding at the LWJGL release `platform.json`
  names (`cleanroom.lwjgl`), which it compares at startup with the release the game loaded.
  Its JAR lies beside Quadrature's among the mods; Foundation, the game's class loader,
  defines `org.lwjgl` classes itself, so the embedded binding links against Cleanroom's core.
  Where Forge has not put the JAR on the class path, the host adds it. No NVIDIA SDK material
  is in it.
- **The device.** A Vulkan 1.3 instance and the physical device whose device and driver UUIDs,
  and LUID where both sides report one, match the context's (`DeviceIdentity`); every device
  is logged with why it matches or not. One queue, from a family with graphics and compute by
  default. Win32 external memory and semaphores are required, timeline semaphores enabled,
  calibrated timestamps and the memory budget used where present.
- **The images.** Color (`RGBA16F`), depth (the game's depth format) and motion (`RG16F`) at
  render resolution, and the output (`RGBA16F`) at output resolution: each a Vulkan image with
  every usage its format supports, the mutable-format bit and optimal tiling, in a dedicated
  allocation exported as an NT handle, imported by OpenGL as a memory object flagged
  dedicated and a texture over it, with a framebuffer of its own; the handle is closed at
  once. When the output size changes, both APIs are waited for and the images are made again.
- **The round trip.** OpenGL signals a timeline semaphore, naming the layouts the images are
  left in; Vulkan's batch waits for it with all its commands, acquires the images from the
  external queue family, evaluates, releases them to the external family and signals the next
  value; OpenGL waits for that value on the GPU. One semaphore and rising values serve both
  directions; two binary semaphores are an option. Batches are recorded once per set of images
  and slot of a six-deep ring, and a slot is reused after its fence.
- **What the measurement adds.** Without the scaler the game renders at output resolution, so
  OpenGL fills the render-resolution inputs with scaled copies from the game's framebuffer,
  clears the motion image (the motion vectors of 4.8 exist only beside the scaler) and copies
  the output back.
  In the bridge, OpenGL draws into the shared images directly and the post effects read the
  output, so these copies are timed apart from the handoff. The parts take turns in segments
  of 120 frames (nothing, the round trip alone, with the stand-in, the copies alone, all of
  it); OpenGL and Vulkan timestamps are put on the client's clock; a stamp written into the
  motion image and carried back by the stand-in checks every 600 frames, and after each world
  change and resize, that the output came from that frame.
- **DLSS through NGX.** NGX's entry points come as a static library in NVIDIA's SDK, so the
  module has a shim of its own, `quadrature_ngx.dll` from `src/main/native/ngx_shim.cpp`, which
  links it and offers ten flat C functions; `Ngx` calls them through the Foreign Function and
  Memory API. `./gradlew :quadrature-nvidia:ngxShim -PdlssSdk=<folder>` builds it with clang-cl
  into the module's `build/native`; the shim and NVIDIA's runtime libraries are loaded from
  paths the run names (`shim`, and `dlssPath` for `nvngx_dlss.dll`, by default the shim's own
  folder), never from the JAR. Before the Vulkan instance and device are made, NGX is asked
  which extensions DLSS needs, and they are enabled beside the handoff's, with buffer device
  addresses, which NGX uses and its extension list does not name; NGX then starts on
  the handoff's device with the project's identifier as an engine of its own, handed the
  loader's two lookups, and the handoff stops with NGX's reason where DLSS is not available.
  The feature is made for each set of images, in the quality mode and render preset the
  options name (`quality`, `preset`), with render-resolution motion and, where `depthInverted`
  says so, reversed depth. The DLSS batch is recorded for every submission, since NGX records
  that frame's evaluation into it: it acquires the images as the stand-in's batch does, places
  two more timestamps around NGX's commands, then runs the stand-in on the output's first
  pixel alone, which carries the stamp the check reads, and releases them. A world change
  starts DLSS's history afresh. NGX is shut down after the queue is idle and before the device
  goes.
- **Beside the scaler, DLSS takes the scaler's picture.** With the scaler's switches on (4.8)
  the world is drawn into a smaller framebuffer standing in for the game's, so at the end of the
  world pass the game's framebuffer already holds the scaler's output. A handoff that evaluates
  DLSS (`evaluates`, answered from the settings before anything opens) is then called from the
  scaler's composite instead (`Handoff.Evaluation`), after the motion pass: the drawn picture,
  its depth renderbuffer, the motion target, the frame's sub-pixel offset, whether history
  applies, whether depth is reversed, and the game's framebuffer as the output. It copies the
  three inputs into the shared images at the size they were drawn, evaluates with the offset in
  render pixels (`jitterSign` multiplies each axis, 1 unless set), copies DLSS's output into the
  game's framebuffer, and the scaler's temporal and scale passes do not run; the drawn depth is
  stretched over the game's and the offset taken out of the projection as before. The motion
  target already holds what DLSS reads: where each pixel's point lay a frame earlier minus where
  it lies, in render pixels, without the offset, and since both APIs see the same rows, the
  numbers carry over unchanged. DLSS's mode follows the scaler's ratio (Quality near two thirds,
  Performance at one half, DLAA at one) unless `quality` names one, and the handoff stops with
  NGX's accepted range where the drawn size lies outside it. Once the handoff makes the picture,
  the scaler cycles through eight offsets per output pixel, 8 over the scale's square (18 at
  0.67, 32 at 0.5), unless `quadrature.taa.phases` is set. No stamp is carried: the output's
  first pixel is the picture's. The scaler's own history starts afresh if it makes the picture
  again. A handoff that measures the game's framebuffer, the stand-in, is turned away without
  opening, with a message, and the scaler runs; so is any handoff when the scaler runs without
  its temporal pass, which makes the offset and the motion.

Measured on the RTX 3060 (VERIFICATION.md, the handoff from OpenGL to Vulkan): the round trip
alone costs 0.36 to 0.37 ms of GPU time at P0 at 1080p and 1440p, about half each way, and the
client 0.06 ms a frame; the four images take 32 and 57 MiB, the Vulkan device 5 to 6 MiB more.
DLSS in Performance mode (VERIFICATION.md, DLSS through the handoff) takes 1.54 and 2.20 ms of
its own at 1080p and 1440p with preset K and 2.55 and 4.53 ms with preset M, at P0; the round
trip around it adds 0.30 to 0.34 ms.

Not built: the bridge as a stage with declared resources in the pass graph, OpenGL drawing
into the shared images directly in place of the copies, a texture LOD bias for the render
scale, and the motion vectors DLSS needs from every draw (D21): the scaler's motion is the
camera's, from depth, so what moves on its own (entities, particles) carries none. DLSS takes
the game's frame as LDR, which needs no exposure value.

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
- **Room for every cube stays.** With the lookup and occupancy bricks the buffers take
  77.8 MiB, inside the 128 MiB the shading work allows for materials and visibility. The
  most slots in use at reach twelve (VERIFICATION, the light scene's slots by place): 6,406
  in forest, 9,262 over reef, kelp and shallows, 5,377 among fins, tower karst and tsingy
  standing in for a built-up area, and 11,632 in a cave system, where every view from 150
  blocks below the sea down to a lava river holds 8,656 to 10,304 of the box's 15,625 cubes
  as bricks. One and a half times the caves' figure is
  more than the box, so the buffers keep room for every cube. The most slots in use is
  published and logged.
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

### 5.2 Rays through the scene

Built behind the scene's buffers: a compute program that walks rays through the lookup and
the occupancy bricks of 5.1, the operation indirect light and far shadows both reduce to
(research R4, 7.8). `RayTrace` is its entry point, `RayTracer.get()` the one instance,
`BrickTrace` in `core` the reference it is checked against.

- **Rays in, results out, time later.** A ray is 32 bytes: its origin relative to the
  lowest corner of the box's origin cube, its length, and the inverse of its direction.
  A result is 16 bytes: the distance at which the ray entered the cell that ended it, that
  cell, how it ended and how many nodes it visited. A trace takes the rays in one buffer
  and writes the results into another between two GPU timestamps, read in a later frame
  without waiting, and puts back every binding it changes. It also keeps a bit for each
  state that blocks light, 8 KiB, sent again when a state is first met.
- **Two sizes of node, from the scene's own layout.** A cube whose lookup entry names one
  state that does not block is passed whole; in a cube with a brick each cell is tested
  with one 32-bit load. The bricks' coarser bits are not read: a walk that also passed
  blocks of two cells a side by them was built first, and set aside on 2026-11-19 by the
  user's decision after the patent search of research R4, 7.8a. Leaving a node, the nearest
  of its far faces is crossed, x before y before z on equal distances.
- **To the bit.** Every distance is worked out afresh as `(face - origin) * inverse` from a
  whole-numbered face, marked `precise`, and never accumulated; after a node is passed the
  ray's cell on the other two axes is found by the same crossing test a walk of single
  cells makes. The walk by nodes therefore ends in the cell, at the distance to the bit,
  where a walk of single cells ends, and since the program reads the inverse direction the
  CPU reads back, the GPU's results can be compared with `BrickTrace`'s word for word.
- **How a ray ends:** in the first cell that blocks light (at distance 0 if it began in
  one); at the first cube nothing is known of, which never reads as empty; on leaving the
  box; where the next face lies past its length; or after 4,096 nodes.
- **Beyond radius 12.** The lookup is sized by the box, `(2r + 1)²` by `(2v + 1)` cubes,
  and made anew when the reach changes, so radii 16 and 20 need no other scheme: a cell is
  packed in ten bits an axis and a slot in 24, enough for a reach of 31 cubes. What grows
  is the room taken for a brick for every cube of the box: at a vertical radius of 12 the
  lookup and bricks take 8.6, 15.0 and 23.1 MiB at radii 12, 16 and 20, with at most 5,863,
  10,969 and 17,401 bricks in use over the dwell tour. The trace's rate barely depends on the
  radius (VERIFICATION, rays through the scene).
- **Each cell's state is what outgrows the budget**, not the bricks. With room for every
  cube, the states of 5.1 add 69, 120 and 185 MiB at radii 12, 16 and 20 (126 MiB at radius
  20 with a vertical radius of 8), against the 128 MiB the shading pool allows for materials
  and visibility. Sized to one and a half times the most bricks seen at radius 20 they still
  take about 115 MiB, the lookup and bricks besides. MAX keeps the states within radius 12
  and the bricks alone out to 20: 69 MiB, or 39 MiB sized to the bricks seen, and 23 MiB of
  bricks. The trace needs no state at all. **Default;** only forest has been measured.
- **What a batch costs** on an RTX 3060: two million probe rays, whose directions spread
  over the sphere, take 1.1, 4.9 and 12.2 ms at lengths of 16, 64 and 256 blocks; two
  million rays toward a low sun, side by side and parallel, 1.4, 3.6 and 5.4 ms. Incoherent
  rays pass well under half as many nodes a second as coherent ones. Batches of 65,536 rays
  leave the card partly idle. The earlier walk, which also passed blocks of two cells a side
  by the coarser bits, visited 57 to 75% of the nodes; probe rays took 0.77 to 0.93 times as
  long with it, rays toward the sun 0.82 to 1.27 times (VERIFICATION, rays through the scene).
- **The CPU never reads a buffer a trace reads.** A buffer read back is kept by the driver
  in host memory for a while, and probe rays traced from it took three to twelve times as
  long, coherent rays barely longer. The benchmark reads back only staging buffers filled by
  copies on the GPU; the scene's buffers so used trace as fast as a copy the CPU never sees.
  The light scene check reads them back directly and slows a trace that follows it.

**For the tiers** (12.1), measured on the RTX 3060, a card of the lowest speed cluster:

| Tier | Indirect light | Its cost, whole update | Far shadows |
|---|---|---|---|
| LOW | An ambient term | | None |
| MEDIUM | 16x8x16 probes, 32 rays of 64 blocks, all every frame | 0.22 ms | None |
| HIGH | 32x8x32 probes, 64 rays of 64 blocks, a quarter of the probes a frame | 1.45 ms, 0.36 ms a frame | None |
| MAX | Two cascades of 32x8x32 probes, 128 rays: 64 blocks for the one 4 blocks apart, 256 for the one 16 apart, a quarter a frame | about 8.5 ms, 2.1 ms a frame | A march toward the light from pixels the near layer of the light's depth does not cover, inside the box: 2.6 ms a million rays at a low sun |

MEDIUM's cost was measured at radius 16. Rays of 256 blocks cost 2.4 to 2.5 times those of
64 for probes. The bricks reach only the
near box, 320 blocks at radius 20, so far shadows past it need distant terrain's own data
(section 8). **Default;** the tier benchmark (D22) places each card and scales these.

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

- Builds run on Cubature's build pool (Quadrature starts no threads). A build's priority
  is the squared distance from the camera to the cube, doubled for a cube outside the
  view, and every waiting build is given its priority anew when the camera enters another
  cube or turns 30 degrees. A waiting build holds no snapshot and reads the cube as it is
  when it starts; one whose cube's blocks or light change after its snapshot is dropped by
  the pool, and the change hands in another. A result older than what its cube already
  shows is dropped. **Built.**
- A mesher belongs to one build thread at a time. What meshers share is numbered under a
  lock that each thread's own cache keeps it from taking for what it has met: the atlas's
  rectangles and the material table's rows. States for the light scene are handed back
  with each result and defined on the context thread. **Built.**
- A cube the source says holds only air is settled without a snapshot or a build.
  **Built.**
- **A change reaches a touching cube only where its last build read it.** A build reads
  the world through a wrapper of its snapshot that notes, for each of the 26 cubes around,
  whether it read their blocks (states, block entities, biomes) and whether it read their
  light. The game's renderers read the light around a block only for a face they draw, so a
  hidden block reads its face neighbors' states alone and nothing of its edges and corners.
  The source's own changes of a touching cube carry the whole cube and no side, so they are
  not followed; each cube's own arrival, departure and bounded change are, by the source's
  rule for which touching cubes a change reaches (`TouchingSides` in `core`). A touching
  cube is rebuilt if its last build read the changed one (only its light, for a change of
  light alone), if it has no build yet, or if one is in flight; all air reads nothing.
  Rebuilds that drew what they replaced fell by three quarters, and builds by a third while
  the world generates around the player, with the pictures unchanged (VERIFICATION,
  side-aware rebuilds). `-Dquadrature.terrain.neighbors=all` follows every touching change
  as before. **Built.**
- A build copies what it made into arrays of its own, so a result the pool drops leaves
  nothing to free; it allocates nothing on the heap per face. The context thread copies a
  result's vertices into one native staging buffer as it places them. **Built.**
- The context thread spends at most 1 ms a frame placing finished builds into GPU memory
  and 0.5 ms sorting the cubes that changed. **Built.**
- A mesh put in place with the shape of the one it replaces keeps the light's depth as
  drawn: the shape follows the block versions of the cube and of the six cubes that share
  a face with it, so a change of light alone redraws no tile. **Built.**

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
   **Measured, not built:** the pyramid and the test exist as a measure that leaves nothing
   out (`quadrature.terrain.occlusion=measure`, `DepthPyramid` in `core`). Against the frame's
   own terrain they find 34% of the player's drawn vertices hidden over the forest tour and 45%
   over the cave tour, and 28% and 56% of the light's wide box hidden from the light
   (VERIFICATION, what a depth pyramid would leave out).
   **Built in first form**, with steps 3 and 4, behind `quadrature.terrain.cull=gpu` (`GpuCulling`):
   the client thread's commands sorted on the GPU into the two phases, the counts read by
   `glMultiDrawArraysIndirectCount`, the frame's depth blitted into a texture of its own. Every picture
   unchanged; on the RTX 3060 at 2560 by 1440, radius 16, it costs about 0.3 ms more than the drawing it
   saves, breaking even only where four fifths of the terrain is hidden, so it is off by default
   (VERIFICATION, culling on the GPU).
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
  changes, no oftener than every twenty frames, and then it is brought up to date and not
  filled anew: only the cubes that arrived, left or were meshed since the last time are
  told of, and only their columns are judged again. It is filled anew when the box is
  another, and every 64th time as a guard.
- *The highest cubes held are never meshed*, for want of a neighbor above, so nothing
  would be known to be air and every column would read as having no surface. With
  coverage on, a cube that cannot be meshed is therefore asked whether it is empty, and
  an empty one counts as air.
- *Where the cube source says more, it is used.* The host looks once for the cube
  source's surface heights, surface version and cube content by name. Where they are
  present a column's band comes from the heights, a column is judged by the band's cubes,
  and emptiness is one look-up; where they are absent the scan above stands and emptiness
  is asked of a snapshot. The cube source pinned has them. A column's heights are asked
  for when a cube of it is meshed, beside the mesh, and kept until a cube of the column
  changes; the pass itself asks only for a column left without an answer, the nearest
  first, and stops asking after 0.15 ms. One ask is a call into the cube source and is
  not bounded by this.
- *What the rule cannot tell:* a cube that is solid and wholly enclosed draws nothing and
  reads as air, which matters only above the surface, where there is none; and a roof
  over a column, an overhang or a tree's crown, is taken for its surface. A height for
  each column from the cube source settles both.

**The walk over a model's quads.** The game's dispatcher writes a block's vertices and
keeps nothing of the quad they came from. With face records, on unless
`-Dquadrature.faces=false`, or `-Dquadrature.faces.check=true` the mesher walks a model's quads itself, in the game's
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
Per-cube back-to-front order is established on the CPU from cube positions, and faces
within a cube are sorted again as the eye moves. **Default.** A weighted blended method that
needs no sorting is the alternative if sorting cost shows up.

Built (PLAN item 12e), and on under own terrain; with `-Dquadrature.terrain.translucent=game`
the game meshes and draws the layer as before:

- **Meshing.** Each build meshes the translucent layer into a buffer of its own beside the
  three opaque ones, every block through the game's dispatcher, so its quads are the game's.
  The game's chunk rebuild leaves the layer out by 12a's rule (`GameChunkRebuilds.leftOut`).
- **Holding.** A cube's quads stay on the client as meshed, with each quad's center, and in
  a buffer of the cube's own on the GPU in the order last sorted (`TranslucentCube`).
- **Order.** Quads are ordered as the game orders a render chunk's: by the squared distance
  from the eye to the mean of a quad's four corners, the farthest first, ties in mesh order
  (`QuadOrder`). A cube is sorted when first seen, and again once the eye has moved more
  than a block from where it was last sorted, the nearest such cubes first, at most 15 a
  frame (`quadrature.terrain.translucentSorts`). Quadrature sorts from the camera the frame
  is drawn from; the game sorts from the viewer's position at the last tick.
- **Drawing.** At the game's translucent pass, under the state the game sets for it, the
  cubes the search found are drawn in the reverse of its order, the farthest first, each by
  one draw of its buffer through the forward program. With the fade on that program fades
  them too; that combination has not been run.

### 6.6 Targets for shading

Behind `-Dquadrature.gbuffer=true`, with faces and reversed depth on, each opaque layer is
drawn a second time into four targets of 8 bits a channel and a depth of Quadrature's own,
the size of the window, from the commands and records the player's view already made. No
lit color is written.

| Target | Color | Alpha |
|---|---|---|
| A | world normal as n * 0.5 + 0.5 | perceptual roughness |
| B | sky light and block light, each 0 to 1 over the game's 0 to 15; in green the emission strength of the texel's kind and color | 0.375 on terrain, 0 where nothing was written |
| C | the square root of linear albedo after tint; the texel is decoded by the exact sRGB curve | 1 for a conductor |
| D | reflectance and subsurface, or for a metal its colored reflectance, the texel's linear albedo after tint (D18) | the game's vertex occlusion |

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
The list is read as a properties file is: a line that ends in a backslash goes on in the
next, and a key ends at an equals sign, a colon or a space. Where the folder has a
manifest that names the list, the list read must have the hash the manifest gives, and
the list shipped is used where it has not. A list that gives fewer names than it states,
or than its revision is known to hold, is logged as `MATERIAL KINDS FAILED`: read short,
it would give most blocks the wrong surface and nothing else would show it.
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
| 120, 121 | dim and bright emitters; an unnamed block that gives light is 121 from light 12, 120 below | nothing of their own in the table |
| 130, 131 | glass, ice | roughness 0.12 |
| 140 | metals | roughness 0.32, conductor |
| 199 | plain | the defaults, and no emission whatever light is given |

The defaults are roughness 0.85 and reflectance 0.04. The table's emission byte is the
light a state gives over 15, whatever its kind, but for lava and plain states; it is kept
for readers of the table and is not what the targets hold.

**Emission in the targets.** Target B's green is worked out for each texel where the
targets are written, from the state's kind and the texel's linear albedo after tint, by
`KindEmission` and the same rule in GLSL. With m the greatest channel of the albedo:

| Kind | Emission |
|---|---|
| 101 lava | 0.10 |
| 120 dim emitter | 0.65 * smoothstep(0.20, 0.70, m) |
| 121 bright emitter | 0.90 * smoothstep(0.12, 0.65, m) |
| any other | 0 |

The target stores it to the nearest of 255.

**What is published.** `TerrainHook.shadingTargets()` gives the framebuffer, the five
textures, their size and a generation that moves when they are made anew.
`TerrainHook.materials()` gives the table and `TerrainHook.material(state)` a state's
number, filling its row if near terrain has not met the state.
`TerrainHook.mainView()` gives the player's view the frame's terrain was drawn through.
`TerrainHook.materialsBuffer()` gives the storage buffer the table lies in, brought up to
the table's version when asked. `TerrainHook.frame()` gives the frame's index, a count
of the world passes own terrain has drawn, and `shadingTargets().drawnFrame()` that
count when the targets were last cleared and drawn; `generation()` moves only when the
textures are made anew. The targets are cleared and drawn once in a world pass, so once
a frame but for the game's two-colored stereo picture, which draws the world twice.

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
are `NearFade`'s, in `core`. Near translucent terrain drawn by the game does not fade. It is built and has not been exercised with distant terrain.

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

### 6.7 The game's chunk system

In a cubic world under own terrain Quadrature takes over the game's chunk system (D24, PLAN
item 12g; `GameChunks`). It is decided per world when the game loads its renderers for it,
before the cube source attaches: own terrain asked for and the world cubic.
`-Dquadrature.terrain.gameChunks=keep` keeps the game's system running beside own terrain.

- **What stops.** The game's terrain setup does nothing after its own reaction to a changed
  view distance, and its chunk updates nothing at all, so it never positions, searches,
  rebuilds or uploads a render chunk, and its list of render chunks to draw stays empty. Its
  grid holds one render chunk, and its rebuild dispatcher one buffer set where it would make
  ten a core (`MixinRenderGlobalChunks`, `MixinViewFrustumChunks`).
- **Entities and block entities.** The game's entity pass keeps its own frame, outlines,
  global block entities and breaking overlays; its two loops over the empty list find
  nothing, and at the same two points Quadrature's take their place over the cubes the
  player's view saw, nearest first, reading each cube's entities and block entities from the
  cube through Cubature's API, under the game's own tests (`EntityPass`). The view's search
  lists every loaded cube it reaches, empty ones included, and never leaves out one the
  game's search would reach. Block entities whose renderer draws them from anywhere go to
  the game's set of global block entities as their cubes are placed and forgotten.
- **Layers other mods add.** A layer another mod adds to the game's list after the
  translucent one is asked of each block, meshed through the game's dispatcher with the
  layer set, as the game's rebuild would mesh it, into a buffer of the cube's own, and drawn
  for the seen cubes nearest first, unsorted, wherever the game's renderer is asked to draw
  that layer, into whatever framebuffer is bound (`AddedLayerCube`). GTCEu: Reloaded's bloom
  layer is drawn so, inside its own wrap of the game's translucent draw. At most three.
- **Mods that hook the rebuild.** An event or a wrap of a call inside the game's rebuild no
  longer runs (PLAN item 12, mods that add to the game's chunk meshes).
- **A game-drawn run.** The game's system draws a cubic world only through Cubature's
  integration with it, which builds of Cubature since this takeover do not carry;
  `-Dquadrature.terrain=game` and `gameChunks=keep` stop at loading with a message unless
  Cubature's reference build, the last with that integration, is installed.

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

### 9.1 A stage handed over in a folder

Built behind `-Dquadrature.stage=true`, with the folder named by
`-Dquadrature.stage.dir`; it needs the targets for shading. The folder is data: a
manifest, program sources, assets, and files that stand for the host's inputs and for
what the stage should write from them.

- **The manifest** (`StageManifest` and `StagePlan` in `core`) lists every file with its
  size and hash, and a file is read only by a plain name the manifest gives and only if
  its size and hash are the manifest's. From its fifth version it says what is to be
  run: the textures it asks the host for, each with how it is sampled; the assets it
  brings; its targets, each with a format, a divisor of the window's size and whether it
  is kept from frame to frame; its passes in order, each with a program, where each
  sampler reads from and where each output goes; and checks, each an output of a pass set
  beside a file under a tolerance. A target with divisor n is ceil(width / n) by
  ceil(height / n). A manifest of a version that is not known is refused, and so is
  anything in a manifest the host does not do, by name: a format, a filter, a uniform
  block other than the frame's, vertex buffers, depth or blending in a pass, a size of
  the frame's block other than the one the host writes.
- **A sampler's source** is one of `host:` a texture of the host's, `asset:` one the
  folder brings, `target:` a target as a pass earlier in the frame wrote it, and
  `history:` a kept target as the frame before left it. The host holds two textures for
  a kept target and swaps them each frame. A pass may not read a target before it is
  written in the frame, nor read and write one.
- **The host gives** the four targets and the depth of 6.6, and the light's depth of 4.7
  twice, once to be read and once to be compared; where no light's depth is drawn it
  gives one texel at the farthest and counts no light view.
- **The frame's block** (`FrameBlock` in `core`) is a std140 uniform block of 592 bytes:
  the inverse view-projection with the eye at the origin; four light-view matrices and
  their scales; the directions toward the sun and toward the light that casts shadows;
  the eye's block and its place within it; the targets' size; rain, wetness, exposure and
  a time in seconds that stands still while the game is paused; the frame's index,
  whether history is dropped, the dimension and the count of light views; the frame
  before's view-projection; how far the eye moved since; and how far clouds have
  drifted, at 3.1 and 1.2 blocks a second. History is dropped (`FrameHistory`) on the
  first frame, when the window's size or the dimension changes, when the stage's targets
  are made anew, and when the eye has moved farther in a frame than the manifest allows.
- **The host sets no uniform of a stage's program.** Where a sampler and the block are
  bound is the program's own to declare. At load the host asks the driver how it lays
  the block out for each program, logs it, and refuses a program whose block differs in
  size, in a member's offset or in binding from what the host fills, or whose sampler is
  bound to another unit than the manifest says.
- **In the frame** the stage is a stage of its own, entered in front of the game's
  entities, so its time and what it leaves changed are measured apart from terrain's.
  A pass that writes `display` writes a texture of the host's. It is shown only with
  `-Dquadrature.stage.show=true`: its color is then written over the whole of the game's
  picture with the game's depth untouched, so that what the game draws after is drawn
  over it by the game's own depth. Which pixels are sky is the stage's to decide.
- **The check**, `-Dquadrature.stage.check=true`, runs the same passes once, before the
  first frame, on the files that stand for the host's inputs and for the frame's block,
  reads each checked output back right after its pass, and logs PASS or FAIL with counts;
  passes the manifest lists as diagnostic are run after the frame's own. What differed is
  written into the client's `quadrature-stage/`.
- **Images and the probe**, `-Dquadrature.stage.image=true`: for each frame images are
  written of, the stage's display and its diagnostic outputs are written into
  `quadrature-stage/`, and the light's depth is probed in each layer at a pixel low in the
  middle of the picture and at the pixels of `-Dquadrature.stage.probePixels=x,y;x,y`,
  counted from the bottom left (unless set, eight pixels at the edges of the built
  scene's views; empty, the middle one alone). For each it logs the reference depth and
  the stored one, where the eye's ray meets the world and the map's texel lies there,
  inverted from the layer's matrix, with the block at each, and for the cube of each what
  near terrain holds: its mesh and its version, and whether each layer listed it when last
  drawn. `-Dquadrature.stage.targetsRaw=true` writes targets B, C and D of the same frame
  beside the image as raw floats.

## 10. Mod interface

The interface is OrthLib's (D25): types free of the game's and GL's classes in its core,
the client seam beside the game. A mod declares a layer with a role (emissive or plain),
the game layer it falls back to and which block states draw in it; defines bloom kinds,
each a strength, a base, two brightness limits and a radius; and submits emissive
contributions, geometry it writes into a buffer the provider begins, in a stated layout,
primitive and blend, bound to a world and a bloom kind. It holds OrthLib's handles, each its
own lease, and its code is the same with a provider or without one. Without one, OrthLib's
absence answers put a declared layer's geometry in its fallback layer, which the game draws
at full brightness, and OrthLib draws the contributions plainly at the end of the world
pass through the game's own drawing: everything appears, nothing glows. It does the same in
every world pass the provider does not mark as one whose contributions it draws. The
contract's format and its rules for change are research R5, 10.2.

- **The provider.** Quadrature registers as the interface's one provider while it loads,
  after OrthLib, through a bridge of a few classes reached by name only where OrthLib is
  installed (D26; `ModInterfaces`, `OrthLibBridge`). It states its name, the interface
  version it was built for and what it draws, declared layers, emissive contributions and
  bloom, as literals. A second provider is another renderer, and loading stops naming both
  (D6). The rest of Quadrature reads the declarations through `ModInterface`, as one
  immutable snapshot whose layer generation moves only when the declared layers do.
- **Where its answers hold.** Only inside scopes own terrain opens on a build thread: a
  build that meshes declared layers apart runs in the interface's providing scope, in which
  no declared layer is current in the game layer it falls back to, and meshes each declared
  layer through the game's dispatcher inside that layer's own scope, with Forge's current
  layer none. Every other draw of blocks, a mod's own previews among them, gets the absence
  answers. Own terrain meshes declared layers apart where it draws the world, takes over the
  game's chunk system (section 6.7) and draws translucent terrain.
- **Meshes.** Each cube keeps a buffer for each declared layer it has geometry in, at most
  31 layers, and draws them as the layers other mods add are drawn (section 6.7); which
  states draw in a layer is asked once a state and kept while the layers stand. A new
  layer generation drops every declared mesh and builds the loaded cubes again;
  contributions coming and going build nothing.
- **The emissive stage.** Before translucent terrain, after entities and particles, where
  GTCEu: Reloaded's own path drew its emissive layer and its tickets: the declared layers,
  writing depth as that path did, then each contribution, untextured or from the blocks
  atlas and blended as it states, with positions relative to the camera. What glows also
  writes its bloom kind's two weights (D27), the fragment's alpha their cover, into a weight
  target the size of the drawn picture that shares its depth: a declared layer in the same
  draw as its color, through the terrain program's emissive variant and a framebuffer with
  both targets; a contribution in a second draw of its vertices by a program that places
  them by the fixed-function transform, so the two agree in depth. The stage changes only
  state the game's cache knows, through the cache, puts back what the game sets for
  translucent terrain at that point, and reads nothing back from the driver. A
  contribution's draw sets every piece of state it depends on, so a mod's callback before it
  cannot reach it; under the state audit the stage, the callbacks and the glow are stretches
  of their own. Where own terrain meshes declared layers apart, the stage first marks the
  world pass as one whose contributions Quadrature draws, so OrthLib leaves them out of the
  plain drawing it gives every other pass; a contribution not yet in the frame's snapshot
  then appears a frame later, glowing, rather than once without glow.
- **The glow.** One chain for every kind (D27; `BloomStage`, `core` `BloomChain`): the
  picture's color times the weight over black, formed a texel at a time for the first
  halving, which carries the darkening of the weight over white in a fourth channel; five
  levels sized from the output; doublings by a tent at the first drawn kind's radius, each
  level weighed 0.2 to 1.0 from the finest as GTCEu: Reloaded's default style weighs its own;
  the composite, which gives GTCEu: Reloaded's rule with the background's lightness. Without the temporal scaler it runs after
  translucent terrain on the picture, where that mod's own path laid its glow. Where the
  scaler makes the picture, the temporal pass resolves the weight over black into its
  history's alpha as it resolves the color, and the chain reads that output after the scale
  pass and lays the glow over the output's picture, unsharpened and never in the history;
  where the handoff makes the picture, from the drawn picture and the weights as drawn.
  `-Dquadrature.bloom=off` draws the emissive stage without glow, and `=alternate` glows on
  frames chosen by a mix of the frame's number, so the stage's cost is compared within one
  run; `.levels`, `.gain` (1.5, calibrated against GTCEu: Reloaded's own glow) and
  `.brightnessWeighted` (high dynamic range, Phase 5) set the chain. A declared layer none of
  whose cubes the view saw is skipped, and so is a contribution whose written vertices lie
  wholly outside the view's volume, as GTCEu: Reloaded's own path left its effects outside
  the view undrawn (the interface gives a source no view volume of its own to test), so the
  glow runs only on frames where something that glows was drawn in view.
- **Not in version 1.** A mod's own stage at a named point in the graph, and read access to
  frame resources (scene depth with its convention, color), wait for a mod that needs them
  (requirement rule 4): the first consumer's depth texture and passes served only its own
  bloom, which the provider now draws.

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
| Shading: shadow maps, light volumes. The light's depth takes 128 MiB of it with its scratch, 256 MiB with the near layer | 1.0 GiB | Phase 5 |
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

### 12.1 Quality tiers

LOW, MEDIUM, HIGH and MAX are presets of per-group settings, chosen on two axes (D22). A
versioned startup benchmark sets the speed tier and the GPU memory the driver reports,
scaled by output pixels, sets the memory tier; groups that hold memory take the
lower of the two, and the player can override any group. **Decided; built behind a switch and
calibrated on one card** (12.1.1); no group's settings follow the tiers yet.

On game benchmarks the supported cards fall into four clusters against an RTX 2060 Super at
1440p: about 0.8 to 1.3 times (37% of qualifying Steam players in October 2026), 1.3 to 2.0
(29%), 2.0 to 2.8 (21%) and 3.1 to 4.5 (12%). Most cards in the lower two have 8 GB. The
benchmark, not this table, places a machine, since terrain's vertex and draw load may scale
differently from games.

The values below are starting points for the groups that dominate cost, at 1440p output.
**Default**, each revised by the measurement that fills it.

| Group | LOW | MEDIUM | HIGH | MAX |
|---|---|---|---|---|
| Render scale and scaler | 67%, own TAA and scaler (D23) | 77%, own TAA and scaler | 100%, own TAA | 100%; own temporal upscaling at 4K; DLSS where offered (D21) |
| Near terrain, horizontal and vertical radius in cubes | 8, 4 | 12, 6 | 16, 8 | 20, 8 |
| Distant reach in blocks a ring, and the view it gives | 256, 16.4 km | 384, 24.6 km | 512, 32.8 km | 768, 49.2 km |
| The light's depth | One 2,048² layer | One 4,096² layer | 4,096² near and wide layers | Both, and far shadows by a march through the bricks |
| Shadow filter | Hardware 2x2 | 3x3 | 5x5 | Soft shadows on the near layer |
| Ambient occlusion | Vertex | Vertex and half-resolution screen-space | Full resolution | Full resolution with bent normals |
| Indirect light | An ambient term | Probes, 16x8x16, 32 rays | 32x8x32, 64 rays | Two probe cascades, 128 rays |
| Fog and clouds | Height fog; a cloud layer | 160x90x64 fog cells; volumetric clouds at quarter resolution, 32 to 64 steps | 160x90x128; 64 to 128 steps | 8-pixel cells by 128; half resolution, 96 to 160 steps |

The rays indirect light and far shadows share were measured through the occupancy bricks
(5.2): on an RTX 3060 MEDIUM's probe update takes 0.22 ms whole, HIGH's 1.45 ms, MAX's two
cascades about 8.5 ms, and MAX's far shadows 2.6 ms a million rays toward a low sun; HIGH and
MAX spread their updates over frames.

Some work is a precondition of a tier rather than a setting in it. Every tier needs the
light's depth redrawn spread over frames: one layer already peaks at 6.5 ms on the frames
that draw it. HIGH also needs the near layer scrolled and spread over frames, which took the
worst frame to 16 ms when it was redrawn whole.

Opaque terrain was measured against the horizontal radius at settled views, 1280 by 720 on
an RTX 3060 ([VERIFICATION.md](VERIFICATION.md), opaque terrain against the near radius).
Drawn forward it takes 0.96, 1.81, 2.23 and 2.32 ms on the GPU at radii 8, 12, 16 and 20,
2.73 ms at the worst view. With the targets for shading drawn beside the forward picture it
takes 2.15 to 3.74 ms, 6.73 ms at the worst view at radius 20. Memory in use is 626 MiB at
radius 20, inside the arenas' 1.5 GiB. Neither sets the horizontal radii above. What limits
HIGH and MAX now is filling a new place: until builds run on Cubature's executor, a place
settles in 10 to 18 seconds at radius 12, 30 to 43 at 16 and 70 to 88 at 20. The vertical
radii are not measured; the runs held 12, or 8 at radius 8.

#### 12.1.1 The tier benchmark

Built (`core.tier`, `platform/TierBench`); the first-launch path is behind a switch, off by
default, until a second card has been measured against this calibration.

**How tiers set the groups.** On the first-launch path the tiers chosen last are applied before
the game's classes load (`asm/TierStartup`, from the loading plugin), since the parts they set read
their settings once, as they start, and the scaler starts with the game's first framebuffer. Each
group's tier becomes the properties of the groups built so far (`core.tier.TierSettings`): the
temporal pass on every tier and, below the output, the render scale the selection chose, stored
with the run; distant terrain's reach, 256, 384, 512 or 768 blocks; and the light's near layer on
HIGH and MAX. A property given on the command line stands, as does the player's tier for a group.
Near terrain's radius is the game's render distance and Cubature's vertical distance, which stay
the player's. Where the GPU or the driver changed, the benchmark runs again when the first screen
opens and its tiers apply from the next launch.

**When it runs.** Where nothing is stored under the key, a digest of the GPU (the context's
vendor and renderer strings and interop's device identifier), the driver (the context's
version string and interop's driver identifier) and the benchmark's version, it runs once at
the first settled moment: the view still for 240 frames with nothing waiting to be meshed, no
mesh coming or going, and the view's search meeting no cube that is not loaded, since a place
still arriving can hold its meshes for seconds between batches; and, where the light scene is
copied to the GPU for the trace, that copy caught up, with nothing left to take from the scene
or copy and some bricks held, since it trails the meshes. A run takes about 75 frames, one
probe repetition a frame after near terrain's last opaque layer, so no frame is held for long;
it is abandoned and begun again later if the view moves or terrain changes, and a run whose
terrain drew fewer than 200,000 primitives, too few to measure the card, chooses nothing. Times are taken by pairs of timestamps, never by a time-elapsed query, since
the frame's own stage timer holds one open around opaque terrain. Every result is read only
once the driver reports it ready.

**What it measures.** First a settling load, every frame, until its own times agree within 5%
(an arithmetic pass and a pass over 256 MiB, with two arithmetic passes untimed; 13 to 40 frames
on the RTX 3060), since the card's clock follows its load. Then two warm-up repetitions and seven
kept ones of each, medians kept, the GPU kept loaded between them:

- a bandwidth kernel: a compute program reading a buffer of 8 MiB and one of 256 MiB through,
  512 MiB between each pair of timestamps. It runs first, which also brings the clocks up;
- opaque terrain at each tier's render scale of the window: the frame's own commands issued
  again into a target of the probe's own, each layer under the game's state for it, with the
  primitives drawn and the samples passed counted by queries, the sizes taken in a new order
  each round;
- the light view's wide box, drawn whole into its scratch as its check draws it (4,096
  texels a side), with its primitives and samples counted;
- the indirect trace at each render resolution, through `TraceProbe`, which the indirect
  light's trace supplies; without one the run goes on, and the groups it stands for are
  scaled by the other probes.

**How a tier is chosen.** Each probe gives a ratio against the reference card: bandwidth as
the geometric mean of the two sets; terrain as what the reference card's model, a line a
resolution by primitives drawn, says it would have taken at the run's sizes, summed, over what
this card took, which leaves a view's own make-up out; the light's box the same by primitives
and fragments; the trace as measured. A run whose trace stands more than twice or less than half
the geometric mean of the others chooses nothing: it timed something other than the card, as the
runs that met the light scene's copy before it caught up did (VERIFICATION.md, the tier
benchmark). A tier's cost on the
card is the reference card's cost of each of its groups at the window's output, over the
ratio of the probe standing for that group; its fit is the budget over that cost. The speed
tier is the highest tier that fits, with a band at each boundary: from the tier chosen last
on the same GPU, a higher one is taken only once it fits by the band, and the last one is
left only once it misses by it (`TierSelection`). The memory tier is chosen the same way from
the memory each tier holds at the output against the memory free at the first screen, less
512 MiB for the world's own textures and buffers, under an enforced budget where
`quadrature.tier.memoryLimitMiB` is set. A group that holds memory (near terrain, distant
reach, the light's depth) takes the lower of the two; every other group the speed tier
(`TierChoice`). A player's tier for a group, an `override` line of the stored file or
`-Dquadrature.tier.<group>`, stands over both. At run time two-second windows of frames are
watched: where the driver's eviction count rose in two windows running and the 1% low fell
a fifth below the best quiet window's, the memory tier goes down a step (`EvictionWatch`).

The result, the tiers and the figures they came from are stored in the client's
`quadrature-tiers.txt`, the GPU and the driver as digests only.

**The calibration** (`TierCalibrationTable`), measured on the RTX 3060 12 GB, driver 610.88, at
settled clocks ([VERIFICATION.md](VERIFICATION.md), the tier benchmark). The reference card's
figures are fitted over 128 runs on three fresh instances, at the forest tour's 17 places at
radius 12 and at seven of them at 16 and 20: bandwidth 348 and 350 GB/s; opaque terrain 0.062 +
0.263 ms a million primitives at 1280 by 720, 0.097 + 0.289 at 1920 by 1080, 0.135 + 0.330 at
2560 by 1440 and 0.187 + 0.867 at 3840 by 2160; the light's box -0.249 ms + 0.126 ms a million
primitives + 0.053 ms a million fragments; the trace 305 million rays a second for MEDIUM's
probes. The trace's figure is 376 million over the 128 runs, the same with or without the nine
that met the light scene's copy before it caught up, measured on the walk that read the
bricks' coarser bits, scaled to the walk as shipped by the trace's own times of the probe's
batch, MEDIUM's at 64 blocks: 0.180 ms with those bits against 0.222 without, 0.81. The
benchmark went to version 2 for it, and to 3 when it began to wait for the light scene's copy
and to turn away a trace far from the other probes, so a result stored before is measured
again. A tier's cost on the reference card, in ms at 2560 by 1440 output, against a budget of
7 ms (85% of a frame at 120 frames a second), and the speed against this card a machine needs:

| Group, and where its figure comes from | LOW | MEDIUM | HIGH | MAX |
|---|---|---|---|---|
| Opaque terrain drawn forward at the tier's radius and render scale, the median place (radii 12, 16, 20 measured; 8 scaled) | 0.27 | 0.54 | 0.94 | 1.18 |
| The targets for shading, half the forward draw (the near radius's table) | 0.14 | 0.27 | 0.47 | 0.59 |
| The light's depth a frame that draws it: one layer, both layers (PLAN, capability work, item 2) | 0.17 | 0.17 | 0.49 | 0.49 |
| Indirect light's rays as item 4 schedules them, on the walk as shipped: MEDIUM every probe a frame, HIGH and MAX a quarter; MAX's far shadows, an upper bound | | 0.53 | 0.87 | 2.13 + 5.43 |
| Shading, 0.19 ms a render megapixel (the stage handed over in a folder) | 0.31 | 0.42 | 0.70 | 0.70 |
| The game's other drawing, 0.33 ms a render megapixel (the stage reports) | 0.55 | 0.72 | 1.22 | 1.22 |
| Own temporal anti-aliasing, with the scaler below the output's resolution at 0.67 and 0.77, its passes measured at 2560 by 1440 and 3840 by 2160 and carried as a line between (the scaler's costs) | 0.98 | 0.97 | 0.96 | 0.96 |
| **In all, 2560 by 1440** | **2.42** | **3.62** | **5.64** | **12.70** |
| The speed a machine needs, 1920 by 1080 / 2560 by 1440 / 3840 by 2160 | 0.26 / 0.35 / 0.60 | 0.40 / 0.52 / 0.91 | 0.59 / 0.81 / 1.57 | 1.60 / 1.81 / 2.60 |

The rays' figures are item 4's last measurement, of the walk that reads no coarser bits
([VERIFICATION.md](VERIFICATION.md), rays through the scene). That run sampled no clocks; its
figures are taken as the top state's, as the other items' settled runs are.

- Opaque terrain at radius 12, the median place, takes 0.476, 0.571, 0.670 and 1.778 ms from
  1280 by 720 to 3840 by 2160. At the same views radius 16 takes 1.46, 1.39, 1.37 and 1.24 times
  as long and radius 20 about 1.95, 1.83, 1.72 and 1.44 times (two views); radius 8 is taken as
  0.53 of 12 from the near radius's table. Each radius is held as a + b MP + c MP². The vertical
  radii are those of the runs, 12.
- Not counted, since not built or not measured: fog and clouds, ambient occlusion, and the
  shading that the shadow filter and indirect light add to the stage. Each is added to its tiers
  when measured, which can only raise the speeds needed.
- Distant terrain, the reach group, is its own line since benchmark version 5 (VERIFICATION, distant
  terrain's cost): 0.973 ms, 0.928 ms a million faces and 0.181 ms a million render pixels, at the
  faces each tier's reach draws once its tiles are stored, the mean of two forest places: 1.25, 1.98,
  2.82 and 4.30 million at 256, 384, 512 and 768 blocks (version 6). A primitive of it costs about
  three times one of opaque terrain, so the reference's figures are its own; the terrain probe's ratio
  stands for how much faster a machine draws it. At 2560 by 1440 it takes about 2.4, 3.2, 4.3 and
  5.6 ms on the reference card, the largest group of every tier.
- **Rendering below the output costs time at today's frame.** The scaler's passes take, at P0,
  0.98 ms at 2560 by 1440 output from 0.67, 0.97 from 0.77 and 0.96 for the temporal pass alone
  at the output's size (HIGH and MAX), and 1.52, 1.78 and 2.01 ms at 3840 by 2160, with about
  0.01 ms between the passes (VERIFICATION, the scaler's costs). For two and a quarter times the
  pixels they take 1.55, 1.83 and 2.10 times as long, not in proportion, so each tier's is
  carried as a line through its two outputs. A render below the output pays only once a tier's resolution-dependent work passes
  about 1.8 ms at 0.67 and 2.4 ms at 0.77 at 2560 by 1440, and 2.8 and 4.4 ms at 3840 by 2160,
  were all of it to follow the pixels; on the short tour at 3840 by 2160 the world's terrain
  took 2.36 ms at the output's size, and 2.81 and 3.29 ms with the scaler at 0.67 and 0.77.
  So the selection prices LOW and MEDIUM both ways, at 67% and 77% with the scaler and at the
  output's resolution with the temporal pass alone, each group divided by its probe's ratio, and a
  tier draws below the output only where that costs less (D23; benchmark version 4,
  `TierSelection.priced`). On the reference card it does from 1920 by 1080 up, saving 0.42 and 0.39 ms
  there, 1.17 and 0.99 ms at 2560 by 1440 and 3.73 and 3.32 ms at 3840 by 2160; at 1280 by 720
  LOW draws natively on the card's measured run; a card
  whose terrain runs faster than its memory against the reference's saves less and may draw
  natively. The chosen scales are logged and stored with each run. On the run the unit test holds, this card takes
  MEDIUM at 2560 by 1440 (6.42 ms of the 7, a fit of 1.09) and LOW at 3840 by 2160 (6.56 ms, a
  fit of 1.07), distant terrain counted; before it was counted, HIGH and MEDIUM. Its score runs 0.85
  to 1.14 between places, so at the slowest places these fits fall below one; the band keeps
  whichever tier is stored first.
- The memory each tier holds, at 2560 by 1440: 307, 660, 1,029 and 1,441 MiB (near terrain's
  arenas 128 to 704 MiB, distant terrain's reach about 40 to 250 MiB by DISTANT's survey, the
  light's layers with their scratch 32 to 256 MiB, the light scene's lookup and bricks 8.6, 15.0
  and 23.1 MiB with MAX's states within radius 12, 39 MiB, and 30.5 MiB a render megapixel and
  15.3 an output megapixel for targets and history).
- DLSS is offered where the speed tier leaves room for it (D21), at its cost measured taking
  the scaler's picture through the handoff on this card, preset K in Performance mode: 2.65 ms
  at 2560 by 1440 and 5.48 ms at 3840 by 2160, the copies, the round trip and NGX's work, carried
  as 0.39 ms fixed and 0.61 ms an output megapixel and scaled by the probes' combined ratio. It
  is counted above the tier's cost, although it takes the place of the scaler's temporal and
  scale passes.
- The trace probe times MEDIUM's own batch, 65,536 rays: a small batch runs at about half to
  seven tenths of a large one's rate, so the probe gives a card's ratio to the reference, and
  HIGH's and MAX's costs are item 4's own timings of their batches.

**What this card scores.** 0.96 at the median against its own calibration (0.85 to 1.14 over
128 runs), so it takes HIGH at 1280 by 720, 1920 by 1080 and 2560 by 1440 and MEDIUM at 3840 by
2160, on the boundary with LOW (above); DLSS has room above HIGH at 1920 by 1080 and not at 2560
by 1440. Its memory admits MAX at every output.

**Against the four clusters.** Published game comparisons put the RTX 3060 within about 9%
of the RTX 2060 Super (research R1, 1.10), so the clusters' bounds stand at about the same
values against this card. At 2560 by 1440 the first cluster (0.8 to 1.3) reaches HIGH, the
second (1.3 to 2.0) HIGH up to 1.58 and MAX above, the upper two MAX. At 3840 by 2160 the first
reaches LOW up to 0.99 and MEDIUM above, the second MEDIUM up to 1.38 and HIGH above, the third
(2.0 to 2.8) HIGH up to 2.29 and MAX above, the fourth MAX. The table's tiers above were meant to
put the first cluster at LOW: at 2560 by 1440 it is the groups not yet counted, not the ones
measured, that will separate them.

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
| The light's depth: orthographic boxes fixed in the world at whole texels, a near one 192 blocks across and 544 deep behind its own switch and a wide one, each a layer of an array texture drawn by tiles: dirty tiles in place, a new box over about 8 frames into a scratch copied over it whole | Built, behind switches | Entities in it; what samples it; the frame cost against the whole-layer draws it replaced |
| Visibility's coarse step: a search per view over shared per-cube face links, on the CPU | Built | |
| Terrain memory: an arena of pages written through the driver, never mapped, free blocks split and joined; draws by indirect multi-draw per layer and page | Built | Compaction, and pages given back; a single write that stalls; commands written on the GPU |
| GPU-driven visibility in two phases | Default | Phase 2 measurements |
| Compact faces with indirect multi-draw, first | Default | Phase 2 measurements |
| Meshlets as a second technique | Candidate | The mesh shader measurement, on distant terrain |
| Ringed distant terrain with shared mesher | Default | Memory per square kilometer |
| Far-field representation | Candidate | The same |
| Deferred shading with clustered lights | Default | Phase 5 prototype |
| Far shadows, indirect light | Candidate; the trace they share through the bricks built and measured (5.2) | Phase 5 prototypes |
| Shader format | Candidate | Two concrete shaders |
| Driver floor R580, a warning below the production branch | Decided (D20); built | |
| Quality tiers on two axes, speed by a startup benchmark and memory by what the driver reports | Decided (D22); built behind a switch, calibrated on the RTX 3060 (12.1.1) | The groups not yet counted; a second card; a driver update |
| Own temporal anti-aliasing and spatial scaler; DLSS through a Vulkan bridge as an option | Decided (D21, D23); own temporal pass and plain scaler built behind switches (4.8); the handoff to Vulkan measured (4.9) | DLSS's own evaluation; motion vectors for hosted draws; ghosting under motion |

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
