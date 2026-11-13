# R1: platform, pass graph, hosted rendering

Requirement sections 1, 2 and 9. First pass, 2026-11-13. Basis marks are defined in
[README.md](README.md).

## 1. Platform and context

**1.1 Cleanroom's context. [V]**
Cleanroom requests OpenGL 4.6 with the compatibility profile by default
(`OPENGL_VERSION_MAJOR = 4`, `OPENGL_VERSION_MINOR = 6`, `OPENGL_COMPAT_PROFILE = true` in
`ForgeEarlyConfig`). Its window code tries 4.6, 4.5, 4.4, 4.3, 4.2, 4.1, 4.0, 3.3, 3.2,
3.1, 3.0 and 2.1 in order and then falls back to GLES 2 for translation layers. All three
settings are player-configurable.
*Implication:* Quadrature requests nothing. Its gate reads the context it was given and
stops if the version is below 4.6 or the profile is core.
Observed in the development client, 2026-11-13, RTX 3060, driver 610.88 [V]:
- With the version setting lowered to 4.5, the driver still returned a 4.6 compatibility
  context. A lower version setting does not produce a lower context on NVIDIA.
- With the compatibility profile setting off, the game aborted in `Minecraft.init` on its
  first fixed-function call (`glAlphaFunc`), after mod pre-initialization and before any
  screen. No mod can show a screen in that state; the cause can only be logged.
- The context reports 405 extensions and 12288 MiB through `GL_NVX_gpu_memory_info`.

**1.2 Identifying Turing and later. [V, partly]**
Mesh shaders arrived with Turing and are exposed in OpenGL as `GL_NV_mesh_shader`. Pascal
predates it. A cross-vendor `GL_EXT_mesh_shader` entered the Khronos registry in October
2025 and is implemented in Mesa; no source confirmed it in NVIDIA's driver.
*Implication:* the capability set is "OpenGL 4.6 compatibility, mesh shaders, 8 GB". The
mesh shader capability is satisfied by either extension name, so the EXT form costs
nothing to admit later.

**1.3 Reading GPU memory. [V]**
`GL_NVX_gpu_memory_info` reports total dedicated memory through `glGetIntegerv` with token
`0x9047`, in kilobytes. LWJGL 3 binds it as `NVXGPUMemoryInfo`.

**1.4 Minimum driver. [M]**
No minimum branch was established. The development machine runs an RTX 3060 on driver
610.88. The minimum is set by the oldest driver a test run passes on.

**1.5 Shader compilation on NVIDIA. [V, dated]**
A 2018 NVIDIA developer forum thread reports that the parallel shader compile extension
parallelizes linking only, with bugs, and that loading cached program binaries gave the
least stutter. The reports are eight years old.
*Implication:* compile and link at load, never mid-frame; cache program binaries keyed by
driver version; treat parallel compile as unproven until tested on a current driver.

**1.6 Java and native calls. [V]**
LWJGL 3.4.0 added a backend built on the foreign function and memory API, enabled by
default on JDK 25 and later, and runs with unsafe memory access denied.
OrthLib's platform manifest records LWJGL 3.4.3 for Cleanroom 0.7.14-alpha, so that
backend is the one in use. The LWJGL 2 shim is described in 9.2.

**1.7 Driver threading. [K]**
A GL context is current on one thread at a time. NVIDIA's driver can move work to its own
thread ("threaded optimization"), which changes the cost of synchronous queries.
*Implication:* avoid readbacks and `glGet` calls in the frame; verify by measurement.

## 2. Pass graph and frame

**2.1 Frame graph design. [V]**
Frostbite's FrameGraph (O'Donnell, GDC 2017) is the reference: passes are nodes, resources
are edges, a setup phase declares usage, a compile phase orders passes, culls unreferenced
ones and assigns lifetimes to transient resources, and an execute phase runs them. Unity,
Unreal and AMD's render pipeline shaders use the same coarse pass granularity.
*Implication:* Quadrature's graph changes when the selected shader or settings change, not
every frame, so it can compile once per configuration.

**2.2 Depth: the whole frame reversed. [V, measured]**
The whole frame uses reversed depth (D17). This section records what that requires, how
hosted code is kept unaware of it, and what a prototype showed.

