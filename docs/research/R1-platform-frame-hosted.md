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
predates it. A cross-vendor `GL_EXT_mesh_shader` entered the Khronos registry in 2025 and
is implemented in Mesa; no NVIDIA driver through 621.14 reports it, on any architecture
(public reports, 2026-11-18), and the development machine's does not (1.8).
*Implication:* the capability set is "OpenGL 4.6 compatibility, mesh shaders, 8 GB". The
mesh shader capability is satisfied by either extension name, so the EXT form costs
nothing to admit later.

**1.3 Reading GPU memory. [V]**
`GL_NVX_gpu_memory_info` reports total dedicated memory through `glGetIntegerv` with token
`0x9047`, in kilobytes. LWJGL 3 binds it as `NVXGPUMemoryInfo`.

**1.4 Minimum driver. [V]**
Every feature Quadrature uses is in NVIDIA's drivers from 440.97 (October 2019), so the
floor is a support policy; D20 sets it at the R580 branch, with a warning below the
current production branch.

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

**1.8 The context's limits, read in the game. [V]**
Read by the capability dump (`-Dquadrature.capabilities=true`) in the development client,
2026-11-18, RTX 3060 12 GB (28 SMs), driver 610.88, in the game's compatibility context.
No query raised a GL error, and the world ran after it.
- The context is not a debug context (`GL_CONTEXT_FLAGS` 0). `GL_KHR_debug` is present, but
  a non-debug context may report few or no messages, so a test that must fail on driver
  messages needs a debug context requested.
- Mesh shaders: 256 output vertices and 512 output primitives a work group, work groups of
  at most 32 invocations (32, 1, 1) for mesh and task stages, 16 KiB of output memory each,
  4 views, output granularity 32.
- Subgroups: size 32 in every stage, mesh and task included, with every feature bit:
  basic, vote, arithmetic, ballot, shuffle, relative shuffle, clustered, quad and
  partitioned.
- Shading rate: 16 by 16 pixels a texel, palettes of 16 entries, 16 palettes (the
  per-primitive rate is present, Ampere and later), 16 coarse fragment samples at most.
- Sparse resources: 64 KiB buffer pages. Texture pages are 64 KiB: 128 by 128 for RGBA8,
  R11F_G11F_B10F, R32F and both depth formats, 128 by 64 for RGBA16F, 256 by 256 for R8,
  the same in arrays, and 32 by 32 by 16 in 3D textures (64 by 32 by 32 for R8).
- Limits as recorded publicly for every supported architecture: 8 draw buffers and color
  attachments, 2,048 array layers, 32,768 texels a side, 16x anisotropy, 48 KiB of compute
  shared memory, 1,024 invocations a work group, 2 GiB less one byte a storage block with 96
  bindings, a texture buffer of 2^28 texels, 16 viewports, one program binary format.
- Compatibility profile: 8 lights, 8 clip planes, 4 fixed-function texture units, stacks
  of 32 (model-view), 4 (projection), 10 (texture) and 16 (attributes).
- Present: `GL_ARB_shading_language_include`, `GL_EXT_texture_shadow_lod`, the interop set
  (`GL_EXT_memory_object`, `GL_EXT_semaphore` and their Win32 forms,
  `GL_NV_timeline_semaphore`, `GL_NV_draw_vulkan_image`, one device), and
  `GL_EXT_shader_realtime_clock`. Absent: `GL_EXT_mesh_shader`, `GL_ATI_meminfo` and the
  POSIX descriptor forms of the interop set.
*Implication:* a meshlet is at most 256 vertices and 512 triangles, built by 32 threads;
cascade lookups can use `textureLod` on array shadow textures through
`GL_EXT_texture_shadow_lod` where the capability is present; shader sources can share
include files through the driver; and a sparse light layer or texture array commits in
64 KiB pages.

**1.9 The handoff from OpenGL to Vulkan, and DLSS's cost. [V; measured costs in VERIFICATION]**
Read 2026-11-18 for PLAN item 7, before the bridge of D21 is built. The rules come from the
Khronos specifications of `EXT_external_objects` (revision 15, 2022-07-15),
`EXT_external_objects_win32` (revision 10, 2023-08-29) and `NV_timeline_semaphore`
(revision 1, 2020-07-10), and from the Vulkan specification's reference pages; DLSS's
requirements and costs from NVIDIA's DLSS Super Resolution Programming Guide (revision
310.6.0, March 2026) and DLSS-RR Integration Guide (August 2026), read as published
documents. No SDK was downloaded and no license accepted.

