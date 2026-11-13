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

**2.2 Depth precision. [V] and a conflict. [K]**
`glClipControl` is core since 4.5. The 4.6 compatibility specification defines it in
section 13.7 as a property of clipping, and section 13.8 changes the depth mapping to
`s = f - n, b = n` in `ZERO_TO_ONE` mode; both apply to every primitive, however its
vertices were transformed. Reversed depth needs the `GL_ZERO_TO_ONE` clip range, a
floating-point depth buffer, swapped near and far, and reversed comparisons; NVIDIA's own
write-up recommends it with an infinite far plane for large distances.
The conflict: hosted renderers call `glDepthFunc(GL_LEQUAL)` and `glClearDepth(1)`
themselves and assume conventional depth. Two candidates:
- translate depth state where the game sets it (see 9.2), so hosted code runs reversed
  without knowing;
- keep conventional depth for the near field and give distant terrain its own depth range,
  composited afterwards.
*Open:* which candidate; needs a prototype.

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
*Implication:* `GlStateManager` is the observation point. Direct LWJGL calls are handled
by reading back or resetting the affected state at stage boundaries, not by interception.
*Open:* how many common mods bypass `GlStateManager`.

**9.3 Fixed-function state the game uses. [K]**
Texture unit 0 for the base texture and unit 1 for the lightmap with a texture matrix; two
directional lights with color material for entities and items; linear and exponential fog;
alpha test at 0.1; texture matrix animation for enchantment glint; texture environment
combine for the entity damage tint.
*Follow-up:* turn this into a complete list by instrumenting a running client.

**9.4 Cost of state tracking. [M]**

## Follow-ups

1. Prototype reversed depth with hosted entity draws (2.2).
2. Instrument a client for fixed-function state use and direct LWJGL calls (9.2, 9.3).
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