What reversed depth requires. `glClipControl` is core since 4.5; the compatibility
specification defines it in section 13.7 as a property of clipping, and section 13.8
changes the depth mapping to `s = f - n, b = n` in `ZERO_TO_ONE` mode, for every primitive
however its vertices were transformed. To gain precision the frame also needs a
floating-point depth buffer, a projection whose clip depth runs from w at the near plane
to 0 at the far plane, a cleared depth of 0 instead of 1, a "greater" comparison instead
of "less or equal", and a polygon offset of opposite sign. NVIDIA's write-up recommends
this with an infinite far plane.

Who sets that state. Every item is state the game and other mods set themselves. In the
surveyed pack (R5, section 11.3):

| State | Who sets it |
|---|---|
| Depth comparison | The game; 18 mods through the game's state manager; 2 mods directly |
| Depth clear value | The game; 4 mods |
| Polygon offset | The game (block damage); 8 mods |
| Projection matrix | The game; 15 mods load or multiply matrices directly |
| Depth read back | 6 mods read pixels, 2 copy the framebuffer to a texture, 5 keep their own framebuffers |

How hosted code is kept unaware. Two mechanisms, both at one point each:

1. **Calls are translated where they are made.** A class transformer rewrites, in every
   class the game loads, each call to one of 27 OpenGL functions so that it lands in a
   translator of the same name and signature. The translator swaps the comparison's
   direction, replaces a depth value d by 1 - d, negates polygon offsets, and maps a depth
   range (n, f) to (1 - f, 1 - n). The functions that read that state back translate the
   other way, so a caller that saves and restores state sees the values it set. This
   covers direct calls as well as calls through the game's state manager, which section
   9.2 could not observe.
2. **The projection stack's base is a reversal.** Fixed-function matrix calls multiply on
   the right. So when a caller loads identity or a matrix onto the projection stack, the
   translator loads the reversal R times that matrix instead, where R maps clip depth z to
   (w - z) / 2. Every later `glOrtho`, `glFrustum` or perspective multiply keeps R in
   front without being touched, and the fixed-function `gl_ProjectionMatrix` that other
   mods' shaders read is already reversed. Reading the projection matrix returns it with
   R removed, so the game's frustum culling and camera position are computed as before.

The translator needs to know the current matrix mode. It tracks `glMatrixMode` and reads
the mode again after `glPopAttrib`, which can restore a mode it never saw set. It
translates only on the render thread; the loading screen draws on its own thread and
context and is left alone. Reversed depth is switched on at the first pass of the game
loop, when the render thread holds the window's context, and the state set before that
moment is read back and set again translated.

The game's framebuffers get a 32-bit floating-point depth buffer in place of the 24-bit
fixed-point one.

Measured 2026-11-13, prototype behind `-Dquadrature.reversedDepth=true`:

- Development client: 1,194 frames, no OpenGL errors; the state audit, which reads the
  driver directly, shows comparison "greater or equal" and clear value 0 at every pass
  while the game's own cache holds "less or equal" and 1.
- Cubic instance (Cubature, Riven, GregTech CEu: Reloaded, CodeChickenLib, ModularUI,
  OrthLib), forest tour, second run against the settled vanilla reference: all 17 views
  pass the near-terrain profile. The worst view changes 0.063% of pixels and the largest
  mean difference is 0.017. No view is pixel-identical; the same comparison without
  reversed depth had 6 identical views and a worst of 0.039%. The first run fails three
  views, as first runs do against a settled reference (`docs/VERIFICATION.md`).

- Nine more mods, client-side, from the surveyed pack, each of which sets depth state:
  JourneyMap, Had Enough Items, CustomLoadingScreen, Chisel with CTM, Storage Drawers with
  Chameleon, BetterAdvancements and Universal Tweaks. Five tours ran, two with reversed
  depth and three without, none with an error. The same instance with the switch on and
  off, both settled: all 17 views pass, worst 0.056% of pixels. Of the 2,627 pixels that
  differ across the 17 views, 2,506 have at most two differing neighbors, and most lie in
  the band of the frame that holds distant terrain. That is the pattern of depth rounding
  along distant edges, and it is what reversed depth alone changes.
- Two instances with the switch on in one and off in the other: 16 views pass and one
  fails at 0.13%. About 860 of its pixels are one patch of ground lit differently, which
  is gone in the same-instance comparison: a difference between instances, not of depth.
  The reference instance against itself is identical in 16 views.

Cost, measured 2026-11-13 with four flight captures (lifecycle flight workload, RTX 3060,
no distant terrain, frame rate capped at 120), two with the switch off and two with it on:

| | Off | On |
|---|---|---|
| Render work per frame, median | 1.51, 1.51 ms | 1.60, 1.83 ms |
| Render work per frame, 95th percentile | 4.43, 4.29 ms | 4.34, 4.79 ms |
| Frame time, 99th percentile | 19.5, 19.5 ms | 18.5, 18.0 ms |
| World pass, sum of stage medians, CPU | 1.11 ms (one capture) | 1.10, 1.17 ms |
| World pass, sum of stage medians, GPU | 1.08 ms (one capture) | 1.14, 1.16 ms |

Median render work is 0.09 and 0.32 ms higher with the switch on. The two captures with it
on differ from each other by more than the smaller of those, so four captures bound the
cost at a few tenths of a millisecond without fixing it. Frame-time percentiles do not
move. The stage times cover the whole capture, not the flight alone.

*Not yet covered:*

- The added mods were loaded and rewritten, but a terrain tour opens no inventory and
  places no block, so their own depth-setting code mostly did not run. JourneyMap's map
  and CustomLoadingScreen's loading screen did draw.
- Depth read back with `glReadPixels`, depth textures sampled by other mods' shaders, and
  `gl_FragCoord.z` in them are not translated. In the surveyed pack 6 mods read pixels;
  which of them read depth is not known.
- A display list that changes the matrix mode, and matrix calls through direct state
  access, are not tracked.
- A projection built by multiplying a perspective onto the base is reversed by the driver
  in single precision, which shifts the effective near plane by a relative 0.02% at the
  game's own far plane. It is monotonic and the same for everything drawn with that
  matrix. Quadrature's own projections must be derived from the same matrix, read from
  the driver, so its depth matches hosted depth exactly.
- The rewriter's share of class loading, in a large pack, is not measured.
- The rest of the pack: 19 mods in all were present, 9 of the surveyed pack's 27 that set
  depth state.

**2.3 Color. [K]**
The game's textures and vertex colors are display-encoded, and its own blending happens in
that encoding. A linear HDR pipeline must decode hosted draws' output, which requires
replacing their fragment stage (see 9.1).

**2.4 State isolation. [K]**
Direct state access (core in 4.5) lets Quadrature create and modify its own objects
without changing bindings. The game caches GL state in `GlStateManager`; changing state
behind it desynchronizes the cache. Quadrature must restore what it changes or route
changes through the same cache.

**2.5 Taking the frame on Cleanroom. [V]**
Read from Cleanroom's patch to `EntityRenderer` and Kirino's configuration, 2026-11-13.
Kirino is woven into the game's world render in three states:

| Kirino state | Configuration | Effect on the frame |
|---|---|---|
| Off | `enable=false` (the default in every instance examined) | None |
| Headless | `enable=true`, `enableRenderDelegate=false`, or rendering unsupported | The game's `renderWorldPass` runs, and Kirino is called at seven named phases inside it (prepare, pre-update, update, render opaque, render transparent, post-update, render overlay) |
| Graphics | `enable=true`, `enableRenderDelegate=true`, OpenGL 4.6 | `updateCameraAndRender` calls Kirino's world render instead of the game's `renderWorld` |

Kirino treats anything but OpenGL 4.6 as unsupported, and Quadrature's platform is always
4.6, so on Quadrature's platform the graphics state is reachable by configuration alone.
*Implications:*
- Kirino's graphics state is another renderer under decision D6. Both settings are static
  fields readable before any graphics call, so the gate can refuse it at pre-initialization.
- The game's `renderWorldPass` also carries Forge's hooks (render pass selection, block
  highlight, render-last dispatch, first-person hand) and is where other mods inject. A
  frame that replaces `renderWorldPass` must reproduce all of them, Kirino's headless
  phases included. A frame that keeps `renderWorldPass` and takes its stage boundaries by
  injecting between the calls inside it inherits them, and leaves other mods' injections
  working. Phase 1 should take boundaries by injection.
- The three opaque `renderBlockLayer` calls and the translucent one are the terrain
  stages. They are the same call sites other renderers redirect, which is why two
  renderers cannot coexist there.

**2.6 GPU memory budgeting. [V, engine practice]**
Published engine practice for a streamed budget: take the budget from the memory the
driver reports, not the card's advertised size; keep a fixed reserve for allocations that
are not streamed, because fluctuation there makes a streamer thrash; load more below a low
watermark (one engine uses about 50% of the budget), unload unused data above it, and stop
loading near a high watermark (about 90%); when demand exceeds the budget, degrade detail
instead of failing. No vendor guidance on over-commit was found.
*Implication:* the terrain arenas follow this shape: one budget, a reserve for
framebuffers and textures, watermarks driving eviction and refinement.