- *Matching the device.* Memory and semaphores exported as opaque Win32 handles may be
  imported only where the device UUID and the driver UUID both match (Vulkan's compatibility
  tables for `OPAQUE_WIN32` and `OPAQUE_WIN32_KMT`; `EXT_external_objects` 4.2.1 and issue
  20). OpenGL reports one device UUID per device of its device group, one driver UUID, and,
  through the Win32 extension, the adapter's LUID and a node mask. Vulkan reports the same in
  `VkPhysicalDeviceIDProperties`; where `deviceLUIDValid` is set on Windows, the LUID equals
  the DXGI adapter's. The LUID is not in Vulkan's tables. Neither specification says what an
  import from a mismatched device does: the OpenGL extension's error list is empty.
  *Implication:* the Vulkan device is chosen by comparing both UUIDs, and the LUID where both
  sides report one, against the context's, never by name or vendor; a machine with an
  integrated GPU beside the card has two candidates.
- *Handles.* An `OPAQUE_WIN32` handle is an NT handle that holds a reference to its payload
  and belongs to the application, which closes it; a KMT handle holds none and dies with the
  last Vulkan object using it. Neither OpenGL nor Vulkan takes ownership of a Win32 handle it
  imports (`EXT_external_objects_win32` 6.1; `vkGetMemoryWin32HandleKHR`). *Implication:*
  each handle is exported, imported and closed at once; one left open per image leaks a
  kernel object at every resize.
- *Memory and images.* OpenGL imports a memory object with its size and handle type; its
  `DEDICATED_MEMORY_OBJECT_EXT` parameter must be true exactly when the exporter allocated
  the memory as a dedicated allocation (6.2, issue 19), and Vulkan requires a dedicated
  allocation for the same image when it imports memory it exported as dedicated. A texture
  is made over the memory with `glTextureStorageMem2DEXT` at an offset, with the levels,
  format and size the exporter's image has, after its `TEXTURE_TILING_EXT` parameter is set
  to the exporter's tiling (issue 18, with a query for supported tilings). The Vulkan image
  is created for external memory (`VkExternalMemoryImageCreateInfo`), with every usage its
  format supports (issue 13), and with the mutable-format bit wherever OpenGL can make
  texture views, which 4.6 can (issue 17). No issue restricts which formats may be imported,
  and none mentions depth or stencil. *Implication:* every shared image is created with all
  supported usages, mutable format and a dedicated, exported allocation; which formats this
  driver imports, depth among them, is measured.
- *Layouts and ownership.* `glSignalSemaphoreEXT` transitions the listed textures to the
  given layouts before it signals; `glWaitSemaphoreEXT` takes the layouts the other API left
  them in. Nine layout tokens map one to one to Vulkan image layouts (table 4.4). Issue 9
  settles that ownership transfer and barriers are needed, with the application naming only
  the source side when moving from the other API to OpenGL and only the destination side the
  other way, and leaves open how much of Vulkan's barrier is exposed.
  `VK_QUEUE_FAMILY_EXTERNAL` names queues outside the instance on the same device and driver;
  a barrier with it as destination releases an image, with it as source acquires one.
  *Implication:* Vulkan acquires each image from the external family in the layout OpenGL's
  signal named, and releases it to the external family in the layout OpenGL's wait names.
- *Semaphores.* A signal flushes as if `glFlush` followed it. A wait blocks the GL server,
  never the client. A binary semaphore's wait needs a signal already flushed or submitted.
  With `NV_timeline_semaphore`, a semaphore's type is set before the import, the value to
  signal or wait for is set with `glSemaphoreParameterui64vEXT` before each operation, and a
  wait may be issued before its signal. Vulkan 1.2 has timeline semaphores in core; one
  exported as `OPAQUE_WIN32` transfers by reference and may be imported only permanently.
  *Implication:* one timeline semaphore carries both directions of a frame's round trip with
  rising values, and the client thread never blocks on it.
- *Clocks.* `vkGetCalibratedTimestampsKHR` reads the device's timestamp counter and the
  Windows performance counter together, and Java's `System.nanoTime` counts the performance
  counter on Windows; OpenGL's `GL_TIMESTAMP` query reads the GPU's time without waiting for
  work. *Implication:* OpenGL's and Vulkan's timestamps of a round trip can be put on one
  clock, so the time from OpenGL's signal to Vulkan's start and from Vulkan's end to OpenGL's
  resumption can be measured apart from Vulkan's own work.
