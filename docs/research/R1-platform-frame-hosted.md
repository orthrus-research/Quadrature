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

**1.9 Timing a card at first launch. [V]**
Observed 2026-11-18 and 19 with the tier benchmark (ARCHITECTURE 12.1.1) on an RTX 3060 12
GB, driver 610.88, in the game, with the card's clocks sampled beside each tour by
`nvidia-smi` (evidence only; the benchmark asks no clock of the driver).
- **The card's clock follows its load.** Under the game alone, own terrain at radius 12 and
  120 frames a second, the card moves among P5, P3 and P0: graphics 510 to 1,440 MHz, memory
  810, 5,001 or 7,501 MHz. It reaches its top state, about 1,950 MHz and 7,501 MHz, only
  under added load, over one to two seconds. The first run at a place read opaque terrain
  24% slow while the clock climbed (1.04 against 0.84 ms at 1280 by 720); whole runs left
  in P3 read the bandwidth kernel 33% slow (232 against 348 GB/s, the ratio of the memory
  clocks). A benchmark that times whatever state it finds measures the state, not the card.
- **One time-elapsed query a target.** A context has at most one `GL_TIME_ELAPSED` query open,
  and the frame's own stage timer holds one around opaque terrain, where the probes run: a
  probe's begin fails and its end closes the stage's query. Pairs of `GL_TIMESTAMP` counters
  nest anywhere.
- **At one place and one clock, a run repeats to within a tenth of a percent:** the median
  spread of a probe's figure over the repetitions at one still view was 0.03 to 0.07%.
- **Opaque terrain's time rises faster than its pixels.** From 2560 by 1440 to 3840 by 2160,
  2.25 times the pixels, far views take 2.0 to 2.25 times as long and near views full of
  foliage up to 4.6 times. Fitted per view against its fragments, the split into geometry and
  fragments swings from all geometry to all fragments between places. At one resolution the
  time follows the primitives drawn closely, so the reference card is held as a line a
  resolution, a fixed part and a part a million primitives, mixed by pixels between them: it
  leaves a standard deviation of 8.6% between places. The light view's box drawn whole, by its
  primitives and fragments, leaves 7.1%.
- **The trace's rate follows its rays' scene.** MEDIUM's probes, 65,536 rays of 64 blocks, ran
  at 290 to 460 million rays a second at 16 of 17 places and 4,267 at one, where the rays left
  the scene at once. Without a count of the nodes each ray visited, the rate is a figure of the
  place as much as of the card.
- **The settling load.** An arithmetic pass and a pass over 256 MiB, with two more arithmetic
  passes untimed, about 6 ms a frame, agreed within 5% after 13 to 15 frames in most runs and
  within 40 in nearly all. The loaded top clock still steps between about 1,950 and 2,100 MHz,
  moving the arithmetic pass by up to 7%. With it, a place's first run reads as its later ones,
  and the same place on another fresh instance agrees to 0.5%.
- The RTX 3060 stands within about 9% of an RTX 2060 Super at 1440p in published game
  comparisons, on either side of it by the source, so it sits in the first of ARCHITECTURE
  12.1's clusters.
*Implication:* a first-launch benchmark brings the clocks up with a load of its own and waits
until that load's own times stop changing before it times anything, keeps the GPU loaded
between its probes, times with timestamps, takes its settings in a new order each round, and
scores a view against a model of the reference card fitted over many places, with a band at
each tier's boundary wider than what remains between places. A count of the work each ray
does would make the trace's rate a figure of the card.

**1.10 Patents searched for the tier benchmark. [V for what the pages show]**
Searched 2026-11-19 on Google Patents, with full-text and claims-field queries, for the five
techniques of ARCHITECTURE 12.1.1. Statuses and dates are as the Google Patents pages show
them, which the pages call assumptions; claims were read on the pages, verbatim where quoted
here. Not a legal reading.
- **Searches.** Full text: settings chosen from a benchmark of the graphics processor; "graphics
  settings" with benchmark, game and "frame rate"; benchmark with first launch, first execution
  or first run and a quality level, preset or setting. Claims field: benchmark with graphics or
  rendering and a quality level, quality setting, graphics settings or rendering quality (46
  results); a frame, render or rendering time, predicted or estimated, with a quality level or
  setting and a budget or target (11); hysteresis with a quality level, graphics or rendering
  quality, level of detail or quality setting and a frame rate or time (6); evictions with a
  frame rate or time, quality or level of detail, and memory (3); video, graphics or GPU memory
  with a frame rate, time or percentile and a quality or graphics setting (none); a benchmark or
  performance test of a GPU with its clock frequency and stable, steady state, converge or
  warm-up (10). General web searches for each of the five pointed to the patents below.
