# Decisions

Standing decisions. An entry that stops being true is deleted or rewritten, not annotated.

## 2026-11-13

**D1. Quadrature is a complete rendering pipeline, built new.**
Cubic worlds need handling that no third-party renderer removes, and each one adds an
adaptation layer to maintain. Owning the pipeline from cube to pixel removes those layers.
Kirino, Cleanroom's rendering engine, is not a target: its terrain renderer serves vanilla
worlds.

**D2. The implementation is original.**
No code is taken from Celeritas, Sodium, Iris, Demonica, Actinium, Nothirium, RenderLib,
Distant Horizons or any other renderer. Specifications, papers, vendor documentation and
public API references are the sources. `docs/PROVENANCE.md` states the rules and holds a
record for each technique.

**D3. Cubic worlds only.**
Quadrature renders worlds served by Cubature. This removes vanilla's column assumptions from
the design. Other world types are a possible later extension through the scene source seam.

**D4. Quadrature draws distant terrain itself.**
Levels of detail are part of the pipeline, sharing its visibility, lighting and shading with
near terrain. Distant Horizons is replaced when that phase closes.

**D5. Shaders are Quadrature's own.**
Quadrature ships its own shader and a suite of its own shaders for players to choose from.
Third-party shader pack formats are not loaded and no compatibility layer for them is
planned at this time.

**D6. No coexistence with other renderers.**
A player runs Quadrature or another renderer, never both. When another renderer is
installed, or the graphics context does not meet the requirements, the game stops with a
message that names the cause and the remedy. One case has no screen: with Cleanroom's
compatibility profile turned off, the game itself aborts during startup before any screen
exists. There Quadrature writes the cause to the log, and that is accepted as sufficient.

**D7. Platform: OpenGL 4.6 compatibility profile, NVIDIA GPUs, Cleanroom, Java 26.**
Cleanroom requests OpenGL 4.6 with the compatibility profile by default. The compatibility
profile lets the game's and other mods' fixed-function drawing run inside Quadrature's frame
unchanged. Only NVIDIA GPUs are supported, which means tested and promised; the design
itself stays vendor-neutral (D8). The frame is drawn and presented through OpenGL;
a Vulkan device is opened beside it only to evaluate DLSS on images the two share (D21).
Java is Temurin 26.0.2.1+1, the one JDK that builds, tests and runs every project of ours,
OrthLib and Cubature among them (the user's decision of 2026-11-21: the most advanced of those
in use); the mod's class files are Java 26's.

**D11. The GPU floor is NVIDIA's Turing generation (RTX 20 series) with 8 GB.**
Turing is the first generation with mesh shaders and variable-rate shading available in
OpenGL, and it is on NVIDIA's current driver branch; the GTX 10 series stopped at the R580
branch. On the same driver, later generations expose the same OpenGL extensions and limits
but one usable extension, `GL_NV_primitive_shading_rate` (Ampere and later), which is an
optional capability and no reason for a higher floor. What later generations add beyond it
is speed, cache and memory, which the quality tiers measure (D22), and features reachable
only through Vulkan. In the October 2026 Steam Hardware Survey the GTX 10 series was 4.83%
of all users, about 7% of NVIDIA users; its 1070 to 1080 Ti models were 1.50%. The GPU
memory minimum is 8 GB: it gives the budgets a workable target while still covering a
large share of players. The floor is checked as a capability set plus that memory amount,
never as a model list. Cards that qualify include the RTX 2060 12 GB, the RTX 2060 Super
and above, the 8 GB Turing laptop parts, the RTX 3050 8 GB and 3060 8 GB, and every RTX 30,
40 and 50 card with 8 GB or more; the desktop RTX 2060 6 GB, the GTX 16 series, the RTX 3060
Laptop and the RTX 4050 Laptop do not. The slowest qualifying desktop card is the RTX 3050
8 GB, about 0.8 times an RTX 2060 Super, and the lowest tier's budgets are set against it.
Every budget must fit an 8 GB card. Mesh shaders reach OpenGL on NVIDIA as
`GL_NV_mesh_shader` only: no NVIDIA driver through 621.14 exposes `GL_EXT_mesh_shader`.
Whether mesh shaders are the only terrain technique is decided by measurement.

**D12. Clients may hold the world seed and definitions.**
Riven's terrain is a pure function of the seed and the world's recorded definitions. A
client that holds both computes ungenerated distant terrain itself, so the server sends
only terrain that differs from generation. Exposing the seed to players is accepted.

**D13. Quadrature has its own material model; LabPBR is mapped into it.**
The material model is designed for Quadrature's shading, not copied from a texture format.
LabPBR, the resource pack convention for material textures, is read as one input and
converted into that model at load, alongside Quadrature's and mods' own per-block
definitions. Resource packs that follow the convention therefore work, while the internal
model stays free to carry properties the convention has no channel for. LabPBR is a
resource pack format, not a shader pack format, so this does not touch D5.

**D14. Phase 2's performance bar is the vanilla renderer's measured baseline.**
Quadrature's terrain must match or beat the vanilla near renderer as recorded in
`docs/BASELINE.md`, under the same workload. That baseline exists and can be repeated with
the same harness. Celeritas is measured as a comparison to report alongside, not as the
condition for closing the phase.

**D15. Quadrature is licensed Apache-2.0.**
Original Quadrature material is licensed under the Apache License 2.0, in place of NRR 1.0
since 2026-11-18, so that NVIDIA's RTX runtimes can ship with Quadrature on NVIDIA's terms
(D19). Its patent grant takes the place of NRR's, and its notice convention carries the
terms of the separately licensed components `NOTICE.md` lists. Material offered under
NRR 1.0, up to commit 65af717, keeps that grant. The original-implementation rule (D2)
does not rest on the license: it keeps other renderers' code out whatever the terms.

**D16. Cubature owns the cube source contract.**
The contract for what Cubature supplies to a renderer lives in Cubature's repository and
its interfaces in Cubature's public API module. It is written for any renderer, not for
Quadrature by name. Quadrature keeps only a record of what it relies on.

**D17. The whole frame uses reversed depth.**
One depth convention for everything drawn: the near plane at depth 1, the far plane at 0,
clip depth from 0 to 1, a floating-point depth buffer. Hosted code keeps setting and
reading conventional depth state; Quadrature translates it where the calls are made
(research R1, section 2.2). A prototype passes the frame comparison in a cubic instance;
it stays behind a switch until the items listed there are covered.

**D18. A metal's reflectance is its texel's tinted albedo.**
For a surface of the metal kind, the colored reflectance in the targets is the texel's linear
albedo after tint; no physical reflectance is put in its place. GregTech draws a material's
blocks from shared textures tinted by the material's color, so each material reflects in its
own color: aluminium light blue, gold with almost no blue. Those are not the values of real
metals, and they are intended: in the supported pack a material's color is how it is told
apart, and its reflection keeps it.

**D8. Narrow support, general design.**
The supported platform is narrow; the taxonomy and paradigms are not. Vendor, API, world
type and shader source each sit behind a seam named in `docs/REQUIREMENTS.md`, so support
can widen without being forced in. Generality does not cost performance or quality by
default. Where it would, the specific design wins unless the tradeoff is judged large
enough, and that judgment is recorded here.
Supported means tested and promised, so only NVIDIA is supported while only NVIDIA hardware
is tested (D7), and the design stays vendor-neutral. Nothing a stage needs comes from one
vendor: a vendor technique may be the one chosen where its capability is present, but a
core OpenGL 4.6 technique for the same stage always exists beside it, and no quality tier
requires a vendor technique or a vendor's SDK. The platform facts the gate and the tiers
read, GPU memory and the driver's version, come through interfaces with a source per
vendor. Admitting another vendor is then a matter of testing its capability set, not of
rewriting.

**D9. Own repository, named Quadrature.**
Cubature integrates over a volume; quadrature integrates over a surface. The renderer turns
the first into the second. Java namespace `cc.orthrus.quadrature`, mod ID `quadrature`.

**D10. Existing support stays until replaced.**
Cubature's vanilla chunk-render and Distant Horizons integrations remain supported until the
Quadrature phase that replaces each one closes. They are then deleted, not moved. Demonica, with
the Celeritas it bundles, is not a target, and Cubature carries no integration with either.

## 2026-11-18

**D19. NVIDIA's RTX runtimes ship as a separately licensed part of Quadrature.**
Where Quadrature uses one of NVIDIA's RTX SDKs (DLSS first), it reaches it only through a
module of its own, `quadrature-nvidia`, under Orthrus Research's own terms rather than the
Apache License: its bindings restate NVIDIA's interface, and NVIDIA's license (v. 2024-03-14)
forbids its SDK becoming subject to an open-source license (§4.e). Orthrus Research is the
licensee. The SDK never enters the repository: whoever builds the module supplies a pinned
copy they have accepted themselves. The module and NVIDIA's object code ship only inside
Quadrature's release, never on their own (§1.c, §4.b), with NVIDIA's terms beside them
(§2.c) and the attribution NVIDIA requires (RTX Supplement §7.1(b)). Before the first release
that contains it, NVIDIA is asked whether a free mod is a commercial release, how its
splash-screen attribution applies to a mod, and whether an OpenGL frame handing images to
a Vulkan evaluation is supported. Quadrature without the module runs unchanged.

**D20. The driver floor is kept per vendor; NVIDIA's is the R580 branch.**
The gate holds one driver floor for each vendor it admits, read through the same interface
as the rest of the platform profile (D8); NVIDIA is the only entry. Every feature Quadrature
uses has been in NVIDIA's drivers since 440.97 (October 2019), so its floor is a support
policy, not a feature requirement. R580 is the oldest branch NVIDIA
still patches, with security builds reported to about August 2028, and the last for the GTX
10 series. The gate stops the game below R580 and warns below the current production
branch, R595 at this writing. The warning's branch moves with NVIDIA's releases, and the
floor is reviewed before R580's security support ends. This is the one exception to the rule
that code asks for capabilities, never for a version. It is applied after the capability
check, so a missing capability is reported first, and it reads the branch from the leading
number of the driver version in the context's version string. Any later driver-specific
handling goes through a list of known-bad builds, empty today. Both numbers belong in
`platform.json` with the other platform pins. A player on an older packaged driver, Debian
13's 550 among them, is refused with the remedy named.

**D21. DLSS Super Resolution and Ray Reconstruction are planned as an option for the higher
tiers, through a Vulkan bridge.**
OpenGL has no path to DLSS. The frame stays OpenGL and is presented by it. When DLSS is
chosen, a Vulkan device allocates the images DLSS reads and writes and shares them with the
GL frame through `GL_EXT_memory_object`, `GL_EXT_semaphore` and `GL_NV_timeline_semaphore`,
present on every supported card. GL renders the jittered frame into them and signals;
Vulkan evaluates DLSS and signals back; GL then draws post effects and the interface at
output resolution. The bridge lives in the `quadrature-nvidia` module (D19), which brings its
own Vulkan binding, since Cleanroom's LWJGL has none. Its precondition is a motion vector for
everything drawn, the game's hosted draws included: the game's fixed-function drawing
produces none, and DLSS takes bad motion vectors as its main source of artifacts. Own
temporal anti-aliasing and scaling (D23) remain the default on every tier; DLSS is offered
where the startup benchmark finds room for its cost. NVIDIA's DLSS Super Resolution
Programming Guide (revision 310.6.0) gives, for an RTX 3060 at 1920x1080 in Performance
mode, 1.45 ms with presets J and K and 2.81 ms with preset M; its Ray Reconstruction guide
gives 5.83 ms at 1080p and 10.27 ms at 1440p. It is an extra above a tier, never part of what a tier needs (D8): its license
limits it to systems with NVIDIA GPUs (RTX Supplement §1). The shared-image handoff itself is
Khronos interop, so the same bridge can later carry a vendor-neutral evaluator. Frame generation and Reflex are not planned: both need the frame presented by
Vulkan. No shipped OpenGL application is known to reach DLSS this way, so the handoff's cost
is measured before the module is built.

**D22. Quality tiers are chosen on two axes: speed and memory.**
LOW, MEDIUM, HIGH and MAX are presets of per-group settings. The capability set is one tier
for every supported card: `GL_NV_primitive_shading_rate` and `GL_EXT_shader_realtime_clock`
are optional, with fallbacks, and no capability marks a tier. At first launch a short,
versioned benchmark sets the speed tier: a bandwidth kernel at two working sets, about 8 MB
and 256 MB (a small one measures cache on Ada and Blackwell but memory on Turing and
Ampere), and timed probes of opaque terrain, the light view and the indirect trace at the
player's render resolution, with hysteresis at each boundary. The memory tier comes from the
total and free GPU memory, scaled by output pixels, read through an interface whose NVIDIA
source is `GL_NVX_gpu_memory_info` (D8). Groups that
set per-frame work take the speed tier; groups that hold memory take the lower of the two.
The benchmark runs again when the GPU, the driver or the benchmark changes, and at run time
rising evictions with falling 1% lows lower the memory tier one step. The player can
override any group. The lowest tier's budgets are measured on the development card under an
enforced 8 GB budget and scaled until an RTX 3050 8 GB and a Turing 8 GB card are measured;
no budget is taken from a card with Ada's or Blackwell's cache.

**D23. Rendering below output resolution uses Quadrature's own scaler.**
Quadrature's own temporal anti-aliasing and a spatial scaler, written from published
descriptions (D2), serve every tier that renders below output resolution. NVIDIA
Image Scaling is a comparison reference only and is never shipped: its license would allow
it, but no published timing shows an advantage worth an exception to D2. DLSS is the option
above it (D21). The history is rectified by clipping to the neighborhood's minimum and
maximum; variance clipping, which NVIDIA patents describe, is not used (the user's decision of
2026-11-18), and its quality gain is not to be measured (2026-11-19). The scaler is Keys'
separable cubic. A kernel steered along edges by the structure tensor is not used (the user's
decision of 2026-11-19, with US 7,889,950 in view): measured against the separable cubic at 67%
and 77%, it scored no better and cost more. A tier renders below the output only where the startup
benchmark predicts that costs less than drawing at the output's resolution with the temporal pass
alone; 67% and 77% are the scales LOW and MEDIUM go down to where it does (the user's decision of
2026-11-20).

## 2026-11-21

**D24. Quadrature takes over the game's chunk system in cubic worlds.**
In a cubic world under own terrain the game's terrain setup, its search over render chunks, its chunk
rebuilds and its rebuild buffers do not run; entities and block entities are drawn for the cubes
Quadrature's view sees, and layers other mods add to the game's list are meshed and drawn by Quadrature
(the user's decision of 2026-11-21, which replaces the policy of 2026-11-20 that kept the game's rebuild as
the point where mods join). What a mod adds through the rebuild itself, an event inside it or a wrap of a
call it makes, no longer runs; a mod that needs it is examined and given its place in Quadrature's own
build. Cubature's integration with the game's chunk system in cubic worlds is then deleted (D10). A
game-drawn run of a cubic world needs Cubature's reference build, the last that carries it.

**D25. Mods draw bloom and emissive geometry only through OrthLib's render interface.**
GTCEu: Reloaded keeps no rendering of its own for bloom and emissive geometry: no pass, framebuffer,
program or depth texture, and no GL call for them. It declares its emissive layer and submits its
contributions, geometry with its format, blend and bloom kind stated, through OrthLib's interface, which
Quadrature implements; Quadrature draws them. Where no renderer provides the interface, OrthLib's
absence answers route the declared layer's geometry into the fallback layer the mod names, which the game
draws at full brightness, and OrthLib draws the contributions plainly through the game's own drawing:
everything appears, nothing glows, and the fusion reactor's ring stays visible (the user's decisions of
2026-11-21). The rule is per world pass: one whose contributions Quadrature does not draw, in a world its
own terrain does not draw for instance, gets OrthLib's plain drawing. Quadrature's bloom is one technique of its own (D2), chosen by research; GTCEu: Reloaded's
bloom styles are retired, and its strength, base, limits and radius carry over as each bloom kind's
parameters (the user's decision of 2026-11-21).

**D26. The bridge to OrthLib is a source set of its own.**
The few classes that implement OrthLib's render interface are a source set of their own, compiled
against OrthLib's JAR, which `platform.json` pins by commit and hash beside Cubature's. Nothing else
names an OrthLib type: the bridge is reached by name only where OrthLib is installed, so a game
without OrthLib loads none of it. OrthLib's pinned JAR is built, and the bridge compiled, by the one
JDK of D7.

**D27. Bloom is a chain of halvings and doublings over a weighted source.**
Quadrature's bloom (D25) is Mittring's recurrence with Jimenez's filters (research R4, 7c): a
thirteen-read halving into six levels sized from the output, a three-by-three tent while doubling at
the kind's radius in texels of the level read, the levels weighted in equal steps up to 1 at the
coarsest, as GTCEu: Reloaded's default style weighs its five, so that in the 8-bit picture the glow's
light lies in its halo rather than in a core that clamps (P3-V1: weighted equally, a gain bright enough
for the halo blew the cores out; with five levels the far halo fell short of GTCEu: Reloaded's). Its
source is the picture
weighted per pixel by the bloom kinds of what declared emissive layers and contributions cover, with no
brightness threshold: a kind's strength, base and limits become two weights in one chain, which
reproduces GTCEu: Reloaded's composite rule exactly where one kind lights a pixel. Under the temporal
pass the chain reads its output, the weight resolved in its history, and the glow is added after the
scale pass; without it, after translucent terrain, where GTCEu: Reloaded's own path added it. A
calibration gain, set against GTCEu: Reloaded's own glow in P3-V1, keeps the chain's response to a source
of one color at its own: its five levels' weights summed times the strength its default style applies a
second time, 4.5, over the chain's levels' weights summed, 9/7 at six levels, which P3-V1 measured as
the gain that matches its light. Kinds of different radii share the chain at the first
one's radius until a mod sets them apart, and the weighting against single bright samples waits for
high dynamic range (Phase 5).

**D28. Mods reach Quadrature's drawing through version 1.0 of OrthLib's render interface.**
The interface's first version is the one Phase 3 verified, at OrthLib commit 0c2995e: declared layers,
each with the block states that draw in it and the game layer it falls back to, meshed apart inside the
provider's scopes; bloom kinds of a strength, a base, two brightness limits and a radius; emissive
contributions, geometry a mod writes into a buffer the provider begins, in a stated layout, primitive and
blend; a lease for every registration; capabilities stated as literals, and one provider, a second
refused by name (D6). Without a provider, and in every world pass the provider does not mark as one whose
contributions it draws, OrthLib's absence answers draw everything plainly: everything appears, nothing
glows. Frame resources and work at named points stay out of it until a mod needs them (requirement rule
4). It changes by research R5's rules (10.2): within version 1 only additions, nothing removed or changed
in meaning. What using it in Phase 3 left wanting (a view volume a source can cull by, a test host for
consumers, a bloom kind changed in place among them) is what its next minor version weighs.

## 2026-11-22

**D29. Distant Horizons is another renderer, and loading stops where it is installed.**
The 2026-11-22 launch showed Distant Horizons loading beside Quadrature unchallenged: its own OpenGL
renderer drew the distance while Quadrature drew near terrain. Owning the seam between the two would be
work that Phase 4 throws away, and Quadrature's own distant terrain (`-Dquadrature.distant=own`) is the
intended state (D4). So loading stops naming Distant Horizons, as for the renderers of D6. The part of D10
that keeps Cubature's Distant Horizons integration supported now holds for the game's renderer only.
`-Dquadrature.test.allowDistantHorizons=true` lets it load beside Quadrature, with a warning, for
verification runs that compare against it (the baseline flight, the `repeat` profile) until Phase 4 closes.