- *What DLSS requires.* An NVIDIA RTX GPU and a driver issued after 2022-03-03; D3D11, D3D12 or
  Vulkan 1.1 or later; OpenGL is not mentioned anywhere in the guide, which states that
  interoperation between APIs is the application's to handle (5.2). Each evaluation takes the
  color at render resolution (HDR or LDR), screen-space motion vectors at 16 or 32 bits a
  pixel (`RG16F` or `RG32F`), depth in any one-channel or depth-stencil format, an exposure
  value in HDR, and sub-pixel jitter of at least 16 phases; it writes the output at output
  resolution (3.3, 2.2). Inputs are sampled images and the output a storage image, created
  with `VK_IMAGE_USAGE_SAMPLED_BIT` and `VK_IMAGE_USAGE_STORAGE_BIT` (3.4). It runs early in
  post-processing, before tone mapping (3.1). Its Vulkan instance and device extensions come
  from a query against the SDK, not from a list in the guide (2.3.1). Render size per mode
  comes from a query, not a fixed table (5.2.8).
- *What DLSS costs.* NVIDIA ran the library from a command-line utility without a renderer,
  in Performance mode, where the input has a quarter of the output's pixels. On an RTX 3060,
  at 1920x1080 / 2560x1440 output: presets E and F 0.60 / 0.98 ms, J and K 1.45 / 2.39 ms,
  L 3.96 / 6.53 ms, M 2.81 / 5.05 ms. As of 310.6.0, presets E and F are deprecated, K is the
  default for Quality, Balanced and DLAA, M for Performance and L for Ultra Performance. The
  memory DLSS allocates on RTX 20 and 30 cards is 85.8 / 143.5 MB for J and K and 119.3 /
  207.4 MB for M (pages 5 to 7). D21 cites the span from presets J and K to preset M,
  which first read "about 1.45 to 2.8 ms at 1080p": right as published, but a Performance-mode
  figure taken without a renderer, not a Quality-mode one taken inside a frame. Ray
  Reconstruction on the RTX 3060 in Performance mode takes 5.83 / 10.27 ms and 159.6 / 273.9
  MB with its default preset.
- *What an evaluation needs.* The NGX API is linked from static libraries,
  `nvsdk_ngx_s.lib` for the static C runtime or `nvsdk_ngx_d.lib` for the dynamic one, with
  the `nvsdk_ngx*.h` headers and their Vulkan variants (5.1); the runtime library
  `nvngx_dlss.dll` (Super Resolution) or `nvngx_dlssd.dll` (Ray Reconstruction) ships beside
  the program from the release folder, signed by NVIDIA, never the development build (4.2).
  A program initializes NGX with a GUID-like project ID, an engine type and version, or with
  an application ID NVIDIA assigns (5.2.1). The latest release in NVIDIA's repository is
  310.9.16, of October 2026. NVIDIA's RTX SDK license (v. 2024-03-14) is accepted by using the
  SDK. NVIDIA's Streamline (2.14.16) wraps DLSS behind dynamically loaded libraries under a
  permissive license of its own, but its interface is C++ headers and its binaries come only in
  release archives. *Implication:* a Java module reaches NGX through a small native library of its own,
  compiled against the SDK the builder supplies, since static libraries cannot be loaded from
  Java; what the evaluation needs is listed for the user in PLAN item 7's report.