- **(1) Settings from a benchmark at first launch; (2) a predicted cost per group and the highest
  tier that fits a budget.** The patents found choose settings from the hardware's description,
  not from a measurement of it:
  - US9286247B2, "System, method, and computer program product for determining settings for a
    device by utilizing a directed acyclic graph containing a plurality of directed nodes each
    with an associated speed and image quality", NVIDIA, priority 2012-07-06, shown active to
    2033-08-15. Claim 1: identify parameters of a device, "including descriptions of hardware and
    software installed within the device"; create a directed acyclic graph whose nodes each
    represent "a unique variation of the plurality of parameters"; give each node a speed
    "determined based on the parameters represented by the node"; identify the device's node;
    take the nodes "known to be slower than or equal in speed to the identified node" that have
    a monotonic set of presets, and use the presets with "the highest output image quality".
    Claims 6 and 7 are the same as a program and a system. It cites US6059842A and
    US20030023841A1 (IBM, optimum configurations of a system), US8296781B1, US8276133B1,
    US8280864B1 and US7659897B1 (NVIDIA), and Hellerstein, "Optimizing Software Packages for
    Application Management" (IEEE 2008). Quadrature builds no graph of device configurations and
    names no device node: it times probes on the machine and compares each tier's predicted cost
    with a budget.
  - US8276133B1, "... determining a plurality of application settings utilizing a mathematical
    function", NVIDIA, priority 2007-12-11, shown active to 2031-06-09. Claim 1: identify
    "hardware specifications associated with a system" and an application; identify application
    parameters from the specifications; define them as a "mathematical build function"; choose
    the settings for which "a composition of the application build function and an execution
    function [is] less than a predetermined threshold value", the parameters coming from "a
    database storing the application parameters ... including the hardware specification ...
    [with] an optimized application setting corresponding to the hardware specification".
    Its citations were not read. Quadrature has no database of settings by hardware
    specification; its comparison with a budget is of costs scaled by measured ratios.
  - US8296781B1, "... determining application parameters based on hardware specifications",
    NVIDIA, priority 2007-12-11, shown active to 2030-06-14. Claims 1, 19 and 21: identify
    hardware specifications of at least two of the graphics processor, central processor and
    memory, query a database whose table correlates them, the application and an optimized set
    of parameters, and configure the application with the result. Performance data appear only
    in dependent claim 16. It cites 37 patents, among them US6646653B2 (programmable graphics
    processor for a video game system).
  - US11413542B2, "Systems and methods for measuring and optimizing the visual quality of a video
    game application", Dell, priority 2020-04-29, shown active to 2041-02-04. Claims 1 and 13: a
    target frame rate; each setting's range divided into subsets numbered from lowest to
    highest; the optimal settings for the target. Claim 10: during play, adjust the applied
    settings "one setting at a time, until the generated FPS equals the target FPS". Dependent
    claims score a combination by the frame rate times the sum of its subsets' numbers and take
    the highest. It cites NVIDIA's US20140011581A1, US20150154934A1, US10878770B2,
    US20160291989A1 and US20160323336A1, and AMD's and NVIDIA's settings tools as non-patent
    art. Quadrature numbers no subsets, scores no combination and does not step settings toward
    a frame rate.
  - US20160291989A1, "Method and system for applying optimal settings from first invocation of
    a gaming application", NVIDIA, priority 2015-03-30: shown abandoned (claims: pre-defined
    optimal settings by hardware configuration written into a game's settings file).
  - US20250061640A1, "Realtime 3D Performance Management System", Ascendant Studios, priority
    2023-08-17: shown pending. Claim 1: a hardware scan when the user launches a game, a total
    GPU and CPU performance budget, and the user adjusting settings against it.
  - Expired, as prior art: EP1055199B1, "Estimating graphics system performance for polygons",
    Sun Microsystems, priority 1998-02-17, shown expired 2019-02-16. Claim 1: calculate a
    rendering time for graphics data and, where it exceeds that of a minimum frame rate, decide
    whether the data are "polygon overhead limited or pixel fill limited" and select object
    variants with fewer polygons or fewer pixel calculations. US7659897B1, "... video
    benchmarking", NVIDIA, priority 2006-06-30: shown expired, fee related (claims: a video
    output's performance from its frames a second at one of four pre-defined quality levels).