## 9. Hosted rendering

**9.1 Redirecting fixed-function draws into custom shading. [V]**
Read from the OpenGL 4.6 compatibility specification (May 5, 2022) and the GLSL 4.60
specification on 2026-11-13.
- **Vertex stage.** Chapter 12 opens: fixed-function vertex transformation and lighting
  are performed only when programmable vertex processing is not. With a vertex shader
  bound, transforms, lighting and coloring are the shader's job.
- **Fragment stage.** Chapter 16 opens the same way: texture application (16.2), color sum
  (16.3) and fog (16.4) are performed only when programmable fragment processing is not.
- **Alpha test.** Section 17.3.2: the test still runs with a fragment shader, on the alpha
  of the output linked to color number zero. With several outputs, only output zero's
  alpha is tested, and the test is skipped if draw buffer zero has an integer format.
- **Inputs.** GLSL 4.60 section 7.2 declares the compatibility vertex inputs: `gl_Color`,
  `gl_SecondaryColor`, `gl_Normal`, `gl_Vertex`, `gl_MultiTexCoord0` to `7`, `gl_FogCoord`.
  The built-in uniform state of section 7.4 is present: the matrix stacks, `gl_LightSource`,
  `gl_FrontMaterial`, `gl_Fog`, `gl_TextureEnvColor`, and `ftransform()`.
- **Enable flags.** Neither specification gives a shader a way to read whether texturing,
  lighting, fog or the alpha test is enabled [K: by absence].
*Implication:* hosted draws can be routed through Quadrature's programs and write its
geometry outputs. Enable flags must be mirrored into uniforms. A geometry buffer must keep
an alpha-bearing, non-integer attachment at draw buffer zero so the game's alpha-tested
draws (entities, cutout textures) keep working.

**9.1a Display lists. [V]**
Section 21.4 of the compatibility specification: executing a list runs its commands "just
as if they were given normally", so a list drawn while Quadrature's program is bound runs
through that program. Program and shader object commands (create, compile, link, attach)
are never compiled into a list; they execute immediately. The game's display lists
(entity models, sky, some block entity renderers) therefore need no special handling.

**9.2 Where state can be observed. [K, partly V]**
Minecraft 1.12.2 routes nearly all state changes through `GlStateManager`. That is one
place to mirror state and translate depth. Mods that call LWJGL directly bypass it.
Cleanroom's LWJGL 2 shim (LWJGLY) [V, README read 2026-11-13] works at class load: where a
class exists in both LWJGL versions, such as `GL11`, the methods LWJGL 2 had and LWJGL 3
dropped are merged into LWJGL 3's class from generated shims and handwritten adapters,
most of them a renamed delegate. So a mod's direct `GL11` call lands in LWJGL 3's own
class. There is no routing layer to observe; seeing direct calls would mean transforming
the calling classes, which is Cleanroom's mechanism and not one Quadrature owns.
*Implication:* `GlStateManager` is the observation point for mirroring state. Depth is
the exception: Quadrature rewrites the calling classes for a fixed list of depth functions
(section 2.2). Measured there: the binding's own classes pass through class transformers
too and must be excluded, or the translator's calls return to itself.
*Open:* how many common mods bypass `GlStateManager`.

**9.3 The state the game really uses and leaves. [V, measured]**
Measured 2026-11-13 with the state audit (`-Dquadrature.stateAudit=true`), which reads 41
state items at every stage boundary for 120 frames. Two runs: the development client (the
game alone, a flat world) and a cubic instance of the current stack (Cubature, Riven,
GregTech CEu: Reloaded, CodeChickenLib, ModularUI, OrthLib), at the forest tour's first
view.

At the start of the world pass, in both runs and every frame: no program, no vertex array
object, the game's framebuffer bound for drawing and reading, no array or element buffer,
no sampler objects, pack and unpack alignment 4, matrix mode modelview, an empty attribute
stack, texture unit 0 active, depth test on with "less or equal", depth writes on, depth
cleared to 1, blending off, alpha test on, culling on, lighting and fog off.

What the stages leave:

- **Fixed-function state is never restored between stages.** Fog, lighting, color
  material, blending and its factors, the alpha test and its reference, culling, the
  depth mask, the shade model and the bound textures change at nearly every boundary and
  stay changed. For example the opaque terrain stage leaves lighting on and the alpha
  reference at 0.1, and the entities stage leaves blending on and culling off.