- *Patents searched* (2026-11-19, Google Patents; a record of the search, not a legal
  conclusion). For each technique the handoff uses:
  - *Sharing memory and semaphores between two APIs through exported handles.* US 8,539,516 B1
    (NVIDIA, "System and method for enabling interoperability between application programming
    interfaces"; priority 2008-02-14; granted 2013-09-17; shown active, adjusted expiration
    2032-06-01). Its independent claims 1, 6 and 11, read from the claims text, require (a) a
    semaphore buffer at a location a first and a second driver can access; (b) a memory buffer
    made accessible to the first driver; (c) on a request to make it accessible to the second,
    determining it is not accessible to both, moving it to a location both can access and marking
    it registered there; (d) suspending the second driver's commands until the semaphore shows the
    first has finished with the buffer; (e) updating the semaphore. It cites US 5,842,015 (HP),
    Apple's WO 2008/127610, 127622 and 127623 and US 2008/0276220, and NVIDIA's CUDA Programming
    Guide 0.8. Steps (a), (d) and (e) correspond in kind to the handoff's semaphore and waits.
    Steps (b) and (c) are not performed by Quadrature: its memory is allocated exportable in
    Vulkan at creation and imported once in OpenGL, and nothing asks a driver to move an existing
    buffer. US 2023/0185635
    A1 (family CN 116257354 A, DE 102022132017 A1) and US 2023/0185636 A1 (CN 116257371 A,
    DE 102022132176 A1), NVIDIA, "Application programming interfaces for interoperability",
    priority 2021-12-10, shown pending: their descriptions have one API import or update a
    timeline semaphore exported by another, with Vulkan and CUDA as the example; their claims
    could not be read from the published pages (USPTO applications 17/548,412 and 17/548,433).
    They are to be checked again before any release and when either is granted. EP 3159849 B1 and US 10,497,083 B2 (Channel One
    Holdings, priority 2015-10-21, active to 2036-10-21) claim translating OpenGL objects, state
    and render calls into Vulkan objects and command buffers, which the handoff does not do.
  - *Choosing the Vulkan device by the context's UUIDs and LUID.* Three searches (device UUID
    and LUID matching across graphics APIs; OpenGL context and Vulkan physical device identity)
    found no patent on point; the hits concern switching between GPUs by load or policy.
  - *Handing a frame's color, depth and motion to another API's pass and taking the output back
    within the frame.* Three searches (upscaling a frame from one API with another; interop
    with super-resolution; shared textures and semaphores with post-processing) found none on
    point. The nearest render at low resolution on one GPU and scale on another (US 9,171,350
    B2) or super-resolve streamed frames on a client (US 11,683,358 and family), without a
    second API or synchronization between APIs; their status was not checked, as their claims
    concern neither.
  - *The stamp check.* US 9,424,619 B2 (Google, priority 2014-02-20, active to 2034-12-07)
    claims sensor data embedded in camera images with a frame identifier in two corner pixels
    whose values are compared; US 8,347,344 B2 (VMware, priority 2008-12-18, expired for
    unpaid fees) and US 8,910,228 B2 (Omnissa, priority 2010-11-09, active to 2033-07-11) claim
    markers and frame numbers in frames sent to a remote client to measure display performance.
    The handoff writes a frame number into one texel of an input, the pass carries it to the
    output, and the client compares a copy of it locally.

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
   class the game loads, each call to one of 47 OpenGL functions so that it lands in a
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
the mode again after `glPopAttrib`, which can restore a mode it never saw set. A display
list can also change the mode when it is called, where no rewritten call sees it. The
translator therefore watches lists being compiled: inside one it follows the mode the list
sets, and afterwards returns to the mode that held before, since compiling runs nothing.
If any list sets the mode, the translator stops trusting its own record for the rest of
the session and asks the driver before every projection load. In the surveyed pack and the
current stack no class that compiles a display list sets the matrix mode, so that path is
not taken there. Row-major loads and loads that name their stack (`glLoadTransposeMatrix`,
`glMatrixLoad*EXT`) are translated too; no surveyed mod calls them. It
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

Depth read back. The survey (R5, section 11.3) finds two mods that read depth pixels, both
in the current stack: GregTech's world scene renderer and ModularUI's schema renderer. Each
reads the depth under the pointer and unprojects it through the projection to find the
hovered block of a multiblock preview. `glReadPixels` with the depth format is therefore
translated as well: floating-point values become 1 - d and normalized integers their
bitwise complement. A self-test (`-Dquadrature.depthSelfTest=true`) loads projections each
way hosted code can, including inside a display list that sets the matrix mode and after
one has changed it, and compares what the driver then holds; all cases pass. Also checked
in the development client: with the switch on, a sky pixel
reads 0 from the driver and 1 through the translator. A preview's picking in the game was
not exercised.

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

The rewriter's share of class loading, measured 2026-11-13 outside the game by passing
every class of a mod folder through it once: the surveyed pack's 68,661 classes (252 MiB)
take 0.25 to 0.41 s on a first pass and rewrite 56 classes; the current stack's classes
take about 0.1 s. No class failed. That is an upper bound on the classes
involved, since the game loads only some of them, and it excludes the game's own classes
and JARs nested inside JARs. Nearly all the time is the byte search that rules a class
out; a class is parsed only when it names an OpenGL class.

*Not yet covered:*

- The added mods were loaded and rewritten, but a terrain tour opens no inventory and
  places no block, so their own depth-setting code mostly did not run. JourneyMap's map
  and CustomLoadingScreen's loading screen did draw.
- Depth textures that other mods create and sample in their own shaders, and
  `gl_FragCoord.z` in those shaders, are not translated and cannot be from outside the
  shader. In the surveyed pack three mods create or attach a depth texture (IvToolkit,
  GroovyScript, Commons0815); none is in the current stack. These are reported, not fixed.
- A display list that loads a projection without setting the matrix mode itself, relying
  on the mode its caller has set, is not reversed. No surveyed mod compiles such a list.