- **(3) A band at each tier's boundary.** US9940904B2, "Techniques for determining an adjustment
  for a visual output", Intel, priority 2013-10-23, shown active to 2034-12-21, with its
  continuation US10573275B2: up and down frame-time hysteresis counters, each incremented where
  a change in perceived quality passes a threshold, the frame time changed once a counter passes
  its own threshold (claim text read through a page summary). It cites Qualcomm's
  US20140002730A1, "Adaptive frame rate control", and LG's US6611653B1. Quadrature counts
  nothing: a tier is left or taken once a run's fit lies past a fixed band. CN122089573B,
  Moore Threads, shown active in China to 2046: its claim 1 sets a driver's super-resolution
  parameter, device and algorithm from frame commit intervals, rendering time and memory use;
  a first and a smaller second time threshold appear in its later claims, not read in full.
- **(4) The memory tier lowered at run time on evictions and falling 1% lows.** Nothing found in
  any search that claims evictions or a frame-time percentile as the trigger. The nearest:
  US8547378B2, "Time-based degradation of images using a GPU", Adobe, priority 2008-08-28, shown
  active to 2031-08-30: claim 1 renders a frame, finds its elapsed rendering time above a
  threshold, and renders the next at its resolution divided by a downsampling factor (claim text
  through a page summary); it cites US6075513A (Cirrus Logic, maintaining an image quality) and
  US6975319B1 (NVIDIA, level of detail).
- **(5) The warm-up by agreement of the benchmark's own times.** Nothing found claiming it. The
  one claims-field result about a performance test, US10534696B1 (Facebook, priority
  2017-08-29), is shown expired, fee related, and claims comparing two builds' frame times on a
  RAM drive. Published practice for warm-up before timing a GPU: NVIDIA's CUTLASS measurement
  guidelines (warm-up iterations; clocks oscillating for about three seconds before settling).
- Also met and not related to the claims: US10909653B2 (Intel, a video encoder's quantization
  range by its target), US10379611B2 (Intel, light-field streaming for virtual reality),
  US11798514B2 and US20210074237A1 (NVIDIA, settings ranked by users' preferences between
  clips).
*Implication:* no patent found claims the benchmark as built. The nearest in force, NVIDIA's
three, choose settings from a device's hardware description through a database or a graph of
configurations; the benchmark measures the machine and keeps no table of devices. The record
is for the user's decision, not a conclusion.

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
4. Repeat the tier benchmark after a driver update, on this card, and on an RTX 3050 8 GB and a Turing
   8 GB card (1.9; D22).
5. Count the nodes each traced ray visits, so the trace probe's rate is a figure of the card (1.9).

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
- RTX 3060 against the RTX 2060 Super (1.9), read 2026-11-18: dropreference.com game comparison
  (https://dropreference.com/en/compare/gpu/nvidia-geforce-rtx-2060-super-vs-nvidia-geforce-rtx-3060-12gb),
  CheckFPS (https://checkfps.io/en/compare/rtx-3060-vs-rtx-2060-super), PassMark G3D
  (https://www.videocardbenchmark.net/compare/4345vs4117/GeForce-RTX-3060-vs-GeForce-RTX-2060-SUPER) and Windows
  Central (https://www.windowscentral.com/nvidia-geforce-rtx-3060-vs-rtx-2060-super)
- NVIDIA, DLSS Super Resolution Programming Guide 310.6.0 (March 2026), the cost table measured with
  its command-line tool (D21's cost for the room the tier benchmark finds)