- **Modern state is never left changed.** In neither run did any stage leave a different
  program, vertex array object, framebuffer, buffer, sampler, pixel storage mode, matrix
  mode or active texture unit from the one it found.
- **The mod stack shows up in one stage.** In the cubic instance the translucent terrain
  stage leaves a different texture on unit 0 and none on unit 1, and the next stage puts
  them back. Which mod does it is not established; GregTech CEu's bloom hooks this stage
  and is the first candidate.

*Implication:* both envelope rules match the frame as it is. A stage cannot rely on what
the one before left, because the game itself does not tidy up; and the neutral values
Quadrature returns to on leaving are the ones the game holds at every boundary.
*Limits:* 120 frames at one view in each run, and ten mods, not the 189 of the surveyed
pack. The audit sees state at boundaries, not what a stage does and undoes inside itself.

**9.4 Cost of state tracking. [M]**

## 9a. The state envelope

What keeps Quadrature's drawing and hosted drawing from corrupting each other. Researched
2026-11-13 from the specification, published practice and the survey in R5, section 11.3.

**9a.1 What the attribute stack saves, and what it does not. [V]**
The compatibility specification, section 21.6: `PushAttrib` and `PopAttrib` save and
restore state by attribute group, and `PushClientAttrib` covers pixel storage and vertex
array state. Three limits matter:
- Matrix stacks are never pushed or popped.
- Sampler object bindings and state are not pushed or popped.
- Texture state is pushed only for the fixed-function texture units, up to
  `MAX_TEXTURE_UNITS`; a renderer that uses higher units gets no help for them.

The bound program, vertex array object, framebuffer and buffer bindings belong to no
attribute group and are not saved either [K, by their absence from the groups; published
reports agree]. The stack is at least 16 deep, and 18 mods in the survey already use it,
so Quadrature must not consume much of that depth.
*Implication:* the attribute stack cannot be the envelope. It misses exactly the state a
modern renderer adds.

**9a.2 The game's state cache. [K]**
`GlStateManager` remembers what it last set (blend, depth, alpha, cull, fog, lighting,
color mask, polygon offset, the active texture unit and each unit's bound 2D texture,
color, shade model and others) and skips a call that would repeat it. State changed
behind it is therefore silently wrong: the cache says one thing and the context another.
It does not cache matrices, viewport, scissor, the program, the vertex array object, the
framebuffer, buffer bindings, samplers or pixel storage.

**9a.3 The hazards, each a known class of defect. [V for the first two, K otherwise]**

| State left behind by a modern renderer | What the hosted code then does |
|---|---|
| Pixel storage modes (unpack alignment and others) | Uploads textures skewed. They are client state, outside `PushAttrib`; the Khronos pitfalls article singles them out |
| A selector: active texture unit, matrix mode, bound buffer | Edits the wrong object. NVIDIA's direct state access specification was written to remove this hazard for layered libraries |
| A vertex array object | Fixed-function vertex arrays are set on the wrong object |
| A sampler object bound to a unit | The game's texture is sampled with the wrong filtering and wrapping; nothing in the attribute stack restores it |
| An enabled generic vertex attribute array | A fixed-function draw reads from an array it never set |
| A bound program | Fixed-function drawing silently runs through it |
| A bound framebuffer | The game draws into the wrong target |
| Any cached state changed directly | The cache skips the call that would have fixed it |

**9a.4 What the surveyed mods do. [V]**
In one pack of 189 client mods: 45 enable or disable state with direct calls, bypassing
the cache; 18 use the attribute stack; 9 bind their own shader programs; 5 bind their own
framebuffers; 10 use the stencil buffer; 4 bind buffers directly; 2 change pixel storage;
1 binds vertex array objects; 1 uses generic vertex attributes; 8 patch the state manager
itself. Hosted code is not a clean fixed-function guest: it leaves modern state behind
too.

**9a.5 The envelope that follows from this.**
Two rules, one for each direction.

*Entering a Quadrature stage: assume nothing.* Quadrature sets every piece of state its
stage depends on, every time. It never relies on what a hosted stage left, because a
quarter of the surveyed mods change state where it cannot be seen.

*Leaving a Quadrature stage: leave the context as the game believes it to be.*
- State the cache knows is changed only through the cache, so the cache stays true.
  Where Quadrature must set such state directly, it resets the cache's entry on leaving.