- A projection built by multiplying a perspective onto the base is reversed by the driver
  in single precision, which shifts the effective near plane by a relative 0.02% at the
  game's own far plane. It is monotonic and the same for everything drawn with that
  matrix. Quadrature's own projections must be derived from the same matrix, read from
  the driver, so its depth matches hosted depth exactly.
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

**9a.7 The envelope, measured. [V]**
Measured 2026-11-13 with a development stage of Quadrature's own, the probe
(`-Dquadrature.probe`), which runs between the sky and terrain setup and draws with a
shader program, a vertex array object, a buffer and its own framebuffer, all created
through direct state access.

- State audit, development client, reversed depth off and on: the probe stage leaves none
  of the 41 state items changed.
- Frame, cubic instance, forest tour, one instance, settled: with the probe drawing only
  into its own framebuffer, 16 of 17 views are identical to the run without it and the
  seventeenth differs by 0.077%, the view that differs between any two runs.
- Depth, same instance: with the probe also drawing a flat square into the game's frame,
  two blocks below the eye, terrain drawn afterwards cuts it where it rises above it, and
  16 of 17 views agree between reversed and conventional depth within 0.042% of pixels.
- The seventeenth view differs by 1.6%, and the difference is in the conventional run: the
  square lies in the plane of the ground there, and with conventional 24-bit depth the two
  fight in stripes, while with reversed floating-point depth the ground, drawn later,
  wins everywhere.

*Implication:* a shader of Quadrature's own and the game's fixed-function path do not
produce bit-identical depth for one surface. Reversed depth keeps that from showing in
this case. Phase 2 must not rely on it: hosted drawing that lies exactly on Quadrature's
terrain, such as overlays and decals from other mods, needs to be checked there.

One correction to the rule as first written. The game does rely on state set earlier in
its own pass, such as face culling enabled once at the top. So neutral values are for
state the game's cache does not know; state the cache does know, a stage puts back to what
its place in the frame expects, through the cache.

## Follow-ups

1. Prototype the distant terrain composite and its transition band, in Phase 4 (2.2).
2. Run the state audit on more views and with a larger pack installed (9.3).
3. Test parallel compile and program binary caching on a current driver (1.5).
4. Evaluate DLSS itself on the measured handoff once an SDK is supplied and its license
   accepted (1.9), and ask NVIDIA whether an OpenGL frame handing images to a Vulkan
   evaluation is supported (D19).

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
- Khronos, `EXT_external_objects` (revision 15, 2022-07-15), sections 4.2, 6 and issues 9 to 23:
  https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt, read 2026-11-18
- Khronos, `EXT_external_objects_win32` (revision 10, 2023-08-29):
  https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects_win32.txt, read 2026-11-18
- Khronos, `NV_timeline_semaphore` (revision 1, 2020-07-10):
  https://registry.khronos.org/OpenGL/extensions/NV/NV_timeline_semaphore.txt, read 2026-11-18
- Vulkan reference pages, read 2026-11-18: `VkPhysicalDeviceIDProperties`,
  `VkExternalMemoryHandleTypeFlagBits`, `VkExternalSemaphoreHandleTypeFlagBits`,
  `vkGetMemoryWin32HandleKHR`, `VkMemoryDedicatedAllocateInfo`, `VkExternalMemoryFeatureFlagBits`,
  `VK_QUEUE_FAMILY_EXTERNAL`, `VkImageMemoryBarrier`, `VkSemaphoreTypeCreateInfo`,
  `vkGetSemaphoreWin32HandleKHR`, `vkGetCalibratedTimestampsKHR`, `VkTimeDomainKHR`,
  `VkPhysicalDeviceMemoryBudgetPropertiesEXT`: https://docs.vulkan.org/refpages/latest/refpages/source/ ;
  synchronization chapter (semaphore waits, timelines, payload import):
  https://docs.vulkan.org/spec/latest/chapters/synchronization.html
- NVIDIA, DLSS Super Resolution Programming Guide, revision 310.6.0 (March 2026), sections 2 to 5:
  https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf, read 2026-11-18
- NVIDIA, DLSS-RR Integration Guide, SWE-DLSS-001-PGRF (August 2026), section 2.2:
  https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS-RR%20Integration%20Guide.pdf, read 2026-11-18
- NVIDIA, DLSS SDK repository and releases (310.9.16, October 2026) and its license file,
  "NVIDIA RTX SDKs LICENSE" (v. March 14, 2024): https://github.com/NVIDIA/DLSS, read 2026-11-18
- NVIDIA, Streamline 2.14.16, README and license: https://github.com/NVIDIA-RTX/Streamline, read 2026-11-18
