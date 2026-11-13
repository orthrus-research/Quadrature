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
public API references are the sources. `docs/PLAN.md` states the reading rules.

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
message that names the cause and the remedy.

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