- State the cache does not know is returned to a fixed neutral value on leaving: no
  program, no vertex array object, the game's framebuffer, no array or element buffer
  bound, no sampler objects on the units the game uses, generic vertex attribute arrays
  disabled, default pixel storage, the matrix mode and active texture unit the game
  expects.
- Quadrature's own objects are created and modified through direct state access, which
  changes no binding at all.
- Quadrature uses texture units, buffer binding points and sampler units the game and
  fixed-function drawing do not, wherever it can, so there is less to return.

Neutral values, not saved ones, are the deliberate choice: reading state back to save it
is a synchronizing query (section 1.7), and the game's expected values at each boundary
are known constants.

**9a.6 Proving it. [M]**
An audit, in development builds only: at each stage boundary, read back a fixed list of
state and compare it with what the cache holds and with the neutral values, and report
the first stage that leaves a difference. Run against the game alone it yields the list
of state the game really uses (section 9.3); run with other mods installed it shows which
of them leave state behind. It reads state back, so it is a diagnostic, never part of a
normal frame.

## Follow-ups

1. Prototype the distant terrain composite and its transition band, in Phase 4 (2.2).
2. Run the state audit on more views and with a larger pack installed (9.3).
3. Test parallel compile and program binary caching on a current driver (1.5).

## Sources

- CleanroomMC/Cleanroom, `module/cleanroom/src/main/java/com/cleanroommc/client/sdl/Window.java`
  and `ForgeEarlyConfig`, read 2026-11-13
- NVIDIA, "Introduction to Turing Mesh Shaders": https://developer.nvidia.com/blog/introduction-turing-mesh-shaders
- Khronos registry, `NV_mesh_shader`; coverage of `EXT_mesh_shader` (October 2025):
  https://forums.developer.nvidia.com/t/gl-ext-mesh-shader-support/350412
- NVIDIA, `GL_NVX_gpu_memory_info`: https://developer.download.nvidia.com/opengl/specs/GL_NVX_gpu_memory_info.txt
- NVIDIA developer forum, parallel shader compile reports (2018):
  https://forums.developer.nvidia.com/t/bugs-with-gl-arb-parallel-shader-compile/43715
- LWJGL 3.4.0 release notes: https://github.com/lwjgl/lwjgl3/releases
- O'Donnell, "FrameGraph: Extensible Rendering Architecture in Frostbite", GDC 2017:
  https://www.slideshare.net/DICEStudio/framegraph-extensible-rendering-architecture-in-frostbite
- Khronos, `glClipControl` reference: https://registry.khronos.org/OpenGL-Refpages/gl4/html/glClipControl.xhtml
- Kilgard, "Avoiding 16 Common OpenGL Pitfalls" (pixel store state):
  https://www.opengl.org/archives/resources/features/KilgardTechniques/oglpitfall/
- NVIDIA, `EXT_direct_state_access` specification (selectors and layered libraries):
  https://developer.download.nvidia.com/opengl/specs/GL_EXT_direct_state_access.txt
- Microsoft, saving and restoring sets of state variables:
  https://learn.microsoft.com/en-us/windows/win32/opengl/saving-and-restoring-sets-of-state-variables
- Cesium, "Graphics Tech in Cesium" (multiple frustums, depth cleared between them):
  https://cesium.com/blog/2015/05/14/graphics-tech-in-cesium/
- NVIDIA, "Depth Precision Visualized": https://developer.nvidia.com/content/depth-precision-visualized
- CleanroomMC/LWJGLY, README, read 2026-11-13: https://github.com/CleanroomMC/LWJGLY
- CleanroomMC/Cleanroom, `module/minecraft/patches/net/minecraft/client/renderer/EntityRenderer.java.patch`,
  read 2026-11-13
- BeamNG, texture streaming budget thresholds: https://documentation.beamng.com/modding/materials/texture_streaming/
- Epic Games, texture streaming pool and metrics:
  https://dev.epicgames.com/documentation/unreal-engine/texture-streaming-metrics-in-unreal-engine
- Godot, texture streaming budget: https://docs.godotengine.org/en/latest/classes/class_texturestreaming.html
- OpenGL 4.6 compatibility profile specification (May 5, 2022), chapters 12 and 16,
  sections 13.7, 13.8, 17.3.2 and 21.4: https://registry.khronos.org/OpenGL/specs/gl/glspec46.compatibility.pdf
- OpenGL Shading Language 4.60 specification, sections 7.2 and 7.4:
  https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf
