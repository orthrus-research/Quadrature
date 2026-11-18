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

**D7. Platform: OpenGL 4.6 compatibility profile, NVIDIA GPUs, Cleanroom, Java 25.**
Cleanroom requests OpenGL 4.6 with the compatibility profile by default. The compatibility
profile lets the game's and other mods' fixed-function drawing run inside Quadrature's frame
unchanged. Only NVIDIA GPUs are supported.

**D11. The GPU floor is NVIDIA's Turing generation (RTX 20 series).**
Turing is the first generation with mesh shaders and variable-rate shading available in
OpenGL, and it is on NVIDIA's current driver branch; the GTX 10 series stopped at the 580
branch. Later generations add nothing reachable from OpenGL, so a higher floor would only
remove players. In the October 2026 Steam Hardware Survey the GTX 10 series was 4.83% of
all users, about 7% of NVIDIA users; its 1070 to 1080 Ti models were 1.50%. The GPU
memory minimum is 8 GB: it gives the budgets a workable target while still covering a
large share of players. The floor is checked as a capability set plus that memory amount,
so Turing cards with 8 GB or more qualify (RTX 2060 Super and above) and those with less do
not (RTX 2060 6 GB, the GTX 16 series). Every budget in Phase 0 must fit an 8 GB card.
Whether
mesh shaders are the only terrain technique is decided by measurement in Phase 0.

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

**D9. Own repository, named Quadrature.**
Cubature integrates over a volume; quadrature integrates over a surface. The renderer turns
the first into the second. Java namespace `cc.orthrus.quadrature`, mod ID `quadrature`.

**D10. Existing support stays until replaced.**
Cubature's Celeritas, Demonica, vanilla chunk-render and Distant Horizons integrations
remain supported until the Quadrature phase that replaces each one closes. They are then
deleted, not moved.

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
