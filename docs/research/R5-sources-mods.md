# R5: scene sources, mod interface, other mods

Requirement sections 3, 10 and 11. First pass, 2026-11-13. Basis marks are defined in
[README.md](README.md).

## 3. Scene sources

**3.1 Cube snapshots. [K, with a local precedent V]**
Cubature's current renderer integration already clones blocks and block entities out of
cubes into the renderer's sections, and reads a cube without storage as its implicit
height-based sky light. That is a working, if copying, snapshot. Designs that avoid the
copy: versioned immutable sections shared by reference, or copy-on-write at the block
storage level.
*Implication:* the cube source contract (work order P0-A2) starts from what the existing
integration needs: blocks, block entities, light, neighbors, and implicit sky light.

**3.2 Distant source and the server. [K]**
See R3, 6.5: a client holding the seed and definitions computes ungenerated terrain
itself. The bandwidth question reduces to changed terrain only.

## 10. Mod interface

**10.1 First client. [V]**
GTCEu: Reloaded today adds a bloom value to the game's render layer enum, wraps the
translucent terrain call in the world pass, and carries its own bloom framebuffers, depth
texture hook and post-processing shaders. Under Quadrature these become: declare an
emissive layer, submit emissive geometry, and receive bloom as a pipeline stage.

**10.2 Shape. [K]**
Handles instead of object references, capability queries instead of version checks, and a
versioned contract in OrthLib. Open design.

## 11. Exclusivity and other mods

**11.1 Mods that reach into the game's chunk rendering. [V, from published documents]**
Two renderer projects publish their compatibility work per mod; those documents show what
each mod needs from a renderer. Read for behavior only, on 2026-11-13.

| Mod | What it does to rendering | What it needs from Quadrature |
|---|---|---|
| CodeChickenLib (and Thermal, Ender IO pipes) | Draws blocks by writing vertices directly | The capture technique (R2, 4.4) |
| ConnectedTexturesMod, Chisel | Wraps block models; extra layers per quad | Model path with per-quad layer |
| LittleTiles, ArchitectureCraft | Own chunk-level geometry and lighting | Capture technique; lighting inputs |
| Fluidlogged API | Changes which layer a block draws in | Layer query honored off-thread |
| Chunk Animator | Animates sections as they appear | A per-section transform hook, or reported |
| Snow! Real Magic!, Component Model Hider | Redraw or hide blocks inside the dispatcher | Capture technique for affected blocks |
| GregTech CEu, Lumenized | Bloom layer and post-processing | The mod interface (10.1) |
| Texture animation optimizers (Chibi) | Skip animating unseen textures | A visible-texture report, or reported |
| HUD caches (StellarCore, Gnetum) | Cache HUD draws in framebuffers | State containment (R1, 2.4) |
| ReplayMod, minimaps | Read frames and depth back | Frame resource access |
| OptiFine and other renderers | Replace the renderer | None: the game stops (decision D6) |

Cleanroom's LWJGL 2 shim keeps its own list of mods that reach LWJGL 2 internals by
reflection and fail at runtime [V, `MOD_COMPATIBILITY.md`, read 2026-11-13]. Two entries
concern rendering: FarPlaneTwo's LWJGL 2 backend resolves GL entry points through members
the shim does not provide, and Vivecraft's input injection drives LWJGL 2's platform
classes. Both fail on Cleanroom regardless of Quadrature.

*Implication:* three needs recur: the capture technique, an honored layer query, and state
containment. Most of the list is covered by those three.

**11.2 When a stop message can be shown. [V, partly]**
Forge 1.12.2 provides `CustomModLoadingErrorDisplayException`, which a client mod throws
during loading to show its own error screen (`GuiCustomModLoadingErrorScreen`).
Detection can happen earlier, when Mixin configurations are selected, but no screen exists
then.
Confirmed on Cleanroom 0.7.14-alpha, 2026-11-13 [V]: thrown from pre-initialization, the
exception makes Forge log "the game will display an error screen and halt" and open that
screen. The graphics context cannot be read at pre-initialization, because the loading
screen's thread holds it; it is read when the first screen opens, and an unsupported
context replaces every screen with a stop screen from then on.
*Implication:* other renderers are refused at pre-initialization; the context is refused
at the first screen. Detection will have to move earlier, to where Mixin configurations
are selected, once Quadrature has Mixins of its own.

## Follow-ups

1. Write the cube source contract with Cubature (3.1).
2. Extend the table in 11.1 from a survey of the mods in the packs the projects target.
3. Move renderer detection to Mixin selection when Quadrature gains Mixins (11.2).

## Sources

- Cubature, renderer integration (`core/compat/celeritas` and the `selectable.celeritas`
  Mixin set), as of 2026-11-13
- GTCEu: Reloaded, `gregtech/mixins` and `gregtech/client/utils` (bloom), as of 2026-11-13
- Actinium, `docs/compatibility-matrix.md` (updated 2026-11-07), read for behavior
- Demonica, README and release notes 0.9.1, read for behavior
- Relictium, README (known incompatibilities)
- CleanroomMC/LWJGLY, `MOD_COMPATIBILITY.md`: https://github.com/CleanroomMC/LWJGLY
- Forge 1.12.2 Javadoc, `CustomModLoadingErrorDisplayException`:
  https://skmedix.github.io/ForgeJavaDocs/javadoc/forge/1.12.2-14.23.5.2859/net/minecraftforge/fml/client/CustomModLoadingErrorDisplayException.html
